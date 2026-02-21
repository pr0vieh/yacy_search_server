package net.yacy.rocksdb;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Iterator;
import java.util.TreeSet;
import java.util.TreeMap;

import net.yacy.cora.order.Base64Order;
import net.yacy.cora.order.ByteOrder;
import net.yacy.cora.order.CloneableIterator;
import net.yacy.cora.order.Order;
import net.yacy.cora.sorting.Rating;
import net.yacy.cora.storage.HandleSet;
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

public class RocksDBIndexCellBackend implements IndexCellBackend<WordReference> {
    private final WordUrlRefStore store;
    private final WordReferenceFactory factory;
    private final ByteOrder termOrder;

    public RocksDBIndexCellBackend(final File dbPath) {
        this.store = new WordUrlRefStore(dbPath);
        this.factory = new WordReferenceFactory();
        this.termOrder = Base64Order.enhancedCoder;
        
        // Einmaliger automatischer Import von alten Kelondro BLOB-Dateien
        tryImportKelondroBlobs(dbPath);
    }

    private void tryImportKelondroBlobs(final File dbPath) {
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

            // Import durchführen
            ConcurrentLog.info("RocksDBIndexCellBackend", "starting import of " + blobFiles.length 
                + " Kelondro BLOB files from " + kelondroHeapDir);
            
            final long startTime = System.currentTimeMillis();
            final long importedRefs = WordUrlBlobImportJob.importBlobDirectory(
                kelondroHeapDir, 
                "text.index", 
                Word.commonHashLength, 
                this.store, 
                50_000
            );
            final long duration = System.currentTimeMillis() - startTime;
            
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
        while (iterator.hasNext()) {
            add(wordHash, iterator.next());
        }
    }

    @Override
    public void add(final byte[] termHash, final WordReference entry) throws IOException, SpaceExceededException {
        if (termHash == null || entry == null) return;
        final byte[] urlHash = entry.urlhash();
        final byte[] meta = entry.toKelondroEntry().bytes();
        store.upsert(termHash, urlHash, meta);
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
        final List<Rating<byte[]>> ratings = new ArrayList<Rating<byte[]>>();
        for (final Map.Entry<byte[], ReferenceContainer<WordReference>> entry : containersByWord().entrySet()) {
            ratings.add(new Rating<byte[]>(entry.getKey(), entry.getValue().size()));
        }
        return new ListCloneableIterator<Rating<byte[]>>(rotateRatings(ratings, startHash, rot));
    }

    @Override
    public CloneableIterator<ReferenceContainer<WordReference>> referenceContainerIterator(final byte[] startHash, final boolean rot, final boolean excludePrivate) throws IOException {
        final List<ReferenceContainer<WordReference>> containers = new ArrayList<ReferenceContainer<WordReference>>(containersByWord().values());
        return new ListCloneableIterator<ReferenceContainer<WordReference>>(rotateContainers(containers, startHash, rot));
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
        return this.store.size();
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
        return this.store.size();
    }

    @Override
    public int getSegmentCount() {
        return this.store.distinctWordCount();
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

    private TreeMap<byte[], ReferenceContainer<WordReference>> containersByWord() {
        final TreeMap<byte[], ReferenceContainer<WordReference>> map = new TreeMap<byte[], ReferenceContainer<WordReference>>(this.termOrder);
        for (final WordUrlRefRecord rec : this.store.scanAll()) {
            ReferenceContainer<WordReference> container = map.get(rec.wordHash());
            if (container == null) {
                container = new ReferenceContainer<WordReference>(this.factory, rec.wordHash());
                map.put(rec.wordHash(), container);
            }
            try {
                container.add(this.factory.produceSlow(this.factory.getRow().newEntry(rec.meta())));
            } catch (final SpaceExceededException e) {
            }
        }
        return map;
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
}
