package net.yacy.rocksdb;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.yacy.cora.util.ConcurrentLog;
import net.yacy.kelondro.blob.HeapReader;

public final class WordUrlBlobImporter {

    private static final int DEFAULT_BATCH_SIZE = 50_000;

    private WordUrlBlobImporter() {
    }

    public static long importBlobFile(final File blobFile,
                                      final int keyLength,
                                      final WordUrlRefStore store) throws IOException {
        return importBlobFile(blobFile, keyLength, store, DEFAULT_BATCH_SIZE);
    }

    public static long importBlobFile(final File blobFile,
                                      final int keyLength,
                                      final WordUrlRefStore store,
                                      final int batchSize) throws IOException {
        if (blobFile == null || store == null) return 0L;
        if (!blobFile.exists()) throw new IOException("Blob file not found: " + blobFile.getAbsolutePath());

        long recordsRead = 0L;
        long refsWritten = 0L;
        final int actualBatchSize = Math.max(1, batchSize);
        final List<WordUrlRefRecord> batch = new ArrayList<WordUrlRefRecord>(actualBatchSize);

        final boolean inplace = Boolean.getBoolean("yacy.index.progress.inplace");
        final long startTime = System.currentTimeMillis();
        long lastConsoleTime = startTime;
        String lastConsoleLine = "";
        final long fileSize = blobFile.length();

        HeapReader.entries entries = null;
        try {
            entries = new HeapReader.entries(blobFile, keyLength);
            for (final Map.Entry<byte[], byte[]> entry : entries) {
                final byte[] wordHash = entry.getKey();
                final byte[] value = entry.getValue();
                final List<byte[]> metas = RefMetaCodec.splitRefMetas(value);

                for (final byte[] meta : metas) {
                    final byte[] urlHash = RefMetaCodec.extractUrlHash(meta);
                    batch.add(new WordUrlRefRecord(wordHash, urlHash, meta));
                    refsWritten++;

                    if (batch.size() >= actualBatchSize) {
                        flushBatch(store, batch);
                    }
                }

                recordsRead++;

                // In-place progress update
                if (inplace) {
                    final long now = System.currentTimeMillis();
                    if (now - lastConsoleTime >= 1000) {
                        final double progress = (double) recordsRead / Math.max(1, fileSize / 100);
                        final String line = String.format("Importing %s: %.1f%% (%d records, %d refs)",
                            blobFile.getName(), progress, recordsRead, refsWritten);
                        
                        // Padding if line is shorter
                        final String paddedLine;
                        if (line.length() < lastConsoleLine.length()) {
                            final StringBuilder pad = new StringBuilder(line);
                            for (int i = line.length(); i < lastConsoleLine.length(); i++) {
                                pad.append(' ');
                            }
                            paddedLine = pad.toString();
                        } else {
                            paddedLine = line;
                        }
                        
                        System.out.print("\r" + paddedLine);
                        System.out.flush();
                        lastConsoleLine = paddedLine;
                        lastConsoleTime = now;
                    }
                }
            }

            if (!batch.isEmpty()) {
                flushBatch(store, batch);
            }
        } finally {
            if (entries != null) {
                entries.close();
            }
            if (inplace) {
                System.out.print("\n");
                System.out.flush();
            }
        }

        ConcurrentLog.info("WordUrlBlobImporter", "imported blob=" + blobFile.getName()
                + " records=" + recordsRead + " refs=" + refsWritten);

        return refsWritten;
    }

    private static void flushBatch(final WordUrlRefStore store,
                                   final List<WordUrlRefRecord> batch) throws IOException {
        store.upsertBatch(batch);
        batch.clear();
    }
}
