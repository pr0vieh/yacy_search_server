package net.yacy.rocksdb;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.yacy.cora.util.ConcurrentLog;
import net.yacy.kelondro.blob.HeapReader;

public final class WordUrlBlobImporter {

    private static final int DEFAULT_BATCH_SIZE = 200_000;

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
        final boolean inplace = true;

        final long startTime = System.currentTimeMillis();
        long estimatedBytes = 0L;
        long lastLoggedBytes = 0L;
        long lastLoggedRecords = 0L;
        long lastLogTime = startTime;
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
                estimatedBytes += 4L + keyLength + value.length;

                final long now = System.currentTimeMillis();

                if (now - lastLogTime >= 60000L) {
                    final double pct = fileSize > 0 ? (100.0 * estimatedBytes / fileSize) : 100.0;
                    final long elapsedMs = Math.max(1L, now - startTime);
                    final long intervalMs = Math.max(1L, now - lastLogTime);
                    final double mibDone = estimatedBytes / 1024.0 / 1024.0;
                    final double mibTotal = fileSize / 1024.0 / 1024.0;
                    final double mibPerSec = ((estimatedBytes - lastLoggedBytes) / 1024.0 / 1024.0) / (intervalMs / 1000.0);
                    final double recPerSec = (recordsRead - lastLoggedRecords) / (intervalMs / 1000.0);
                    final long etaMs = (estimatedBytes > 0 && fileSize > estimatedBytes)
                            ? (long) ((fileSize - estimatedBytes) * (elapsedMs / (double) estimatedBytes))
                            : 0L;
                            ConcurrentLog.info("WordUrlBlobImporter", "blob import " + String.format("%.1f", pct)
                                + "% for " + blobFile.getName()
                                + " (" + recordsRead + " records, "
                                + String.format("%.1f", mibDone) + "/" + String.format("%.1f", mibTotal) + " MiB, "
                                + String.format("%.2f", mibPerSec) + " MiB/s, "
                                + String.format("%.0f", recPerSec) + " rec/s, ETA "
                                + formatDuration(etaMs) + ")");
                    lastLoggedBytes = estimatedBytes;
                    lastLoggedRecords = recordsRead;
                    lastLogTime = now;
                }

                // In-place progress update
                if (inplace && now - lastConsoleTime >= 1000) {
                    final double pct = fileSize > 0 ? (100.0 * estimatedBytes / fileSize) : 100.0;
                    final long elapsedMs = Math.max(1L, now - startTime);
                    final double mibDone = estimatedBytes / 1024.0 / 1024.0;
                    final double mibTotal = fileSize / 1024.0 / 1024.0;
                    final double mibPerSec = (estimatedBytes / 1024.0 / 1024.0) / (elapsedMs / 1000.0);
                    final long etaMs = (estimatedBytes > 0 && fileSize > estimatedBytes)
                            ? (long) ((fileSize - estimatedBytes) * (elapsedMs / (double) estimatedBytes))
                            : 0L;
                    String line = "WordUrlBlobImporter: import " + renderProgressBar(pct, 30)
                            + " " + String.format("%5.1f", pct) + "%"
                            + " | " + String.format("%.1f", mibDone) + "/" + String.format("%.1f", mibTotal) + " MiB"
                            + " | " + String.format("%.2f", mibPerSec) + " MiB/s"
                            + " | " + recordsRead + " rec"
                            + " | ETA " + formatDuration(etaMs);

                    // Padding if line is shorter
                    if (line.length() < lastConsoleLine.length()) {
                        final StringBuilder pad = new StringBuilder(line);
                        for (int i = line.length(); i < lastConsoleLine.length(); i++) {
                            pad.append(' ');
                        }
                        line = pad.toString();
                    }

                    System.out.print("\r" + line);
                    System.out.flush();
                    lastConsoleLine = line;
                    lastConsoleTime = now;
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
                final long doneAt = System.currentTimeMillis();
                final long elapsedMs = Math.max(1L, doneAt - startTime);
                final double mibDone = estimatedBytes / 1024.0 / 1024.0;
                final double mibTotal = fileSize / 1024.0 / 1024.0;
                final double mibPerSec = mibDone / (elapsedMs / 1000.0);
                String line = "WordUrlBlobImporter: import " + renderProgressBar(100.0, 30)
                        + " 100.0%"
                        + " | " + String.format("%.1f", mibDone) + "/" + String.format("%.1f", mibTotal) + " MiB"
                        + " | " + String.format("%.2f", mibPerSec) + " MiB/s"
                        + " | " + recordsRead + " rec"
                        + " | ETA 0s";
                if (line.length() < lastConsoleLine.length()) {
                    final StringBuilder pad = new StringBuilder(line);
                    for (int i = line.length(); i < lastConsoleLine.length(); i++) {
                        pad.append(' ');
                    }
                    line = pad.toString();
                }
                System.out.print("\r" + line);
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

    private static String formatDuration(final long millis) {
        long sec = Math.max(0L, millis / 1000L);
        final long h = sec / 3600L;
        sec %= 3600L;
        final long m = sec / 60L;
        final long s = sec % 60L;
        if (h > 0) return h + "h " + m + "m " + s + "s";
        if (m > 0) return m + "m " + s + "s";
        return s + "s";
    }

    private static String renderProgressBar(final double pct, final int width) {
        final int clamped = (int) Math.max(0, Math.min(width, Math.round((pct / 100.0) * width)));
        final StringBuilder sb = new StringBuilder(width + 2);
        sb.append('[');
        for (int i = 0; i < width; i++) sb.append(i < clamped ? '=' : ' ');
        sb.append(']');
        return sb.toString();
    }
}
