package net.yacy.rocksdb;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.DBOptions;
import org.rocksdb.Options;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksIterator;

public class WordCfLookupTest {

    static {
        RocksDB.loadLibrary();
    }

    public static void main(String[] args) throws Exception {
        final String hash = args.length > 0 ? args[0] : "KZzU-Vf6h5k-";
        if (hash.length() != WordUrlKeyCodec.WORD_HASH_LENGTH) {
            System.out.println("ERROR: hash length must be " + WordUrlKeyCodec.WORD_HASH_LENGTH + ", got " + hash.length());
            return;
        }

        final File dbPath = new File("DATA/INDEX/freeworld/SEGMENTS/rocksdb");
        if (!dbPath.exists()) {
            System.out.println("ERROR: RocksDB path not found: " + dbPath.getAbsolutePath());
            return;
        }

        final DBOptions dbOptions = new DBOptions().setCreateIfMissing(true);
        final ColumnFamilyOptions cfOptions = new ColumnFamilyOptions();
        final ReadOptions readOptions = new ReadOptions();

        final List<ColumnFamilyDescriptor> cfDescs = new ArrayList<>();
        try (final Options listOptions = new Options().setCreateIfMissing(true)) {
            final List<byte[]> cfNames = RocksDB.listColumnFamilies(listOptions, dbPath.getAbsolutePath());
            for (final byte[] cfName : cfNames) {
                cfDescs.add(new ColumnFamilyDescriptor(cfName, cfOptions));
            }
        }

        final List<ColumnFamilyHandle> cfHandles = new ArrayList<>();
        final RocksDB db = RocksDB.open(dbOptions, dbPath.getAbsolutePath(), cfDescs, cfHandles);

        final Map<String, ColumnFamilyHandle> cfByName = new HashMap<>();
        for (int i = 0; i < cfDescs.size() && i < cfHandles.size(); i++) {
            cfByName.put(new String(cfDescs.get(i).getName(), StandardCharsets.UTF_8), cfHandles.get(i));
        }

        final ColumnFamilyHandle mainCF = cfByName.get("default");
        final ColumnFamilyHandle wordCF = cfByName.get("words");
        if (mainCF == null || wordCF == null) {
            System.out.println("ERROR: Required CF not found. Available: " + cfByName.keySet());
            db.close();
            return;
        }

        final byte[] wordHash = hash.getBytes(StandardCharsets.US_ASCII);

        final byte[] value = db.get(wordCF, wordHash);
        final boolean inWordCF = value != null;

        long mainCount = 0;
        final byte[] prefix = WordUrlKeyCodec.wordPrefix(wordHash);
        try (final RocksIterator iter = db.newIterator(mainCF, readOptions)) {
            iter.seek(prefix);
            while (iter.isValid() && WordUrlKeyCodec.hasWordPrefix(iter.key(), prefix)) {
                mainCount++;
                iter.next();
            }
        }

        System.out.println("hash: " + hash);
        System.out.println("wordCF hit: " + (inWordCF ? 1 : 0));
        System.out.println("mainCF refs: " + mainCount);

        db.close();
        readOptions.close();
        cfOptions.close();
        dbOptions.close();
    }
}
