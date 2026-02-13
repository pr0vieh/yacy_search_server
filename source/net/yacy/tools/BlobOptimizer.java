package net.yacy.tools;

import net.yacy.tools.optimizer.*;
import java.io.File;
import java.io.IOException;

/**
 * YaCy BLOB Optimizer & Defragmenter
 * Standalone tool to optimize (merge, sort, defragment) BLOB files without running YaCy
 * 
 * Usage:
 *   java -jar BlobOptimizer.jar \
 *     --index-dir /path/to/DATA/INDEX/freeworld/SEGMENTS/default \
 *     --blob-pattern "text.index*.blob" \
 *     --max-file-size 2147483648 \
 *     --threads 4 \
 *     --output-dir ./optimized_blobs
 */
public class BlobOptimizer {

    private static final String VERSION = "1.0";
    private OptimizerConfig config;
    private ProgressReporter progress;

    public BlobOptimizer(String[] args) {
        this.config = new OptimizerConfig(args);
        this.progress = new ProgressReporter();
    }

    public static void main(String[] args) {
        try {
            BlobOptimizer optimizer = new BlobOptimizer(args);
            optimizer.run();
        } catch (Exception e) {
            System.err.println("ERROR: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    public void run() throws IOException {
        printHeader();
        
        // Validate configuration
        if (!config.validate()) {
            printUsage();
            System.exit(1);
        }

        long startTime = System.currentTimeMillis();

        try {
            // Phase 1: Scan BLOB files
            progress.startPhase(1, "Scanning BLOB Files...");
            BlobScanner scanner = new BlobScanner(config, progress);
            java.util.List<BlobScanner.BlobFileInfo> blobFiles = scanner.scanBlobFiles();
            long totalSize = blobFiles.stream().mapToLong(f -> f.size).sum();
            progress.completePhase("Found: " + blobFiles.size() + " BLOB files (" + formatFileSize(totalSize) + " total)");

            if (blobFiles.isEmpty()) {
                System.out.println("No BLOB files found matching pattern: " + config.getBlobPattern());
                return;
            }

            // Phase 2: Merge BLOBs
            progress.startPhase(2, "Merging BLOBs...");
            BlobMerger merger = new BlobMerger(config, progress);
            File mergedBlob = merger.mergeBlobs(blobFiles);
            progress.completePhase("Merged into: " + mergedBlob.getName());

            // Phase 3: Optimize & Sort (shrinkReferences + sort)
            progress.startPhase(3, "Optimizing & Sorting...");
            BlobOptimizationPhase optimizer = new BlobOptimizationPhase(config, progress);
            File optimizedBlob = optimizer.optimizeBlob(mergedBlob);
            progress.completePhase("Optimized blob created");

            // Phase 4: Defragmentation
            progress.startPhase(4, "Defragmentation...");
            BlobDefragmenter defragmenter = new BlobDefragmenter(config, progress);
            File defragmentedBlob = defragmenter.defragment(optimizedBlob);
            progress.completePhase("Defragmentation complete");

            // Phase 5: Splitting & Validation
            progress.startPhase(5, "Splitting & Validation...");
            BlobSplitter splitter = new BlobSplitter(config, progress);
            java.util.List<File> finalBlobs = splitter.splitBlob(defragmentedBlob);
            
            BlobValidator validator = new BlobValidator(config, progress);
            boolean valid = validator.validateBlobs(finalBlobs);
            
            if (valid) {
                progress.completePhase("✓ All blobs validated successfully");
            } else {
                throw new IOException("Validation failed - output blobs are corrupted");
            }

            // Print summary
            long endTime = System.currentTimeMillis();
            long duration = (endTime - startTime) / 1000;
            long finalSize = finalBlobs.stream().mapToLong(File::length).sum();
            long saved = totalSize - finalSize;
            double savingPercent = (saved / (double) totalSize) * 100;

            printSummary(totalSize, finalSize, saved, savingPercent, duration, finalBlobs.size());

        } catch (Exception e) {
            progress.error("Optimization failed!");
            System.err.println();
            System.err.println("Exception details:");
            System.err.println("  " + e.getClass().getName() + ": " + e.getMessage());
            if (e.getCause() != null) {
                System.err.println("  Caused by: " + e.getCause().getMessage());
            }
            System.err.println();
            System.err.println("Stack trace:");
            e.printStackTrace(System.err);
            System.err.println();
            throw e;
        }
    }

    private void printHeader() {
        System.out.println();
        System.out.println("=================================================================");
        System.out.println("      YaCy BLOB Optimizer & Defragmenter v" + VERSION);
        System.out.println("=================================================================");
        System.out.println();
    }

    private void printUsage() {
        System.out.println("Usage:");
        System.out.println("  java -jar BlobOptimizer.jar \\");
        System.out.println("    --index-dir /path/to/INDEX/freeworld/SEGMENTS/default \\");
        System.out.println("    [--blob-pattern \"text.index*.blob\"] \\");
        System.out.println("    [--max-file-size 2147483648] \\");
        System.out.println("    [--threads 4] \\");
        System.out.println("    [--output-dir ./optimized_blobs]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --index-dir DIR          (required) Where BLOB files are located");
        System.out.println("  --blob-pattern PATTERN   (optional) BLOB filename pattern (default: *.blob)");
        System.out.println("  --max-file-size SIZE     (optional) Max output file size in bytes (default: 2GB)");
        System.out.println("  --threads N              (optional) Number of parallel threads (default: CPU cores)");
        System.out.println("  --output-dir DIR         (optional) Where to write optimized BLOBs (default: index-dir)");
    }

    private void printSummary(long original, long final_size, long saved, double savingPercent, long duration, int outputFiles) {
        System.out.println();
        System.out.println("SUMMARY:");
        System.out.println("--------");
        System.out.println("Original size:  " + formatFileSize(original));
        System.out.println("Final size:     " + formatFileSize(final_size) + " (saved: " + formatFileSize(saved) + " / " + String.format("%.1f", savingPercent) + "%)");
        System.out.println("Optimization:   " + String.format("%.1f", (saved / (double) original) * 100) + "%");
        System.out.println("Time elapsed:   " + formatDuration(duration));
        System.out.println("Output files:   " + outputFiles + " BLOBs");
        System.out.println("Status:         ✓ READY (copy to your YaCy data directory)");
        System.out.println();
        System.out.println("Output location: " + config.getOutputDir().getAbsolutePath());
        System.out.println();
    }

    private String formatFileSize(long bytes) {
        if (bytes <= 0) return "0 B";
        final String[] units = new String[]{"B", "KB", "MB", "GB", "TB"};
        int digitGroups = (int) (Math.log10(bytes) / Math.log10(1024));
        return String.format("%.1f %s", bytes / Math.pow(1024, digitGroups), units[digitGroups]);
    }

    private String formatDuration(long seconds) {
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        if (hours > 0) {
            return String.format("%dh %dm %ds", hours, minutes, secs);
        } else if (minutes > 0) {
            return String.format("%dm %ds", minutes, secs);
        } else {
            return String.format("%ds", secs);
        }
    }
}
