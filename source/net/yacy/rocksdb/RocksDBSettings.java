package net.yacy.rocksdb;

public final class RocksDBSettings {

    public static final String SWITCH_USE_ROCKSDB_REFS = "rocksdb.refs.enabled";
    public static final boolean SWITCH_USE_ROCKSDB_REFS_DEFAULT = false;

    public static final String ROCKSDB_PATH = "rocksdb.path";
    public static final String ROCKSDB_PATH_DEFAULT = "DATA/INDEX/freeworld/SEGMENTS/default/rocksdb";

    public static final String ROCKSDB_IMPORT_BATCH_SIZE = "rocksdb.import.batchsize";
    public static final int ROCKSDB_IMPORT_BATCH_SIZE_DEFAULT = 50000;

    private RocksDBSettings() {
    }
}
