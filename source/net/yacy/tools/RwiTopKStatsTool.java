package net.yacy.tools;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.DBOptions;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksIterator;

/**
 * Read-only statistics tool for RWI-style RocksDB keyspaces.
 *
 * Expected key layout (default):
 *   wordhash|urlhash
 * with a fixed wordhash prefix length (default 12 bytes).
 *
 * Usage example:
 * java -cp "build/classes/java/main;lib/*" net.yacy.tools.RwiTopKStatsTool \
 *   --db=D:/Repo/yacy_my/DATA/INDEX/freeworld/SEGMENTS/rocksdb \
 *   --cf=main --wordBytes=12 --ks=1000,5000,10000 --topN=100
 */
public class RwiTopKStatsTool {

    static {
        RocksDB.loadLibrary();
    }

    private static final class TopWord {
        final byte[] word;
        final long count;

        TopWord(final byte[] word, final long count) {
            this.word = word;
            this.count = count;
        }
    }

    public static void main(final String[] args) throws Exception {
        final Map<String, String> params = parseArgs(args);
        final String dbPath = required(params, "db");
        final String cfName = params.getOrDefault("cf", "main");
        final int wordBytes = Integer.parseInt(params.getOrDefault("wordBytes", "12"));
        final int topN = Integer.parseInt(params.getOrDefault("topN", "50"));
        final List<Integer> ks = Arrays.stream(params.getOrDefault("ks", "1000,5000,10000").split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Integer::parseInt)
                .collect(Collectors.toList());

        final List<byte[]> cfNames = RocksDB.listColumnFamilies(new Options(), dbPath);
        if (cfNames == null || cfNames.isEmpty()) {
            System.err.println("Keine Column Families gefunden: " + dbPath);
            return;
        }

        final List<ColumnFamilyDescriptor> descriptors = new ArrayList<>();
        for (final byte[] name : cfNames) {
            descriptors.add(new ColumnFamilyDescriptor(name, new ColumnFamilyOptions()));
        }
        final List<ColumnFamilyHandle> handles = new ArrayList<>();

        try (DBOptions dbOptions = new DBOptions().setCreateIfMissing(false);
             RocksDB db = RocksDB.openReadOnly(dbOptions, dbPath, descriptors, handles)) {

            ColumnFamilyHandle target = null;
            for (int i = 0; i < descriptors.size(); i++) {
                final String current = new String(descriptors.get(i).getName(), StandardCharsets.UTF_8);
                if (cfName.equals(current)) {
                    target = handles.get(i);
                    break;
                }
            }

            if (target == null) {
                System.err.println("CF nicht gefunden: " + cfName);
                return;
            }

            analyze(db, target, wordBytes, topN, ks);
        } finally {
            for (final ColumnFamilyHandle handle : handles) {
                try {
                    handle.close();
                } catch (final Exception ignored) {
                }
            }
            for (final ColumnFamilyDescriptor descriptor : descriptors) {
                try {
                    descriptor.getOptions().close();
                } catch (final Exception ignored) {
                }
            }
        }
    }

    private static void analyze(
            final RocksDB db,
            final ColumnFamilyHandle cf,
            final int wordBytes,
            final int topN,
            final List<Integer> ks) {

        long postings = 0;
        long words = 0;

        final TreeMap<Long, Long> postingsToWordCount = new TreeMap<>();
        final PriorityQueue<TopWord> top = new PriorityQueue<>((a, b) -> Long.compare(a.count, b.count));

        byte[] currentWord = null;
        long currentCount = 0;

        try (RocksIterator iterator = db.newIterator(cf)) {
            iterator.seekToFirst();
            while (iterator.isValid()) {
                final byte[] key = iterator.key();
                if (key.length < wordBytes) {
                    iterator.next();
                    continue;
                }

                final byte[] word = Arrays.copyOfRange(key, 0, wordBytes);

                if (currentWord == null) {
                    currentWord = word;
                    currentCount = 1;
                } else if (Arrays.equals(currentWord, word)) {
                    currentCount++;
                } else {
                    postings += currentCount;
                    words++;
                    postingsToWordCount.merge(currentCount, 1L, Long::sum);
                    pushTop(top, topN, currentWord, currentCount);

                    currentWord = word;
                    currentCount = 1;
                }

                iterator.next();
            }
        }

        if (currentWord != null) {
            postings += currentCount;
            words++;
            postingsToWordCount.merge(currentCount, 1L, Long::sum);
            pushTop(top, topN, currentWord, currentCount);
        }

        System.out.println("=== RWI Stats (read-only) ===");
        System.out.println("Words(unique): " + words);
        System.out.println("Postings(total): " + postings);
        System.out.println("Avg postings/word: " + (words == 0 ? 0.0 : (double) postings / (double) words));

        printQuantiles(postingsToWordCount, words);

        for (final int k : ks) {
            projectTopK(postingsToWordCount, words, postings, k);
        }

        final List<TopWord> topWords = new ArrayList<>(top);
        topWords.sort((a, b) -> Long.compare(b.count, a.count));
        System.out.println("Top " + topWords.size() + " heavy words:");
        for (final TopWord topWord : topWords) {
            System.out.println(toHex(topWord.word) + " -> " + topWord.count);
        }
    }

    private static void projectTopK(
            final TreeMap<Long, Long> histogram,
            final long words,
            final long postings,
            final int k) {

        long kept = 0;
        long dropped = 0;
        long cappedWords = 0;

        for (final Map.Entry<Long, Long> entry : histogram.entrySet()) {
            final long count = entry.getKey();
            final long countWords = entry.getValue();

            if (count > k) {
                cappedWords += countWords;
            }

            kept += Math.min(count, k) * countWords;
            dropped += Math.max(0, count - k) * countWords;
        }

        System.out.println("--- TopK projection K=" + k + " ---");
        System.out.println("Kept postings: " + kept);
        System.out.println("Dropped postings: " + dropped + " (" + percent(dropped, postings) + "%)");
        System.out.println("Words capped: " + cappedWords + " (" + percent(cappedWords, words) + "%)");
    }

    private static void printQuantiles(final TreeMap<Long, Long> histogram, final long words) {
        if (words == 0) {
            return;
        }

        System.out.println("p50 postings/word: " + quantile(histogram, words, 0.50));
        System.out.println("p90 postings/word: " + quantile(histogram, words, 0.90));
        System.out.println("p99 postings/word: " + quantile(histogram, words, 0.99));
        System.out.println("max postings/word: " + histogram.lastKey());
    }

    private static long quantile(final TreeMap<Long, Long> histogram, final long totalWords, final double q) {
        final long target = Math.max(1, (long) Math.ceil(totalWords * q));
        long cumulative = 0;
        for (final Map.Entry<Long, Long> entry : histogram.entrySet()) {
            cumulative += entry.getValue();
            if (cumulative >= target) {
                return entry.getKey();
            }
        }
        return histogram.isEmpty() ? 0 : histogram.lastKey();
    }

    private static void pushTop(final PriorityQueue<TopWord> top, final int topN, final byte[] word, final long count) {
        if (top.size() < topN) {
            top.offer(new TopWord(word, count));
            return;
        }
        if (top.peek().count < count) {
            top.poll();
            top.offer(new TopWord(word, count));
        }
    }

    private static String percent(final long part, final long total) {
        if (total <= 0) {
            return "0.00";
        }
        return String.format(Locale.ROOT, "%.2f", (part * 100.0) / total);
    }

    private static String toHex(final byte[] data) {
        final StringBuilder sb = new StringBuilder(data.length * 2);
        for (final byte b : data) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static Map<String, String> parseArgs(final String[] args) {
        final Map<String, String> parsed = new HashMap<>();
        for (final String arg : args) {
            if (!arg.startsWith("--")) {
                continue;
            }
            final int separator = arg.indexOf('=');
            if (separator > 2) {
                parsed.put(arg.substring(2, separator), arg.substring(separator + 1));
            }
        }
        return parsed;
    }

    private static String required(final Map<String, String> params, final String key) {
        final String value = params.get(key);
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("Missing --" + key + "=...");
        }
        if (!new File(value).exists()) {
            throw new IllegalArgumentException("Path not found: " + value);
        }
        return value;
    }
}
