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

            // Check disk space before starting
            checkDiskSpace(totalSize);

            // Phase 2: Optimize & Deduplicate (chunked, disk-backed)
            progress.startPhase(2, "Optimize & Deduplicate (chunked)...");
            BlobOptimizationPhase optimizer = new BlobOptimizationPhase(config, progress);
            java.util.List<File> finalBlobs = optimizer.optimizeBlobs(blobFiles);
            progress.completePhase("Optimized blobs created");

            // Phase 3: Validation
            progress.startPhase(3, "Validate BLOB Files...");
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

    private void checkDiskSpace(long requiredSpace) throws IOException {
        File outputDir = config.getOutputDir();
        long freeSpace = outputDir.getUsableSpace();
        
        // Need: current size * 2 (working space) + 5% buffer
        long neededSpace = (long) (requiredSpace * 2.05);
        
        System.out.println();
        System.out.println("Disk Space Check:");
        System.out.println("  Input size:     " + formatFileSize(requiredSpace));
        System.out.println("  Required space: " + formatFileSize(neededSpace) + " (2x input + 5% buffer)");
        System.out.println("  Available:      " + formatFileSize(freeSpace));
        
        if (freeSpace < neededSpace) {
            long missing = neededSpace - freeSpace;
            System.err.println();
            System.err.println("✗ ERROR: Insufficient disk space!");
            System.err.println("  Missing: " + formatFileSize(missing));
            System.err.println("  Free up space on: " + outputDir.getAbsolutePath());
            System.err.println();
            throw new IOException("Insufficient disk space: need " + formatFileSize(neededSpace) + ", have " + formatFileSize(freeSpace));
        }
        
        System.out.println("  Status:         ✓ OK (" + formatFileSize(freeSpace - neededSpace) + " buffer)");
        System.out.println();
    }

    private void printSummary(long original, long final_size, long saved, double savingPercent, long duration, int outputFiles) {
        double compressionRatio = (double) original / final_size;
        
        System.out.println();
        System.out.println("=".repeat(65));
        System.out.println("  OPTIMIZATION SUMMARY");
        System.out.println("=".repeat(65));
        System.out.println();
        System.out.println("  BEFORE:");
        System.out.println("    Total size:        " + String.format("%15s", formatFileSize(original)));
        System.out.println();
        System.out.println("  AFTER:");
        System.out.println("    Total size:        " + String.format("%15s", formatFileSize(final_size)));
        System.out.println("    Output files:      " + String.format("%15d BLOBs", outputFiles));
        System.out.println();
        System.out.println("  SAVINGS:");
        System.out.println("    Space freed:       " + String.format("%15s", formatFileSize(saved)));
        System.out.println("    Reduction:         " + String.format("%14.1f%%", savingPercent));
        System.out.println("    Compression ratio: " + String.format("%14.2f:1", compressionRatio));
        System.out.println();
        System.out.println("  PERFORMANCE:");
        System.out.println("    Time elapsed:      " + String.format("%15s", formatDuration(duration)));
        System.out.println("    Throughput:        " + String.format("%15s", formatFileSize(original / Math.max(1, duration)) + "/s"));
        System.out.println();
        System.out.println("  STATUS:            ✓ READY");
        System.out.println();
        System.out.println("  Output location: " + config.getOutputDir().getAbsolutePath());
        System.out.println();
        System.out.println("=".repeat(65));
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
