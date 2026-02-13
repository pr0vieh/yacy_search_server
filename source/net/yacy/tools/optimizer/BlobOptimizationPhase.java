package net.yacy.tools.optimizer;

import java.io.*;
import java.util.*;

/** Optimizes merged BLOB by applying shrinkReferences and sorting */
public class BlobOptimizationPhase {

    private final OptimizerConfig config;
    private final ProgressReporter progress;

    public BlobOptimizationPhase(OptimizerConfig config, ProgressReporter progress) {
        this.config = config;
        this.progress = progress;
    }

    public File optimizeBlob(File mergedBlob) throws IOException {
        progress.startPhase(3, "Optimize & Deduplicate");

        File optimized = new File(config.getOutputDir(), "megablob.optimized");
        File tempDir = new File(config.getOutputDir(), "optimize_tmp");
        if (!tempDir.exists() && !tempDir.mkdirs()) {
            throw new IOException("Cannot create temp dir: " + tempDir.getAbsolutePath());
        }

        List<File> chunks = writeSortedChunks(mergedBlob, tempDir);
        if (chunks.isEmpty()) {
            try (DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(optimized), 4 * 1024 * 1024))) {
                // write empty optimized blob
            }
            progress.completePhase("Optimized: 0 records");
            return optimized;
        }

        int recordsWritten = mergeChunks(chunks, optimized);
        cleanupChunks(chunks, tempDir);

        progress.completePhase(String.format("Optimized: %,d records", recordsWritten));
        return optimized;
    }

    private List<File> writeSortedChunks(File file, File tempDir) throws IOException {
        List<File> chunks = new ArrayList<>();
        List<byte[]> records = new ArrayList<>();
        long bytesInChunk = 0;
        long totalBytes = file.length();
        long processedBytes = 0;
        long lastUpdate = 0;
        final long updateInterval = 10L * 1024 * 1024; // Update every 10 MB
        final long chunkTargetBytes = getChunkTargetBytes();

        try (DataInputStream dis = new DataInputStream(new BufferedInputStream(new FileInputStream(file), 4 * 1024 * 1024))) {
            while (processedBytes < totalBytes - 4) {
                try {
                    int len = dis.readInt();
                    if (len <= 0 || len > 10_000_000) break;

                    byte[] data = new byte[len];
                    dis.readFully(data);
                    records.add(data);
                    bytesInChunk += 4L + len;
                    processedBytes += 4L + len;

                    if (processedBytes - lastUpdate >= updateInterval) {
                        double pct = 0.1 + 0.4 * processedBytes / totalBytes;
                        progress.updateProgress(pct, String.format("Chunking: %s / %s", formatBytes(processedBytes), formatBytes(totalBytes)));
                        lastUpdate = processedBytes;
                    }

                    if (bytesInChunk >= chunkTargetBytes) {
                        File chunk = writeChunk(records, tempDir, chunks.size());
                        chunks.add(chunk);
                        records.clear();
                        bytesInChunk = 0;
                    }
                } catch (EOFException e) {
                    break;
                }
            }
        }

        if (!records.isEmpty()) {
            File chunk = writeChunk(records, tempDir, chunks.size());
            chunks.add(chunk);
        }

        return chunks;
    }

    private File writeChunk(List<byte[]> records, File tempDir, int index) throws IOException {
        Collections.sort(records, (a, b) -> Integer.compare(hashCode(a), hashCode(b)));

        File chunk = new File(tempDir, String.format("chunk_%03d.blob", index + 1));
        try (DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(chunk), 4 * 1024 * 1024))) {
            boolean hasLast = false;
            int lastHash = 0;
            for (byte[] r : records) {
                int h = hashCode(r);
                if (hasLast && h == lastHash) {
                    continue;
                }
                dos.writeInt(r.length);
                dos.write(r);
                lastHash = h;
                hasLast = true;
            }
        }
        return chunk;
    }

    private int mergeChunks(List<File> chunks, File out) throws IOException {
        List<DataInputStream> inputs = new ArrayList<>();
        PriorityQueue<ChunkRecord> pq = new PriorityQueue<>(Comparator.comparingInt(a -> a.hash));
        long totalBytes = 0;
        for (File f : chunks) {
            totalBytes += f.length();
        }

        try {
            for (int i = 0; i < chunks.size(); i++) {
                DataInputStream dis = new DataInputStream(new BufferedInputStream(new FileInputStream(chunks.get(i)), 4 * 1024 * 1024));
                inputs.add(dis);
                byte[] data = readNextRecord(dis);
                if (data != null) {
                    pq.add(new ChunkRecord(hashCode(data), data, i));
                }
            }

            long processedBytes = 0;
            long lastUpdate = 0;
            final long updateInterval = 10L * 1024 * 1024; // Update every 10 MB
            int recordsWritten = 0;
            boolean hasLast = false;
            int lastHash = 0;

            try (DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(out), 4 * 1024 * 1024))) {
                while (!pq.isEmpty()) {
                    ChunkRecord rec = pq.poll();

                    if (!hasLast || rec.hash != lastHash) {
                        dos.writeInt(rec.data.length);
                        dos.write(rec.data);
                        recordsWritten++;
                        lastHash = rec.hash;
                        hasLast = true;
                    }

                    processedBytes += 4L + rec.data.length;
                    if (processedBytes - lastUpdate >= updateInterval && totalBytes > 0) {
                        double pct = 0.6 + 0.3 * processedBytes / totalBytes;
                        progress.updateProgress(pct, String.format("Merging chunks: %s / %s", formatBytes(processedBytes), formatBytes(totalBytes)));
                        lastUpdate = processedBytes;
                    }

                    byte[] next = readNextRecord(inputs.get(rec.sourceIndex));
                    if (next != null) {
                        pq.add(new ChunkRecord(hashCode(next), next, rec.sourceIndex));
                    }
                }
            }

            return recordsWritten;
        } finally {
            for (DataInputStream dis : inputs) {
                try {
                    dis.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private byte[] readNextRecord(DataInputStream dis) throws IOException {
        try {
            int len = dis.readInt();
            if (len <= 0 || len > 10_000_000) {
                return null;
            }
            byte[] data = new byte[len];
            dis.readFully(data);
            return data;
        } catch (EOFException e) {
            return null;
        }
    }

    private long getChunkTargetBytes() {
        long maxMemory = Runtime.getRuntime().maxMemory();
        long target = (long) (maxMemory * 0.30);
        long min = 64L * 1024 * 1024;
        long max = 512L * 1024 * 1024;
        if (target < min) target = min;
        if (target > max) target = max;
        return target;
    }

    private void cleanupChunks(List<File> chunks, File tempDir) {
        for (File f : chunks) {
            if (f.exists() && !f.delete()) {
                progress.info("Could not delete temp chunk: " + f.getName());
            }
        }
        if (tempDir.isDirectory()) {
            File[] remaining = tempDir.listFiles();
            if (remaining != null && remaining.length == 0) {
                if (!tempDir.delete()) {
                    progress.info("Could not delete temp dir: " + tempDir.getName());
                }
            }
        }
    }

    private String formatBytes(long bytes) {
        if (bytes <= 0) return "0 B";
        String[] units = new String[]{"B", "KB", "MB", "GB", "TB"};
        int digitGroups = (int) (Math.log10(bytes) / Math.log10(1024));
        return String.format("%.1f %s", bytes / Math.pow(1024, digitGroups), units[digitGroups]);
    }

    private static class ChunkRecord {
        final int hash;
        final byte[] data;
        final int sourceIndex;

        ChunkRecord(int hash, byte[] data, int sourceIndex) {
            this.hash = hash;
            this.data = data;
            this.sourceIndex = sourceIndex;
        }
    }

    private int hashCode(byte[] data) {
        int h = 0;
        for (byte b : data) {
            h = h * 31 + (b & 0xFF);
        }
        return h;
    }
}
