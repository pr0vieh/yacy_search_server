package net.yacy.tools.optimizer;

import java.io.*;
import java.util.*;

/** Splits defragmented mega-BLOB back to maxFileSize-compatible chunks */
public class BlobSplitter {

    private final OptimizerConfig config;
    private final ProgressReporter progress;

    public BlobSplitter(OptimizerConfig config, ProgressReporter progress) {
        this.config = config;
        this.progress = progress;
    }

    public List<File> splitBlob(File defragBlob) throws IOException {
        progress.startPhase(5, "Split & Validate");

        long maxSize = config.getMaxFileSize();
        long srcSize = defragBlob.length();
        List<File> outputs = new ArrayList<>();

        try (FileInputStream fis = new FileInputStream(defragBlob)) {
            byte[] buf = new byte[65536];
            int chunkNum = 0;
            long chunkSize = 0;
            FileOutputStream fos = null;
            long processed = 0;

            int n;
            while ((n = fis.read(buf)) > 0) {
                // Start new chunk if needed
                if (fos == null || chunkSize + n > maxSize) {
                    if (fos != null) {
                        fos.close();
                        chunkNum++;
                    }
                    
                    File chunk = new File(config.getOutputDir(), 
                        String.format("text.index%d.blob", chunkNum + 1));
                    fos = new FileOutputStream(chunk);
                    outputs.add(chunk);
                    chunkSize = 0;

                    progress.updateProgress(0.3 + 0.5 * processed / srcSize,
                        String.format("Writing chunk %d", chunkNum + 1));
                }

                fos.write(buf, 0, n);
                chunkSize += n;
                processed += n;

                if (processed % (50L * 1024 * 1024) == 0) {
                    progress.updateProgress(0.3 + 0.5 * processed / srcSize,
                        String.format("Split: %s / %s", formatSize(processed), formatSize(srcSize)));
                }
            }

            if (fos != null) {
                fos.close();
            }
        }

        // Validate chunks
        for (File f : outputs) {
            if (f.length() == 0) {
                throw new IOException("Empty chunk: " + f.getName());
            }
        }

        long totalOut = outputs.stream().mapToLong(File::length).sum();
        long saved = srcSize - totalOut;

        progress.completePhase(String.format("Split into %d chunks, saved %s (%.1f%%)",
            outputs.size(), formatSize(saved), 100.0 * saved / srcSize));

        return outputs;
    }

    private static String formatSize(long b) {
        if (b <= 0) return "0B";
        String[] u = {"B", "KB", "MB", "GB", "TB"};
        int d = (int) (Math.log10(b) / Math.log10(1024));
        return String.format("%.1f %s", b / Math.pow(1024, d), u[d]);
    }
}
