# Kelondro Storage Layer Architecture Analysis

## Executive Summary

The Kelondro storage layer is a **three-tier architecture** that separates concerns across:
1. **BLOB Layer (blob/)** - Raw binary storage with key-value mapping
2. **Index Layer (index/)** - Structured row-based indexing with memory optimization
3. **RWI Layer (rwi/)** - Search index semantics with reference containers and caching

The architecture is **already partially RocksDB-enabled** with `RocksDBBlobStore.java` and `RocksDBHandleMap.java` providing off-heap alternatives to traditional heap-based storage.

---

## Part 1: Component Deep Dive

### Layer 1: BLOB Storage (Disk-based Key-Value Store)

#### **BLOB.java** (Interface)
- **Purpose**: Abstract interface for binary large object storage
- **Key Methods**:
  - `insert(key, blob)` - Store arbitrary binary data by key
  - `get(key)` - Retrieve blob by key
  - `delete(key)` - Remove entry
  - `keys(up, firstKey)` - Ordered iteration
  - `size()`, `mem()`, `optimize()` - Metadata operations

| Aspect | Details |
|--------|---------|
| **Storage Type** | **Disk-based** (random access file) |
| **Data Model** | Binary blobs indexed by byte[] keys |
| **Access Pattern** | Key → byte[] lookup |
| **Memory** | O(index size), data stays on disk until fetched |

---

#### **Heap.java** (Primary Disk Storage Implementation)
- **Purpose**: File-based storage with free space management
- **Storage Structure**:
  ```
  File Layout:
  record ::= reclen key blob
  reclen  ::= 4-byte integer (size of key + blob)
  key     ::= fixed-length primary key (bytes)
  blob    ::= variable-sized binary data
  ```
- **Key Components**:
  - `SortedMap<byte[], byte[]> buffer` - Write buffer (TreeMap) for batching disk I/O
  - `index` - HandleMap mapping keys to file positions (seek offsets)
  - `free` - Gap tracking for deleted record space reuse
  
| Aspect | Details |
|--------|---------|
| **In-Memory Overhead** | Write buffer + index (typically 100MB-1GB) |
| **Disk-Based** | 100% of actual data on disk |
| **Is Replaceable?** | **YES** - Implement BLOB interface |
| **Difficulty** | **EASY** - just disk I/O + indexing |

---

#### **HeapReader.java** (Initialization & Access Layer)
- **Purpose**: Manages heap initialization with index rebuilding, RocksDB blob store optional initialization
- **Dual-Mode Operation**:
  ```java
  if (USE_ROCKSDB_BLOBSTORE_ONLY) {
      // RocksDB is authoritative, no traditional index needed
      this.index = null;  // Skip HeapFile I/O entirely
  } else {
      // Traditional mode: build/load index from disk
  }
  ```

- **Key Responsibilities**:
  1. **Index Recovery**: Loads `.idx` file or rebuilds from heap file
  2. **RocksDB Integration**: Optional `RocksDBBlobStore` for blob storage
  3. **Gap Management**: Tracks deleted record spaces for reuse
  4. **Fingerprinting**: Stores/verifies index dumps for performance

| Aspect | Details |
|--------|---------|
| **RocksDB Support** | ✅ Already integrated (optional) |
| **abstraction Level** | Low-level file I/O handling |
| **Criticality** | **VERY HIGH** - initialization affects all layers |

---

### Layer 2: Index Structures (Memory-Efficient Row Storage)

#### **Index.java** (Interface)
- **Purpose**: Abstract interface for structured indexed data
- **Contracts**:
  - `put(Row.Entry)` / `get(byte[] key)` - CRUD operations
  - `has(byte[] key)` - Existence check
  - `replace(Row.Entry)` - Update entry
  - `keys()`, `rows()` - Iteration (ordered or unordered)

| Aspect | Details |
|--------|---------|
| **Abstraction** | **Strong** - 5+ implementations exist |
| **Memory Model** | Flexible - in-memory or disk-based |
| **RocksDB-Ready** | ✅ `RocksDBHandleMap` implements HandleMap pattern |

---

#### **RAMIndex.java** (Hybrid In-Memory Implementation)
- **Purpose**: Maintains two RowSet structures for optimized insertion/lookup
- **Dual-Phase Design**:
  - **Phase 1 (index0)**: Accumulation phase - unsorted inserts
  - **Phase 2 (index1)**: Normal phase - sorted, merged data

```java
private RowSet index0;    // Initialization phase (unsorted writes)
private RowSet index1;    // Operational phase (sorted, merged)

// On first query: finishInitialization()
// - sort index0
// - remove duplicates (uniq)
// - trim memory
// - move to index1
```

- **Why Two Indices?**
  - Allows fast bulk insert without sorting cost
  - Maintains search performance via dual-lookup
  - Memory-efficient initialization

| Aspect | Details |
|--------|---------|
| **Storage** | **100% in-memory** |
| **Memory Model** | TreeMap-based sorted storage |
| **Is Replaceable?** | **NO** - core to search workflow |
| **Difficulty** | **CRITICAL** - deep coupling in rwi layer |

---

#### **RowSet.java** (Core In-Memory Data Structure)
- **Purpose**: Compact binary row storage in a single byte array
- **Storage Model**:
  - Single byte[] buffer containing all rows
  - Each row is `rowdef.objectSize` bytes
  - Maintains sort boundary for partial sorting
  
```
Buffer Layout:
[row0 | row1 | row2 | ... | rowN]
      ^                    ^
   0-sortBound      sortBound-end
   (sorted)         (unsorted)
```

- **Key Features**:
  - `exportCollection()` / `importRowSet()` - Serialization
  - `sort()`, `uniq()`, `trim()` - In-place operations
  - Binary search for lookups
  - Supports external iteration

| Aspect | Details |
|--------|---------|
| **Storage** | **In-memory byte array** |
| **Mutation** | **Read-heavy optimization** (sort, trim, uniq) |
| **Is Replaceable?** | **VERY DIFFICULT** - deeply embedded data structure |
| **Why Hard?** | Tight coupling with Row/Column mechanisms |

---

### Layer 3: RWI (Reversed Word Index) - Search Index Semantics

#### **IndexCell.java** (Unified Search Index Controller)
- **Purpose**: Manages the complete word-index for a shard with memory/disk tiering
- **Architecture**:
  ```
  [RAM Cache]  ← Fast writes, flushed when full
       ↓
  [IODispatcher] ← Async merge queue
       ↓
  [BLOB Array] ← On-disk compressed containers
  ```

- **Key Components**:
  - `ReferenceContainerCache<ReferenceType> ram` - In-memory word index (TreeMap)
  - `ReferenceContainerArray<ReferenceType> array` - Disk BLOB files
  - `IODispatcher merger` - Shared merge thread pool
  - `removeDelayedURLs` - Deferred deletion tracking

- **Flush Strategy**:
  ```java
  if (ram.size() >= maxRamEntries ||
      (ram.size() > 2000 && !MemoryControl.request(120MB, false)) ||
      (ram.isEmpty() == false && lastDump + 4min < now)) {
      
      // Dump RAM to disk
      File dump = array.newContainerBLOBFile();
      merger.dump(ram, dump, array);
      ram = new ReferenceContainerCache(...);  // Fresh cache
  }
  ```

| Aspect | Details |
|--------|---------|
| **Storage** | **Hybrid**: RAM + on-disk BLOB files |
| **Data Model** | Word hash → ReferenceContainer (URL set) |
| **Latency** | RAM: ~1µs lookup, Disk: ~10ms first access |
| **Is Replaceable?** | **MODERATE DIFFICULTY** - needs ReferenceContainer abstraction |

---

#### **IODispatcher.java** (Async I/O Coordinator)
- **Purpose**: Queued merge/dump operations to prevent I/O blocking
- **Design Pattern**: **Command Queue with Single Worker Thread**
  - `dumpQueue`: Serialized dumps (RAM → BLOB file)
  - `mergeQueue`: File consolidation operations
  - `controlQueue`: Semaphore limiting concurrent ops

```java
public class IODispatcher extends Thread {
    ArrayBlockingQueue<DumpJob> dumpQueue;    // 100+ pending dumps
    ArrayBlockingQueue<MergeJob> mergeQueue;  // Merge operations
    Semaphore controlQueue;  // Only 1 op at a time
    
    // Single-threaded worker processes queue
    @Override
    public void run() {
        while (running) {
            if (dumpQueue has work) processDump();
            if (mergeQueue has work) processMerge();
        }
    }
}
```

| Aspect | Details |
|--------|---------|
| **Threading** | Single-threaded queue processor (prevents I/O contention) |
| **Scalability** | **Bottleneck** - serializes all merges across entire index |
| **Is Replaceable?** | **DIFFICULT** - fundamental async pattern |
| **Alternative** | RocksDB has built-in async compaction |

---

#### **ReferenceContainerCache.java** (Word Index Cache)
- **Purpose**: In-memory cache for word → URL mappings (during active writes)
- **Storage**:
  - `ConcurrentHashMap<ByteArray, ReferenceContainer<ReferenceType>>` cache
  - Key = word hash (12 bytes)
  - Value = ReferenceContainer (variable size, ~10KB avg)

- **Operations**:
  - `add(ReferenceContainer)` - Merge new references
  - `get(byte[] key)` - Retrieve container
  - `dump(File)` - Serialize to disk
  - `count(byte[] key)` - Reference count

```java
public class ReferenceContainerCache<ReferenceType extends Reference> {
    ConcurrentHashMap<ByteArray, ReferenceContainer<ReferenceType>> cache;
    // Flushed to ReferenceContainerArray when:
    // - size >= maxRamEntries (e.g., 100,000)
    // - memory pressure (< 120MB free)
    // - time-based (every 4 minutes)
}
```

| Aspect | Details |
|--------|---------|
| **Storage** | **100% in-memory** |
| **Data Model** | Word hash → ReferenceContainer |
| **Concurrency** | ConcurrentHashMap (thread-safe) |
| **Is Replaceable?** | **VERY DIFFICULT** - coupled to ReferenceContainer |

---

## Part 2: Data Flow & Interactions

### Layered Dependency Chain

```
┌─────────────────────────────────────────────────────┐
│                  RWI Layer (Search)                  │
├─────────────────────────────────────────────────────┤
│  IndexCell → IODispatcher → ReferenceContainerArray │
│  ReferenceContainerCache ↔ ReferenceContainer       │
└──────────────┬──────────────────────────────────────┘
               │
        ┌──────▼──────┐
        │ Index Layer │
        ├─────────────┤
        │ RAMIndex    │ (in-memory)
        │ RowSet      │ (byte array)
        │ Index iface │
        └──────┬──────┘
               │
        ┌──────▼──────┐
        │ BLOB Layer  │
        ├─────────────┤
        │ Heap        │ (disk file)
        │ HeapReader  │ (I/O, init)
        │ BLOB iface  │
        └─────────────┘
```

### Request Flow: Search Query

```
1. User Query "hello world"
   ↓
2. Extract word hashes: sha1("hello") = [12 bytes], sha1("world") = [12 bytes]
   ↓
3. IndexCell.get(wordHash1)
   ├─ Check RAM cache (ReferenceContainerCache)  ← Fast path
   │  └─ If found: return Container with 10K references
   │
   └─ If miss: Check disk (ReferenceContainerArray)
      ├─ Each file is serialized ReferenceContainer (from previous dump)
      ├─ Deserialize: RowSet.importRowSet() → ReferenceContainer
      ├─ Filter by URL hash (conjunction)
      └─ Return combined results
   ↓
4. Result: 950 matching URLs
```

### Write Flow: Index Update

```
1. Crawled URL: www.example.com/page
   Content words: ["hello", "world", "example"]
   ↓
2. For each word:
   - Calculate hash: sha1("hello") = [12 bytes]
   - Create Reference (URL hash + metadata)
   - IndexCell.add(ReferenceContainer(wordHash, reference))
   ↓
3. ReferenceContainerCache.add(wordHash)
   ├─ Check if word hash already in cache
   ├─ If yes: ReferenceContainer.addReference()
   └─ If no: Create new ReferenceContainer
   ↓
4. Monitor cache size:
   if (cache.size() >= 100,000 entries) {
       ├─ Create BLOB file: "words.001.blob"
       ├─ Serialize cache: RowSet.exportCollection()
       ├─ Queue async dump: IODispatcher.dump()
       ├─ Reset cache (get fresh ReferenceContainerCache)
       └─ Continue accepting writes
   }
   ↓
5. Async IODispatcher processes dump queue
   └─ Write to disk, merge if needed
```

---

## Part 3: Memory vs Disk Breakdown

| Component | Type | Typical Size | Notes |
|-----------|------|--------------|-------|
| **Heap index** | Disk | 8-16 GB | HandleMap of key→position mappings |
| **Heap buffer** | RAM | 100-500 MB | TreeMap write buffer |
| **RAMIndex** | RAM | 1-10 GB | Dual RowSet structures holding indexed data |
| **ReferenceContainerCache** | RAM | 100-500 MB | Current word index being written |
| **BLOB files** | Disk | 50-200 GB+ | Compressed serialized ReferenceContainers |
| **Total RAM** | — | **5-12 GB** | Across all layers |
| **Total Disk** | — | **60-250 GB+** | Index data |

---

## Part 4: RocksDB Integration Status

### ✅ Already Implemented

#### `RocksDBBlobStore.java` (BLOB Replacement)
- **Function**: Replaces Heap/HeapReader file I/O
- **Features**:
  - LSM tree storage for write-heavy workloads
  - Automatic compaction (prevents disk fragmentation)
  - Version-based conflict resolution
  - Deduplication via merge semantics
  - Status quo: **Optional** (toggle via `USE_ROCKSDB`)

```java
// In HeapReader.java
if (USE_ROCKSDB_BLOBSTORE_ONLY) {
    this.blobStore = new RocksDBBlobStore(dbPath);
    this.index = null;  // No traditional index needed
}
```

#### `RocksDBHandleMap.java` (Index Replacement)
- **Function**: Replaces HandleMap (key→position mapping)
- **Features**:
  - Column-family based byte-order comparators
  - Batch import from .idx dump files
  - Configurable memtable (512MB) + cache
  - Sorted iteration (supports range queries)
  - Status quo: **Optional** (toggle via `yacy.index.rocksdb=true`)

```java
public final class RocksDBHandleMap implements HandleMap {
    // Takes .idx dump file
    // Stores key→position in RocksDB instead of memory
    // Compaction manages memory automatically
}
```

---

## Part 5: Hardest Components to Replace

### Ranked by Difficulty (Hardest First)

### 🔴 **1. RAMIndex (MOST DIFFICULT)**
**Why Nearly Impossible:**
- **Dual-phase design** is tightly coupled to insertion workflow
- **Three-tier dependency**: RAMIndex → RowSet → Row.Entry
- **No abstraction** - concrete class, not interface
- **Synchronization semantics** based on finishInitialization() state machine
- **Usage in rwi/**: Assumed to be fast dual-lookup (index0 + index1)

```java
// Problem: This design is hardwired
public final synchronized Row.Entry get(byte[] key, boolean forceclone) {
    assert this.index0.isSorted();
    Row.Entry indexentry = this.index0.get(key);  // Fast path
    if (indexentry != null) return indexentry;
    return this.index1.get(key);  // Fallback
}
```

**To Replace Requires:**
- Rewrite all rwi layer search contracts
- Modify ReferenceContainerCache to use different source
- Change IndexCell.get() semantics

**Verdict:** **NOT PRACTICAL** without full rwi redesign

---

### 🔴 **2. ReferenceContainerCache (VERY DIFFICULT)**
**Why Hard:**
- **Type-generic** ReferenceContainer dependencies
- **Serialization format** tightly coupled to RowSet
- **Concurrent access patterns** assume in-memory semantics
- **Dump format** (exportCollection) specific to RowSet

```java
public final class ReferenceContainerCache<ReferenceType extends Reference> {
    // Dump method:
    cache.dump(file, writeBufferSize, true);
    // Deserialization:
    RowSet.importRowSet(bytes, blobMergeRow);
}
```

**To Replace Requires:**
- New serialization format for ReferenceContainer
- New dump protocol in IODispatcher
- Modify IndexCell dump/load logic

**Verdict:** **POSSIBLE** but risky due to coupled serialization

---

### 🟠 **3. IODispatcher (DIFFICULT)**
**Why:**
- **Single-threaded bottleneck** is architectural choice
- **Queue semantics** deeply assumed in IndexCell
- **Merge operations** depend on specific BLOB file format
- **RocksDB has auto-compaction** (no explicit merge needed)

```java
// Problem: Entire design assumes single threaded worker
public synchronized void dump(ReferenceContainerCache<ReferenceType> cache, 
                              File file, ReferenceContainerArray<ReferenceType> array) {
    this.dumpQueue.add(new DumpJob(cache, file, array));
    // ...single thread processes all dumps
}
```

**To Replace Requires:**
- Remove IODispatcher from IndexCell entirely
- Let RocksDB handle async compaction
- Modify dump protocol

**Verdict:** **FEASIBLE** with RocksDB (auto-compaction)

---

### 🟡 **4. HeapReader (MODERATE DIFFICULTY)**
**Why:**
- **Initialization state machine** complex (index rebuild)
- **Gap management** file-specific feature
- **RocksDB already integrated** (easier than others)

**To Replace Requires:**
- Simplify initialization (RocksDB is self-initializing)
- Remove free-space tracking (RocksDB auto-manages)
- Test migration of existing .idx files

**Verdict:** **Already partially solved** with RocksDBBlobStore

---

### 🟢 **5. Heap (EASY)**
**Why:**
- **BLOB interface** already decouples it
- **RocksDBBlobStore fully replaces** it
- **No special state** (just disk I/O)

**Verdict:** **✅ ALREADY SOLVED** (use RocksDBBlobStore)

---

## Part 6: Database Abstraction Opportunities

### Current Abstraction Landscape

```
Interface Hierarchy:
├── BLOB (kelondro/blob/)              ← Abstracts storage
│   ├── Heap
│   └── RocksDBBlobStore ✅
│
├── HandleMap (cora/storage/)          ← Abstracts key-lookup
│   └── RocksDBHandleMap ✅
│
├── Index (kelondro/index/)            ← Abstracts row storage
│   ├── RAMIndex (⚠️ not interface)
│   ├── RowSet
│   └── RocksDBHandleMap (HandleMap only)
│
├── BufferedIndex (kelondro/rwi/)      ← Abstracts search index
│   ├── IndexCell
│   └── AbstractBufferedIndex
│
└── Reference (kelondro/rwi/)          ← Abstracts indexed data
    └── ReferenceContainer
```

### ✅ Existing Good Abstractions

**1. BLOB Interface** (Perfect)
```java
public interface BLOB {
    public void insert(byte[] key, byte[] b) throws IOException;
    public byte[] get(byte[] key) throws IOException, SpaceExceededException;
    public void delete(byte[] key) throws IOException;
    public CloneableIterator<byte[]> keys(boolean up, byte[] firstKey) throws IOException;
    // ... 15 other methods
}

// Implementations:
- Heap (traditional file)
- RocksDBBlobStore (off-heap)
```
**Status:** ✅ Complete separation of concerns

---

**2. HandleMap Interface** (Good)
```java
public interface HandleMap {
    public void put(byte[] key, long value) throws IOException;
    public long get(byte[] key);
    public boolean has(byte[] key);
    public void delete(byte[] key);
    // ... 10 others
}

// Implementations:
- MapDBHandleMap
- RocksDBHandleMap ✅
```
**Status:** ✅ Multiple implementations work

---

### ⚠️ Missing Abstractions

**1. Missing: Index Interface (for RAMIndex)**
```java
// ❌ RAMIndex is CONCRETE, not based on interface
public final class RAMIndex implements Index, Iterable<Row.Entry> {
    // No abstraction for the dual-phase design
    // Can't swap implementation
}
```
**Need:** Interface that abstracts dual-phase semantics
```java
public interface PhaseAwareIndex extends Index {
    public void finishInitialization();  // ← Make explicit
    public boolean isInitialized();
}
```

---

**2. Missing: ReferenceContainer Serialization Interface**
```java
// ❌ ReferenceContainerCache hard-coded for RowSet serialization
public final class ReferenceContainerCache<ReferenceType extends Reference> {
    // Uses: RowSet.importRowSet() / exportCollection()
    // Can't swap serialization format
}
```
**Need:** Abstraction for container I/O
```java
public interface ReferenceContainerSerializer<T extends Reference> {
    public byte[] serialize(ReferenceContainer<T> container) throws IOException;
    public ReferenceContainer<T> deserialize(byte[] data) throws IOException;
}
```

---

**3. Missing: IODispatcher Abstraction**
```java
// ❌ IODispatcher is concrete queue implementation
public class IODispatcher extends Thread {
    // Single-threaded queue
    // Can't swap to RocksDB's built-in compaction
}
```
**Need:** Async job coordinator interface
```java
public interface AsyncJobCoordinator {
    public void queue(DumpJob job);
    public void queue(MergeJob job);
    public void waitForCompletion();
}
```

---

## Part 7: RocksDB Migration Strategy

### Phase 1: Low-Risk (Already Implemented ✅)
```java
// Enable RocksDB backend:
index.rocksdb.enabled=true
```

### Phase 2: Medium-Risk
- Create abstraction for ReferenceContainerCache serialization
- Implement RocksDB-based container store
- Migrate one index cell at a time

### Phase 3: High-Risk (Avoid for Now)
- Replace RAMIndex dual-phase design
- Would require complete rwi layer rewrite

---

## Summary Table

| Component | Type | Current | RocksDB Alternative | Difficulty | Risk |
|-----------|------|---------|---------------------|------------|------|
| **Heap** | Storage | ✅ BLOB impl | RocksDBBlobStore | 🟢 Easy | ✅ Low |
| **HeapReader** | I/O Init | ✅ File ops | Simplified | 🟡 Medium | ⚠️ Medium |
| **RAMIndex** | Index | ❌ Concrete | N/A (don't replace) | 🔴 Impossible | 🔴 High |
| **RowSet** | Data Struct | ❌ Concrete | N/A (deep coupling) | 🔴 Impossible | 🔴 High |
| **IndexCell** | Controller | ✅ Abstract | Keep (wrapper) | 🟢 Easy | ✅ Low |
| **IODispatcher** | Async Queue | ❌ Concrete | Auto-compaction | 🟠 Hard | ⚠️ Medium |
| **RefContainerCache** | Cache | ❌ Concrete | Needs abstraction | 🟠 Hard | ⚠️ Medium |
| **ReferenceContainer** | Data Model | ✅ RowSet-based | Abstraction needed | 🟠 Hard | ⚠️ Medium |

---

## Recommendations

### ✅ DO Replace (Low Risk)
1. **Activate RocksDBBlobStore** (already implemented)
2. **Activate RocksDBHandleMap** (already implemented)
3. **Profile performance** to justify migration

### ⚠️ CONSIDER Replacing (Medium Risk)
1. Create `ReferenceContainerSerializer` abstraction
2. Add `RocksDB-based compaction` coordinator
3. Replace traditional I/O with RocksDB async

### 🔴 DO NOT Replace (High Risk)
1. **RAMIndex** - Too deeply embedded
2. **RowSet** - Core data structure, tight coupling
3. **ReferenceContainer** - Type-generic complexity

---

## Architecture Flow Diagram

```
┌─────────────────────────────────────────────────────────────┐
│                    User Search Query                         │
└────────────────────────┬────────────────────────────────────┘
                         │
        ┌────────────────▼────────────────┐
        │   SearchHandler (query parsing)  │
        │   Word hashes: [hash1, hash2]   │
        └────────────────┬────────────────┘
                         │
        ┌────────────────▼────────────────────────────────────┐
        │          IndexCell.searchConjunction()              │
        │  (Unified search index for a shard)                │
        └────┬──────────────────────────┬─────────────────────┘
             │                          │
    ┌────────▼────────┐      ┌──────────▼──────────┐
    │  RAM Cache      │      │   Disk BLOB Array   │
    │  (Hot terms)    │      │  (Cold terms/merge) │
    │  ReferenceCache │      │  (N files)          │
    │                 │      │  ReferenceArray     │
    └────────┬────────┘      └──────────┬──────────┘
             │                          │
    ┌────────▼────────────────────────────────────────┐
    │  ReferenceContainerCache / ReferenceArray       │
    │  Returns: ReferenceContainer (set of URLs)      │
    └────────┬─────────────────────────────────────────┘
             │
    ┌────────▼──────────────────────────────────────┐
    │  Filter by URL hash (conjunction/disjunction) │
    │  Remove deleted URLs (removeDelayedURLs)      │
    │  Sort by relevance (PageRank, etc.)           │
    └────────┬──────────────────────────────────────┘
             │
    ┌────────▼──────────────────────┐
    │  Return: 1000 matching URLs    │
    │  Send to result formatter      │
    └───────────────────────────────┘
```

---

## Conclusion

The Kelondro architecture provides **strong abstraction at the BLOB level** and **weak abstraction at the Index/RWI levels**. 

**RocksDB is ideal for:**
- ✅ Replacing Heap (BLOB storage)
- ✅ Replacing HandleMap (index lookups)
- ✅ Providing auto-compaction (instead of IODispatcher)

**RocksDB cannot easily replace:**
- ❌ RAMIndex (architectural choice, not implementation)
- ❌ RowSet (core memory-efficient data structure)
- ❌ ReferenceContainer (generic type coupling)

**Best path forward:**
1. Enable existing RocksDB components (low friction)
2. Create abstraction layers for serialization
3. Add RocksDB-aware async coordinator
4. Profile before/after for performance validation

