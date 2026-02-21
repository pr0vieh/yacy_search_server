// RocksDBBlobStore.java
// (C) 2026 by YaCy Contributors
// first published 21.02.2026 on http://yacy.net
//
// LICENSE
//
// This program is free software; you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation; either version 2 of the License, or
// (at your option) any later version.

package net.yacy.rocksdb;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.rocksdb.ComparatorOptions;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.WriteOptions;

import net.yacy.cora.order.ByteOrder;
import net.yacy.cora.order.CloneableIterator;
import net.yacy.cora.util.ConcurrentLog;
import net.yacy.cora.util.SpaceExceededException;
import net.yacy.kelondro.index.Row;
import net.yacy.kelondro.index.RowSet;

public final class RocksDBBlobStore implements RocksDBStore {

    static {
        RocksDB.loadLibrary();
    }

    private static final Object OPEN_LOCK = new Object();
    private static final Map<String, SharedDb> OPEN_DBS = new HashMap<String, SharedDb>();

    private static final class SharedDb {
        private final String path;
        private final String orderName;
        private final Options options;
        private final ComparatorOptions comparatorOptions;
        private final RocksDBByteOrderComparator comparator;
        private final RocksDB db;
        private final WriteOptions normalWriteOptions;
        private final WriteOptions importWriteOptions;
        private int refCount;

        private SharedDb(final String path,
                         final String orderName,
                         final Options options,
                         final ComparatorOptions comparatorOptions,
                         final RocksDBByteOrderComparator comparator,
                         final RocksDB db,
                         final WriteOptions normalWriteOptions,
                         final WriteOptions importWriteOptions) {
            this.path = path;
            this.orderName = orderName;
            this.options = options;
            this.comparatorOptions = comparatorOptions;
            this.comparator = comparator;
            this.db = db;
            this.normalWriteOptions = normalWriteOptions;
            this.importWriteOptions = importWriteOptions;
            this.refCount = 1;
        }
    }

    private final File dbPath;
    private final String dbPathKey;
    private final ByteOrder ordering;
    private final String orderName;
    private final SharedDb sharedDb;
    private final Row mergeRow;
    private volatile boolean closed;

    public RocksDBBlobStore(final File dbPath) {
        this(dbPath, null, null);
    }

    public RocksDBBlobStore(final File dbPath, final Row mergeRow, final ByteOrder ordering) {
        this.dbPath = dbPath;
        this.mergeRow = mergeRow;
        this.ordering = ordering;
        this.orderName = ordering == null ? "default" : ordering.getClass().getSimpleName();
        this.dbPath.mkdirs();
        final String pathKey = normalizePathKey(this.dbPath);
        this.dbPathKey = pathKey;
        try {
            this.sharedDb = acquireSharedDb(pathKey, this.orderName, ordering, mergeRow);
            this.closed = false;
        } catch (final Exception e) {
            throw new RuntimeException("RocksDBBlobStore open failed: " + this.dbPath.getAbsolutePath(), e);
        }
    }

    @Override
    public byte[] get(final byte[] key) {
        if (this.closed || key == null) return null;
        try {
            final byte[] stored = this.sharedDb.db.get(key);
            if (stored == null) return null;
            if (stored.length <= 8) return stored;
            final byte[] blob = new byte[stored.length - 8];
            System.arraycopy(stored, 8, blob, 0, blob.length);
            return blob;
        } catch (final RocksDBException e) {
            ConcurrentLog.warn("RocksDBBlobStore", "get failed: " + e.getMessage());
            return null;
        }
    }

    public void put(final byte[] key, final byte[] value) {
        put(key, value, System.currentTimeMillis());
    }

    @Override
    public void put(final byte[] key, final byte[] value, final long version) {
        if (this.closed || key == null || value == null) return;
        try {
            final byte[] existing = this.sharedDb.db.get(key);

            // If merge-capable, always merge instead of overwrite.
            if (this.mergeRow != null) {
                final long existingVersion = existing != null && existing.length >= 8
                    ? bytesToLong(existing, 0) : Long.MIN_VALUE;
                final byte[] existingPayload = existing != null ? extractPayload(existing) : null;
                final byte[] mergedValue = mergePayloads(existingPayload, value);
                final long mergedVersion = Math.max(existingVersion, version);
                final byte[] encoded = new byte[8 + mergedValue.length];
                longToBytes(mergedVersion, encoded, 0);
                System.arraycopy(mergedValue, 0, encoded, 8, mergedValue.length);
                this.sharedDb.db.put(this.sharedDb.normalWriteOptions, key, encoded);
                return;
            }

            // Without merge capability: use simple version-based overwrite.
            if (existing != null && existing.length >= 8) {
                final long existingVersion = bytesToLong(existing, 0);
                if (existingVersion > version) return;
            }

            final byte[] encoded = new byte[8 + value.length];
            longToBytes(version, encoded, 0);
            System.arraycopy(value, 0, encoded, 8, value.length);
            this.sharedDb.db.put(this.sharedDb.normalWriteOptions, key, encoded);
        } catch (final RocksDBException e) {
            ConcurrentLog.warn("RocksDBBlobStore", "put failed: " + e.getMessage());
        }
    }

    @Override
    public void putOverwrite(final byte[] key, final byte[] value, final long version) {
        if (this.closed || key == null || value == null) return;
        try {
            final byte[] encoded = new byte[8 + value.length];
            longToBytes(version, encoded, 0);
            System.arraycopy(value, 0, encoded, 8, value.length);
            this.sharedDb.db.put(this.sharedDb.normalWriteOptions, key, encoded);
        } catch (final RocksDBException e) {
            ConcurrentLog.warn("RocksDBBlobStore", "putOverwrite failed: " + e.getMessage());
        }
    }

    @Override
    public void putImportFast(final byte[] key, final byte[] value, final long version) {
        if (this.closed || key == null || value == null) return;
        try {
            final byte[] encoded = new byte[8 + value.length];
            longToBytes(version, encoded, 0);
            System.arraycopy(value, 0, encoded, 8, value.length);
            // IMPORTANT: Use .put() not .merge() during import
            // HeapBlobImporter already deduplicated internally, so just write directly
            this.sharedDb.db.put(this.sharedDb.importWriteOptions, key, encoded);
        } catch (final RocksDBException e) {
            ConcurrentLog.warn("RocksDBBlobStore", "putImportFast failed: " + e.getMessage());
        }
    }

    /**
     * Merge semantics: write with automatic ref merging for keys that already exist.
     * 
     * This is for blob imports where the same key may appear in multiple blobs.
     * Instead of deduplicating in-memory, we let RocksDB merge via this method:
     * - If key exists: extract refs, merge with new refs, write combined result
     * - If key is new: just write it
     * 
     * PERFORMANCE: One .get() per key with duplicate (worst case), but avoids
     * large in-memory dedup structures during blob import.
     */
    public void putMerge(final byte[] key, final byte[] value, final long version) {
        if (this.closed || key == null || value == null) return;
        try {
            final byte[] existing = this.sharedDb.db.get(key);
            
            if (this.mergeRow != null && existing != null) {
                // Key exists: merge refs from existing + new value
                final long existingVersion = existing.length >= 8 ? bytesToLong(existing, 0) : Long.MIN_VALUE;
                final byte[] existingPayload = extractPayload(existing);
                final byte[] mergedPayload = mergePayloads(existingPayload, value);
                final long mergedVersion = Math.max(existingVersion, version);
                
                final byte[] encoded = new byte[8 + mergedPayload.length];
                longToBytes(mergedVersion, encoded, 0);
                System.arraycopy(mergedPayload, 0, encoded, 8, mergedPayload.length);
                this.sharedDb.db.put(this.sharedDb.importWriteOptions, key, encoded);
            } else {
                // Key doesn't exist or no merge capability: just write
                final byte[] encoded = new byte[8 + value.length];
                longToBytes(version, encoded, 0);
                System.arraycopy(value, 0, encoded, 8, value.length);
                this.sharedDb.db.put(this.sharedDb.importWriteOptions, key, encoded);
            }
        } catch (final RocksDBException e) {
            ConcurrentLog.warn("RocksDBBlobStore", "putMerge failed: " + e.getMessage());
        }
    }

    /**
     * Batch import with WriteBatch for high-throughput blob imports.
     * Uses .put() since HeapBlobImporter already deduplicated internally.
     * 
     * PERFORMANCE: WriteBatch groups operations into single atomic write,
     * eliminating overhead of individual put/get calls.
     */
    public void putImportBatch(final java.util.List<java.util.Map.Entry<byte[], byte[]>> entries, 
                               final long version) throws RocksDBException {
        if (this.closed || entries == null || entries.isEmpty()) return;
        
        final org.rocksdb.WriteBatch batch = new org.rocksdb.WriteBatch();
        try {
            for (final java.util.Map.Entry<byte[], byte[]> entry : entries) {
                final byte[] key = entry.getKey();
                final byte[] value = entry.getValue();
                if (key == null || value == null) continue;
                
                final byte[] encoded = new byte[8 + value.length];
                longToBytes(version, encoded, 0);
                System.arraycopy(value, 0, encoded, 8, value.length);
                
                // IMPORTANT: Use .put() not .merge() since HeapBlobImporter deduplicated
                batch.put(key, encoded);
            }
            
            this.sharedDb.db.write(this.sharedDb.importWriteOptions, batch);
        } finally {
            batch.close();
        }
    }

    private byte[] extractPayload(final byte[] encoded) {
        if (encoded == null) return null;
        if (encoded.length <= 8) return encoded;
        final byte[] payload = new byte[encoded.length - 8];
        System.arraycopy(encoded, 8, payload, 0, payload.length);
        return payload;
    }

    private byte[] mergePayloads(final byte[] current, final byte[] incoming) {
        if (current == null || current.length == 0) return incoming;
        if (incoming == null || incoming.length == 0) return current;
        try {
            final RowSet currentRows = RowSet.importRowSet(current, this.mergeRow);
            final RowSet incomingRows = RowSet.importRowSet(incoming, this.mergeRow);
            return currentRows.merge(incomingRows).exportCollection();
        } catch (final SpaceExceededException | RuntimeException e) {
            ConcurrentLog.warn("RocksDBBlobStore", "rowset merge failed, fallback to latest payload: " + e.getMessage());
            return incoming;
        }
    }

    @Override
    public void remove(final byte[] key) {
        if (this.closed || key == null) return;
        try {
            this.sharedDb.db.delete(this.sharedDb.normalWriteOptions, key);
        } catch (final RocksDBException e) {
            ConcurrentLog.warn("RocksDBBlobStore", "delete failed: " + e.getMessage());
        }
    }

    @Override
    public void close() {
        if (this.closed) return;
        releaseSharedDb(this.dbPathKey);
        this.closed = true;
    }

    private static String normalizePathKey(final File path) {
        if (path == null) return "";
        try {
            return path.getCanonicalPath();
        } catch (final IOException e) {
            return path.getAbsolutePath();
        }
    }

    private static SharedDb acquireSharedDb(final String pathKey,
                                            final String orderName,
                                            final ByteOrder ordering,
                                            final Row mergeRow) throws RocksDBException {
        synchronized (OPEN_LOCK) {
            final SharedDb existing = OPEN_DBS.get(pathKey);
            if (existing != null) {
                if (!existing.orderName.equals(orderName)) {
                    throw new RocksDBException("Comparator mismatch for " + pathKey + ": "
                        + existing.orderName + " vs " + orderName);
                }
                existing.refCount++;
                return existing;
            }

            Options options = new Options()
                .setCreateIfMissing(true)
                .setCompressionType(org.rocksdb.CompressionType.LZ4_COMPRESSION)
                .setWriteBufferSize(256L * 1024 * 1024)
                .setMaxWriteBufferNumber(3)
                .setLevel0FileNumCompactionTrigger(4);
            ComparatorOptions comparatorOptions = null;
            RocksDBByteOrderComparator comparator = null;
            if (ordering != null) {
                comparatorOptions = new ComparatorOptions();
                comparator = new RocksDBByteOrderComparator(ordering, comparatorOptions);
                options.setComparator(comparator);
            }
            
            final boolean syncWrites = false;
            final boolean importDisableWal = true;
            RocksDB db;
            try {
                db = RocksDB.open(options, pathKey);
            } catch (final RocksDBException e) {
                if (ordering != null && e.getMessage() != null
                    && e.getMessage().contains("does not match existing comparator")) {
                    ConcurrentLog.warn("RocksDBBlobStore", "comparator mismatch for " + pathKey
                        + "; falling back to default comparator (legacy DB). Reimport to upgrade.");
                    try {
                        if (comparatorOptions != null) comparatorOptions.close();
                    } catch (final Exception ignored) {
                    }
                    try {
                        options.close();
                    } catch (final Exception ignored) {
                    }
                    comparatorOptions = null;
                    comparator = null;
                    options = new Options()
                        .setCreateIfMissing(true)
                        .setCompressionType(org.rocksdb.CompressionType.LZ4_COMPRESSION)
                        .setWriteBufferSize(256L * 1024 * 1024)
                        .setMaxWriteBufferNumber(3)
                        .setLevel0FileNumCompactionTrigger(4);
                    db = RocksDB.open(options, pathKey);
                } else {
                    throw e;
                }
            }
            final WriteOptions normalWriteOptions = new WriteOptions()
                .setDisableWAL(false)
                .setSync(syncWrites);
            final WriteOptions importWriteOptions = new WriteOptions()
                .setDisableWAL(importDisableWal)
                .setSync(false);

            ConcurrentLog.info("RocksDBBlobStore", "opened " + pathKey
                + " (order=" + orderName + ", normal: WAL=on, sync=" + syncWrites
                + "; import: WAL=" + (importDisableWal ? "off" : "on") + ")");

            final SharedDb created = new SharedDb(pathKey, orderName, options, comparatorOptions, comparator, db,
                normalWriteOptions, importWriteOptions);
            OPEN_DBS.put(pathKey, created);
            return created;
        }
    }

    private static void releaseSharedDb(final String pathKey) {
        synchronized (OPEN_LOCK) {
            final SharedDb shared = OPEN_DBS.get(pathKey);
            if (shared == null) return;

            shared.refCount--;
            if (shared.refCount > 0) return;

            OPEN_DBS.remove(pathKey);
            try {
                shared.db.close();
            } catch (final Exception e) {
                ConcurrentLog.warn("RocksDBBlobStore", "db close failed for " + shared.path + ": " + e.getMessage());
            }
            try {
                shared.options.close();
            } catch (final Exception e) {
                ConcurrentLog.warn("RocksDBBlobStore", "options close failed for " + shared.path + ": " + e.getMessage());
            }
            try {
                if (shared.comparatorOptions != null) {
                    shared.comparatorOptions.close();
                }
            } catch (final Exception e) {
                ConcurrentLog.warn("RocksDBBlobStore", "comparator options close failed for " + shared.path + ": " + e.getMessage());
            }
            try {
                shared.normalWriteOptions.close();
            } catch (final Exception e) {
                ConcurrentLog.warn("RocksDBBlobStore", "normal write options close failed for " + shared.path + ": " + e.getMessage());
            }
            try {
                shared.importWriteOptions.close();
            } catch (final Exception e) {
                ConcurrentLog.warn("RocksDBBlobStore", "import write options close failed for " + shared.path + ": " + e.getMessage());
            }
        }
    }

    private static long bytesToLong(final byte[] bytes, final int offset) {
        long result = 0;
        for (int i = 0; i < 8; i++) {
            result = (result << 8) | (bytes[offset + i] & 0xFF);
        }
        return result;
    }

    private static void longToBytes(final long value, final byte[] bytes, final int offset) {
        for (int i = 0; i < 8; i++) {
            bytes[offset + 7 - i] = (byte) ((value >>> (i * 8)) & 0xFF);
        }
    }

    @Override
    public long size() {
        if (this.closed) return 0;
        try {
            final long estimate = this.sharedDb.db.getLongProperty("rocksdb.estimate-num-keys");
            return Math.max(0L, estimate);
        } catch (final Exception e) {
            ConcurrentLog.warn("RocksDBBlobStore", "size() estimate failed: " + e.getMessage());
            return 0;
        }
    }

    @Override
    public CloneableIterator<byte[]> keyIterator(final boolean ascending) {
        if (this.closed) return new EmptyIterator();
        try {
            final List<byte[]> keys = new ArrayList<>();
            final RocksIterator iter = this.sharedDb.db.newIterator();
            try {
                if (ascending) {
                    iter.seekToFirst();
                } else {
                    iter.seekToLast();
                }
                while (iter.isValid()) {
                    keys.add(iter.key().clone());
                    if (ascending) {
                        iter.next();
                    } else {
                        iter.prev();
                    }
                }
            } finally {
                iter.close();
            }
            return new ArrayIterator(keys, this.ordering);
        } catch (final Exception e) {
            ConcurrentLog.warn("RocksDBBlobStore", "keyIterator() failed: " + e.getMessage());
            return new EmptyIterator();
        }
    }

    private static final class ArrayIterator implements CloneableIterator<byte[]> {
        private final List<byte[]> list;
        private final ByteOrder ordering;
        private int index = 0;

        private ArrayIterator(final List<byte[]> list, final ByteOrder ordering) {
            this.list = list;
            this.ordering = ordering;
        }

        @Override
        public boolean hasNext() {
            return this.index < this.list.size();
        }

        @Override
        public byte[] next() {
            if (!hasNext()) return null;
            return this.list.get(this.index++);
        }

        @Override
        public void remove() {
        }

        @Override
        public CloneableIterator<byte[]> clone(final Object modifier) {
            final ArrayIterator clone = new ArrayIterator(this.list, this.ordering);
            if (modifier instanceof byte[]) {
                final byte[] lastKey = (byte[]) modifier;
                for (int i = 0; i < this.list.size(); i++) {
                    if (compare(this.list.get(i), lastKey, this.ordering) > 0) {
                        clone.index = i;
                        break;
                    }
                }
            }
            return clone;
        }

        @Override
        public void close() {
            // No resources to free for list-backed iterator
        }

        private static int compare(final byte[] a, final byte[] b, final ByteOrder ordering) {
            if (ordering != null) return ordering.compare(a, b);
            final int minLen = Math.min(a.length, b.length);
            for (int i = 0; i < minLen; i++) {
                final int cmp = (a[i] & 0xFF) - (b[i] & 0xFF);
                if (cmp != 0) return cmp;
            }
            return a.length - b.length;
        }
    }

    private static final class EmptyIterator implements CloneableIterator<byte[]> {
        @Override
        public boolean hasNext() {
            return false;
        }

        @Override
        public byte[] next() {
            return null;
        }

        @Override
        public void remove() {
        }

        @Override
        public CloneableIterator<byte[]> clone(final Object modifier) {
            return this;
        }

        @Override
        public void close() {
            // No resources to free for empty iterator
        }
    }
}
