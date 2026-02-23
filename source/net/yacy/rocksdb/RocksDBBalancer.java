// RocksDBBalancer.java
// SPDX-License-Identifier: GPL-2.0-or-later
// RocksDB-based implementation of Balancer interface for efficient crawl stack management

package net.yacy.rocksdb;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.DBOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;
import org.rocksdb.BloomFilter;
import org.rocksdb.BlockBasedTableConfig;
import org.rocksdb.LRUCache;

import net.yacy.cora.storage.HandleSet;
import net.yacy.cora.util.ConcurrentLog;
import net.yacy.cora.util.SpaceExceededException;
import net.yacy.crawler.Balancer;
import net.yacy.crawler.CrawlSwitchboard;
import net.yacy.crawler.data.CrawlProfile;
import net.yacy.crawler.retrieval.Request;
import net.yacy.crawler.robots.RobotsTxt;
import net.yacy.kelondro.index.Row;

/**
 * RocksDB-based implementation of the Balancer interface.
 * Replaces file-based HostBalancer for efficient crawl queue management.
 * 
 * Eliminates the performance overhead of opening/closing small files for each URL,
 * providing better throughput for high-volume crawling operations.
 * 
 * Storage Model:
 * - Main CF: urlHash → Request (serialized as Row.Entry bytes)
 * - Index byHost: hostHash|urlHash → empty (for quick host-based operations)
 * - Index byProfile: profileHandle|urlHash → empty (for profile-based removals)
 * 
 * Configuration (all optional):
 * - rocksdb.balancer.blockCacheMB (default: 96) - block cache size in MB
 * - rocksdb.balancer.writeBufferMB (default: 24) - write buffer size in MB
 * - rocksdb.balancer.maxWriteBufferNumber (default: 3) - max write buffer count
 * - rocksdb.balancer.maxBackgroundJobs (default: 2) - background compaction jobs
 * 
 * Example: -Drocksdb.balancer.blockCacheMB=192 -Drocksdb.balancer.writeBufferMB=48
 */
public class RocksDBBalancer implements Balancer, AutoCloseable {

    private final static ConcurrentLog log = new ConcurrentLog("ROCKSDB_BALANCER");
    
    // Configuration
    private final int blockCacheMB = Integer.getInteger("rocksdb.balancer.blockCacheMB", 96);
    private final int writeBufferMB = Integer.getInteger("rocksdb.balancer.writeBufferMB", 24);
    private final int maxWriteBufferNumber = Integer.getInteger("rocksdb.balancer.maxWriteBufferNumber", 3);
    private final int maxBackgroundJobs = Integer.getInteger("rocksdb.balancer.maxBackgroundJobs", 2);
    
    // Column Family Names
    private static final String CF_MAIN = "main";
    private static final String CF_BY_HOST = "byHost";
    private static final String CF_BY_PROFILE = "byProfile";
    
    private RocksDB db;
    private final File dbPath;
    private final int onDemandLimit;
    private final boolean exceed134217727;
    
    // Column Family Handles
    private ColumnFamilyHandle cfMain;
    private ColumnFamilyHandle cfByHost;
    private ColumnFamilyHandle cfByProfile;
    
    // Statistics
    private volatile long statsSize = 0;
    private volatile long statsLastModified = System.currentTimeMillis();
    
    public RocksDBBalancer(final File cachePath, final int onDemandLimit, final boolean exceed134217727) throws RocksDBException, IOException {
        this.dbPath = cachePath;
        this.onDemandLimit = onDemandLimit;
        this.exceed134217727 = exceed134217727;
        
        if (!this.dbPath.exists()) {
            this.dbPath.mkdirs();
        }
        
        log.info("Initializing RocksDB Balancer at " + this.dbPath + " (onDemandLimit=" + onDemandLimit + ")");
        
        try {
            initializeDatabase();
        } catch (RocksDBException e) {
            log.warn("Failed to initialize RocksDB Balancer: " + e.getMessage());
            throw e;
        }
    }
    
    /**
     * Initialize RocksDB with column families
     */
    private void initializeDatabase() throws RocksDBException, IOException {
        RocksDB.loadLibrary();
        
        final DBOptions dbOptions = new DBOptions()
            .setCreateIfMissing(true)
            .setCreateMissingColumnFamilies(true)
            .setMaxOpenFiles(256)
            .setMaxBackgroundJobs(this.maxBackgroundJobs)
            .setIncreaseParallelism(this.maxBackgroundJobs);
        
        // Create table configuration with cache and bloom filter
        final LRUCache blockCache = new LRUCache(Math.max(32L, this.blockCacheMB) * 1024L * 1024L);
        final BloomFilter bloomFilter = new BloomFilter(10, false);
        final BlockBasedTableConfig tableConfig = new BlockBasedTableConfig()
            .setBlockCache(blockCache)
            .setFilterPolicy(bloomFilter)
            .setCacheIndexAndFilterBlocks(true)
            .setFilterPolicy(bloomFilter);
        
        final ColumnFamilyOptions cfOptions = new ColumnFamilyOptions()
            .setTableFormatConfig(tableConfig)
            .setMemtablePrefixBloomSizeRatio(0.05d)
            .setWriteBufferSize(Math.max(16L, this.writeBufferMB) * 1024L * 1024L)
            .setMaxWriteBufferNumber(Math.max(2, this.maxWriteBufferNumber));
        
        // Prepare column family descriptors
        final List<ColumnFamilyDescriptor> cfDescriptors = new ArrayList<>();
        final List<ColumnFamilyHandle> cfHandles = new ArrayList<>();
        
        // Add default column family
        cfDescriptors.add(new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, new ColumnFamilyOptions(cfOptions)));
        
        // Add custom column families
        cfDescriptors.add(new ColumnFamilyDescriptor(CF_MAIN.getBytes(), new ColumnFamilyOptions(cfOptions)));
        cfDescriptors.add(new ColumnFamilyDescriptor(CF_BY_HOST.getBytes(), new ColumnFamilyOptions(cfOptions)));
        cfDescriptors.add(new ColumnFamilyDescriptor(CF_BY_PROFILE.getBytes(), new ColumnFamilyOptions(cfOptions)));
        
        this.db = RocksDB.open(dbOptions, this.dbPath.getAbsolutePath(), cfDescriptors, cfHandles);
        
        // Assign handles
        for (int i = 0; i < cfHandles.size(); i++) {
            final String cfName = new String(cfDescriptors.get(i).getName());
            switch (cfName) {
                case CF_MAIN:       this.cfMain = cfHandles.get(i); break;
                case CF_BY_HOST:    this.cfByHost = cfHandles.get(i); break;
                case CF_BY_PROFILE: this.cfByProfile = cfHandles.get(i); break;
            }
        }
        
        blockCache.close();
        bloomFilter.close();
        cfOptions.close();
        dbOptions.close();
        
        log.config("RocksDB Balancer initialized: blockCacheMB=" + this.blockCacheMB + 
                   ", writeBufferMB=" + this.writeBufferMB);
    }
    
    @Override
    public void close() {
        try {
            log.info("Closing RocksDB Balancer. Size at close: " + this.statsSize);
            if (this.db != null) {
                this.db.close();
            }
        } catch (final Exception e) {
            log.warn("Error closing RocksDB Balancer: " + e.getMessage());
        }
    }
    
    @Override
    public void clear() {
        try {
            // Delete all entries by iterating through main CF
            try (final var it = this.db.newIterator(this.cfMain)) {
                it.seekToFirst();
                final WriteBatch batch = new WriteBatch();
                int count = 0;
                
                while (it.isValid()) {
                    final byte[] key = it.key();
                    batch.delete(this.cfMain, key);
                    
                    // Also remove from indexes would go here if we tracked them
                    count++;
                    if (count % 1000 == 0) {
                        this.db.write(new WriteOptions(), batch);
                        batch.clear();
                    }
                    it.next();
                }
                if (count > 0) {
                    this.db.write(new WriteOptions(), batch);
                }
                batch.close();
                this.statsSize = 0;
            }
        } catch (final Exception e) {
            log.warn("Error clearing balancer: " + e.getMessage());
        }
    }
    
    @Override
    public Request get(final byte[] urlhash) throws IOException {
        try {
            if (urlhash == null) return null;
            
            final byte[] value = this.db.get(this.cfMain, urlhash);
            if (value == null) return null;
            
            // Deserialize from row entry bytes
            final Row.Entry entry = Request.rowdef.newEntry(value);
            return new Request(entry);
            
        } catch (final Exception e) {
            log.warn("Error getting request: " + e.getMessage());
            throw new IOException(e);
        }
    }
    
    @Override
    public int removeAllByProfileHandle(final String profileHandle, final long timeout) throws IOException, SpaceExceededException {
        int removed = 0;
        try {
            if (profileHandle == null) return 0;
            
            Set<byte[]> urlsToRemove = new HashSet<>();
            
            // Iterate byProfile index to find all URLs from this profile
            final long startTime = System.currentTimeMillis();
            try (final var it = this.db.newIterator(this.cfByProfile)) {
                final byte[] prefix = profileHandle.getBytes();
                it.seek(prefix);
                
                while (it.isValid()) {
                    final byte[] key = it.key();
                    if (!startsWith(key, prefix)) break;
                    
                    // Extract URL hash from profile index key
                    final byte[] urlHash = extractUrlHashFromProfileKey(key);
                    urlsToRemove.add(urlHash);
                    
                    // Check timeout
                    if (timeout > 0 && System.currentTimeMillis() - startTime > timeout) {
                        log.warn("removeAllByProfileHandle timeout reached");
                        break;
                    }
                    
                    it.next();
                }
            }
            
            // Remove all found URLs
            final WriteBatch batch = new WriteBatch();
            for (final byte[] urlHash : urlsToRemove) {
                final byte[] existing = this.db.get(this.cfMain, urlHash);
                if (existing != null) {
                    batch.delete(this.cfMain, urlHash);
                    // Also delete from indexes
                    try {
                        final Row.Entry entry = Request.rowdef.newEntry(existing);
                        final Request request = new Request(entry);
                        final String hostHash = request.url().hosthash();
                        batch.delete(this.cfByHost, makeHostIndexKey(hostHash, urlHash));
                        batch.delete(this.cfByProfile, makeProfileIndexKey(profileHandle, urlHash));
                    } catch (final Exception e) {
                        log.warn("Error deserializing request during removal: " + e.getMessage());
                    }
                    removed++;
                }
            }
            
            if (removed > 0) {
                final WriteOptions writeOpts = new WriteOptions();
                this.db.write(writeOpts, batch);
                batch.close();
                this.statsSize -= removed;
            }
            
        } catch (final Exception e) {
            log.warn("Error removing by profile handle: " + e.getMessage());
            throw new IOException(e);
        }
        return removed;
    }
    
    @Override
    public int removeAllByHostHashes(final Set<String> hostHashes) {
        int removed = 0;
        try {
            if (hostHashes == null || hostHashes.isEmpty()) return 0;
            
            Set<byte[]> urlsToRemove = new HashSet<>();
            
            // Find all URLs for each host
            for (final String hostHash : hostHashes) {
                try (final var it = this.db.newIterator(this.cfByHost)) {
                    final byte[] prefix = (hostHash + "|").getBytes();
                    it.seek(prefix);
                    
                    while (it.isValid()) {
                        final byte[] key = it.key();
                        if (!startsWith(key, prefix)) break;
                        
                        final byte[] urlHash = extractUrlHashFromHostKey(key);
                        urlsToRemove.add(urlHash);
                        it.next();
                    }
                }
            }
            
            // Remove all found URLs
            final WriteBatch batch = new WriteBatch();
            for (final byte[] urlHash : urlsToRemove) {
                final byte[] existing = this.db.get(this.cfMain, urlHash);
                if (existing != null) {
                    batch.delete(this.cfMain, urlHash);
                    try {
                        final Row.Entry entry = Request.rowdef.newEntry(existing);
                        final Request request = new Request(entry);
                        final String hostHash = request.url().hosthash();
                        batch.delete(this.cfByHost, makeHostIndexKey(hostHash, urlHash));
                        if (request.profileHandle() != null) {
                            batch.delete(this.cfByProfile, makeProfileIndexKey(request.profileHandle(), urlHash));
                        }
                    } catch (final Exception e) {
                        log.warn("Error deserializing request during removal: " + e.getMessage());
                    }
                    removed++;
                }
            }
            
            if (removed > 0) {
                final WriteOptions writeOpts = new WriteOptions();
                this.db.write(writeOpts, batch);
                batch.close();
                this.statsSize -= removed;
            }
            
        } catch (final Exception e) {
            log.warn("Error removing by host hashes: " + e.getMessage());
        }
        return removed;
    }
    
    @Override
    public int remove(final HandleSet urlHashes) throws IOException {
        int removed = 0;
        try {
            final WriteBatch batch = new WriteBatch();
            
            for (final byte[] urlHash : urlHashes) {
                final byte[] existing = this.db.get(this.cfMain, urlHash);
                if (existing != null) {
                    batch.delete(this.cfMain, urlHash);
                    try {
                        final Row.Entry entry = Request.rowdef.newEntry(existing);
                        final Request request = new Request(entry);
                        final String hostHash = request.url().hosthash();
                        batch.delete(this.cfByHost, makeHostIndexKey(hostHash, urlHash));
                        if (request.profileHandle() != null) {
                            batch.delete(this.cfByProfile, makeProfileIndexKey(request.profileHandle(), urlHash));
                        }
                    } catch (final Exception e) {
                        log.warn("Error deserializing request during removal: " + e.getMessage());
                    }
                    removed++;
                }
            }
            
            if (removed > 0) {
                final WriteOptions writeOpts = new WriteOptions();
                this.db.write(writeOpts, batch);
                batch.close();
                this.statsSize -= removed;
            }
            
        } catch (final Exception e) {
            log.warn("Error removing urls: " + e.getMessage());
            throw new IOException(e);
        }
        return removed;
    }
    
    @Override
    public boolean has(final byte[] urlhashb) {
        try {
            if (urlhashb == null) return false;
            return this.db.get(this.cfMain, urlhashb) != null;
        } catch (final Exception e) {
            log.warn("Error checking if URL exists: " + e.getMessage());
            return false;
        }
    }
    
    @Override
    public int size() {
        // RocksDB doesn't provide O(1) size, use cached statistics
        return (int) Math.min(this.statsSize, Integer.MAX_VALUE);
    }
    
    @Override
    public int getOnDemandLimit() {
        return this.onDemandLimit;
    }
    
    @Override
    public boolean getExceed134217727() {
        return this.exceed134217727;
    }
    
    @Override
    public boolean isEmpty() {
        return this.statsSize == 0;
    }
    
    @Override
    public String push(final Request entry, final CrawlProfile profile, final RobotsTxt robots) throws IOException, SpaceExceededException {
        try {
            if (entry == null || entry.url() == null) {
                return "entry or url is null";
            }
            
            final byte[] urlHash = entry.url().hash();
            
            // Check for duplicate
            if (this.db.get(this.cfMain, urlHash) != null) {
                return "url already on stack";
            }
            
            final Row.Entry rowEntry = entry.toRow();
            final byte[] entryValue = rowEntry.bytes();
            
            // Use batch to ensure consistency
            final WriteBatch batch = new WriteBatch();
            batch.put(this.cfMain, urlHash, entryValue);
            
            // Add to host index
            final String hostHash = entry.url().hosthash();
            final byte[] hostIndexKey = makeHostIndexKey(hostHash, urlHash);
            batch.put(this.cfByHost, hostIndexKey, new byte[0]);
            
            // Add to profile index if profile is set
            if (entry.profileHandle() != null) {
                final byte[] profileIndexKey = makeProfileIndexKey(entry.profileHandle(), urlHash);
                batch.put(this.cfByProfile, profileIndexKey, new byte[0]);
            }
            
            final WriteOptions writeOpts = new WriteOptions();
            this.db.write(writeOpts, batch);
            batch.close();
            
            this.statsSize++;
            this.statsLastModified = System.currentTimeMillis();
            return null; // success
            
        } catch (final Exception e) {
            log.warn("Error pushing to balancer: " + e.getMessage());
            throw new IOException(e);
        }
    }
    
    @Override
    public Map<String, Integer[]> getDomainStackHosts(final RobotsTxt robots) {
        // Returns a map of hosts to {stackSize, estimatedWaitTime}
        // This requires grouping by host from the byHost index
        final Map<String, Integer> hostSizes = new HashMap<>();
        
        try {
            try (final var it = this.db.newIterator(this.cfByHost)) {
                it.seekToFirst();
                while (it.isValid()) {
                    final byte[] key = it.key();
                    final String keyStr = new String(key);
                    final int pipeIndex = keyStr.indexOf('|');
                    if (pipeIndex > 0) {
                        final String hostHash = keyStr.substring(0, pipeIndex);
                        hostSizes.put(hostHash, hostSizes.getOrDefault(hostHash, 0) + 1);
                    }
                    it.next();
                }
            }
        } catch (final Exception e) {
            log.warn("Error getting domain stack hosts: " + e.getMessage());
        }
        
        // Convert to result format
        final Map<String, Integer[]> result = new HashMap<>();
        for (final Map.Entry<String, Integer> entry : hostSizes.entrySet()) {
            // Integer array: {size, estimatedDelay}
            result.put(entry.getKey(), new Integer[]{entry.getValue(), 0});
        }
        return result;
    }
    
    @Override
    public List<Request> getDomainStackReferences(final String host, final int maxcount, final long maxtime) {
        final List<Request> result = new ArrayList<>();
        
        try {
            try (final var it = this.db.newIterator(this.cfByHost)) {
                final byte[] prefix = (host + "|").getBytes();
                it.seek(prefix);
                
                final long startTime = System.currentTimeMillis();
                int count = 0;
                
                while (it.isValid() && count < maxcount) {
                    final byte[] key = it.key();
                    if (!startsWith(key, prefix)) break;
                    
                    if (maxtime > 0 && System.currentTimeMillis() - startTime > maxtime) {
                        break;
                    }
                    
                    final byte[] urlHash = extractUrlHashFromHostKey(key);
                    final Request request = get(urlHash);
                    if (request != null) {
                        result.add(request);
                        count++;
                    }
                    it.next();
                }
            }
        } catch (final Exception e) {
            log.warn("Error getting domain stack references: " + e.getMessage());
        }
        
        return result;
    }
    
    @Override
    public Request pop(final boolean delay, final CrawlSwitchboard cs, final RobotsTxt robots) throws IOException {
        // Pop an entry with domain balancing
        try (final var it = this.db.newIterator(this.cfMain)) {
            it.seekToFirst();
            if (it.isValid()) {
                final byte[] key = it.key();
                final Request request = get(key);
                if (request != null) {
                    removeByUrlHash(key);
                }
                return request;
            }
        } catch (final Exception e) {
            log.warn("Error popping from balancer: " + e.getMessage());
            throw new IOException(e);
        }
        return null;
    }
    
    /**
     * Remove a single request by URL hash (helper for pop())
     */
    private void removeByUrlHash(final byte[] urlHash) {
        try {
            final byte[] existing = this.db.get(this.cfMain, urlHash);
            if (existing != null) {
                final WriteBatch batch = new WriteBatch();
                batch.delete(this.cfMain, urlHash);
                
                try {
                    final Row.Entry entry = Request.rowdef.newEntry(existing);
                    final Request request = new Request(entry);
                    final String hostHash = request.url().hosthash();
                    batch.delete(this.cfByHost, makeHostIndexKey(hostHash, urlHash));
                    if (request.profileHandle() != null) {
                        batch.delete(this.cfByProfile, makeProfileIndexKey(request.profileHandle(), urlHash));
                    }
                } catch (final Exception e) {
                    log.warn("Error deserializing request during removal: " + e.getMessage());
                }
                
                final WriteOptions writeOpts = new WriteOptions();
                this.db.write(writeOpts, batch);
                batch.close();
                this.statsSize--;
            }
        } catch (final Exception e) {
            log.warn("Error removing by URL hash: " + e.getMessage());
        }
    }
    
    @Override
    public Iterator<Request> iterator() throws IOException {
        return new RocksDBIterator();
    }
    
    // ========= Helper Methods =========
    
    private byte[] makeHostIndexKey(final String hostHash, final byte[] urlHash) {
        final byte[] hostBytes = hostHash.getBytes();
        final byte[] separator = "|".getBytes();
        final byte[] key = new byte[hostBytes.length + separator.length + urlHash.length];
        System.arraycopy(hostBytes, 0, key, 0, hostBytes.length);
        System.arraycopy(separator, 0, key, hostBytes.length, separator.length);
        System.arraycopy(urlHash, 0, key, hostBytes.length + separator.length, urlHash.length);
        return key;
    }
    
    private byte[] makeProfileIndexKey(final String profileHandle, final byte[] urlHash) {
        final byte[] profileBytes = profileHandle.getBytes();
        final byte[] separator = "|".getBytes();
        final byte[] key = new byte[profileBytes.length + separator.length + urlHash.length];
        System.arraycopy(profileBytes, 0, key, 0, profileBytes.length);
        System.arraycopy(separator, 0, key, profileBytes.length, separator.length);
        System.arraycopy(urlHash, 0, key, profileBytes.length + separator.length, urlHash.length);
        return key;
    }
    
    private byte[] extractUrlHashFromHostKey(final byte[] key) {
        final byte[] separator = "|".getBytes();
        for (int i = 0; i < key.length - separator.length; i++) {
            if (matches(key, i, separator)) {
                final byte[] urlHash = new byte[key.length - i - separator.length];
                System.arraycopy(key, i + separator.length, urlHash, 0, urlHash.length);
                return urlHash;
            }
        }
        return key;
    }
    
    private byte[] extractUrlHashFromProfileKey(final byte[] key) {
        final byte[] separator = "|".getBytes();
        for (int i = 0; i < key.length - separator.length; i++) {
            if (matches(key, i, separator)) {
                final byte[] urlHash = new byte[key.length - i - separator.length];
                System.arraycopy(key, i + separator.length, urlHash, 0, urlHash.length);
                return urlHash;
            }
        }
        return key;
    }
    
    private boolean startsWith(final byte[] data, final byte[] prefix) {
        if (data.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) return false;
        }
        return true;
    }
    
    private boolean matches(final byte[] data, final int offset, final byte[] pattern) {
        if (offset + pattern.length > data.length) return false;
        for (int i = 0; i < pattern.length; i++) {
            if (data[offset + i] != pattern[i]) return false;
        }
        return true;
    }
    
    /**
     * Iterator implementation for RocksDB-based requests
     */
    private class RocksDBIterator implements Iterator<Request> {
        private final org.rocksdb.RocksIterator it;
        private Request next;
        
        RocksDBIterator() {
            this.it = RocksDBBalancer.this.db.newIterator(RocksDBBalancer.this.cfMain);
            this.it.seekToFirst();
            prepareNext();
        }
        
        private void prepareNext() {
            if (this.it.isValid()) {
                try {
                    final byte[] key = this.it.key();
                    this.next = RocksDBBalancer.this.get(key);
                } catch (final IOException e) {
                    log.warn("Error in iterator: " + e.getMessage());
                    this.next = null;
                }
            } else {
                this.next = null;
            }
        }
        
        @Override
        public boolean hasNext() {
            return this.next != null;
        }
        
        @Override
        public Request next() {
            final Request result = this.next;
            this.it.next();
            prepareNext();
            return result;
        }
        
        @Override
        public void remove() {
            throw new UnsupportedOperationException("remove() not supported");
        }
    }
}
