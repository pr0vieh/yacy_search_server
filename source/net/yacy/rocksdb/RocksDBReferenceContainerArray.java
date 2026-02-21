package net.yacy.rocksdb;

import java.io.File;
import java.io.IOException;
import java.util.Iterator;

import net.yacy.cora.order.ByteOrder;
import net.yacy.cora.order.CloneableIterator;
import net.yacy.cora.sorting.Rating;
import net.yacy.cora.util.ConcurrentLog;
import net.yacy.cora.util.SpaceExceededException;
import net.yacy.kelondro.data.word.Word;
import net.yacy.kelondro.index.RowSet;
import net.yacy.kelondro.rwi.Reference;
import net.yacy.kelondro.rwi.ReferenceContainer;
import net.yacy.kelondro.rwi.ReferenceFactory;

public final class RocksDBReferenceContainerArray<ReferenceType extends Reference> {

    private final ReferenceFactory<ReferenceType> factory;
    private final ByteOrder termOrder;
    private final int termSize;
    private final RocksDBBlobStore store;

    public RocksDBReferenceContainerArray(final File dbLocation,
                                          final String prefix,
                                          final ReferenceFactory<ReferenceType> factory,
                                          final ByteOrder termOrder,
                                          final int termSize) {
        this.factory = factory;
        this.termOrder = termOrder;
        this.termSize = termSize;
        final String safePrefix = (prefix == null || prefix.isEmpty()) ? "rwi" : prefix;
        final File dbPath = new File(dbLocation, safePrefix + ".rocksdb");
        this.store = new RocksDBBlobStore(dbPath, factory.getRow(), termOrder);
    }

    public synchronized void close() {
        this.store.close();
    }

    public void clear() {
        final CloneableIterator<byte[]> keys = this.store.keyIterator(true);
        try {
            while (keys.hasNext()) {
                final byte[] key = keys.next();
                if (key != null) {
                    this.store.remove(key);
                }
            }
        } finally {
            keys.close();
        }
    }

    public ByteOrder ordering() {
        return this.termOrder;
    }

    public int termSize() {
        return this.termSize;
    }

    public ReferenceContainer<ReferenceType> get(final byte[] termHash) throws IOException, SpaceExceededException {
        final byte[] payload = this.store.get(termHash);
        if (payload == null) return null;
        return new ReferenceContainer<ReferenceType>(this.factory, termHash, RowSet.importRowSet(payload, this.factory.getRow()));
    }

    public boolean has(final byte[] termHash) {
        return this.store.get(termHash) != null;
    }

    public int count(final byte[] termHash) {
        final byte[] payload = this.store.get(termHash);
        if (payload == null) return 0;
        return RowSet.importRowCount(payload.length, this.factory.getRow());
    }

    public void delete(final byte[] termHash) {
        this.store.remove(termHash);
    }

    public long entries() {
        return this.store.size();
    }

    public CloneableIterator<byte[]> keyIterator(final boolean ascending) {
        return this.store.keyIterator(ascending);
    }

    public void add(final ReferenceContainer<ReferenceType> container) {
        if (container == null || container.isEmpty()) return;
        final byte[] key = container.getTermHash();
        if (key == null || key.length != this.termSize) return;
        this.store.put(key, container.exportCollection(), container.updated());
    }

    public CloneableIterator<ReferenceContainer<ReferenceType>> referenceContainerIterator(final byte[] startWordHash,
                                                                                           final boolean rot,
                                                                                           final boolean excludePrivate) {
        return new ReferenceContainerIterator(startWordHash, rot, excludePrivate);
    }

    public CloneableIterator<Rating<byte[]>> referenceCountIterator(final byte[] startWordHash,
                                                                    final boolean rot,
                                                                    final boolean excludePrivate) {
        return new ReferenceCountIterator(startWordHash, rot, excludePrivate);
    }

    private final class ReferenceContainerIterator implements CloneableIterator<ReferenceContainer<ReferenceType>>, Iterable<ReferenceContainer<ReferenceType>> {

        private final boolean rot;
        private final boolean excludePrivate;
        private CloneableIterator<byte[]> keyIterator;

        private ReferenceContainerIterator(final byte[] startWordHash, final boolean rot, final boolean excludePrivate) {
            this.rot = rot;
            this.excludePrivate = excludePrivate;
            this.keyIterator = RocksDBReferenceContainerArray.this.store.keyIterator(true);
            if (startWordHash != null) {
                this.keyIterator = this.keyIterator.clone(startWordHash);
            }
        }

        @Override
        public ReferenceContainerIterator clone(final Object secondWordHash) {
            return new ReferenceContainerIterator((byte[]) secondWordHash, this.rot, this.excludePrivate);
        }

        @Override
        public boolean hasNext() {
            if (this.rot) return true;
            return this.keyIterator != null && this.keyIterator.hasNext();
        }

        @Override
        public ReferenceContainer<ReferenceType> next() {
            while (this.keyIterator != null && this.keyIterator.hasNext()) {
                final byte[] key = this.keyIterator.next();
                if (key == null) continue;
                if (this.excludePrivate && Word.isPrivate(key)) continue;
                try {
                    return RocksDBReferenceContainerArray.this.get(key);
                } catch (final IOException | SpaceExceededException e) {
                    ConcurrentLog.logException(e);
                    return null;
                }
            }
            if (!this.rot) return null;
            if (this.keyIterator != null) this.keyIterator.close();
            this.keyIterator = RocksDBReferenceContainerArray.this.store.keyIterator(true);
            while (this.keyIterator.hasNext()) {
                final byte[] key = this.keyIterator.next();
                if (key == null) continue;
                if (this.excludePrivate && Word.isPrivate(key)) continue;
                try {
                    return RocksDBReferenceContainerArray.this.get(key);
                } catch (final IOException | SpaceExceededException e) {
                    ConcurrentLog.logException(e);
                    return null;
                }
            }
            return null;
        }

        @Override
        public void remove() {
        }

        @Override
        public Iterator<ReferenceContainer<ReferenceType>> iterator() {
            return this;
        }

        @Override
        public void close() {
            if (this.keyIterator != null) this.keyIterator.close();
        }
    }

    private final class ReferenceCountIterator implements CloneableIterator<Rating<byte[]>>, Iterable<Rating<byte[]>> {

        private final ReferenceContainerIterator delegate;

        private ReferenceCountIterator(final byte[] startWordHash, final boolean rot, final boolean excludePrivate) {
            this.delegate = new ReferenceContainerIterator(startWordHash, rot, excludePrivate);
        }

        @Override
        public ReferenceCountIterator clone(final Object secondWordHash) {
            return new ReferenceCountIterator((byte[]) secondWordHash, this.delegate.rot, this.delegate.excludePrivate);
        }

        @Override
        public boolean hasNext() {
            return this.delegate.hasNext();
        }

        @Override
        public Rating<byte[]> next() {
            final ReferenceContainer<ReferenceType> container = this.delegate.next();
            if (container == null) return null;
            return new Rating<byte[]>(container.getTermHash(), container.size());
        }

        @Override
        public void remove() {
            this.delegate.remove();
        }

        @Override
        public Iterator<Rating<byte[]>> iterator() {
            return this;
        }

        @Override
        public void close() {
            this.delegate.close();
        }
    }
}
