package net.yacy.rocksdb;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.rocksdb.BlockBasedTableConfig;
import org.rocksdb.BloomFilter;
import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.DBOptions;
import org.rocksdb.LRUCache;
import org.rocksdb.Options;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.Snapshot;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;

import net.yacy.cora.util.ConcurrentLog;
import net.yacy.search.EventTracker;

public final class WordUrlRefStore implements AutoCloseable {

    static {
        RocksDB.loadLibrary();
    }

    private static final byte[] EMPTY_VALUE = new byte[0];
    private static final int CLEANUP_THRESHOLD = 1000; // Cleanup nach 1000 gelöschten URLs
    private static final int MAX_RAM_ENTRIES = 50000; // Max unique words im RAM Cache (wie alte IndexCell)
    private static final int MAX_RAM_REFERENCES = Integer.getInteger("index.rocksdb.maxRamReferences", 10_000_000); // Max total URL references
    private static final long RAM_FLUSH_INTERVAL = 60000; // Flush alle 60 Sekunden wenn nicht leer
        private static final int MAX_WRITTEN_WORDS_CACHE = Math.max(10_000,
            Integer.getInteger("index.rocksdb.writtenWordsCacheSize", 200_000));

    private final File dbPath;
    private final DBOptions dbOptions;
    private final ColumnFamilyOptions cfOptions;
    private final LRUCache blockCache;
    private final BloomFilter bloomFilter;
    private final ReadOptions readOptions;
    private final WriteOptions writeOptions;
    private final RocksDB db;
    private final ColumnFamilyHandle mainCF;  // wordhash+urlhash -> meta
    private final ColumnFamilyHandle wordCF;  // wordhash -> empty (nur Keys für Zählung)
    private final Set<ByteArray> deletedWords; // Verzögerte Word-Cleanups
    private final Map<ByteArray, Boolean> writtenWordsCache; // begrenzter LRU Cache für wordCF-Dedup
    
    // RAM Cache für ReferenceContainers (wie alte IndexCell)
    private final Map<ByteArray, Map<ByteArray, byte[]>> ramCache; // word -> (url -> meta)
    private volatile int totalRamReferences = 0; // Gesamtzahl References im Cache
    private volatile long lastRamFlush = System.currentTimeMillis();
    private volatile boolean flushShallRun = true;
    private final Thread flushThread;
    
    private volatile boolean closed;

    private static class ByteArray {
        private final byte[] data;
        private final int hash;

        ByteArray(final byte[] data) {
            this.data = data.clone();
            this.hash = Arrays.hashCode(this.data);
        }

        @Override
        public boolean equals(final Object obj) {
            if (!(obj instanceof ByteArray)) return false;
            return Arrays.equals(this.data, ((ByteArray) obj).data);
        }

        @Override
        public int hashCode() {
            return this.hash;
        }

        byte[] bytes() {
            return this.data;
        }
    }

    public WordUrlRefStore(final File dbPath) {
        if (dbPath == null) throw new IllegalArgumentException("dbPath must not be null");
        if (!dbPath.exists() && !dbPath.mkdirs()) {
            throw new IllegalArgumentException("cannot create db path: " + dbPath.getAbsolutePath());
        }
        final long blockCacheMB = Long.getLong("index.rocksdb.blockCacheMB", 256L);
        final long writeBufferMB = Long.getLong("index.rocksdb.writeBufferMB", 64L);
        final int maxWriteBufferNumber = Integer.getInteger("index.rocksdb.maxWriteBufferNumber", 4);
        final int maxBackgroundJobs = Integer.getInteger("index.rocksdb.maxBackgroundJobs", 4);
        final int maxOpenFiles = Integer.getInteger("index.rocksdb.maxOpenFiles", 64);

        this.dbPath = dbPath;
        this.blockCache = new LRUCache(Math.max(64L, blockCacheMB) * 1024L * 1024L);
        this.bloomFilter = new BloomFilter(10, false);

        final BlockBasedTableConfig tableConfig = new BlockBasedTableConfig()
            .setBlockCache(this.blockCache)
            .setFilterPolicy(this.bloomFilter)
            .setCacheIndexAndFilterBlocks(true)
            .setCacheIndexAndFilterBlocksWithHighPriority(true)
            .setPinTopLevelIndexAndFilter(true);

        this.dbOptions = new DBOptions()
            .setCreateIfMissing(true)
            .setCreateMissingColumnFamilies(true)
            .setMaxBackgroundJobs(Math.max(2, maxBackgroundJobs))
            .setAdviseRandomOnOpen(true)  // ADDED: Tell O/S to avoid aggressive read-ahead on random access patterns
            .setUseFsync(false)  // ADDED: Use fdatasync instead of fsync for better performance (WAL still enabled)
            .setMaxOpenFiles(maxOpenFiles)  // Configurable open files limit (-1 = keep all open)
            .setAllowMmapReads(true)  // ADDED: Allow mmap for READ performance on large indices (auto-unmapped by MaxOpenFiles limit)
            .setAllowMmapWrites(false);  // ADDED: Forbid mmap writes (keep writes to buffered I/O for predictability)
        this.cfOptions = new ColumnFamilyOptions()
            .setTableFormatConfig(tableConfig)
            .useFixedLengthPrefixExtractor(WordUrlKeyCodec.WORD_HASH_LENGTH)
            .setMemtablePrefixBloomSizeRatio(0.05d)
            .setWriteBufferSize(Math.max(16L, writeBufferMB) * 1024L * 1024L)
            .setMaxWriteBufferNumber(Math.max(2, maxWriteBufferNumber));
        this.readOptions = new ReadOptions();
        this.writeOptions = new WriteOptions().setDisableWAL(false);
        this.deletedWords = new HashSet<ByteArray>();
        this.writtenWordsCache = Collections.synchronizedMap(new LinkedHashMap<ByteArray, Boolean>(16_384, 0.75f, true) {
            private static final long serialVersionUID = 1L;

            @Override
            protected boolean removeEldestEntry(final Map.Entry<ByteArray, Boolean> eldest) {
                return size() > MAX_WRITTEN_WORDS_CACHE;
            }
        });
        this.ramCache = new ConcurrentHashMap<ByteArray, Map<ByteArray, byte[]>>();

        try {
            // Column Family Descriptors: default + words
            final List<ColumnFamilyDescriptor> cfDescriptors = new ArrayList<ColumnFamilyDescriptor>();
            cfDescriptors.add(new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, this.cfOptions));
            cfDescriptors.add(new ColumnFamilyDescriptor("words".getBytes(), this.cfOptions));

            final List<ColumnFamilyHandle> cfHandles = new ArrayList<ColumnFamilyHandle>();
            this.db = RocksDB.open(this.dbOptions, dbPath.getAbsolutePath(), cfDescriptors, cfHandles);

            this.mainCF = cfHandles.get(0);
            this.wordCF = cfHandles.get(1);
        } catch (final RocksDBException e) {
            this.dbOptions.close();
            this.cfOptions.close();
            this.readOptions.close();
            this.writeOptions.close();
            this.bloomFilter.close();
            this.blockCache.close();
            throw new IllegalStateException("cannot open rocksdb: " + dbPath.getAbsolutePath(), e);
        }
        this.closed = false;
        
        // Start flush thread für RAM cache (wie alte IndexCell)
        this.flushThread = new Thread(new RamCacheFlusher(), "WordUrlRefStore.FlushThread(" + dbPath.getName() + ")");
        this.flushThread.start();

        ConcurrentLog.info("WordUrlRefStore", "RocksDB tuning active: blockCacheMB=" + Math.max(64L, blockCacheMB)
                + ", writeBufferMB=" + Math.max(16L, writeBufferMB)
                + ", maxWriteBufferNumber=" + Math.max(2, maxWriteBufferNumber)
                + ", maxBackgroundJobs=" + Math.max(2, maxBackgroundJobs)
            + ", maxOpenFiles=" + maxOpenFiles
                + ", maxRamEntries=" + MAX_RAM_ENTRIES
                + ", maxRamReferences=" + MAX_RAM_REFERENCES
                + ", writtenWordsCacheSize=" + MAX_WRITTEN_WORDS_CACHE);
    }
    
    /**
     * Flush thread für RAM cache (wie alte IndexCell FlushThread)
     */
    private class RamCacheFlusher implements Runnable {
        @Override
        public void run() {
            while (flushShallRun) {
                try {
                    checkRamCacheFlush();
                } catch (final Throwable e) {
                    ConcurrentLog.logException(e);
                }
                try { Thread.sleep(3000); } catch (final InterruptedException e) {}
            }
        }
    }
    
    /**
     * Prüft ob RAM Cache geflush werden muss
     */
    private void checkRamCacheFlush() {
        final long now = System.currentTimeMillis();
        final int ramSize = ramCache.size();
        final int ramRefs = totalRamReferences;
        
        // Update EventTracker für Grafik (wie alte IndexCell)
        EventTracker.update(EventTracker.EClass.WORDCACHE, Long.valueOf(ramSize), true);
        
        // Flush wenn Cache voll (words ODER references) oder Zeit-Limit überschritten
        if (ramSize >= MAX_RAM_ENTRIES || 
            ramRefs >= MAX_RAM_REFERENCES ||
            (ramSize > 0 && (now - lastRamFlush) > RAM_FLUSH_INTERVAL)) {
            
            ConcurrentLog.info("WordUrlRefStore", "Flushing RAM cache: " + ramSize + " words, " + ramRefs + " references");
            flushRamCache();
        }
    }
    
    /**
     * Flush den RAM cache zu RocksDB
     */
    private synchronized void flushRamCache() {
        if (ramCache.isEmpty()) return;
        
        try (final WriteBatch batch = new WriteBatch()) {
            int writtenRefs = 0;
            int newWords = 0;
            
            // Durchiteriere alle words im Cache
            for (final Map.Entry<ByteArray, Map<ByteArray, byte[]>> wordEntry : ramCache.entrySet()) {
                final ByteArray wordKey = wordEntry.getKey();
                final byte[] wordHash = wordKey.bytes();
                final Map<ByteArray, byte[]> urlMap = wordEntry.getValue();
                
                // Schreibe alle url references für dieses word
                for (final Map.Entry<ByteArray, byte[]> urlEntry : urlMap.entrySet()) {
                    final byte[] urlHash = urlEntry.getKey().bytes();
                    final byte[] meta = urlEntry.getValue();
                    final byte[] compositeKey = WordUrlKeyCodec.compose(wordHash, urlHash);
                    batch.put(this.mainCF, compositeKey, meta);
                    writtenRefs++;
                }
                
                // Schreibe word nur wenn nicht kürzlich geschrieben (begrenzter LRU-Dedup)
                if (!this.writtenWordsCache.containsKey(wordKey)) {
                    batch.put(this.wordCF, wordHash, EMPTY_VALUE);
                    this.writtenWordsCache.put(wordKey, Boolean.TRUE);
                    newWords++;
                }
            }
            
            this.db.write(this.writeOptions, batch);
            
            final int flushedWords = ramCache.size();
            ramCache.clear();
            totalRamReferences = 0;
            lastRamFlush = System.currentTimeMillis();
            
            ConcurrentLog.info("WordUrlRefStore", "RAM cache flush complete: " + flushedWords + " words (" + newWords + " new), " + writtenRefs + " references");
        } catch (final RocksDBException e) {
            ConcurrentLog.severe("WordUrlRefStore", "RAM cache flush failed", e);
        }
    }

    /**
     * Kontrolliert die WAL-Aktivierung. Beim Bulk-Import sollte WAL deaktiviert sein
     * für bessere Performance. Nach dem Import sollte WAL wieder aktiviert sein.
     *
     * @param disable true = WAL wird deaktiviert, false = WAL wird aktiviert
     */
    public synchronized void setDisableWAL(final boolean disable) {
        if (this.writeOptions != null) {
            this.writeOptions.setDisableWAL(disable);
            final String status = disable ? "DISABLED" : "ENABLED";
            ConcurrentLog.info("WordUrlRefStore", "WAL " + status + " for " + this.dbPath.getName());
        }
    }

    public void upsert(final byte[] wordHash, final byte[] urlHash, final byte[] meta) {
        ensureOpen();
        if (meta == null || meta.length != RefMetaCodec.REF_SIZE) {
            throw new IllegalArgumentException("meta must be 40 bytes");
        }
        
        // Schreibe in den RAM cache (wie alte IndexCell)
        final ByteArray wordKey = new ByteArray(wordHash);
        final ByteArray urlKey = new ByteArray(urlHash);
        
        Map<ByteArray, byte[]> urlMap = this.ramCache.get(wordKey);
        if (urlMap == null) {
            urlMap = new ConcurrentHashMap<ByteArray, byte[]>();
            this.ramCache.put(wordKey, urlMap);
        }
        
        final byte[] oldMeta = urlMap.put(urlKey, meta.clone());
        if (oldMeta == null) {
            // Neue reference
            this.totalRamReferences++;
        }
        // Note: update triggert automatisch flush durch FlushThread bei Bedarf
    }

    public void upsertBatch(final List<WordUrlRefRecord> records) {
        ensureOpen();
        if (records == null || records.isEmpty()) return;

        // Schreibe alle in den RAM cache
        for (final WordUrlRefRecord record : records) {
            if (record == null) continue;
            final byte[] meta = record.meta();
            if (meta == null || meta.length != RefMetaCodec.REF_SIZE) continue;
            
            final ByteArray wordKey = new ByteArray(record.wordHash());
            final ByteArray urlKey = new ByteArray(record.urlHash());
            
            Map<ByteArray, byte[]> urlMap = this.ramCache.get(wordKey);
            if (urlMap == null) {
                urlMap = new ConcurrentHashMap<ByteArray, byte[]>();
                this.ramCache.put(wordKey, urlMap);
            }
            
            final byte[] oldMeta = urlMap.put(urlKey, meta.clone());
            if (oldMeta == null) {
                this.totalRamReferences++;
            }
        }
    }

    public byte[] get(final byte[] wordHash, final byte[] urlHash) {
        ensureOpen();
        try {
            final byte[] value = this.db.get(this.mainCF, WordUrlKeyCodec.compose(wordHash, urlHash));
            return value == null ? null : value.clone();
        } catch (final RocksDBException e) {
            throw new IllegalStateException("rocksdb get failed", e);
        }
    }

    public boolean delete(final byte[] wordHash, final byte[] urlHash) {
        ensureOpen();
        final byte[] key = WordUrlKeyCodec.compose(wordHash, urlHash);
        try {
            final byte[] existing = this.db.get(this.mainCF, key);
            if (existing == null) return false;
            this.db.delete(this.mainCF, this.writeOptions, key);
            // Verzögertes Word-Cleanup: sammeln für späteren Batch
            synchronized (this.deletedWords) {
                this.deletedWords.add(new ByteArray(wordHash));
                if (this.deletedWords.size() >= CLEANUP_THRESHOLD) {
                    cleanupDeletedWords();
                }
            }
            return true;
        } catch (final RocksDBException e) {
            throw new IllegalStateException("rocksdb delete failed", e);
        }
    }

    public int deleteWord(final byte[] wordHash) {
        ensureOpen();
        int deleted = 0;
        final List<byte[]> keys = new ArrayList<byte[]>();
        try (final RocksIterator iterator = this.db.newIterator(this.mainCF, this.readOptions)) {
            iterator.seek(WordUrlKeyCodec.wordPrefix(wordHash));
            while (iterator.isValid()) {
                final byte[] key = iterator.key();
                if (!WordUrlKeyCodec.hasWordPrefix(key, wordHash)) break;
                keys.add(key.clone());
                iterator.next();
            }

            for (final byte[] key : keys) {
                this.db.delete(this.mainCF, this.writeOptions, key);
                deleted++;
            }

            // Word aus Word CF löschen (sofort, da wir wissen dass keine Refs mehr existieren)
            if (deleted > 0) {
                this.db.delete(this.wordCF, this.writeOptions, wordHash);
                this.writtenWordsCache.remove(new ByteArray(wordHash));
            }
        } catch (final RocksDBException e) {
            throw new IllegalStateException("rocksdb deleteWord failed", e);
        }
        return deleted;
    }

    private void cleanupDeletedWords() {
        // Wird nur aus synchronized Block aufgerufen
        final List<ByteArray> toCleanup = new ArrayList<ByteArray>(this.deletedWords);
        this.deletedWords.clear();

        try {
            for (final ByteArray wordArray : toCleanup) {
                final byte[] wordHash = wordArray.bytes();
                // Prüfe ob noch Refs für dieses Word existieren
                boolean hasRefs = false;
                try (final RocksIterator iterator = this.db.newIterator(this.mainCF, this.readOptions)) {
                    iterator.seek(WordUrlKeyCodec.wordPrefix(wordHash));
                    if (iterator.isValid()) {
                        final byte[] key = iterator.key();
                        hasRefs = WordUrlKeyCodec.hasWordPrefix(key, wordHash);
                    }
                }
                // Wenn keine Refs mehr: aus Word CF löschen
                if (!hasRefs) {
                    this.db.delete(this.wordCF, this.writeOptions, wordHash);
                    this.writtenWordsCache.remove(wordArray);
                }
            }
        } catch (final RocksDBException e) {
            throw new IllegalStateException("rocksdb cleanup failed", e);
        }
    }

    public List<WordUrlRefRecord> scanWord(final byte[] wordHash, final int limit) {
        ensureOpen();
        final List<WordUrlRefRecord> out = new ArrayList<WordUrlRefRecord>();
        try (final RocksIterator iterator = this.db.newIterator(this.mainCF, this.readOptions)) {
            iterator.seek(WordUrlKeyCodec.wordPrefix(wordHash));
            while (iterator.isValid()) {
                final byte[] key = iterator.key();
                if (!WordUrlKeyCodec.hasWordPrefix(key, wordHash)) break;
                final byte[] urlHash = WordUrlKeyCodec.extractUrlHash(key);
                out.add(new WordUrlRefRecord(wordHash.clone(), urlHash, iterator.value().clone()));
                if (limit > 0 && out.size() >= limit) break;
                iterator.next();
            }
        }

        return out;
    }

    public List<WordUrlRefRecord> scanAll() {
        ensureOpen();
        final List<WordUrlRefRecord> out = new ArrayList<WordUrlRefRecord>();
        try (final RocksIterator iterator = this.db.newIterator(this.mainCF, this.readOptions)) {
            iterator.seekToFirst();
            while (iterator.isValid()) {
                final byte[] key = iterator.key();
                final byte[] wordHash = WordUrlKeyCodec.extractWordHash(key);
                final byte[] urlHash = WordUrlKeyCodec.extractUrlHash(key);
                out.add(new WordUrlRefRecord(wordHash, urlHash, iterator.value().clone()));
                iterator.next();
            }
        }
        return out;
    }

    public RocksIterator newWordIterator() {
        ensureOpen();
        return this.db.newIterator(this.wordCF, this.readOptions);
    }

    /**
     * Returns the number of distinct words (keys in wordCF).
     * O(1) operation - just reads the counter.
     * Automatically triggers background refresh every 10 minutes.
     */
    public long distinctWordCount() {
        ensureOpen();

        // Always use estimate to avoid expensive iteration
        try {
            final String estimate = this.db.getProperty(this.wordCF, "rocksdb.estimate-num-keys");
            return estimate != null ? Long.parseLong(estimate) : 0L;
        } catch (final Exception e) {
            return 0L;
        }
    }

    /**
     * Returns the total number of index entries (word+url pairs).
     * O(1) operation - just reads the counter.
     * Automatically triggers background refresh every 10 minutes.
     */
    public long size() {
        ensureOpen();

        // Always use estimate to avoid expensive iteration
        try {
            final String estimate = this.db.getProperty(this.mainCF, "rocksdb.estimate-num-keys");
            return estimate != null ? Long.parseLong(estimate) : 0L;
        } catch (final Exception e) {
            return 0L;
        }
    }

    public boolean isEmpty() {
        ensureOpen();
        return size() == 0L;
    }

    public void clear() {
        ensureOpen();
        final List<byte[]> mainKeys = new ArrayList<byte[]>();
        final List<byte[]> wordKeys = new ArrayList<byte[]>();
        try (final RocksIterator mainIter = this.db.newIterator(this.mainCF, this.readOptions);
             final RocksIterator wordIter = this.db.newIterator(this.wordCF, this.readOptions)) {
            // Sammle alle Keys aus Main CF
            mainIter.seekToFirst();
            while (mainIter.isValid()) {
                mainKeys.add(mainIter.key().clone());
                mainIter.next();
            }
            // Sammle alle Keys aus Word CF
            wordIter.seekToFirst();
            while (wordIter.isValid()) {
                wordKeys.add(wordIter.key().clone());
                wordIter.next();
            }
            // Lösche alle Keys
            for (final byte[] key : mainKeys) {
                this.db.delete(this.mainCF, this.writeOptions, key);
            }
            for (final byte[] key : wordKeys) {
                this.db.delete(this.wordCF, this.writeOptions, key);
            }
        } catch (final RocksDBException e) {
            throw new IllegalStateException("rocksdb clear failed", e);
        }
    }

    public File dbPath() {
        return this.dbPath;
    }

    /**
     * Get words in indexing cache (für Status-Seite)
     * @return Anzahl unique words im RAM cache
     */
    public int wordsInCache() {
        return this.ramCache.size();
    }
    
    /**
     * Get total references in indexing cache (für Status-Seite)
     * @return Gesamtzahl word-url references im RAM cache
     */
    public int referencesInCache() {
        return this.totalRamReferences;
    }
    
    /**
     * Get RAM cache statistics
     * @return array [words, references, maxWords, fillPercent]
     */
    public double[] getRamCacheStats() {
        final int words = this.ramCache.size();
        final int refs = this.totalRamReferences;
        final double fillPercent = 100.0 * words / MAX_RAM_ENTRIES;
        return new double[]{words, refs, MAX_RAM_ENTRIES, fillPercent};
    }

    /**
     * Log RAM cache statistics
     */
    public void logRamCacheStats() {
        final double[] stats = getRamCacheStats();
        ConcurrentLog.info("WordUrlRefStore", String.format(
            "RAM cache: %d words, %d references (%.1f%% full, max: %d words)",
            (int) stats[0], (int) stats[1], stats[3], (int) stats[2]));
    }

    private void ensureOpen() {
        if (this.closed) throw new IllegalStateException("store is closed");
    }

    @Override
    public void close() throws IOException {
        if (this.closed) return;
        this.closed = true;
        
        // Stop flush thread
        this.flushShallRun = false;
        try {
            this.flushThread.join(10000); // Wait max 10 seconds
        } catch (final InterruptedException e) {
            ConcurrentLog.warn("WordUrlRefStore", "FlushThread interrupt during shutdown");
        }
        
        // Flush RAM cache before shutdown
        ConcurrentLog.info("WordUrlRefStore", "Flushing RAM cache before shutdown...");
        flushRamCache();
        
        // Final cleanup of pending Word deletes
        synchronized (this.deletedWords) {
            if (!this.deletedWords.isEmpty()) {
                cleanupDeletedWords();
            }
        }
        
        // Log cache statistics before closing
        logRamCacheStats();
        
        ConcurrentLog.info("WordUrlRefStore", "RocksDB is closing, please wait...");
        // RocksDB optimization before closing
        /*try {
            // Compact both Column Families
            this.db.compactRange(this.mainCF);
            this.db.compactRange(this.wordCF);
            ConcurrentLog.info("WordUrlRefStore", "RocksDB optimization completed");
        } catch (final RocksDBException e) {
            ConcurrentLog.warn("WordUrlRefStore", "RocksDB optimization failed, but continuing with shutdown: " + e.getMessage());
        }*/
        this.mainCF.close();
        this.wordCF.close();
        this.db.close();
        this.writeOptions.close();
        this.readOptions.close();
        this.cfOptions.close();
        this.dbOptions.close();
        this.bloomFilter.close();
        this.blockCache.close();
        ConcurrentLog.info("WordUrlRefStore", "RocksDB closed");
    }
}
