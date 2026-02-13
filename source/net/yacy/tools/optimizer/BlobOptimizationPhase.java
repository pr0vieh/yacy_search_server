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

        // Read records
        List<byte[]> records = readRecords(mergedBlob);
        int origCount = records.size();
        
        progress.updateProgress(0.3, String.format("Read %,d records", origCount));

        // Apply shrinkReferences (dedup)
        records = dedup(records);
        int dedupCount = records.size();
        
        progress.updateProgress(0.6, String.format("Deduplicated: -%d (%.1f%% removed)", 
            origCount - dedupCount, 100.0 * (origCount - dedupCount) / origCount));

        // Sort records
        Collections.sort(records, (a, b) -> {
            int ha = hashCode(a);
            int hb = hashCode(b);
            return Integer.compare(ha, hb);
        });

        // Write optimized blob
        File optimized = new File(config.getOutputDir(), "megablob.optimized");
        writeRecords(optimized, records);

        progress.completePhase(String.format("Optimized: %,d records", records.size()));
        return optimized;
    }

    private List<byte[]> readRecords(File file) throws IOException {
        List<byte[]> records = new ArrayList<>();
        int lastUpdateCount = 0;
        final int updateInterval = 50_000; // Update every 50k records

        try (DataInputStream dis = new DataInputStream(new BufferedInputStream(new FileInputStream(file), 4 * 1024 * 1024))) {
            long pos = 0;
            long size = file.length();

            while (pos < size - 4) {
                try {
                    int len = dis.readInt();
                    if (len <= 0 || len > 10_000_000) break;

                    byte[] data = new byte[len];
                    dis.readFully(data);
                    records.add(data);
                    pos += 4 + len;

                    if (records.size() - lastUpdateCount >= updateInterval) {
                        progress.updateProgress(0.1 + 0.2 * pos / size, 
                            String.format("Reading: %,d records", records.size()));
                        lastUpdateCount = records.size();
                    }
                } catch (EOFException e) {
                    break;
                }
            }
            // Final update at the end
            progress.updateProgress(0.3, String.format("Reading: %,d records", records.size()));
        }

        return records;
    }

    private List<byte[]> dedup(List<byte[]> records) {
        Map<Integer, byte[]> map = new LinkedHashMap<>();
        for (byte[] r : records) {
            int h = hashCode(r);
            map.put(h, r); // Keep last occurrence
        }
        return new ArrayList<>(map.values());
    }

    private void writeRecords(File out, List<byte[]> records) throws IOException {
        int lastUpdateCount = 0;
        final int updateInterval = 50_000; // Update every 50k records
        
        try (DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(out), 4 * 1024 * 1024))) {
            for (int i = 0; i < records.size(); i++) {
                byte[] r = records.get(i);
                dos.writeInt(r.length);
                dos.write(r);

                if ((i + 1) - lastUpdateCount >= updateInterval) {
                    progress.updateProgress(0.6 + 0.2 * (i + 1) / records.size(), 
                        String.format("Writing: %,d/%,d", i + 1, records.size()));
                    lastUpdateCount = i + 1;
                }
            }
            // Final update at the end
            progress.updateProgress(0.8, String.format("Writing: %,d/%,d", records.size(), records.size()));
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
