package net.yacy.tools;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import net.yacy.rocksdb.WordUrlBlobImporter;
import net.yacy.rocksdb.WordUrlRefStorage;
import net.yacy.rocksdb.WordUrlRefStorageFactory;

public class RocksBlobImportSmokeTool {

    public static void main(final String[] args) throws Exception {
        final Map<String, String> params = parseArgs(args);
        final File blobFile = new File(required(params, "blob"));
        final File dbPath = new File(required(params, "db"));
        final String storageMode = params.getOrDefault("storageMode", "blob");
        final int keyLength = Integer.parseInt(params.getOrDefault("keyLength", "12"));
        final int batchSize = Integer.parseInt(params.getOrDefault("batchSize", "50000"));
        final int topK = Integer.parseInt(params.getOrDefault("topK", "1000"));
        final int maxPerHost = Integer.parseInt(params.getOrDefault("maxPerHost", "3"));

        if (!blobFile.exists() || !blobFile.isFile()) {
            throw new IllegalArgumentException("blob file not found: " + blobFile.getAbsolutePath());
        }

        if (!dbPath.exists() && !dbPath.mkdirs()) {
            throw new IllegalArgumentException("cannot create db path: " + dbPath.getAbsolutePath());
        }

        final long start = System.currentTimeMillis();
        try (WordUrlRefStorage storage = WordUrlRefStorageFactory.create(dbPath, storageMode)) {
            final long importedRefs = WordUrlBlobImporter.importBlobFile(blobFile, keyLength, storage, batchSize, topK, maxPerHost);
            final long distinctWords = storage.distinctWordCount();
            final long totalRefs = storage.size();
            final long tookMs = System.currentTimeMillis() - start;

            System.out.println("=== RocksBlobImportSmokeTool ===");
            System.out.println("blob=" + blobFile.getAbsolutePath());
            System.out.println("db=" + dbPath.getAbsolutePath());
            System.out.println("mode=" + storage.mode());
            System.out.println("importedRefs=" + importedRefs);
            System.out.println("distinctWords=" + distinctWords);
            System.out.println("totalRefs=" + totalRefs);
            System.out.println("durationMs=" + tookMs);
        }
    }

    private static String required(final Map<String, String> params, final String key) {
        final String value = params.get(key);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("missing parameter --" + key);
        }
        return value;
    }

    private static Map<String, String> parseArgs(final String[] args) {
        final Map<String, String> params = new HashMap<String, String>();
        if (args == null) return params;
        for (final String arg : args) {
            if (arg == null) continue;
            final String trimmed = arg.trim();
            if (!trimmed.startsWith("--")) continue;
            final int p = trimmed.indexOf('=');
            if (p < 0) {
                params.put(trimmed.substring(2), "true");
            } else {
                params.put(trimmed.substring(2, p), trimmed.substring(p + 1));
            }
        }
        return params;
    }
}