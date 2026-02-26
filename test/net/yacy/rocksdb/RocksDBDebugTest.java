package net.yacy.rocksdb;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.security.SecureRandom;
import java.util.List;
import java.util.Map;

import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.DBOptions;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksIterator;

/**
 * Einfacher RocksDB Debug Test:
 * - Zählt mainCF (wordhash+urlhash -> meta) Einträge insgesamt
 * - Zählt wordCF (wordhash -> empty) Einträge insgesamt
 * - Zeigt Sample wordhashes mit URL-Anzahl
 */
public class RocksDBDebugTest {

    static {
        RocksDB.loadLibrary();
    }

    public static void main(String[] args) throws Exception {
        final File dbPath;
        if (args.length > 0 && args[0] != null && !args[0].trim().isEmpty()) {
            dbPath = new File(args[0].trim());
        } else {
            dbPath = new File("DATA/INDEX/freeworld/SEGMENTS/rocksdb");
        }

        if (!dbPath.exists()) {
            System.out.println("ERROR: RocksDB path does not exist: " + dbPath.getAbsolutePath());
            System.out.println("Usage: java net.yacy.rocksdb.RocksDBDebugTest <rocksdb-path>");
            return;
        }

        System.out.println("Opening RocksDB at: " + dbPath.getAbsolutePath());
        System.out.println();

        try {
            // Lade RocksDB Library
            RocksDB.loadLibrary();

            // Öffne RocksDB mit beiden CFamilies
            final DBOptions dbOptions = new DBOptions().setCreateIfMissing(true);
            final ColumnFamilyOptions cfOptions = new ColumnFamilyOptions();
            final ReadOptions readOptions = new ReadOptions();

            // Lies CFamilies aus Metadaten
            final java.util.List<ColumnFamilyDescriptor> cfDescs = new java.util.ArrayList<>();
            

            // Suche nach den Standard CFamilies
            try (final org.rocksdb.Options listOptions = new org.rocksdb.Options().setCreateIfMissing(true)) {
                final java.util.List<byte[]> cfNames = RocksDB.listColumnFamilies(listOptions, dbPath.getAbsolutePath());
                System.out.println("Available Column Families: " + cfNames);

                for (final byte[] cfName : cfNames) {
                    cfDescs.add(new ColumnFamilyDescriptor(cfName, cfOptions));
                }
            } catch (final Exception e) {
                System.out.println("Could not list CFamilies, trying default...");
                cfDescs.add(new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, cfOptions));
                cfDescs.add(new ColumnFamilyDescriptor("mainCF".getBytes(), cfOptions));
                cfDescs.add(new ColumnFamilyDescriptor("wordCF".getBytes(), cfOptions));
            }

            final java.util.List<ColumnFamilyHandle> cfHandles = new java.util.ArrayList<>();
            final RocksDB db = RocksDB.open(dbOptions, dbPath.getAbsolutePath(), cfDescs, cfHandles);

            System.out.println("RocksDB opened with " + cfHandles.size() + " column families");
            System.out.println();

            // Zähle Einträge in jeder CF
            if (cfHandles.size() >= 2) {
                final Map<String, ColumnFamilyHandle> cfByName = new HashMap<>();
                for (int i = 0; i < cfDescs.size() && i < cfHandles.size(); i++) {
                    final String cfName = new String(cfDescs.get(i).getName(), StandardCharsets.UTF_8);
                    cfByName.put(cfName, cfHandles.get(i));
                }
                ColumnFamilyHandle mainCF = cfByName.get("mainCF");
                ColumnFamilyHandle wordCF = cfByName.get("wordCF");
                if (mainCF == null) {
                    mainCF = cfByName.get("default");
                }
                if (wordCF == null) {
                    wordCF = cfByName.get("words");
                }
                if (mainCF == null || wordCF == null) {
                    System.out.println("ERROR: Could not resolve mainCF/wordCF by name: " + cfByName.keySet());
                    db.close();
                    return;
                }

                System.out.println("=== RocksDB Statistics ===\n");

                long wordCFCount = 0;

                final int firstCount = args.length > 1 ? Math.max(1, Integer.parseInt(args[1])) : 10;
                final int lastCount = args.length > 2 ? Math.max(1, Integer.parseInt(args[2])) : 10;
                final int randomCount = args.length > 3 ? Math.max(0, Integer.parseInt(args[3])) : 5;
                final int wordHashLength = WordUrlKeyCodec.WORD_HASH_LENGTH;
                final List<byte[]> sampleHashes = new java.util.ArrayList<>();
                final java.util.List<Integer> sampleCounts = new java.util.ArrayList<>();

                // 1) Nimm die ersten N wordhashes aus wordCF
                System.out.print("Scanning wordCF entries for first " + firstCount + " hashes... ");
                try (final RocksIterator iter = db.newIterator(wordCF, readOptions)) {
                    iter.seekToFirst();
                    while (iter.isValid() && sampleHashes.size() < firstCount) {
                        final byte[] key = iter.key();
                        if (key.length >= wordHashLength) {
                            addUniqueSample(sampleHashes, sampleCounts, Arrays.copyOfRange(key, 0, wordHashLength));
                        }
                        wordCFCount++;
                        iter.next();
                    }
                    System.out.println(" Done! (scanned " + wordCFCount + " wordCF keys)");
                }

                // 2) Nimm die letzten N wordhashes aus wordCF
                System.out.print("Scanning wordCF entries for last " + lastCount + " hashes... ");
                try (final RocksIterator iter = db.newIterator(wordCF, readOptions)) {
                    iter.seekToLast();
                    int added = 0;
                    while (iter.isValid() && added < lastCount) {
                        final byte[] key = iter.key();
                        if (key.length >= wordHashLength) {
                            if (addUniqueSample(sampleHashes, sampleCounts, Arrays.copyOfRange(key, 0, wordHashLength))) {
                                added++;
                            }
                        }
                        iter.prev();
                    }
                    System.out.println(" Done! (added " + added + " hashes)");
                }

                // 3) Nimm M zufaellige wordhashes via seek auf zufaellige Prefixe
                if (randomCount > 0) {
                    System.out.print("Sampling " + randomCount + " random hashes... ");
                    final SecureRandom rng = new SecureRandom();
                    try (final RocksIterator iter = db.newIterator(wordCF, readOptions)) {
                        int added = 0;
                        int attempts = 0;
                        while (added < randomCount && attempts < randomCount * 10) {
                            attempts++;
                            final byte[] randomKey = new byte[wordHashLength];
                            rng.nextBytes(randomKey);
                            iter.seek(randomKey);
                            if (!iter.isValid()) {
                                iter.seekToFirst();
                            }
                            if (iter.isValid()) {
                                final byte[] key = iter.key();
                                if (key.length >= wordHashLength) {
                                    if (addUniqueSample(sampleHashes, sampleCounts, Arrays.copyOfRange(key, 0, wordHashLength))) {
                                        added++;
                                    }
                                }
                            }
                        }
                        System.out.println(" Done! (added " + added + " hashes)");
                    }
                }

                // 2) Zähle mainCF Vorkommnisse dieser N wordhashes per Prefix-Scan
                System.out.println("\nCounting mainCF occurrences per wordhash (prefix scan)...");
                for (int i = 0; i < sampleHashes.size(); i++) {
                    final byte[] wordHash = sampleHashes.get(i);
                    final byte[] prefix = WordUrlKeyCodec.wordPrefix(wordHash);
                    long count = 0;
                    try (final RocksIterator iter = db.newIterator(mainCF, readOptions)) {
                        iter.seek(prefix);
                        while (iter.isValid() && WordUrlKeyCodec.hasWordPrefix(iter.key(), prefix)) {
                            count++;
                            iter.next();
                        }
                    }
                    sampleCounts.set(i, (int) Math.min(Integer.MAX_VALUE, count));
                }

                System.out.println("\n=== Sample wordhashes (from wordCF) ===");
                for (int i = 0; i < sampleHashes.size(); i++) {
                    final byte[] wordHash = sampleHashes.get(i);
                    final int urlCount = sampleCounts.get(i);
                    final byte[] wordValue = db.get(wordCF, wordHash);
                    final boolean inWordCF = wordValue != null;
                    final int wordCFHits = inWordCF ? 1 : 0;
                    System.out.println("  " + bytesToHex(wordHash, 0, wordHash.length)
                        + " | mainCF URLs: " + urlCount
                        + " | wordCF hits: " + wordCFHits);
                }

            } else {
                System.out.println("ERROR: Expected at least 2 column families, got " + cfHandles.size());
            }

            db.close();

        } catch (final Exception e) {
            System.out.println("ERROR: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static String bytesToHex(byte[] bytes, int offset, int length) {
        final StringBuilder sb = new StringBuilder();
        for (int i = offset; i < offset + length && i < bytes.length; i++) {
            sb.append(String.format("%02x", bytes[i]));
        }
        return sb.toString();
    }

    private static int indexOfSample(final List<byte[]> samples, final byte[] key, final int len) {
        for (int i = 0; i < samples.size(); i++) {
            if (bytesEquals(samples.get(i), key, len)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean addUniqueSample(final List<byte[]> samples, final List<Integer> counts, final byte[] sample) {
        if (indexOfSample(samples, sample, sample.length) >= 0) {
            return false;
        }
        samples.add(sample);
        counts.add(0);
        return true;
    }

    private static boolean bytesEquals(final byte[] sample, final byte[] key, final int len) {
        if (sample.length < len || key.length < len) {
            return false;
        }
        for (int i = 0; i < len; i++) {
            if (sample[i] != key[i]) {
                return false;
            }
        }
        return true;
    }
}
