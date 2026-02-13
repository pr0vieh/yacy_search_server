package net.yacy.tools.optimizer;

import java.io.*;
import java.util.*;
import net.yacy.cora.date.GenericFormatter;

/** Optimizes merged BLOB by applying shrinkReferences and sorting */
public class BlobOptimizationPhase {

    private final OptimizerConfig config;
    private final ProgressReporter progress;
    private long outputTimeBase = 0;
    private int outputCounter = 0;

    public BlobOptimizationPhase(OptimizerConfig config, ProgressReporter progress) {
        this.config = config;
        this.progress = progress;
    }

    public List<File> optimizeBlobs(List<BlobScanner.BlobFileInfo> files) throws IOException {
        File tempDir = new File(config.getOutputDir(), "optimize_tmp");
        if (!tempDir.exists() && !tempDir.mkdirs()) {
            throw new IOException("Cannot create temp dir: " + tempDir.getAbsolutePath());
        }

        long maxMemory = Runtime.getRuntime().maxMemory();
        long chunkTargetBytes = getChunkTargetBytes();
        progress.info(String.format("Chunk target size: %s (30%% of max heap %s, min 64 MB)",
            formatBytes(chunkTargetBytes), formatBytes(maxMemory)));

        String blobPrefix = getBlobPrefix(files);
        List<File> chunks = writeSortedChunks(files, tempDir);
        if (chunks.isEmpty()) {
            return java.util.Collections.emptyList();
        }

        MergeResult result = mergeChunksToSplit(chunks, blobPrefix);
        cleanupChunks(chunks, tempDir);

        progress.completePhase(String.format("Optimized: %,d records into %,d blobs", result.recordsWritten, result.outputs.size()));
        return result.outputs;
    }

    private List<File> writeSortedChunks(List<BlobScanner.BlobFileInfo> files, File tempDir) throws IOException {
        List<File> chunks = new ArrayList<>();
        List<byte[]> records = new ArrayList<>();
        long bytesInChunk = 0;
        long totalBytes = files.stream().mapToLong(f -> f.size).sum();
        long processedBytes = 0;
        long lastUpdate = 0;
        final long updateInterval = 10L * 1024 * 1024; // Update every 10 MB
        final long chunkTargetBytes = getChunkTargetBytes();

        for (BlobScanner.BlobFileInfo bf : files) {
            try (DataInputStream dis = new DataInputStream(new BufferedInputStream(new FileInputStream(bf.file), 4 * 1024 * 1024))) {
                long localBytes = 0;
                while (localBytes < bf.size - 4) {
                    try {
                        int len = dis.readInt();
                        if (len <= 0 || len > 10_000_000) break;

                        byte[] data = new byte[len];
                        dis.readFully(data);
                        records.add(data);
                        bytesInChunk += 4L + len;
                        processedBytes += 4L + len;
                        localBytes += 4L + len;

                        if (processedBytes - lastUpdate >= updateInterval) {
                            double pct = 0.1 + 0.4 * processedBytes / totalBytes;
                            progress.updateProgress(pct, String.format("Chunking: %s / %s (%s)",
                                formatBytes(processedBytes), formatBytes(totalBytes), bf.file.getName()));
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

    private MergeResult mergeChunksToSplit(List<File> chunks, String blobPrefix) throws IOException {
        List<DataInputStream> inputs = new ArrayList<>();
        PriorityQueue<ChunkRecord> pq = new PriorityQueue<>(Comparator.comparingInt(a -> a.hash));
        long totalBytes = 0;
        for (File f : chunks) {
            totalBytes += f.length();
        }

        List<File> outputs = new ArrayList<>();
        DataOutputStream dos = null;
        long currentSize = 0;
        int outputIndex = 0;
        long maxFileSize = config.getMaxFileSize();

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

            while (!pq.isEmpty()) {
                ChunkRecord rec = pq.poll();

                if (!hasLast || rec.hash != lastHash) {
                    long recSize = 4L + rec.data.length;
                    if (dos == null || (currentSize + recSize > maxFileSize && hasLast)) {
                        if (dos != null) {
                            dos.close();
                        }
                        outputIndex++;
                        File out = nextOutputFile(blobPrefix);
                        dos = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(out), 4 * 1024 * 1024));
                        outputs.add(out);
                        currentSize = 0;
                    }

                    dos.writeInt(rec.data.length);
                    dos.write(rec.data);
                    recordsWritten++;
                    currentSize += recSize;
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

            if (dos != null) {
                dos.close();
            }

            return new MergeResult(outputs, recordsWritten);
        } finally {
            for (DataInputStream dis : inputs) {
                try {
                    dis.close();
                } catch (IOException ignored) {
                }
            }
            if (dos != null) {
                try {
                    dos.close();
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
        if (target < min) target = min;
        return target;
    }

    private File nextOutputFile(String prefix) {
        if (outputTimeBase == 0) {
            outputTimeBase = System.currentTimeMillis();
        }
        long ts = outputTimeBase + outputCounter;
        outputCounter++;
        String name = prefix + "." + GenericFormatter.SHORT_MILSEC_FORMATTER.format(new Date(ts)) + ".blob";
        return new File(config.getOutputDir(), name);
    }

    private String getBlobPrefix(List<BlobScanner.BlobFileInfo> files) {
        String pattern = config.getBlobPattern();
        String prefix = null;

        int star = pattern.indexOf('*');
        if (star >= 0) {
            prefix = pattern.substring(0, star);
        } else if (pattern.endsWith(".blob")) {
            prefix = pattern.substring(0, pattern.length() - 5);
        }

        if (prefix != null) {
            if (prefix.endsWith(".")) {
                prefix = prefix.substring(0, prefix.length() - 1);
            }
            if (!prefix.isEmpty()) {
                return prefix;
            }
        }

        if (!files.isEmpty()) {
            String name = files.get(0).file.getName();
            if (name.endsWith(".blob")) {
                String base = name.substring(0, name.length() - 5);
                int lastDot = base.lastIndexOf('.');
                if (lastDot > 0 && base.length() - lastDot - 1 == 17) {
                    return base.substring(0, lastDot);
                }
                int firstDot = base.indexOf('.');
                if (firstDot > 0) {
                    return base.substring(0, firstDot);
                }
            }
        }

        return "text.index";
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

    private static class MergeResult {
        final List<File> outputs;
        final int recordsWritten;

        MergeResult(List<File> outputs, int recordsWritten) {
            this.outputs = outputs;
            this.recordsWritten = recordsWritten;
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
