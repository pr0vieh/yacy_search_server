package net.yacy.rocksdb;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.rocksdb.Options;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;

import net.yacy.cora.util.ConcurrentLog;
import net.yacy.kelondro.data.word.WordReference;
import net.yacy.kelondro.data.word.WordReferenceFactory;
import net.yacy.kelondro.data.word.WordReferenceVars;

/**
 * Blob-mode storage contract:
 * - RocksDB key level is strictly one key per word hash (12 bytes).
 * - All URL references (urlHash + meta payload) for that word are packed into the value blob.
 * - No composite (wordHash+urlHash) keys are used in this class.
 */
public final class BlobWordUrlRefStorage implements WordUrlRefStorage {

    static {
        RocksDB.loadLibrary();
    }

    private static final int META_LENGTH = RefMetaCodec.REF_SIZE;
    private static final WordReferenceFactory WORD_REFERENCE_FACTORY = new WordReferenceFactory();

    private final File dbPath;
    private final Options options;
    private final ReadOptions readOptions;
    private final WriteOptions writeOptions;
    private final RocksDB db;

    private static final class ByteArray {
        private final byte[] bytes;
        private final int hash;

        ByteArray(final byte[] bytes) {
            this.bytes = bytes == null ? new byte[0] : bytes.clone();
            this.hash = Arrays.hashCode(this.bytes);
        }

        byte[] bytes() {
            return this.bytes;
        }

        @Override
        public int hashCode() {
            return this.hash;
        }

        @Override
        public boolean equals(final Object other) {
            if (!(other instanceof ByteArray)) return false;
            return Arrays.equals(this.bytes, ((ByteArray) other).bytes);
        }
    }

    public BlobWordUrlRefStorage(final File dbPath) {
        if (dbPath == null) throw new IllegalArgumentException("dbPath must not be null");
        if (!dbPath.exists() && !dbPath.mkdirs()) {
            throw new IllegalArgumentException("cannot create db path: " + dbPath.getAbsolutePath());
        }

        this.dbPath = dbPath;
        this.options = new Options().setCreateIfMissing(true);
        this.readOptions = new ReadOptions();
        this.writeOptions = new WriteOptions().setDisableWAL(false);
        try {
            this.db = RocksDB.open(this.options, this.dbPath.getAbsolutePath());
        } catch (final RocksDBException e) {
            this.writeOptions.close();
            this.readOptions.close();
            this.options.close();
            throw new IllegalStateException("cannot open blob-mode rocksdb: " + this.dbPath.getAbsolutePath(), e);
        }
        ConcurrentLog.info("BlobWordUrlRefStorage", "blob storage mode active at " + this.dbPath.getAbsolutePath());
    }

    @Override
    public String mode() {
        return "blob";
    }

    @Override
    public void setDisableWAL(final boolean disableWAL) {
        this.writeOptions.setDisableWAL(disableWAL);
    }

    @Override
    public void upsertBatch(final List<WordUrlRefRecord> records) throws IOException {
        if (records == null || records.isEmpty()) return;

        final Map<ByteArray, Map<ByteArray, byte[]>> grouped = new LinkedHashMap<ByteArray, Map<ByteArray, byte[]>>();
        for (final WordUrlRefRecord record : records) {
            if (record == null || record.wordHash() == null || record.urlHash() == null || record.meta() == null) continue;
            if (record.wordHash().length != WordUrlKeyCodec.WORD_HASH_LENGTH) continue;
            final ByteArray word = new ByteArray(record.wordHash());
            Map<ByteArray, byte[]> byUrl = grouped.get(word);
            if (byUrl == null) {
                byUrl = new HashMap<ByteArray, byte[]>();
                grouped.put(word, byUrl);
            }
            final ByteArray urlKey = new ByteArray(record.urlHash());
            final byte[] current = byUrl.get(urlKey);
            if (current == null) {
                byUrl.put(urlKey, record.meta().clone());
            } else {
                byUrl.put(urlKey, mergeRefMeta(current, record.meta()));
            }
        }
        if (grouped.isEmpty()) return;

        try (final WriteBatch batch = new WriteBatch()) {
            for (final Map.Entry<ByteArray, Map<ByteArray, byte[]>> entry : grouped.entrySet()) {
                final byte[] wordHash = entry.getKey().bytes();
                final List<byte[]> existing = decodeBlob(readBlob(wordHash));
                final Map<ByteArray, byte[]> merged = metasByUrl(existing);
                for (final Map.Entry<ByteArray, byte[]> incoming : entry.getValue().entrySet()) {
                    final ByteArray urlKey = incoming.getKey();
                    final byte[] previous = merged.get(urlKey);
                    if (previous == null) {
                        merged.put(urlKey, incoming.getValue());
                    } else {
                        merged.put(urlKey, mergeRefMeta(previous, incoming.getValue()));
                    }
                }
                final byte[] encoded = encodeBlob(new ArrayList<byte[]>(merged.values()));
                batch.put(wordHash, encoded);
            }
            this.db.write(this.writeOptions, batch);
        } catch (final RocksDBException e) {
            throw new IOException("blob upsertBatch failed", e);
        }
    }

    @Override
    public List<WordUrlRefRecord> scanWord(final byte[] termHash, final int limit) {
        final List<WordUrlRefRecord> out = new ArrayList<WordUrlRefRecord>();
        if (termHash == null || termHash.length != WordUrlKeyCodec.WORD_HASH_LENGTH) return out;
        final byte[] blob = readBlob(termHash);
        if (blob == null || blob.length == 0) return out;

        final List<byte[]> metas = decodeBlob(blob);
        for (final byte[] meta : metas) {
            if (meta == null || meta.length < WordUrlKeyCodec.URL_HASH_LENGTH) continue;
            out.add(new WordUrlRefRecord(termHash.clone(), RefMetaCodec.extractUrlHash(meta), meta));
            if (limit > 0 && out.size() >= limit) break;
        }
        return out;
    }

    @Override
    public boolean delete(final byte[] termHash, final byte[] urlHash) throws IOException {
        if (termHash == null || urlHash == null) return false;
        if (termHash.length != WordUrlKeyCodec.WORD_HASH_LENGTH || urlHash.length != WordUrlKeyCodec.URL_HASH_LENGTH) return false;

        final List<byte[]> metas = decodeBlob(readBlob(termHash));
        if (metas.isEmpty()) return false;

        final Map<ByteArray, byte[]> byUrl = metasByUrl(metas);
        final byte[] removed = byUrl.remove(new ByteArray(urlHash));
        if (removed == null) return false;

        try {
            if (byUrl.isEmpty()) {
                this.db.delete(this.writeOptions, termHash);
            } else {
                this.db.put(this.writeOptions, termHash, encodeBlob(new ArrayList<byte[]>(byUrl.values())));
            }
            return true;
        } catch (final RocksDBException e) {
            throw new IOException("blob delete failed", e);
        }
    }

    @Override
    public void deleteWord(final byte[] termHash) throws IOException {
        if (termHash == null || termHash.length != WordUrlKeyCodec.WORD_HASH_LENGTH) return;
        try {
            this.db.delete(this.writeOptions, termHash);
        } catch (final RocksDBException e) {
            throw new IOException("blob deleteWord failed", e);
        }
    }

    @Override
    public void clear() throws IOException {
        try (final RocksIterator iterator = this.db.newIterator(this.readOptions);
             final WriteBatch batch = new WriteBatch()) {
            iterator.seekToFirst();
            while (iterator.isValid()) {
                batch.delete(iterator.key());
                iterator.next();
            }
            this.db.write(this.writeOptions, batch);
        } catch (final RocksDBException e) {
            throw new IOException("blob clear failed", e);
        }
    }

    @Override
    public long size() {
        long total = 0L;
        try (final RocksIterator iterator = this.db.newIterator(this.readOptions)) {
            iterator.seekToFirst();
            while (iterator.isValid()) {
                total += decodeBlob(iterator.value()).size();
                iterator.next();
            }
        }
        return total;
    }

    @Override
    public boolean isEmpty() {
        try (final RocksIterator iterator = this.db.newIterator(this.readOptions)) {
            iterator.seekToFirst();
            return !iterator.isValid();
        }
    }

    @Override
    public long distinctWordCount() {
        long count = 0L;
        try (final RocksIterator iterator = this.db.newIterator(this.readOptions)) {
            iterator.seekToFirst();
            while (iterator.isValid()) {
                count++;
                iterator.next();
            }
        }
        return count;
    }

    @Override
    public RocksIterator newWordIterator() {
        final RocksIterator iterator = this.db.newIterator(this.readOptions);
        iterator.seekToFirst();
        return iterator;
    }

    @Override
    public int wordsInCache() {
        return 0;
    }

    @Override
    public int referencesInCache() {
        return 0;
    }

    @Override
    public void close() throws IOException {
        this.db.close();
        this.writeOptions.close();
        this.readOptions.close();
        this.options.close();
    }

    private byte[] readBlob(final byte[] wordHash) {
        try {
            return this.db.get(this.readOptions, wordHash);
        } catch (final RocksDBException e) {
            throw new IllegalStateException("blob read failed", e);
        }
    }

    private static Map<ByteArray, byte[]> metasByUrl(final List<byte[]> metas) {
        final Map<ByteArray, byte[]> out = new LinkedHashMap<ByteArray, byte[]>(Math.max(16, metas.size()));
        for (final byte[] meta : metas) {
            if (meta == null || meta.length < WordUrlKeyCodec.URL_HASH_LENGTH) continue;
            out.put(new ByteArray(RefMetaCodec.extractUrlHash(meta)), meta.clone());
        }
        return out;
    }

    private static byte[] encodeBlob(final List<byte[]> metas) {
        if (metas == null || metas.isEmpty()) return new byte[0];
        final int count = metas.size();
        final byte[] out = new byte[4 + count * META_LENGTH];
        out[0] = (byte) ((count >>> 24) & 0xFF);
        out[1] = (byte) ((count >>> 16) & 0xFF);
        out[2] = (byte) ((count >>> 8) & 0xFF);
        out[3] = (byte) (count & 0xFF);
        int offset = 4;
        for (final byte[] meta : metas) {
            final byte[] normalized = normalizeMeta(meta);
            System.arraycopy(normalized, 0, out, offset, META_LENGTH);
            offset += META_LENGTH;
        }
        return out;
    }

    private static List<byte[]> decodeBlob(final byte[] blob) {
        final List<byte[]> out = new ArrayList<byte[]>();
        if (blob == null || blob.length < 4) return out;
        final int count = ((blob[0] & 0xFF) << 24)
                | ((blob[1] & 0xFF) << 16)
                | ((blob[2] & 0xFF) << 8)
                | (blob[3] & 0xFF);
        if (count <= 0) return out;
        if (blob.length < 4 + count * META_LENGTH) return out;
        int offset = 4;
        for (int i = 0; i < count; i++) {
            final byte[] meta = new byte[META_LENGTH];
            System.arraycopy(blob, offset, meta, 0, META_LENGTH);
            out.add(meta);
            offset += META_LENGTH;
        }
        return out;
    }

    private static byte[] normalizeMeta(final byte[] meta) {
        if (meta == null) return new byte[META_LENGTH];
        if (meta.length == META_LENGTH) return meta;
        final byte[] normalized = new byte[META_LENGTH];
        final int len = Math.min(meta.length, META_LENGTH);
        System.arraycopy(meta, 0, normalized, 0, len);
        return normalized;
    }

    private static byte[] mergeRefMeta(final byte[] leftMeta, final byte[] rightMeta) {
        final byte[] left = normalizeMeta(leftMeta);
        final byte[] right = normalizeMeta(rightMeta);
        try {
            final WordReference leftRef = WORD_REFERENCE_FACTORY.produceSlow(WORD_REFERENCE_FACTORY.getRow().newEntry(left));
            final WordReference rightRef = WORD_REFERENCE_FACTORY.produceSlow(WORD_REFERENCE_FACTORY.getRow().newEntry(right));
            final WordReferenceVars merged = new WordReferenceVars(leftRef, true);
            merged.join(rightRef);
            return merged.toKelondroEntry().bytes();
        } catch (final Throwable e) {
            return right;
        }
    }
}