package net.yacy.rocksdb;

import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import net.yacy.cora.order.ByteOrder;
import net.yacy.cora.order.CloneableIterator;
import net.yacy.cora.order.Order;
import net.yacy.cora.sorting.Rating;
import net.yacy.cora.storage.ComparableARC;
import net.yacy.cora.storage.HandleSet;
import net.yacy.cora.util.ByteArray;
import net.yacy.cora.util.ConcurrentLog;
import net.yacy.cora.util.SpaceExceededException;
import net.yacy.kelondro.data.word.Word;
import net.yacy.kelondro.index.RowHandleSet;
import net.yacy.kelondro.rwi.AbstractBufferedIndex;
import net.yacy.kelondro.rwi.BufferedIndex;
import net.yacy.kelondro.rwi.IndexCellBackend;
import net.yacy.kelondro.rwi.IODispatcher;
import net.yacy.kelondro.rwi.Reference;
import net.yacy.kelondro.rwi.ReferenceContainer;
import net.yacy.kelondro.rwi.ReferenceContainerCache;
import net.yacy.kelondro.rwi.ReferenceContainerOrder;
import net.yacy.kelondro.rwi.ReferenceFactory;
import net.yacy.kelondro.util.MergeIterator;

public final class RocksDBIndexCell<ReferenceType extends Reference> extends AbstractBufferedIndex<ReferenceType>
    implements IndexCellBackend<ReferenceType>, Iterable<ReferenceContainer<ReferenceType>> {

    private final RocksDBReferenceContainerArray<ReferenceType> array;
    private ReferenceContainerCache<ReferenceType> ram;
    private final ComparableARC<byte[], Integer> countCache;
    private final Map<byte[], HandleSet> removeDelayedURLs;
    private int maxRamEntries;

    public RocksDBIndexCell(final File cellPath,
                            final String prefix,
                            final ReferenceFactory<ReferenceType> factory,
                            final ByteOrder termOrder,
                            final int termSize,
                            final int maxRamEntries,
                            final long targetFileSize,
                            final long maxFileSize,
                            final int writeBufferSize,
                            final IODispatcher merger) {
        super(factory);
        this.array = new RocksDBReferenceContainerArray<ReferenceType>(cellPath, prefix, factory, termOrder, termSize);
        this.ram = new ReferenceContainerCache<ReferenceType>(factory, termOrder, termSize);
        this.countCache = new ComparableARC<byte[], Integer>(1000, termOrder);
        this.removeDelayedURLs = new TreeMap<byte[], HandleSet>(termOrder);
        this.maxRamEntries = Math.max(1, maxRamEntries);
    }

    @Override
    public int termKeyLength() {
        return this.ram.termKeyLength();
    }

    @Override
    public synchronized void add(final ReferenceContainer<ReferenceType> newEntries) throws IOException, SpaceExceededException {
        this.ram.add(newEntries);
        this.countCache.remove(newEntries.getTermHash());
        flushBufferIfNeeded();
    }

    @Override
    public synchronized void add(final byte[] termHash, final ReferenceType entry) throws IOException, SpaceExceededException {
        this.ram.add(termHash, entry);
        this.countCache.remove(termHash);
        flushBufferIfNeeded();
    }

    @Override
    public boolean has(final byte[] termHash) {
        if (this.ram.has(termHash)) return true;
        return this.array.has(termHash);
    }

    @Override
    public int count(final byte[] termHash) {
        final Integer cached = this.countCache.get(termHash);
        if (cached != null) return cached.intValue();
        int c = this.ram.count(termHash) + this.array.count(termHash);
        synchronized (this.removeDelayedURLs) {
            final HandleSet delayed = this.removeDelayedURLs.get(termHash);
            if (delayed != null) {
                c -= delayed.size();
            }
        }
        if (c < 0) c = 0;
        this.countCache.insert(termHash, c);
        return c;
    }

    @Override
    public synchronized ReferenceContainer<ReferenceType> get(final byte[] termHash, final HandleSet urlselection) throws IOException {
        final ReferenceContainer<ReferenceType> c0 = this.ram.get(termHash, null);
        ReferenceContainer<ReferenceType> c1 = null;
        try {
            c1 = this.array.get(termHash);
        } catch (final SpaceExceededException e) {
            ConcurrentLog.logException(e);
        }

        ReferenceContainer<ReferenceType> result = null;
        if (c0 != null && c1 != null) {
            try {
                result = c1.merge(c0);
            } catch (final SpaceExceededException e) {
                result = (c1.size() >= c0.size()) ? c1 : c0;
            }
        } else if (c0 != null) {
            result = c0;
        } else if (c1 != null) {
            result = c1;
        }

        if (result == null) return null;

        synchronized (this.removeDelayedURLs) {
            final HandleSet delayed = this.removeDelayedURLs.get(termHash);
            if (delayed != null) {
                result.removeEntries(delayed);
            }
        }

        if (urlselection == null) return result;
        try {
            final ReferenceContainer<ReferenceType> filtered = new ReferenceContainer<ReferenceType>(this.factory, result.getTermHash(), result.size());
            final Iterator<ReferenceType> it = result.entries();
            while (it.hasNext()) {
                final ReferenceType entry = it.next();
                if (entry != null && urlselection.has(entry.urlhash())) {
                    filtered.add(entry);
                }
            }
            return filtered;
        } catch (final SpaceExceededException e) {
            ConcurrentLog.logException(e);
            return result;
        }
    }

    @Override
    public synchronized ReferenceContainer<ReferenceType> remove(final byte[] termHash) throws IOException {
        removeDelayed();
        ReferenceContainer<ReferenceType> backend = null;
        try {
            backend = this.array.get(termHash);
        } catch (final SpaceExceededException e) {
            ConcurrentLog.logException(e);
        }
        this.array.delete(termHash);
        final ReferenceContainer<ReferenceType> memory = this.ram.remove(termHash);
        this.countCache.remove(termHash);

        if (backend == null) return memory;
        if (memory == null) return backend;
        try {
            return backend.merge(memory);
        } catch (final SpaceExceededException e) {
            return (backend.size() >= memory.size()) ? backend : memory;
        }
    }

    @Override
    public synchronized void delete(final byte[] termHash) throws IOException {
        removeDelayed();
        this.array.delete(termHash);
        this.ram.delete(termHash);
        this.countCache.remove(termHash);
    }

    @Override
    public void removeDelayed(final byte[] termHash, final byte[] urlHashBytes) throws IOException {
        HandleSet set;
        synchronized (this.removeDelayedURLs) {
            set = this.removeDelayedURLs.get(termHash);
        }
        if (set == null) {
            set = new RowHandleSet(Word.commonHashLength, Word.commonHashOrder, 0);
        }
        try {
            set.put(urlHashBytes);
        } catch (final SpaceExceededException e) {
            remove(termHash, urlHashBytes);
            return;
        }
        synchronized (this.removeDelayedURLs) {
            this.removeDelayedURLs.put(termHash, set);
        }
    }

    @Override
    public synchronized void removeDelayed() throws IOException {
        final HandleSet words = new RowHandleSet(this.termKeyLength(), this.termKeyOrdering(), 0);
        synchronized (this.removeDelayedURLs) {
            for (final byte[] termHash : this.removeDelayedURLs.keySet()) {
                try {
                    words.put(termHash);
                } catch (final SpaceExceededException e) {
                    ConcurrentLog.logException(e);
                }
            }
        }

        synchronized (this.removeDelayedURLs) {
            for (final byte[] termHash : words) {
                final HandleSet urls = this.removeDelayedURLs.remove(termHash);
                if (urls != null) {
                    remove(termHash, urls);
                }
            }
        }
        this.countCache.clear();
    }

    @Override
    public synchronized int remove(final byte[] termHash, final HandleSet urlHashes) throws IOException {
        this.countCache.remove(termHash);
        final int removedRam = this.ram.remove(termHash, urlHashes);

        ReferenceContainer<ReferenceType> backend = null;
        try {
            backend = this.array.get(termHash);
        } catch (final SpaceExceededException e) {
            ConcurrentLog.logException(e);
        }
        if (backend == null) return removedRam;

        backend.sort();
        final int removedBackend = backend.removeEntries(urlHashes);
        if (backend.isEmpty()) {
            this.array.delete(termHash);
        } else if (removedBackend > 0) {
            this.array.add(backend);
        }

        return removedRam + removedBackend;
    }

    @Override
    public synchronized boolean remove(final byte[] termHash, final byte[] urlHashBytes) throws IOException {
        this.countCache.remove(termHash);
        final boolean removedRam = this.ram.remove(termHash, urlHashBytes);

        ReferenceContainer<ReferenceType> backend = null;
        try {
            backend = this.array.get(termHash);
        } catch (final SpaceExceededException e) {
            ConcurrentLog.logException(e);
        }
        if (backend == null) return removedRam;

        final boolean removedBackend = backend.delete(urlHashBytes);
        if (removedBackend) {
            if (backend.isEmpty()) {
                this.array.delete(termHash);
            } else {
                this.array.add(backend);
            }
        }
        return removedRam || removedBackend;
    }

    @Override
    public Iterator<ReferenceContainer<ReferenceType>> iterator() {
        try {
            return referenceContainerIterator(null, false, false, false);
        } catch (final IOException e) {
            ConcurrentLog.logException(e);
            return this.ram.iterator();
        }
    }

    @Override
    public CloneableIterator<Rating<byte[]>> referenceCountIterator(final byte[] startHash, final boolean rot, final boolean excludePrivate) throws IOException {
        return this.array.referenceCountIterator(startHash, rot, excludePrivate);
    }

    @Override
    public CloneableIterator<ReferenceContainer<ReferenceType>> referenceContainerIterator(final byte[] startHash,
                                                                                           final boolean rot,
                                                                                           final boolean excludePrivate) throws IOException {
        return referenceContainerIterator(startHash, rot, excludePrivate, false);
    }

    @Override
    public CloneableIterator<ReferenceContainer<ReferenceType>> referenceContainerIterator(final byte[] startHash,
                                                                                           final boolean rot,
                                                                                           final boolean excludePrivate,
                                                                                           final boolean ramOnly) throws IOException {
        if (ramOnly) {
            return this.ram.referenceContainerIterator(startHash, rot, excludePrivate);
        }

        final Order<ReferenceContainer<ReferenceType>> containerOrder =
            new ReferenceContainerOrder<ReferenceType>(this.factory, this.ram.rowdef().getOrdering().clone());
        containerOrder.rotate(new ReferenceContainer<ReferenceType>(this.factory, startHash));

        return new MergeIterator<ReferenceContainer<ReferenceType>>(
            this.ram.referenceContainerIterator(startHash, rot, excludePrivate),
            new MergeIterator<ReferenceContainer<ReferenceType>>(
                this.ram.referenceContainerIterator(startHash, false, excludePrivate),
                this.array.referenceContainerIterator(startHash, false, excludePrivate),
                containerOrder,
                ReferenceContainer.containerMergeMethod,
                true),
            containerOrder,
            ReferenceContainer.containerMergeMethod,
            true);
    }

    @Override
    public synchronized void clear() throws IOException {
        this.countCache.clear();
        this.removeDelayedURLs.clear();
        this.ram.clear();
        this.array.clear();
    }

    @Override
    public synchronized void clearCache() {
        this.countCache.clear();
    }

    @Override
    public synchronized void close() {
        this.countCache.clear();
        try {
            removeDelayed();
        } catch (final IOException e) {
            ConcurrentLog.logException(e);
        }
        flushAllRam();
        this.ram.close();
        this.array.close();
    }

    public synchronized boolean isEmpty() {
        return this.ram.isEmpty() && this.array.entries() == 0;
    }

    @Override
    public int size() {
        final Set<ByteArray> keys = new java.util.HashSet<ByteArray>();

        final CloneableIterator<byte[]> backendKeys = this.array.keyIterator(true);
        try {
            while (backendKeys.hasNext()) {
                final byte[] key = backendKeys.next();
                if (key != null) keys.add(new ByteArray(key));
            }
        } finally {
            backendKeys.close();
        }

        final Iterator<ByteArray> ramKeys = this.ram.keys();
        while (ramKeys.hasNext()) {
            keys.add(ramKeys.next());
        }
        return keys.size();
    }

    @Override
    public int minMem() {
        return 4 * 1024 * 1024;
    }

    @Override
    public ByteOrder termKeyOrdering() {
        return this.array.ordering();
    }

    @Override
    public long getBufferMaxAge() {
        return System.currentTimeMillis();
    }

    @Override
    public int getBufferMaxReferences() {
        return this.ram.maxReferences();
    }

    @Override
    public long getBufferMinAge() {
        return System.currentTimeMillis();
    }

    @Override
    public int getBufferSize() {
        return this.ram.size();
    }

    @Override
    public long getBufferSizeBytes() {
        return this.ram.usedMemory();
    }

    @Override
    public void setBufferMaxWordCount(final int maxWords) {
        this.maxRamEntries = Math.max(1, maxWords);
    }

    @Override
    public synchronized int deleteOld(final int minsize, final long maxtime) throws IOException {
        final long timeout = System.currentTimeMillis() + maxtime;
        final java.util.Collection<byte[]> keys = keys4LargeReferences(minsize, maxtime / 3);
        int changed = 0;
        final int oldShrinkMaxsize = ReferenceContainer.maxReferences;
        ReferenceContainer.maxReferences = minsize;
        try {
            for (final byte[] key : keys) {
                final ReferenceContainer<ReferenceType> container = this.get(key, null);
                if (container == null) continue;
                container.shrinkReferences();
                try {
                    this.add(container);
                    changed++;
                } catch (final SpaceExceededException e) {
                    ConcurrentLog.logException(e);
                }
                if (System.currentTimeMillis() > timeout) break;
            }
        } finally {
            ReferenceContainer.maxReferences = oldShrinkMaxsize;
        }
        return changed;
    }

    private java.util.Collection<byte[]> keys4LargeReferences(final int minsize, final long maxtime) throws IOException {
        final long timeout = System.currentTimeMillis() + maxtime;
        final java.util.ArrayList<byte[]> keys = new java.util.ArrayList<byte[]>();

        final Iterator<ByteArray> ramKeys = this.ram.keys();
        while (ramKeys.hasNext()) {
            final byte[] key = ramKeys.next().asBytes();
            if (this.ram.count(key) >= minsize) keys.add(key);
        }

        final CloneableIterator<byte[]> backendKeys = this.array.keyIterator(true);
        try {
            while (backendKeys.hasNext()) {
                final byte[] key = backendKeys.next();
                if (key != null && this.array.count(key) >= minsize) keys.add(key);
                if (System.currentTimeMillis() > timeout) break;
            }
        } finally {
            backendKeys.close();
        }
        return keys;
    }

    @Override
    public int sizesMax() {
        final long size = (long) this.ram.size() + this.array.entries();
        return size > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) size;
    }

    @Override
    public int getSegmentCount() {
        final long segments = this.array.entries();
        return segments > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) segments;
    }

    private void flushBufferIfNeeded() {
        if (this.ram.size() >= this.maxRamEntries) {
            flushAllRam();
        }
    }

    private synchronized void flushAllRam() {
        final CloneableIterator<ReferenceContainer<ReferenceType>> iterator = this.ram.referenceContainerIterator(null, false, false);
        try {
            while (iterator.hasNext()) {
                final ReferenceContainer<ReferenceType> container = iterator.next();
                if (container != null && !container.isEmpty()) {
                    this.array.add(container);
                    this.countCache.remove(container.getTermHash());
                }
            }
        } finally {
            iterator.close();
        }
        this.ram.clear();
    }
}
