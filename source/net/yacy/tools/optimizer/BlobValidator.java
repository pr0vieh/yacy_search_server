package net.yacy.tools.optimizer;

import java.io.*;
import java.util.List;

/** Validates optimized BLOB files for integrity */
public class BlobValidator {

    private final OptimizerConfig config;
    private final ProgressReporter progress;

    public BlobValidator(OptimizerConfig config, ProgressReporter progress) {
        this.config = config;
        this.progress = progress;
    }

    public boolean validateBlobs(List<File> blobs) throws IOException {
        progress.startPhase(5, "Validate BLOB Files");

        int valid = 0;
        long totalRecs = 0;
        long totalSize = 0;

        for (int i = 0; i < blobs.size(); i++) {
            File blob = blobs.get(i);
            progress.updateProgress((double) i / blobs.size(), "Validating: " + blob.getName());

            try {
                long size = blob.length();
                int recs = countRecords(blob);
                
                totalSize += size;
                totalRecs += recs;
                valid++;

                progress.success(String.format("  ✓ %s: %,d records, %s",
                    blob.getName(), recs, formatSize(size)));

            } catch (Exception e) {
                progress.error(String.format("  ✗ %s: %s", blob.getName(), e.getMessage()));
            }
        }

        progress.completePhase(String.format("Validated %d/%d files, %,d records, %s",
            valid, blobs.size(), totalRecs, formatSize(totalSize)));

        return valid == blobs.size();
    }

    private int countRecords(File blob) throws IOException {
        int count = 0;

        try (DataInputStream dis = new DataInputStream(new FileInputStream(blob))) {
            while (true) {
                try {
                    int len = dis.readInt();
                    if (len <= 0) break;
                    
                    dis.skipBytes(len);
                    count++;
                } catch (EOFException e) {
                    break;
                }
            }
        }

        return count;
    }

    private static String formatSize(long b) {
        if (b <= 0) return "0B";
        String[] u = {"B", "KB", "MB", "GB", "TB"};
        int d = (int) (Math.log10(b) / Math.log10(1024));
        return String.format("%.1f %s", b / Math.pow(1024, d), u[d]);
    }
}
