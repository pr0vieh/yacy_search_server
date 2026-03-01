package net.yacy.rocksdb;

import java.io.File;

public final class WordUrlRefStorageFactory {

    private WordUrlRefStorageFactory() {
    }

    public static WordUrlRefStorage create(final File dbPath, final String mode) {
        final String normalized = normalizeMode(mode);
        if ("blob".equals(normalized)) {
            return new BlobWordUrlRefStorage(dbPath);
        }
        return new PostingWordUrlRefStorage(dbPath);
    }

    public static String normalizeMode(final String mode) {
        if (mode == null) return "posting";
        final String normalized = mode.trim().toLowerCase();
        return "blob".equals(normalized) ? "blob" : "posting";
    }
}