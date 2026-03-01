package net.yacy.tools;

import java.io.IOException;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.CompactRangeOptions;
import org.rocksdb.DBOptions;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksIterator;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;

import net.yacy.kelondro.data.word.WordReference;
import net.yacy.kelondro.data.word.WordReferenceFactory;
import net.yacy.kelondro.data.word.Word;

/**
 * Top-K RWI Reorganization Tool with score-based ranking.
 * 
 * Modes:
 *  --dryRun: analyze and project only (no writes)
 *  --apply: perform actual reorg to shadow DB
 * 
 * Usage:
 *  java -cp "build/classes/java/main;lib/*" net.yacy.tools.RwiTopKReorgTool \
 *    --db=D:/Repo/yacy_my/DATA/INDEX/freeworld/SEGMENTS/rocksdb \
 *    --cf=default --wordBytes=12 --k=1000 --dryRun
 * 
 *  java -cp "build/classes/java/main;lib/*" net.yacy.tools.RwiTopKReorgTool \
 *    --db=D:/Repo/yacy_my/DATA/INDEX/freeworld/SEGMENTS/rocksdb \
 *    --cf=default --wordBytes=12 --k=1000 --apply \
 *    --shadowDb=D:/Repo/yacy_my/DATA/INDEX/freeworld/SEGMENTS/rocksdb_topk
 */
public class RwiTopKReorgTool {

    private static final WordReferenceFactory WORD_REFERENCE_FACTORY = new WordReferenceFactory();
    private static final byte[] EMPTY_WORD_VALUE = new byte[0];
    private static final byte[] WORDS_CF_NAME = "words".getBytes(StandardCharsets.UTF_8);

    static {
        RocksDB.loadLibrary();
    }

    private static final class ScoredPosting implements Comparable<ScoredPosting> {
        final byte[] urlHash;
        final byte[] meta;
        final double score;

        ScoredPosting(final byte[] urlHash, final byte[] meta, final double score) {
            this.urlHash = urlHash;
            this.meta = meta;
            this.score = score;
        }

        @Override
        public int compareTo(final ScoredPosting other) {
            return Double.compare(this.score, other.score); // ascending for min-heap
        }
    }

    public static void main(final String[] args) throws Exception {
        final Map<String, String> params = parseArgs(args);
        final String dbPath = required(params, "db");
        final String cfName = params.getOrDefault("cf", "default");
        final int wordBytes = Integer.parseInt(params.getOrDefault("wordBytes", "12"));
        final int k = Integer.parseInt(params.getOrDefault("k", "1000"));
        final boolean hostDiversity = Boolean.parseBoolean(params.getOrDefault("hostDiversity", "true"));
        final int maxPerHost = Integer.parseInt(params.getOrDefault("maxPerHost", "3"));
        final boolean cleanStopwords = Boolean.parseBoolean(params.getOrDefault("cleanStopwords", "false"));
        final String stopwordFiles = params.getOrDefault("stopwordFiles", "");
        final boolean explicitLang = params.containsKey("lang");
        final String language = params.get("lang");
        final boolean dryRun = params.containsKey("dryRun");
        final boolean apply = params.containsKey("apply");
        final boolean rebuildWordsOnly = params.containsKey("rebuildWordsOnly");
        final boolean cleanStopwordsOnly = params.containsKey("cleanStopwordsOnly");
        final String wordsCfName = params.getOrDefault("wordsCf", "words");
        final String shadowDbPath = params.get("shadowDb");
        final boolean needsStopwordHashes = cleanStopwords || cleanStopwordsOnly;
        final String effectiveStopwordFiles;
        if (needsStopwordHashes && (stopwordFiles == null || stopwordFiles.trim().isEmpty())) {
            effectiveStopwordFiles = defaultSolrStopwordFiles(language, explicitLang);
            System.out.println("No --stopwordFiles provided. Using Solr defaults: " + effectiveStopwordFiles);
        } else {
            effectiveStopwordFiles = stopwordFiles;
        }
        final Set<String> stopwordHashes = needsStopwordHashes ? loadStopwordHashes(effectiveStopwordFiles, wordBytes) : Collections.emptySet();

        if (needsStopwordHashes && stopwordHashes.isEmpty()) {
            System.err.println("Stopword cleanup requested but no valid stopword hashes loaded. Provide --stopwordFiles=<file1,file2,...>");
            return;
        }

        if (needsStopwordHashes) {
            System.out.println("Stopword cleanup active: " + stopwordHashes.size() + " hashed stopwords loaded.");
        }

        final int modes = (dryRun ? 1 : 0) + (apply ? 1 : 0) + (rebuildWordsOnly ? 1 : 0) + (cleanStopwordsOnly ? 1 : 0);
        if (modes != 1) {
            System.err.println("Specify exactly one mode: --dryRun or --apply or --rebuildWordsOnly or --cleanStopwordsOnly");
            return;
        }

        if (apply && (shadowDbPath == null || shadowDbPath.isEmpty())) {
            System.err.println("--apply requires --shadowDb=<path>");
            return;
        }

        final List<byte[]> cfNames = RocksDB.listColumnFamilies(new Options(), dbPath);
        if (cfNames == null || cfNames.isEmpty()) {
            System.err.println("No column families found: " + dbPath);
            return;
        }

        final List<ColumnFamilyDescriptor> descriptors = new ArrayList<>();
        for (final byte[] name : cfNames) {
            descriptors.add(new ColumnFamilyDescriptor(name, new ColumnFamilyOptions()));
        }
        final List<ColumnFamilyHandle> handles = new ArrayList<>();

        if (dryRun || apply) {
            try (DBOptions dbOptions = new DBOptions().setCreateIfMissing(false);
                 RocksDB db = RocksDB.openReadOnly(dbOptions, dbPath, descriptors, handles)) {

                ColumnFamilyHandle sourceCf = null;
                for (int i = 0; i < descriptors.size(); i++) {
                    final String current = new String(descriptors.get(i).getName(), StandardCharsets.UTF_8);
                    if (cfName.equals(current)) {
                        sourceCf = handles.get(i);
                        break;
                    }
                }

                if (sourceCf == null) {
                    System.err.println("CF not found: " + cfName);
                    return;
                }

                if (dryRun) {
                    dryRunAnalysis(db, sourceCf, dbPath, wordBytes, k, stopwordHashes);
                } else {
                    applyReorg(db, sourceCf, cfName, cfNames, wordBytes, k, hostDiversity, maxPerHost, shadowDbPath, stopwordHashes);
                }

            } finally {
                for (final ColumnFamilyHandle handle : handles) {
                    try { handle.close(); } catch (final Exception ignored) {}
                }
                for (final ColumnFamilyDescriptor descriptor : descriptors) {
                    try { descriptor.getOptions().close(); } catch (final Exception ignored) {}
                }
            }
            return;
        }

        try (DBOptions dbOptions = new DBOptions().setCreateIfMissing(false).setCreateMissingColumnFamilies(false);
             RocksDB db = RocksDB.open(dbOptions, dbPath, descriptors, handles)) {

            ColumnFamilyHandle sourceCf = null;
            ColumnFamilyHandle wordsCf = null;
            for (int i = 0; i < descriptors.size(); i++) {
                final String current = new String(descriptors.get(i).getName(), StandardCharsets.UTF_8);
                if (cfName.equals(current)) {
                    sourceCf = handles.get(i);
                }
                if (wordsCfName.equals(current)) {
                    wordsCf = handles.get(i);
                }
            }

            if (sourceCf == null) {
                System.err.println("CF not found: " + cfName);
                return;
            }

            if (wordsCf == null) {
                if (rebuildWordsOnly) {
                    try (final ColumnFamilyOptions wordsCfOptions = new ColumnFamilyOptions()) {
                        wordsCf = db.createColumnFamily(new ColumnFamilyDescriptor(wordsCfName.getBytes(StandardCharsets.UTF_8), wordsCfOptions));
                        handles.add(wordsCf);
                        System.out.println("Words CF created: " + wordsCfName);
                    }
                }
                if (wordsCf == null) {
                    System.out.println("Words CF not found: " + wordsCfName + " (continuing with postings-only cleanup)");
                }
            }

            if (rebuildWordsOnly) {
                rebuildWordsCf(db, sourceCf, wordsCf, wordBytes);
            } else {
                cleanStopwordsInPlace(db, sourceCf, wordsCf, wordBytes, stopwordHashes);
            }

        } finally {
            for (final ColumnFamilyHandle handle : handles) {
                try { handle.close(); } catch (final Exception ignored) {}
            }
            for (final ColumnFamilyDescriptor descriptor : descriptors) {
                try { descriptor.getOptions().close(); } catch (final Exception ignored) {}
            }
        }
    }

    private static void rebuildWordsCf(
            final RocksDB db,
            final ColumnFamilyHandle sourceCf,
            final ColumnFamilyHandle wordsCf,
            final int wordBytes) throws Exception {

        System.out.println("=== REBUILD WORDS CF from postings ===");

        long totalPostings = 0L;
        long distinctWords = 0L;
        byte[] currentWord = null;

        try (WriteOptions wo = new WriteOptions().setDisableWAL(true);
             WriteBatch batch = new WriteBatch();
             RocksIterator it = db.newIterator(sourceCf)) {

            it.seekToFirst();
            while (it.isValid()) {
                final byte[] key = it.key();
                if (key.length >= wordBytes) {
                    totalPostings++;
                    final byte[] word = Arrays.copyOfRange(key, 0, wordBytes);
                    if (currentWord == null || !Arrays.equals(currentWord, word)) {
                        batch.put(wordsCf, word, EMPTY_WORD_VALUE);
                        currentWord = word;
                        distinctWords++;

                        if (distinctWords % 20000 == 0) {
                            db.write(wo, batch);
                            batch.clear();
                            System.out.println("Words rebuilt: " + distinctWords + ", postings scanned: " + totalPostings);
                        }
                    }
                }
                it.next();
            }

            if (batch.count() > 0) {
                db.write(wo, batch);
            }
        }

        System.out.println("=== WORDS CF Rebuild Complete ===");
        System.out.println("Distinct words written: " + distinctWords);
        System.out.println("Postings scanned: " + totalPostings);
    }

    private static void cleanStopwordsInPlace(
            final RocksDB db,
            final ColumnFamilyHandle sourceCf,
            final ColumnFamilyHandle wordsCf,
            final int wordBytes,
            final Set<String> stopwordHashes) throws Exception {

        System.out.println("=== CLEAN STOPWORDS ONLY (in-place) ===");

        long scannedPostings = 0L;
        long deletedPostings = 0L;
        long deletedWords = 0L;
        byte[] currentWord = null;
        boolean currentWordIsStopword = false;

        try (WriteOptions wo = new WriteOptions().setDisableWAL(true);
             WriteBatch batch = new WriteBatch();
             RocksIterator it = db.newIterator(sourceCf)) {

            it.seekToFirst();
            while (it.isValid()) {
                final byte[] key = it.key();
                if (key.length < wordBytes) {
                    it.next();
                    continue;
                }

                scannedPostings++;
                final byte[] word = Arrays.copyOfRange(key, 0, wordBytes);
                if (currentWord == null || !Arrays.equals(currentWord, word)) {
                    if (currentWord != null && currentWordIsStopword) {
                        deletedWords++;
                        if (wordsCf != null) {
                            batch.delete(wordsCf, currentWord);
                        }
                    }
                    currentWord = word;
                    currentWordIsStopword = isStopwordHash(word, stopwordHashes);
                }

                if (currentWordIsStopword) {
                    batch.delete(sourceCf, key.clone());
                    deletedPostings++;
                }

                if (batch.count() >= 50000) {
                    db.write(wo, batch);
                    batch.clear();
                    System.out.println("Scanned postings: " + scannedPostings + ", deleted postings: " + deletedPostings + ", deleted words: " + deletedWords);
                }

                it.next();
            }

            if (currentWord != null && currentWordIsStopword) {
                deletedWords++;
                if (wordsCf != null) {
                    batch.delete(wordsCf, currentWord);
                }
            }

            if (batch.count() > 0) {
                db.write(wo, batch);
            }
        }

        System.out.println("=== STOPWORD CLEANUP COMPLETE ===");
        System.out.println("Scanned postings: " + scannedPostings);
        System.out.println("Deleted postings: " + deletedPostings + " (" + percent(deletedPostings, scannedPostings) + "%)");
        System.out.println("Deleted words: " + deletedWords);
    }

    private static void dryRunAnalysis(
            final RocksDB db,
            final ColumnFamilyHandle cf,
            final String dbPath,
            final int wordBytes,
            final int k,
            final Set<String> stopwordHashes) {

        System.out.println("=== DRY RUN: Top-K=" + k + " Projection ===");

        long totalWords = 0;
        long totalPostings = 0;
        long keptPostings = 0;
        long droppedPostings = 0;
        long cappedWords = 0;
        long stopwordWords = 0;
        long stopwordPostings = 0;

        byte[] currentWord = null;
        long currentCount = 0;

        try (RocksIterator it = db.newIterator(cf)) {
            it.seekToFirst();
            while (it.isValid()) {
                final byte[] key = it.key();
                if (key.length < wordBytes) {
                    it.next();
                    continue;
                }

                final byte[] word = Arrays.copyOfRange(key, 0, wordBytes);

                if (currentWord == null) {
                    currentWord = word;
                    currentCount = 1;
                } else if (Arrays.equals(currentWord, word)) {
                    currentCount++;
                } else {
                    totalWords++;
                    totalPostings += currentCount;

                    if (isStopwordHash(currentWord, stopwordHashes)) {
                        stopwordWords++;
                        stopwordPostings += currentCount;
                        droppedPostings += currentCount;
                    } else {
                        if (currentCount > k) {
                            cappedWords++;
                            keptPostings += k;
                            droppedPostings += (currentCount - k);
                        } else {
                            keptPostings += currentCount;
                        }
                    }

                    currentWord = word;
                    currentCount = 1;
                }

                it.next();
            }
        }

        // finalize last word
        if (currentWord != null) {
            totalWords++;
            totalPostings += currentCount;
            if (isStopwordHash(currentWord, stopwordHashes)) {
                stopwordWords++;
                stopwordPostings += currentCount;
                droppedPostings += currentCount;
            } else {
                if (currentCount > k) {
                    cappedWords++;
                    keptPostings += k;
                    droppedPostings += (currentCount - k);
                } else {
                    keptPostings += currentCount;
                }
            }
        }

        System.out.println("Total words: " + totalWords);
        System.out.println("Total postings: " + totalPostings);
        System.out.println("Kept postings: " + keptPostings);
        System.out.println("Dropped postings: " + droppedPostings + " (" + percent(droppedPostings, totalPostings) + "%)");
        System.out.println("Words capped: " + cappedWords + " (" + percent(cappedWords, totalWords) + "%)");
        if (!stopwordHashes.isEmpty()) {
            System.out.println("Stopword words dropped: " + stopwordWords + " (" + percent(stopwordWords, totalWords) + "%)");
            System.out.println("Stopword postings dropped: " + stopwordPostings + " (" + percent(stopwordPostings, totalPostings) + "%)");
        }

        final long sourceBytes = directorySize(new File(dbPath));
        final long keptBytes = totalPostings == 0 ? 0 : Math.round((sourceBytes * (double) keptPostings) / totalPostings);
        final long droppedBytes = Math.max(0L, sourceBytes - keptBytes);

        System.out.println("Source size: " + formatBytes(sourceBytes));
        System.out.println("Estimated kept size: " + formatBytes(keptBytes));
        System.out.println("Estimated dropped size: " + formatBytes(droppedBytes));
        System.out.println("Estimated final size: " + formatBytes(keptBytes));
    }

    private static void applyReorg(
            final RocksDB sourceDb,
            final ColumnFamilyHandle sourceCf,
            final String sourceCfName,
            final List<byte[]> sourceCfNames,
            final int wordBytes,
            final int k,
            final boolean hostDiversity,
            final int maxPerHost,
            final String shadowDbPath,
            final Set<String> stopwordHashes) throws Exception {

        System.out.println("=== APPLY: Creating shadow DB with Top-K=" + k + " ===");
        System.out.println("Host diversity: " + hostDiversity + ", maxPerHost=" + maxPerHost);

        final File shadowDir = new File(shadowDbPath);
        if (shadowDir.exists()) {
            System.err.println("Shadow DB already exists: " + shadowDbPath);
            System.err.println("Please delete or use a different path.");
            return;
        }

        shadowDir.mkdirs();

        final List<ColumnFamilyDescriptor> shadowDesc = new ArrayList<>();
        boolean hasWordsCf = false;
        for (final byte[] cfNameBytes : sourceCfNames) {
            if (Arrays.equals(cfNameBytes, WORDS_CF_NAME)) {
                hasWordsCf = true;
            }
            shadowDesc.add(new ColumnFamilyDescriptor(cfNameBytes.clone(), new ColumnFamilyOptions()));
        }
        if (!hasWordsCf) {
            shadowDesc.add(new ColumnFamilyDescriptor(WORDS_CF_NAME.clone(), new ColumnFamilyOptions()));
            System.out.println("Source DB has no 'words' CF. Creating 'words' CF in shadow DB.");
        }

        final List<ColumnFamilyHandle> shadowHandles = new ArrayList<>();
        final DBOptions shadowDbOpts = new DBOptions()
                .setCreateIfMissing(true)
                .setCreateMissingColumnFamilies(true);

        try (RocksDB shadowDb = RocksDB.open(shadowDbOpts, shadowDbPath, shadowDesc, shadowHandles);
             WriteOptions wo = new WriteOptions().setDisableWAL(true)) {

            final Map<String, ColumnFamilyHandle> shadowCfByName = new HashMap<>();
            for (int i = 0; i < shadowDesc.size() && i < shadowHandles.size(); i++) {
                final String name = new String(shadowDesc.get(i).getName(), StandardCharsets.UTF_8);
                shadowCfByName.put(name, shadowHandles.get(i));
            }

            final ColumnFamilyHandle shadowCf = shadowCfByName.get(sourceCfName);
            if (shadowCf == null) {
                throw new IllegalStateException("Target CF not found in shadow DB: " + sourceCfName);
            }
            final ColumnFamilyHandle shadowWordsCf = shadowCfByName.get("words");

            long totalWords = 0;
            long totalPostings = 0;
            long keptPostings = 0;
            long droppedPostings = 0;
            long stopwordWords = 0;
            long stopwordPostings = 0;

            byte[] currentWord = null;
            final List<byte[]> currentKeys = new ArrayList<>();
            final List<byte[]> currentValues = new ArrayList<>();

            try (RocksIterator it = sourceDb.newIterator(sourceCf)) {
                it.seekToFirst();

                while (it.isValid()) {
                    final byte[] key = it.key();
                    if (key.length < wordBytes) {
                        it.next();
                        continue;
                    }

                    final byte[] word = Arrays.copyOfRange(key, 0, wordBytes);

                    if (currentWord == null) {
                        currentWord = word;
                        currentKeys.add(key);
                        currentValues.add(it.value());
                    } else if (Arrays.equals(currentWord, word)) {
                        currentKeys.add(key);
                        currentValues.add(it.value());
                    } else {
                        // process previous word
                        final boolean stopword = isStopwordHash(currentWord, stopwordHashes);
                        if (stopword) {
                            stopwordWords++;
                            stopwordPostings += currentKeys.size();
                        } else {
                            processWord(shadowDb, shadowCf, shadowWordsCf, wo, currentWord, currentKeys, currentValues, k, wordBytes, hostDiversity, maxPerHost);
                        }
                        
                        totalWords++;
                        totalPostings += currentKeys.size();
                        final int kept = stopword ? 0 : Math.min(currentKeys.size(), k);
                        keptPostings += kept;
                        droppedPostings += Math.max(0, currentKeys.size() - kept);

                        if (totalWords % 100000 == 0) {
                            System.out.println("Processed " + totalWords + " words, " + totalPostings + " postings...");
                        }

                        // start new word
                        currentWord = word;
                        currentKeys.clear();
                        currentValues.clear();
                        currentKeys.add(key);
                        currentValues.add(it.value());
                    }

                    it.next();
                }
            }

            // finalize last word
            if (currentWord != null && !currentKeys.isEmpty()) {
                final boolean stopword = isStopwordHash(currentWord, stopwordHashes);
                if (stopword) {
                    stopwordWords++;
                    stopwordPostings += currentKeys.size();
                } else {
                    processWord(shadowDb, shadowCf, shadowWordsCf, wo, currentWord, currentKeys, currentValues, k, wordBytes, hostDiversity, maxPerHost);
                }
                totalWords++;
                totalPostings += currentKeys.size();
                final int kept = stopword ? 0 : Math.min(currentKeys.size(), k);
                keptPostings += kept;
                droppedPostings += Math.max(0, currentKeys.size() - kept);
            }

            System.out.println("=== Reorg Complete ===");
            System.out.println("Total words: " + totalWords);
            System.out.println("Total postings: " + totalPostings);
            System.out.println("Kept postings: " + keptPostings);
            System.out.println("Dropped postings: " + droppedPostings + " (" + percent(droppedPostings, totalPostings) + "%)");
            if (!stopwordHashes.isEmpty()) {
                System.out.println("Stopword words dropped: " + stopwordWords + " (" + percent(stopwordWords, totalWords) + "%)");
                System.out.println("Stopword postings dropped: " + stopwordPostings + " (" + percent(stopwordPostings, totalPostings) + "%)");
            }

            // compact
            System.out.println("Compacting shadow DB...");
            shadowDb.compactRange(shadowCf, null, null, new CompactRangeOptions());
            System.out.println("Shadow DB created successfully at: " + shadowDbPath);

        } finally {
            for (final ColumnFamilyHandle h : shadowHandles) {
                try { h.close(); } catch (final Exception ignored) {}
            }
            for (final ColumnFamilyDescriptor d : shadowDesc) {
                try { d.getOptions().close(); } catch (final Exception ignored) {}
            }
            shadowDbOpts.close();
        }
    }

    private static void processWord(
            final RocksDB shadowDb,
            final ColumnFamilyHandle shadowCf,
            final ColumnFamilyHandle shadowWordsCf,
            final WriteOptions wo,
            final byte[] word,
            final List<byte[]> keys,
            final List<byte[]> values,
            final int k,
            final int wordBytes,
            final boolean hostDiversity,
            final int maxPerHost) throws Exception {

        if (keys.size() <= k) {
            // all postings fit, write as-is
            try (WriteBatch batch = new WriteBatch()) {
                if (shadowWordsCf != null && !keys.isEmpty()) {
                    batch.put(shadowWordsCf, word, new byte[0]);
                }
                for (int i = 0; i < keys.size(); i++) {
                    batch.put(shadowCf, keys.get(i), values.get(i));
                }
                shadowDb.write(wo, batch);
            }
            return;
        }

        // need to rank and select top-k
        final PriorityQueue<ScoredPosting> heap = new PriorityQueue<>(k);

        for (int i = 0; i < keys.size(); i++) {
            final byte[] urlHash = Arrays.copyOfRange(keys.get(i), wordBytes, keys.get(i).length);
            final byte[] meta = values.get(i);
            final double score = computeScore(meta);

            if (heap.size() < k) {
                heap.offer(new ScoredPosting(urlHash, meta, score));
            } else if (heap.peek().score < score) {
                heap.poll();
                heap.offer(new ScoredPosting(urlHash, meta, score));
            }
        }

        final List<ScoredPosting> selectedPostings = selectTopK(heap, k, hostDiversity, maxPerHost);

        // write selected postings
        try (WriteBatch batch = new WriteBatch()) {
            if (shadowWordsCf != null && !selectedPostings.isEmpty()) {
                batch.put(shadowWordsCf, word, new byte[0]);
            }
            for (final ScoredPosting sp : selectedPostings) {
                final byte[] key = new byte[wordBytes + sp.urlHash.length];
                System.arraycopy(word, 0, key, 0, wordBytes);
                System.arraycopy(sp.urlHash, 0, key, wordBytes, sp.urlHash.length);
                batch.put(shadowCf, key, sp.meta);
            }
            shadowDb.write(wo, batch);
        }
    }

    private static List<ScoredPosting> selectTopK(
            final PriorityQueue<ScoredPosting> heap,
            final int k,
            final boolean hostDiversity,
            final int maxPerHost) {

        if (heap.isEmpty()) {
            return Collections.emptyList();
        }

        final List<ScoredPosting> sorted = new ArrayList<>(heap);
        sorted.sort((a, b) -> Double.compare(b.score, a.score)); // descending by score

        if (!hostDiversity || maxPerHost <= 0) {
            return sorted.size() <= k ? sorted : new ArrayList<>(sorted.subList(0, k));
        }

        final List<ScoredPosting> selected = new ArrayList<>(Math.min(k, sorted.size()));
        final List<ScoredPosting> overflow = new ArrayList<>();
        final Map<String, Integer> hostCounts = new HashMap<>();

        // pass 1: enforce host cap
        for (final ScoredPosting candidate : sorted) {
            if (selected.size() >= k) {
                break;
            }
            final String hostKey = hostKey(candidate.urlHash);
            final int used = hostCounts.getOrDefault(hostKey, 0);
            if (used < maxPerHost) {
                selected.add(candidate);
                hostCounts.put(hostKey, used + 1);
            } else {
                overflow.add(candidate);
            }
        }

        // pass 2: fill remaining slots without cap to avoid losing quality/recall
        if (selected.size() < k) {
            for (final ScoredPosting candidate : overflow) {
                selected.add(candidate);
                if (selected.size() >= k) {
                    break;
                }
            }
        }

        return selected;
    }

    private static String hostKey(final byte[] urlHash) {
        if (urlHash == null || urlHash.length < 12) {
            return "unknown";
        }
        // YaCy URL hash layout: first 6 bytes path/mod part, last 6 bytes host part
        final byte[] hostPart = Arrays.copyOfRange(urlHash, 6, 12);
        return new String(hostPart, StandardCharsets.ISO_8859_1);
    }

    private static double computeScore(final byte[] meta) {
        if (meta == null || meta.length == 0) {
            return 0.0;
        }
        try {
            final WordReference reference = WORD_REFERENCE_FACTORY.produceSlow(WORD_REFERENCE_FACTORY.getRow().newEntry(meta));

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
        } catch (final Throwable ignored) {
            return 0.0;
        }
    }

    private static long directorySize(final File path) {
        if (path == null || !path.exists()) {
            return 0L;
        }
        if (path.isFile()) {
            return path.length();
        }
        final File[] children = path.listFiles();
        if (children == null || children.length == 0) {
            return 0L;
        }
        long sum = 0L;
        for (final File child : children) {
            sum += directorySize(child);
        }
        return sum;
    }

    private static String percent(final long part, final long total) {
        if (total <= 0) return "0.00";
        return String.format(Locale.ROOT, "%.2f", (part * 100.0) / total);
    }

    private static String formatBytes(final long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.2f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format(Locale.ROOT, "%.2f MB", bytes / (1024.0 * 1024.0));
        return String.format(Locale.ROOT, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    private static boolean isStopwordHash(final byte[] wordHash, final Set<String> stopwordHashes) {
        if (stopwordHashes == null || stopwordHashes.isEmpty() || wordHash == null) {
            return false;
        }
        return stopwordHashes.contains(new String(wordHash, StandardCharsets.ISO_8859_1));
    }

    private static Set<String> loadStopwordHashes(final String stopwordFiles, final int wordBytes) throws IOException {
        final Set<String> hashes = new HashSet<>();
        if (stopwordFiles == null || stopwordFiles.trim().isEmpty()) {
            return hashes;
        }

        final String[] files = stopwordFiles.split(",");
        for (final String filePathRaw : files) {
            final String filePath = filePathRaw == null ? "" : filePathRaw.trim();
            if (filePath.isEmpty()) {
                continue;
            }
            final Path path = new File(filePath).toPath();
            if (!Files.exists(path)) {
                System.err.println("Stopword file not found, skipped: " + filePath);
                continue;
            }

            for (final String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                if (line == null) {
                    continue;
                }
                String trimmed = line.trim();
                final int commentPos = trimmed.indexOf('|');
                if (commentPos == 0) {
                    continue;
                }
                if (commentPos > 0) {
                    trimmed = trimmed.substring(0, commentPos).trim();
                }
                if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("|")) {
                    continue;
                }
                final byte[] hash = Word.word2hash(trimmed.toLowerCase(Locale.ENGLISH));
                if (hash != null && hash.length == wordBytes) {
                    hashes.add(new String(hash, StandardCharsets.ISO_8859_1));
                }
            }
        }
        return hashes;
    }

    private static String defaultSolrStopwordFiles(final String language, final boolean explicitLang) {
        final StringBuilder files = new StringBuilder("defaults/solr/stopwords.txt");

        if (explicitLang) {
            final String lang = (language == null || language.trim().isEmpty()) ? "en" : language.trim().toLowerCase(Locale.ROOT);
            files.append(",defaults/solr/lang/stopwords_").append(lang).append(".txt");
            return files.toString();
        }
        System.out.println("No --lang provided. Using only defaults/solr/stopwords.txt. "
                + "For language-specific cleanup use --lang=<code>.");
        return files.toString();
    }

    private static Map<String, String> parseArgs(final String[] args) {
        final Map<String, String> parsed = new HashMap<>();
        for (final String arg : args) {
            if (arg.startsWith("--")) {
                final int sep = arg.indexOf('=');
                if (sep > 2) {
                    parsed.put(arg.substring(2, sep), arg.substring(sep + 1));
                } else {
                    parsed.put(arg.substring(2), "true");
                }
            }
        }
        return parsed;
    }

    private static String required(final Map<String, String> params, final String key) {
        final String value = params.get(key);
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("Missing --" + key + "=...");
        }
        if (!new File(value).exists()) {
            throw new IllegalArgumentException("Path not found: " + value);
        }
        return value;
    }
}
