package net.yacy.rocksdb;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

import org.rocksdb.RocksIterator;

import net.yacy.cora.document.encoding.ASCII;
import net.yacy.cora.order.Base64Order;
import net.yacy.cora.order.ByteOrder;
import net.yacy.cora.order.CloneableIterator;
import net.yacy.cora.order.Order;
import net.yacy.cora.sorting.Rating;
import net.yacy.cora.storage.HandleSet;
import net.yacy.cora.util.ByteArray;
import net.yacy.cora.util.ConcurrentLog;
import net.yacy.cora.util.SpaceExceededException;
import net.yacy.kelondro.data.word.Word;
import net.yacy.kelondro.index.Row;
import net.yacy.kelondro.rwi.IndexCellBackend;
import net.yacy.kelondro.rwi.Index;
import net.yacy.kelondro.rwi.ReferenceContainer;
import net.yacy.kelondro.rwi.ReferenceContainerOrder;
import net.yacy.kelondro.data.word.WordReference;
import net.yacy.kelondro.data.word.WordReferenceFactory;
import net.yacy.kelondro.rwi.TermSearch;
import net.yacy.search.Switchboard;
import net.yacy.search.SwitchboardConstants;

public class RocksDBIndexCellBackend implements IndexCellBackend<WordReference> {
    private final WordUrlRefStore store;
    private final WordReferenceFactory factory;
    private final ByteOrder termOrder;
    private final boolean runtimeTopKEnabled;
    private final int runtimeTopK;
    private final int runtimeSoftCap;
    private final int runtimeMaxPerHost;
    private final boolean syncWrites;

    private static final AtomicLong runtimeAddCalls = new AtomicLong(0L);
    private static final AtomicLong runtimePassDisabled = new AtomicLong(0L);
    private static final AtomicLong runtimePassBelowSoftCap = new AtomicLong(0L);
    private static final AtomicLong runtimeRebalanceRuns = new AtomicLong(0L);
    private static final AtomicLong runtimeCandidates = new AtomicLong(0L);
    private static final AtomicLong runtimeSelected = new AtomicLong(0L);
    private static final AtomicLong runtimeDropped = new AtomicLong(0L);

    private static final class RankedRecord {
        final byte[] urlHash;
        final byte[] meta;
        final double score;

        RankedRecord(final byte[] urlHash, final byte[] meta, final double score) {
            this.urlHash = urlHash;
            this.meta = meta;
            this.score = score;
        }
    }

    public RocksDBIndexCellBackend(final File dbPath) {
        this.store = new WordUrlRefStore(dbPath);
        this.factory = new WordReferenceFactory();
        this.termOrder = Base64Order.enhancedCoder;
        this.runtimeTopKEnabled = configBool(
            SwitchboardConstants.INDEX_RWI_RUNTIME_TOPK_ENABLED,
            SwitchboardConstants.INDEX_RWI_RUNTIME_TOPK_ENABLED_DEFAULT);
        this.runtimeTopK = Math.max(1, configInt(
            SwitchboardConstants.INDEX_RWI_RUNTIME_TOPK_K,
            SwitchboardConstants.INDEX_RWI_RUNTIME_TOPK_K_DEFAULT));
        this.runtimeSoftCap = Math.max(this.runtimeTopK, configInt(
            SwitchboardConstants.INDEX_RWI_RUNTIME_TOPK_SOFTCAP,
            SwitchboardConstants.INDEX_RWI_RUNTIME_TOPK_SOFTCAP_DEFAULT));
        this.runtimeMaxPerHost = Math.max(1, configInt(
            SwitchboardConstants.INDEX_RWI_RUNTIME_TOPK_MAXPERHOST,
            SwitchboardConstants.INDEX_RWI_RUNTIME_TOPK_MAXPERHOST_DEFAULT));
        this.syncWrites = configBool("rwi.rocksdb.syncWrites", true);

        this.store.setDisableWAL(!this.syncWrites);

        ConcurrentLog.info("RocksDBIndexCellBackend", "runtimeTopK: enabled=" + this.runtimeTopKEnabled +
                ", k=" + this.runtimeTopK +
                ", softCap=" + this.runtimeSoftCap +
            ", maxPerHost=" + this.runtimeMaxPerHost +
            ", syncWrites=" + this.syncWrites);
        
        // Einmaliger automatischer Import von alten Kelondro BLOB-Dateien
        tryImportKelondroBlobs(dbPath);
    }

    private static boolean configBool(final String key, final boolean defaultValue) {
        final Switchboard sb = Switchboard.getSwitchboard();
        if (sb != null) {
            return sb.getConfigBool(key, defaultValue);
        }
        return Boolean.parseBoolean(System.getProperty(key, Boolean.toString(defaultValue)));
    }

    private static int configInt(final String key, final int defaultValue) {
        final Switchboard sb = Switchboard.getSwitchboard();
        if (sb != null) {
            return sb.getConfigInt(key, defaultValue);
        }
        final String value = System.getProperty(key);
        if (value == null || value.isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (final NumberFormatException e) {
            return defaultValue;
        }
    }

    public static Map<String, Long> runtimeTopKStatsSnapshot() {
        final Map<String, Long> stats = new HashMap<String, Long>();
        stats.put("addCalls", runtimeAddCalls.get());
        stats.put("passDisabled", runtimePassDisabled.get());
        stats.put("passBelowSoftCap", runtimePassBelowSoftCap.get());
        stats.put("rebalanceRuns", runtimeRebalanceRuns.get());
        stats.put("candidates", runtimeCandidates.get());
        stats.put("selected", runtimeSelected.get());
        stats.put("dropped", runtimeDropped.get());
        return stats;
    }

    private void tryImportKelondroBlobs(final File dbPath) {
        final boolean autoImport = configBool("index.rocksdb.import.auto", true);
        if (!autoImport) {
            return;
        }

        // Marker-Datei verhindert wiederholten Import
        final File importMarker = new File(dbPath, ".kelondro_imported");
        if (importMarker.exists()) {
            return; // Import bereits durchgeführt
        }

        try {
            // Ermittle Segment-Verzeichnis und altes Kelondro default-Verzeichnis
            final File segmentPath = dbPath.getParentFile();
            if (segmentPath == null) return;
            
            final File kelondroHeapDir = new File(segmentPath, "default");
            if (!kelondroHeapDir.exists() || !kelondroHeapDir.isDirectory()) {
                // Kein altes Kelondro-Verzeichnis vorhanden, markiere trotzdem
                importMarker.createNewFile();
                return;
            }

            // Suche nach text.index BLOB-Dateien
            final File[] blobFiles = kelondroHeapDir.listFiles((dir, name) -> 
                name.startsWith("text.index.") && name.endsWith(".blob"));
            
            if (blobFiles == null || blobFiles.length == 0) {
                ConcurrentLog.info("RocksDBIndexCellBackend", "no Kelondro BLOBs found in " + kelondroHeapDir);
                importMarker.createNewFile();
                return;
            }

            // Import durchführen mit deaktiviertem WAL für bessere Performance
            ConcurrentLog.info("RocksDBIndexCellBackend", "starting import of " + blobFiles.length 
                + " Kelondro BLOB files from " + kelondroHeapDir);

            final boolean importDisableWal = configBool("rwi.rocksdb.import.disableWal", true);
            
            // Disable WAL during bulk import
            if (importDisableWal) {
                this.store.setDisableWAL(true);
            }
            
            final long startTime = System.currentTimeMillis();
            final long importedRefs = WordUrlBlobImportJob.importBlobDirectory(
                kelondroHeapDir, 
                "text.index", 
                Word.commonHashLength, 
                this.store, 
                50_000
            );
            final long duration = System.currentTimeMillis() - startTime;
            
            // Re-enable WAL after import
            this.store.setDisableWAL(!this.syncWrites);
            
            ConcurrentLog.info("RocksDBIndexCellBackend", "imported " + importedRefs 
                + " references from Kelondro BLOBs in " + (duration / 1000) + "s");
            
            // Setze Marker, damit Import nicht wiederholt wird
            importMarker.createNewFile();
            
        } catch (final Exception e) {
            ConcurrentLog.warn("RocksDBIndexCellBackend", "failed to import Kelondro BLOBs: " + e.getMessage(), e);
        }
    }

    @Override
    public void add(final ReferenceContainer<WordReference> newEntries) throws IOException, SpaceExceededException {
        if (newEntries == null) return;
        final byte[] wordHash = newEntries.getTermHash();
        final Iterator<WordReference> iterator = newEntries.entries();
        final List<WordReference> entries = new ArrayList<WordReference>();
        while (iterator.hasNext()) {
            final WordReference entry = iterator.next();
            if (entry == null) continue;
            entries.add(entry);
        }
        upsertWithRuntimeGuard(wordHash, entries);
    }

    private void upsertWithRuntimeGuard(final byte[] termHash, final List<WordReference> entries) {
        if (termHash == null || entries == null || entries.isEmpty()) return;
        runtimeAddCalls.incrementAndGet();

        final List<WordUrlRefRecord> incomingRecords = new ArrayList<WordUrlRefRecord>(entries.size());
        for (final WordReference entry : entries) {
            if (entry == null || entry.urlhash() == null) continue;
            incomingRecords.add(new WordUrlRefRecord(termHash, entry.urlhash(), entry.toKelondroEntry().bytes()));
        }
        if (incomingRecords.isEmpty()) return;

        if (!this.runtimeTopKEnabled) {
            runtimePassDisabled.incrementAndGet();
            this.store.upsertBatch(incomingRecords);
            return;
        }

        final int existingApprox = this.store.scanWord(termHash, this.runtimeSoftCap + 1).size();
        if (existingApprox + incomingRecords.size() <= this.runtimeSoftCap) {
            runtimePassBelowSoftCap.incrementAndGet();
            this.store.upsertBatch(incomingRecords);
            return;
        }

        // Rebalance this heavy term: merge existing + incoming, then keep runtimeTopK with host diversity
        final List<WordUrlRefRecord> existing = this.store.scanWord(termHash, 0);
        final Map<ByteArray, RankedRecord> merged = new LinkedHashMap<ByteArray, RankedRecord>(existing.size() + incomingRecords.size());

        for (final WordUrlRefRecord rec : existing) {
            final double score = computeScoreFromMeta(rec.meta());
            merged.put(new ByteArray(rec.urlHash().clone()), new RankedRecord(rec.urlHash(), rec.meta(), score));
        }

        for (int i = 0; i < incomingRecords.size(); i++) {
            final WordUrlRefRecord rec = incomingRecords.get(i);
            final WordReference entry = i < entries.size() ? entries.get(i) : null;
            final double score = entry == null ? computeScoreFromMeta(rec.meta()) : computeScore(entry);
            final ByteArray urlKey = new ByteArray(rec.urlHash().clone());
            final RankedRecord existingRecord = merged.get(urlKey);
            if (existingRecord == null || existingRecord.score <= score) {
                merged.put(urlKey, new RankedRecord(rec.urlHash(), rec.meta(), score));
            }
        }

        final List<RankedRecord> selected = selectTopKWithHostDiversity(new ArrayList<RankedRecord>(merged.values()), this.runtimeTopK, this.runtimeMaxPerHost);
        runtimeRebalanceRuns.incrementAndGet();
        runtimeCandidates.addAndGet(merged.size());
        runtimeSelected.addAndGet(selected.size());
        runtimeDropped.addAndGet(Math.max(0, merged.size() - selected.size()));

        final List<WordUrlRefRecord> rewritten = new ArrayList<WordUrlRefRecord>(selected.size());
        for (final RankedRecord record : selected) {
            rewritten.add(new WordUrlRefRecord(termHash, record.urlHash, record.meta));
        }

        this.store.deleteWord(termHash);
        this.store.upsertBatch(rewritten);
    }

    @Override
    public void add(final byte[] termHash, final WordReference entry) throws IOException, SpaceExceededException {
        if (termHash == null || entry == null) return;
        final List<WordReference> entries = new ArrayList<WordReference>(1);
        entries.add(entry);
        upsertWithRuntimeGuard(termHash, entries);
    }

    private double computeScoreFromMeta(final byte[] meta) {
        try {
            final WordReference reference = this.factory.produceSlow(this.factory.getRow().newEntry(meta));
            return computeScore(reference);
        } catch (final Throwable ignored) {
            return 0.0;
        }
    }

    private double computeScore(final WordReference reference) {
        if (reference == null) return 0.0;

        final int hitcount = Math.max(1, reference.hitcount());
        final int wordsInText = Math.max(1, reference.wordsintext());
        final int posInText = Math.max(0, reference.posintext());
        final int wordsInTitle = Math.max(0, reference.wordsintitle());
        final int outlinks = Math.max(0, reference.llocal()) + Math.max(0, reference.lother());

        double score = 0.0;

        // 1) BM25-like TF normalization (hitcount / docLength)
        final double k1 = 1.2;
        final double b = 0.75;
        final double avgDocLength = 500.0;
        final double normalization = (1.0 - b) + b * (wordsInText / avgDocLength);
        score += (hitcount * (k1 + 1.0)) / (hitcount + k1 * normalization);

        // 2) Position bonus (earlier in text = higher)
        score += 3.0 / (1.0 + Math.log1p(posInText));

        // 3) Freshness bonus (younger = higher, soft 10-year decay)
        final long now = System.currentTimeMillis();
        final long ageDays = Math.max(0L, (now - reference.lastModified()) / 86_400_000L);
        final double freshness = Math.max(0.0, 1.0 - (ageDays / 3650.0));
        score += freshness * 3.0;

        // 4) Title bonus
        if (wordsInTitle > 0) {
            score += 2.0;
        }

        // 5) Quality signal (more outlinks = better, bounded)
        score += Math.min(2.0, Math.log1p(outlinks) * 0.5);

        return score;
    }

    private List<RankedRecord> selectTopKWithHostDiversity(final List<RankedRecord> candidates, final int topK, final int maxPerHost) {
        if (candidates == null || candidates.isEmpty()) return Collections.emptyList();

        candidates.sort((a, b) -> Double.compare(b.score, a.score));

        final List<RankedRecord> selected = new ArrayList<RankedRecord>(Math.min(topK, candidates.size()));
        final List<RankedRecord> overflow = new ArrayList<RankedRecord>();
        final Map<String, Integer> hostCounts = new HashMap<String, Integer>();

        for (final RankedRecord candidate : candidates) {
            if (selected.size() >= topK) break;
            final String host = hostKey(candidate.urlHash);
            final int count = hostCounts.getOrDefault(host, 0);
            if (count < maxPerHost) {
                selected.add(candidate);
                hostCounts.put(host, count + 1);
            } else {
                overflow.add(candidate);
            }
        }

        if (selected.size() < topK) {
            for (final RankedRecord candidate : overflow) {
                selected.add(candidate);
                if (selected.size() >= topK) break;
            }
        }

        return selected;
    }

    private String hostKey(final byte[] urlHash) {
        if (urlHash == null || urlHash.length < 12) return "unknown";
        return ASCII.String(urlHash, 6, 6);
    }

    @Override
    public boolean has(final byte[] termHash) {
        final List<WordUrlRefRecord> refs = store.scanWord(termHash, 1);
        return !refs.isEmpty();
    }

    @Override
    public int count(final byte[] termHash) {
        final List<WordUrlRefRecord> refs = store.scanWord(termHash, 0);
        return refs.size();
    }

    @Override
    public ReferenceContainer<WordReference> get(final byte[] termHash, final HandleSet urlselection) throws IOException {
        final List<WordUrlRefRecord> refs = store.scanWord(termHash, 0);
        final ReferenceContainer<WordReference> container = new ReferenceContainer<WordReference>(this.factory, termHash);
        for (final WordUrlRefRecord rec : refs) {
            if (urlselection != null && !urlselection.has(rec.urlHash())) continue;
            try {
                container.add(this.factory.produceSlow(this.factory.getRow().newEntry(rec.meta())));
            } catch (final SpaceExceededException e) {
                throw new IOException(e);
            }
        }
        return container;
    }

    @Override
    public ReferenceContainer<WordReference> remove(final byte[] termHash) throws IOException {
        final ReferenceContainer<WordReference> removed = get(termHash, null);
        delete(termHash);
        return removed;
    }

    @Override
    public void delete(final byte[] termHash) throws IOException {
        this.store.deleteWord(termHash);
    }

    @Override
    public boolean remove(final byte[] termHash, final byte[] urlHashBytes) throws IOException {
        return this.store.delete(termHash, urlHashBytes);
    }

    @Override
    public void removeDelayed(final byte[] termHash, final byte[] urlHashBytes) {
        try {
            remove(termHash, urlHashBytes);
        } catch (final IOException e) {
        }
    }

    @Override
    public int remove(final byte[] termHash, final HandleSet urlHashes) throws IOException {
        if (urlHashes == null || urlHashes.isEmpty()) return 0;
        int removed = 0;
        final Iterator<byte[]> iterator = urlHashes.iterator();
        while (iterator.hasNext()) {
            if (remove(termHash, iterator.next())) removed++;
        }
        return removed;
    }

    @Override
    public int remove(final HandleSet termHashes, final byte[] urlHashBytes) throws IOException {
        if (termHashes == null || termHashes.isEmpty() || urlHashBytes == null) return 0;
        int removed = 0;
        final Iterator<byte[]> iterator = termHashes.iterator();
        while (iterator.hasNext()) {
            if (remove(iterator.next(), urlHashBytes)) removed++;
        }
        return removed;
    }

    @Override
    public void removeDelayed() throws IOException {
    }

    @Override
    public CloneableIterator<Rating<byte[]>> referenceCountIterator(final byte[] startHash, final boolean rot, final boolean excludePrivate) throws IOException {
        return new StreamingReferenceCountIterator(startHash, rot);
    }

    @Override
    public CloneableIterator<ReferenceContainer<WordReference>> referenceContainerIterator(final byte[] startHash, final boolean rot, final boolean excludePrivate) throws IOException {
        return new StreamingReferenceContainerIterator(startHash, rot);
    }

    @Override
    public CloneableIterator<ReferenceContainer<WordReference>> referenceContainerIterator(final byte[] startHash, final boolean rot, final boolean excludePrivate, final boolean buffer) throws IOException {
        return referenceContainerIterator(startHash, rot, excludePrivate);
    }

    @Override
    public TreeSet<ReferenceContainer<WordReference>> referenceContainer(final byte[] startHash, final boolean rot, final boolean excludePrivate, int count, final boolean buffer) throws IOException {
        final Order<ReferenceContainer<WordReference>> containerOrder = new ReferenceContainerOrder<WordReference>(this.factory, this.termOrder.clone());
        final TreeSet<ReferenceContainer<WordReference>> containers = new TreeSet<ReferenceContainer<WordReference>>(containerOrder);
        final CloneableIterator<ReferenceContainer<WordReference>> iterator = referenceContainerIterator(startHash, rot, excludePrivate, buffer);
        while (count > 0 && iterator.hasNext()) {
            final ReferenceContainer<WordReference> container = iterator.next();
            if (container != null && !container.isEmpty()) containers.add(container);
            count--;
        }
        iterator.close();
        return containers;
    }

    @Override
    public Iterator<ReferenceContainer<WordReference>> iterator() {
        try {
            return referenceContainerIterator(null, false, false);
        } catch (final IOException e) {
            return Collections.<ReferenceContainer<WordReference>>emptyList().iterator();
        }
    }

    @Override
    public int termKeyLength() {
        return Word.commonHashLength;
    }

    @Override
    public void merge(final Index<WordReference> otherIndex) throws IOException, SpaceExceededException {
        if (otherIndex == null) return;
        for (final ReferenceContainer<WordReference> otherContainer: otherIndex) {
            if (otherContainer != null) add(otherContainer);
        }
    }

    @Override
    public void clear() throws IOException {
        this.store.clear();
    }

    @Override
    public void close() {
        try {
            this.store.close();
        } catch (final IOException e) {
        }
    }

    @Override
    public int size() {
        return (int) Math.min(this.store.size(), Integer.MAX_VALUE);
    }

    @Override
    public void setBufferMaxWordCount(final int maxWords) {
    }

    @Override
    public long getBufferSizeBytes() {
        return ((long) this.store.size()) * this.factory.getRow().objectsize;
    }

    @Override
    public void clearCache() {
    }

    @Override
    public boolean isEmpty() {
        return this.store.isEmpty();
    }

    @Override
    public int deleteOld(final int minsize, final long maxtime) throws IOException {
        return 0;
    }

    @Override
    public int sizesMax() {
        return (int) Math.min(this.store.distinctWordCount(), Integer.MAX_VALUE);
    }

    @Override
    public int getSegmentCount() {
        return (int) Math.min(this.store.distinctWordCount(), Integer.MAX_VALUE);
    }

    @Override
    public int minMem() {
        return 0;
    }

    @Override
    public ByteOrder termKeyOrdering() {
        return this.termOrder;
    }

    @Override
    public long getBufferMaxAge() {
        return 0;
    }

    @Override
    public int getBufferMaxReferences() {
        int max = 0;
        try {
            final CloneableIterator<Rating<byte[]>> iterator = referenceCountIterator(null, false, false);
            while (iterator.hasNext()) {
                final int count = (int) iterator.next().getScore();
                if (count > max) max = count;
            }
            iterator.close();
        } catch (final IOException e) {
        }
        return max;
    }

    @Override
    public long getBufferMinAge() {
        return 0;
    }

    @Override
    public int getBufferSize() {
        return 0;
    }

    @Override
    public Row referenceRow() {
        return this.factory.getRow();
    }

    @Override
    public TreeMap<byte[], ReferenceContainer<WordReference>> searchConjunction(final HandleSet wordHashes, final HandleSet urlselection) {
        final TreeMap<byte[], ReferenceContainer<WordReference>> containers = new TreeMap<byte[], ReferenceContainer<WordReference>>(this.termOrder);
        final Iterator<byte[]> iterator = wordHashes.iterator();
        while (iterator.hasNext()) {
            final byte[] hash = iterator.next();
            try {
                final ReferenceContainer<WordReference> container = get(hash, urlselection);
                if (container == null || container.isEmpty()) return new TreeMap<byte[], ReferenceContainer<WordReference>>(this.termOrder);
                containers.put(hash, container);
            } catch (final IOException e) {
                return new TreeMap<byte[], ReferenceContainer<WordReference>>(this.termOrder);
            }
        }
        return containers;
    }

    @Override
    public TermSearch<WordReference> query(final HandleSet queryHashes, final HandleSet excludeHashes, final HandleSet urlselection, final net.yacy.kelondro.rwi.ReferenceFactory<WordReference> termFactory, final int maxDistance) throws SpaceExceededException {
        return new TermSearch<WordReference>(this, queryHashes, excludeHashes, urlselection, termFactory, maxDistance);
    }

    private List<Rating<byte[]>> rotateRatings(final List<Rating<byte[]>> source, final byte[] startHash, final boolean rot) {
        if (source.isEmpty()) return source;
        if (startHash == null) return source;
        int index = 0;
        while (index < source.size() && this.termOrder.compare(source.get(index).getObject(), startHash) < 0) index++;
        if (index <= 0 || index >= source.size()) return source;
        final List<Rating<byte[]>> rotated = new ArrayList<Rating<byte[]>>(source.size());
        rotated.addAll(source.subList(index, source.size()));
        if (rot) rotated.addAll(source.subList(0, index));
        return rotated;
    }

    private List<ReferenceContainer<WordReference>> rotateContainers(final List<ReferenceContainer<WordReference>> source, final byte[] startHash, final boolean rot) {
        if (source.isEmpty()) return source;
        if (startHash == null) return source;
        int index = 0;
        while (index < source.size() && this.termOrder.compare(source.get(index).getTermHash(), startHash) < 0) index++;
        if (index <= 0 || index >= source.size()) return source;
        final List<ReferenceContainer<WordReference>> rotated = new ArrayList<ReferenceContainer<WordReference>>(source.size());
        rotated.addAll(source.subList(index, source.size()));
        if (rot) rotated.addAll(source.subList(0, index));
        return rotated;
    }

    private static final class ListCloneableIterator<E> implements CloneableIterator<E> {
        private final List<E> list;
        private int index;

        private ListCloneableIterator(final List<E> list) {
            this(list, 0);
        }

        private ListCloneableIterator(final List<E> list, final int index) {
            this.list = list;
            this.index = index;
        }

        @Override
        public boolean hasNext() {
            return this.index < this.list.size();
        }

        @Override
        public E next() {
            return this.list.get(this.index++);
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }

        @Override
        public CloneableIterator<E> clone(final Object modifier) {
            int newIndex = this.index;
            if (modifier instanceof Number) {
                newIndex = Math.max(0, Math.min(((Number) modifier).intValue(), this.list.size()));
            }
            return new ListCloneableIterator<E>(this.list, newIndex);
        }

        @Override
        public void close() {
        }
    }

    private final class StreamingReferenceContainerIterator implements CloneableIterator<ReferenceContainer<WordReference>> {
        private final RocksIterator iterator;
        private final byte[] startHash;
        private final boolean rot;
        private boolean wrapped;
        private boolean done;

        private StreamingReferenceContainerIterator(final byte[] startHash, final boolean rot) {
            this.iterator = store.newWordIterator();
            this.startHash = startHash == null ? null : startHash.clone();
            this.rot = rot;
            this.wrapped = false;
            this.done = false;
            if (this.startHash == null) {
                this.iterator.seekToFirst();
            } else {
                this.iterator.seek(this.startHash);
            }
            advanceIfNeeded();
        }

        private void advanceIfNeeded() {
            if (this.done) return;
            while (!this.iterator.isValid()) {
                if (this.rot && !this.wrapped && this.startHash != null) {
                    this.wrapped = true;
                    this.iterator.seekToFirst();
                } else {
                    this.done = true;
                    return;
                }
            }
            if (this.wrapped && this.startHash != null) {
                while (this.iterator.isValid() && termOrder.compare(this.iterator.key(), this.startHash) >= 0) {
                    this.done = true;
                    return;
                }
            }
        }

        @Override
        public boolean hasNext() {
            advanceIfNeeded();
            return !this.done && this.iterator.isValid();
        }

        @Override
        public ReferenceContainer<WordReference> next() {
            while (hasNext()) {
                final byte[] wordHash = this.iterator.key().clone();
                this.iterator.next();
                try {
                    final ReferenceContainer<WordReference> container = get(wordHash, null);
                    if (container != null && !container.isEmpty()) return container;
                } catch (final IOException e) {
                }
            }
            return null;
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }

        @Override
        public CloneableIterator<ReferenceContainer<WordReference>> clone(final Object modifier) {
            return this;
        }

        @Override
        public void close() {
            this.done = true;
            this.iterator.close();
        }
    }

    private final class StreamingReferenceCountIterator implements CloneableIterator<Rating<byte[]>> {
        private final StreamingReferenceContainerIterator containerIterator;

        private StreamingReferenceCountIterator(final byte[] startHash, final boolean rot) {
            this.containerIterator = new StreamingReferenceContainerIterator(startHash, rot);
        }

        @Override
        public boolean hasNext() {
            return this.containerIterator.hasNext();
        }

        @Override
        public Rating<byte[]> next() {
            final ReferenceContainer<WordReference> container = this.containerIterator.next();
            if (container == null) return null;
            return new Rating<byte[]>(container.getTermHash(), container.size());
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }

        @Override
        public CloneableIterator<Rating<byte[]>> clone(final Object modifier) {
            return this;
        }

        @Override
        public void close() {
            this.containerIterator.close();
        }
    }
    
    /**
     * Anzahl Words im RAM cache (für Status-Seite)
     * @return Anzahl unique words im indexing cache
     */
    public int wordsInCache() {
        return this.store.wordsInCache();
    }
    
    /**
     * Anzahl References im RAM cache (für Status-Seite)
     * @return Gesamtanzahl URL references im indexing cache
     */
    public int referencesInCache() {
        return this.store.referencesInCache();
    }
}
