// HeapBlobImporter.java
// (C) 2026 by YaCy Contributors
// first published 21.02.2026 on http://yacy.net
//
// LICENSE
//
// This program is free software; you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation; either version 2 of the License, or
// (at your option) any later version.

package net.yacy.rocksdb.migration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.rocksdb.RocksDBException;

import net.yacy.cora.util.ConcurrentLog;
import net.yacy.kelondro.blob.HeapReader;
import net.yacy.rocksdb.RocksDBBlobStore;

/**
 * Import heap blob files into RocksDB with merge-aware writes.
 */
public final class HeapBlobImporter {

    private static final long PROGRESS_UPDATE_MS = 500L;

    private HeapBlobImporter() {
    }

    public static long importBlobDirectory(final File heapDir,
                                           final String prefix,
                                           final int keylength,
                                           final RocksDBBlobStore store) throws IOException {
        final List<File> blobs = listBlobFiles(heapDir, prefix);
        long totalBytes = 0L;
        for (final File blob : blobs) {
            totalBytes += Math.max(0L, blob.length());
        }

        final boolean showInplace = System.console() != null;
        final long globalStart = System.currentTimeMillis();
        long processedBytesBase = 0L;
        long total = 0;
        for (int index = 0; index < blobs.size(); index++) {
            final File blob = blobs.get(index);
            total += importBlobFile(blob, keylength, store, index + 1, blobs.size(),
                    processedBytesBase, totalBytes, globalStart, showInplace);
            processedBytesBase += Math.max(0L, blob.length());
            archiveImportedBlob(blob);
        }
        if (showInplace) {
            System.out.print("\r");
            System.out.println();
        }
        return total;
    }

    public static long importBlobFile(final File blobFile,
                                      final int keylength,
                                      final RocksDBBlobStore store) throws IOException {
        return importBlobFile(blobFile, keylength, store, 1, 1, 0L,
                Math.max(0L, blobFile == null ? 0L : blobFile.length()),
                System.currentTimeMillis(), false);
    }

    private static long importBlobFile(final File blobFile,
                                       final int keylength,
                                       final RocksDBBlobStore store,
                                       final int fileIndex,
                                       final int fileCount,
                                       final long processedBytesBase,
                                       final long totalBytes,
                                       final long globalStart,
                                       final boolean showInplace) throws IOException {
        if (blobFile == null || store == null) return 0;
        if (!blobFile.exists()) throw new IOException("Blob file not found: " + blobFile.getAbsolutePath());

        long count = 0;
        final long version = blobFile.lastModified();
        long lastProgressTs = System.currentTimeMillis();
        long lastBatchTs = System.currentTimeMillis();
        HeapReader.entries entries = null;
        
        // Batch settings: 50k entries per batch for optimal performance
        final int batchSize = 50000;
        final java.util.List<Map.Entry<byte[], byte[]>> batch = new java.util.ArrayList<>(batchSize);
        
        try {
            entries = new HeapReader.entries(blobFile, keylength);
            for (final Map.Entry<byte[], byte[]> entry : entries) {
                batch.add(entry);
                count++;
                
                // Flush batch when it reaches size limit or every 2 seconds
                final long now = System.currentTimeMillis();
                final boolean shouldFlush = batch.size() >= batchSize || (now - lastBatchTs >= 2000L && !batch.isEmpty());
                
                if (shouldFlush) {
                    try {
                        store.putImportBatch(batch, version);
                        batch.clear();
                        lastBatchTs = now;
                    } catch (final RocksDBException e) {
                        throw new IOException("Batch import failed: " + e.getMessage(), e);
                    }
                }
                
                // Progress reporting every 500ms
                if (now - lastProgressTs >= PROGRESS_UPDATE_MS) {
                    reportProgress(blobFile, fileIndex, fileCount, count, entries.readBytes(), processedBytesBase,
                            totalBytes, globalStart, now, showInplace);
                    lastProgressTs = now;
                }
            }
            
            // Flush remaining entries
            if (!batch.isEmpty()) {
                try {
                    store.putImportBatch(batch, version);
                    batch.clear();
                } catch (final RocksDBException e) {
                    throw new IOException("Final batch import failed: " + e.getMessage(), e);
                }
            }
            
            final long endTs = System.currentTimeMillis();
            reportProgress(blobFile, fileIndex, fileCount, count, entries.readBytes(), processedBytesBase,
                    totalBytes, globalStart, endTs, showInplace);
            ConcurrentLog.info("HeapBlobImporter", "imported " + count + " entries from " + blobFile.getName() 
                    + " in batches of " + batchSize);
        } finally {
            if (entries != null) {
                entries.close();
            }
            batch.clear();
        }
        return count;
    }

    private static void reportProgress(final File blobFile,
                                       final int fileIndex,
                                       final int fileCount,
                                       final long entriesImported,
                                       final long fileBytesRead,
                                       final long processedBytesBase,
                                       final long totalBytes,
                                       final long globalStart,
                                       final long now,
                                       final boolean showInplace) {
        final long doneBytes = Math.min(totalBytes, processedBytesBase + Math.max(0L, fileBytesRead));
        final long elapsedMs = Math.max(1L, now - globalStart);
        final double bytesPerSec = doneBytes * 1000.0d / elapsedMs;
        final double recPerSec = entriesImported * 1000.0d / Math.max(1L, now - Math.max(globalStart, now - elapsedMs));
        final double percent = totalBytes <= 0L ? 100.0d : (doneBytes * 100.0d / totalBytes);
        final long remainingBytes = Math.max(0L, totalBytes - doneBytes);
        final long etaSec = bytesPerSec <= 0.0001d ? -1L : Math.round(remainingBytes / bytesPerSec);

        final String line = String.format(
                "blob import [%d/%d] %s | %.1f%% | %,d rec | %.1f MiB/s | %.0f rec/s | ETA %s",
                Integer.valueOf(fileIndex),
                Integer.valueOf(fileCount),
                blobFile.getName(),
                Double.valueOf(Math.max(0.0d, Math.min(100.0d, percent))),
                Long.valueOf(entriesImported),
                Double.valueOf(bytesPerSec / (1024.0d * 1024.0d)),
                Double.valueOf(recPerSec),
                etaSec < 0 ? "--:--:--" : formatDuration(etaSec));

        if (showInplace) {
            System.out.print("\r" + line);
        }
        ConcurrentLog.info("HeapBlobImporter", line);
    }

    private static String formatDuration(final long seconds) {
        final long s = Math.max(0L, seconds);
        final long h = s / 3600L;
        final long m = (s % 3600L) / 60L;
        final long sec = s % 60L;
        return String.format("%02d:%02d:%02d", Long.valueOf(h), Long.valueOf(m), Long.valueOf(sec));
    }

    private static List<File> listBlobFiles(final File heapDir, final String prefix) {
        if (heapDir == null || !heapDir.isDirectory()) return new ArrayList<File>();
        final File[] files = heapDir.listFiles((dir, name) ->
            name.startsWith(prefix + ".") &&
            name.endsWith(".blob") &&
            name.length() >= prefix.length() + 1 + 17 + 5);

        final List<File> result = new ArrayList<File>();
        if (files != null) {
            result.addAll(Arrays.asList(files));
            result.sort(Comparator.comparing(File::getName));
        }
        return result;
    }

    private static void archiveImportedBlob(final File blobFile) {
        if (blobFile == null || !blobFile.exists()) return;
        final File archived = new File(blobFile.getParentFile(), blobFile.getName() + ".imported");
        if (archived.exists()) return;
        if (!blobFile.renameTo(archived)) {
            ConcurrentLog.warn("HeapBlobImporter", "could not rename imported blob "
                    + blobFile.getAbsolutePath() + " to " + archived.getName());
        }
    }
}
