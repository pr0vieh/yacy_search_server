package net.yacy.tools.optimizer;

import java.io.*;
import java.util.List;

/** Merges multiple BLOB files into a single mega-BLOB */
public class BlobMerger {

    private final OptimizerConfig config;
    private final ProgressReporter progress;

    public BlobMerger(OptimizerConfig config, ProgressReporter progress) {
        this.config = config;
        this.progress = progress;
    }

    public File mergeBlobs(List<BlobScanner.BlobFileInfo> files) throws IOException {
        progress.startPhase(2, "Merge BLOB Files");

        File megaBlob = new File(config.getOutputDir(), "megablob.temp");
        long total = files.stream().mapToLong(f -> f.size).sum();
        long processed = 0;
        long lastUpdate = 0;
        final long updateInterval = 10L * 1024 * 1024; // Update every 10 MB

        try (FileOutputStream out = new FileOutputStream(megaBlob)) {
            byte[] buf = new byte[4 * 1024 * 1024]; // 4MB buffer for faster I/O

            for (int i = 0; i < files.size(); i++) {
                BlobScanner.BlobFileInfo bf = files.get(i);
                
                try (FileInputStream in = new FileInputStream(bf.file)) {
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        processed += n;
                        
                        // Update every 10 MB or when we've read enough data
                        if (processed - lastUpdate >= updateInterval) {
                            double pct = 100.0 * processed / total;
                            progress.updateProgress(pct / 100, String.format("Merged %d/%d - %s", i + 1, files.size(), bf.file.getName()));
                            lastUpdate = processed;
                        }
                    }
                }
                
                // Update progress after each file to show current file number
                double pct = 100.0 * processed / total;
                progress.updateProgress(pct / 100, String.format("Merged %d/%d - %s (✓)", i + 1, files.size(), bf.file.getName()));
                lastUpdate = processed;
            }
        }

        progress.completePhase("Merged to: " + megaBlob.getName());
        return megaBlob;
    }
}
