package net.yacy.rocksdb;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Set;

import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.DBOptions;
import org.rocksdb.Options;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;

import net.yacy.cora.util.ConcurrentLog;

public final class WordUrlRefStore implements AutoCloseable {

    static {
        RocksDB.loadLibrary();
    }

    private static final byte[] EMPTY_VALUE = new byte[0];
    private static final int CLEANUP_THRESHOLD = 1000; // Cleanup nach 1000 gelöschten URLs
    private static final int WRITE_BUFFER_SIZE = 1000; // Batch nach 1000 Upserts

    private final File dbPath;
    private final DBOptions dbOptions;
    private final ColumnFamilyOptions cfOptions;
    private final ReadOptions readOptions;
    private final WriteOptions writeOptions;
    private final RocksDB db;
    private final ColumnFamilyHandle mainCF;  // wordhash+urlhash -> meta
    private final ColumnFamilyHandle wordCF;  // wordhash -> empty (nur Keys für Zählung)
    private final Set<ByteArray> deletedWords; // Verzögerte Word-Cleanups
    private final Queue<UpsertRecord> writeBuffer; // Input buffering für Batching
    private volatile boolean closed;

    /**
     * Upsert record für batched writes
     */
    private static class UpsertRecord {
        final byte[] wordHash;
        final byte[] urlHash;
        final byte[] meta;

        UpsertRecord(final byte[] wordHash, final byte[] urlHash, final byte[] meta) {
            this.wordHash = wordHash;
            this.urlHash = urlHash;
            this.meta = meta;
        }
    }

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
        this.dbPath = dbPath;
        this.dbOptions = new DBOptions().setCreateIfMissing(true).setCreateMissingColumnFamilies(true);
        this.cfOptions = new ColumnFamilyOptions();
        this.readOptions = new ReadOptions();
        this.writeOptions = new WriteOptions().setDisableWAL(false);
        this.deletedWords = new HashSet<ByteArray>();
        this.writeBuffer = new LinkedList<UpsertRecord>();

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
            throw new IllegalStateException("cannot open rocksdb: " + dbPath.getAbsolutePath(), e);
        }
        this.closed = false;
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
        synchronized (this.writeBuffer) {
            this.writeBuffer.offer(new UpsertRecord(wordHash.clone(), urlHash.clone(), meta.clone()));
            if (this.writeBuffer.size() >= WRITE_BUFFER_SIZE) {
                flushWriteBuffer();
            }
        }
    }

    /**
     * Flush buffered write records to RocksDB using batched WriteBatch
     */
    private void flushWriteBuffer() {
        if (this.writeBuffer.isEmpty()) return;
        
        try (final WriteBatch batch = new WriteBatch()) {
            while (!this.writeBuffer.isEmpty()) {
                final UpsertRecord rec = this.writeBuffer.poll();
                if (rec == null) continue;
                final byte[] compositeKey = WordUrlKeyCodec.compose(rec.wordHash, rec.urlHash);
                batch.put(this.mainCF, compositeKey, rec.meta);
                batch.put(this.wordCF, rec.wordHash, EMPTY_VALUE);
            }
            this.db.write(this.writeOptions, batch);
        } catch (final RocksDBException e) {
            throw new IllegalStateException("rocksdb batch flush failed", e);
        }
    }

    public void upsertBatch(final List<WordUrlRefRecord> records) {
        ensureOpen();
        if (records == null || records.isEmpty()) return;

        try (final WriteBatch batch = new WriteBatch()) {
            for (final WordUrlRefRecord record : records) {
                if (record == null) continue;
                final byte[] meta = record.meta();
                if (meta == null || meta.length != RefMetaCodec.REF_SIZE) continue;
                final byte[] compositeKey = WordUrlKeyCodec.compose(record.wordHash(), record.urlHash());
                batch.put(this.mainCF, compositeKey, meta.clone());
                batch.put(this.wordCF, record.wordHash().clone(), EMPTY_VALUE);
            }
            this.db.write(this.writeOptions, batch);
        } catch (final RocksDBException e) {
            throw new IllegalStateException("rocksdb batch upsert failed", e);
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

    public long distinctWordCount() {
        ensureOpen();
        return estimateNumKeys(this.wordCF);
    }

    public long size() {
        ensureOpen();
        return estimateNumKeys(this.mainCF);
    }

    public boolean isEmpty() {
        ensureOpen();
        return estimateNumKeys(this.mainCF) == 0;
    }

    private long estimateNumKeys(final ColumnFamilyHandle cf) {
        try {
            final String estimate = this.db.getProperty(cf, "rocksdb.estimate-num-keys");
            return estimate != null ? Long.parseLong(estimate) : 0L;
        } catch (final Exception e) {
            return 0L;
        }
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

    private void ensureOpen() {
        if (this.closed) throw new IllegalStateException("store is closed");
    }

    @Override
    public void close() throws IOException {
        if (this.closed) return;
        this.closed = true;
        // Flush any buffered writes before shutdown
        synchronized (this.writeBuffer) {
            if (!this.writeBuffer.isEmpty()) {
                flushWriteBuffer();
            }
        }
        // Finales Cleanup ausstehender Word-Deletes
        synchronized (this.deletedWords) {
            if (!this.deletedWords.isEmpty()) {
                cleanupDeletedWords();
            }
        }
        // RocksDB-Optimierung vor dem Schließen
        try {
            ConcurrentLog.info("WordUrlRefStore", "RocksDB wird optimiert, bitte warten...");
            // Komprimiere beide Column Families
            this.db.compactRange(this.mainCF);
            this.db.compactRange(this.wordCF);
            ConcurrentLog.info("WordUrlRefStore", "RocksDB-Optimierung abgeschlossen");
        } catch (final RocksDBException e) {
            ConcurrentLog.warn("WordUrlRefStore", "RocksDB-Optimierung fehlgeschlagen, aber fahren mit Shutdown fort: " + e.getMessage());
        }
        this.mainCF.close();
        this.wordCF.close();
        this.db.close();
        this.writeOptions.close();
        this.readOptions.close();
        this.cfOptions.close();
        this.dbOptions.close();
    }
}
