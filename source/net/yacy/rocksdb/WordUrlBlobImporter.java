package net.yacy.rocksdb;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.yacy.cora.document.encoding.ASCII;
import net.yacy.cora.util.ConcurrentLog;
import net.yacy.kelondro.blob.HeapReader;
import net.yacy.kelondro.data.word.WordReference;
import net.yacy.kelondro.data.word.WordReferenceFactory;
import net.yacy.kelondro.data.word.WordReferenceVars;

public final class WordUrlBlobImporter {

    private static final int DEFAULT_BATCH_SIZE = 500_000;
    private static final WordReferenceFactory WORD_REFERENCE_FACTORY = new WordReferenceFactory();

    private static final class RankedMeta {
        final byte[] urlHash;
        final byte[] meta;
        final double score;

        RankedMeta(final byte[] urlHash, final byte[] meta, final double score) {
            this.urlHash = urlHash;
            this.meta = meta;
            this.score = score;
        }
    }

    private WordUrlBlobImporter() {
    }

    public static long importBlobFile(final File blobFile,
                                      final int keyLength,
                                      final WordUrlRefStorage store) throws IOException {
        return importBlobFile(blobFile, keyLength, store, DEFAULT_BATCH_SIZE);
    }

    public static long importBlobFile(final File blobFile,
                                      final int keyLength,
                                      final WordUrlRefStorage store,
                                      final int batchSize) throws IOException {
        return importBlobFile(blobFile, keyLength, store, batchSize, 0, 3);
    }

    public static long importBlobFile(final File blobFile,
                                      final int keyLength,
                                      final WordUrlRefStorage store,
                                      final int batchSize,
                                      final int topK,
                                      final int maxPerHost) throws IOException {
        if (blobFile == null || store == null) return 0L;
        if (!blobFile.exists()) throw new IOException("Blob file not found: " + blobFile.getAbsolutePath());

        long recordsRead = 0L;
        long refsWritten = 0L;
        long refsDroppedTopK = 0L;
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

                final List<byte[]> selectedMetas;
                if (topK > 0 && metas.size() > topK) {
                    selectedMetas = selectTopKMetas(metas, topK, Math.max(1, maxPerHost));
                    refsDroppedTopK += Math.max(0, metas.size() - selectedMetas.size());
                } else {
                    selectedMetas = metas;
                }

                for (final byte[] meta : selectedMetas) {
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
                + " records=" + recordsRead + " refs=" + refsWritten
                + " droppedTopK=" + refsDroppedTopK);

        return refsWritten;
    }

    private static List<byte[]> selectTopKMetas(final List<byte[]> metas, final int topK, final int maxPerHost) {
        if (metas == null || metas.isEmpty() || metas.size() <= topK) {
            return metas == null ? Collections.<byte[]>emptyList() : metas;
        }

        final Map<String, RankedMeta> bestByUrl = new HashMap<String, RankedMeta>(metas.size());
        for (final byte[] meta : metas) {
            if (meta == null || meta.length == 0) continue;
            final byte[] urlHash = RefMetaCodec.extractUrlHash(meta);
            final String key = ASCII.String(urlHash);
            final RankedMeta existing = bestByUrl.get(key);
            if (existing == null) {
                final double score = computeScoreFromMeta(meta);
                bestByUrl.put(key, new RankedMeta(urlHash, meta, score));
            } else {
                final byte[] mergedMeta = mergeRefMeta(existing.meta, meta);
                final double score = computeScoreFromMeta(mergedMeta);
                bestByUrl.put(key, new RankedMeta(urlHash, mergedMeta, score));
            }
        }

        final List<RankedMeta> candidates = new ArrayList<RankedMeta>(bestByUrl.values());
        candidates.sort((a, b) -> Double.compare(b.score, a.score));

        final List<RankedMeta> selected = new ArrayList<RankedMeta>(Math.min(topK, candidates.size()));
        final List<RankedMeta> overflow = new ArrayList<RankedMeta>();
        final Map<String, Integer> hostCounts = new HashMap<String, Integer>();

        for (final RankedMeta candidate : candidates) {
            if (selected.size() >= topK) break;
            final String host = hostKey(candidate.urlHash);
            final int count = hostCounts.getOrDefault(host, 0);
            if (count < maxPerHost) {
                selected.add(candidate);
                hostCounts.put(host, count + 1);
            } else {
                overflow.add(candidate);
            }
        }

        if (selected.size() < topK) {
            for (final RankedMeta candidate : overflow) {
                selected.add(candidate);
                if (selected.size() >= topK) break;
            }
        }

        final List<byte[]> out = new ArrayList<byte[]>(selected.size());
        for (final RankedMeta ranked : selected) {
            out.add(ranked.meta);
        }
        return out;
    }

    private static String hostKey(final byte[] urlHash) {
        if (urlHash == null || urlHash.length < 12) return "unknown";
        return ASCII.String(urlHash, 6, 6);
    }

    private static double computeScoreFromMeta(final byte[] meta) {
        try {
            final WordReference reference = WORD_REFERENCE_FACTORY.produceSlow(WORD_REFERENCE_FACTORY.getRow().newEntry(meta));

            final int hitcount = Math.max(1, reference.hitcount());
            final int wordsInText = Math.max(1, reference.wordsintext());
            final int posInText = Math.max(0, reference.posintext());
            final int wordsInTitle = Math.max(0, reference.wordsintitle());
            final int outlinks = Math.max(0, reference.llocal()) + Math.max(0, reference.lother());

            double score = 0.0;

            final double k1 = 1.2;
            final double b = 0.75;
            final double avgDocLength = 500.0;
            final double normalization = (1.0 - b) + b * (wordsInText / avgDocLength);
            score += (hitcount * (k1 + 1.0)) / (hitcount + k1 * normalization);

            score += 3.0 / (1.0 + Math.log1p(posInText));

            final long now = System.currentTimeMillis();
            final long ageDays = Math.max(0L, (now - reference.lastModified()) / 86_400_000L);
            final double freshness = Math.max(0.0, 1.0 - (ageDays / 3650.0));
            score += freshness * 3.0;

            if (wordsInTitle > 0) {
                score += 2.0;
            }

            score += Math.min(2.0, Math.log1p(outlinks) * 0.5);
            return score;
        } catch (final Throwable ignored) {
            return 0.0;
        }
    }

    private static void flushBatch(final WordUrlRefStorage store,
                                   final List<WordUrlRefRecord> batch) throws IOException {
        store.upsertBatch(batch);
        batch.clear();
    }

    private static byte[] mergeRefMeta(final byte[] leftMeta, final byte[] rightMeta) {
        if (leftMeta == null || leftMeta.length != RefMetaCodec.REF_SIZE) return rightMeta;
        if (rightMeta == null || rightMeta.length != RefMetaCodec.REF_SIZE) return leftMeta;
        try {
            final WordReference leftRef = WORD_REFERENCE_FACTORY.produceSlow(WORD_REFERENCE_FACTORY.getRow().newEntry(leftMeta));
            final WordReference rightRef = WORD_REFERENCE_FACTORY.produceSlow(WORD_REFERENCE_FACTORY.getRow().newEntry(rightMeta));
            final WordReferenceVars merged = new WordReferenceVars(leftRef, true);
            merged.join(rightRef);
            return merged.toKelondroEntry().bytes();
        } catch (final Throwable ignored) {
            return rightMeta;
        }
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
