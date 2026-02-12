package net.yacy.tools.optimizer;

import java.io.File;

/**
 * Configuration parser for BlobOptimizer command-line arguments
 */
public class OptimizerConfig {

    private File indexDir;
    private String blobPattern = "*.blob";
    private long maxFileSize = 2147483648L; // 2GB default
    private int threads;
    private File outputDir;

    public OptimizerConfig(String[] args) {
        this.threads = Runtime.getRuntime().availableProcessors();
        parseArgs(args);
    }

    private void parseArgs(String[] args) {
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            
            if ("--index-dir".equals(arg) && i + 1 < args.length) {
                this.indexDir = new File(args[++i]);
            } else if ("--blob-pattern".equals(arg) && i + 1 < args.length) {
                this.blobPattern = args[++i];
            } else if ("--max-file-size".equals(arg) && i + 1 < args.length) {
                try {
                    this.maxFileSize = Long.parseLong(args[++i]);
                } catch (NumberFormatException e) {
                    System.err.println("Invalid max-file-size: " + args[i]);
                }
            } else if ("--threads".equals(arg) && i + 1 < args.length) {
                try {
                    this.threads = Integer.parseInt(args[++i]);
                } catch (NumberFormatException e) {
                    System.err.println("Invalid threads: " + args[i]);
                }
            } else if ("--output-dir".equals(arg) && i + 1 < args.length) {
                this.outputDir = new File(args[++i]);
            }
        }
        
        // Set output dir to index dir if not specified
        if (this.outputDir == null && this.indexDir != null) {
            this.outputDir = this.indexDir;
        }
    }

    public boolean validate() {
        if (indexDir == null) {
            System.err.println("ERROR: --index-dir is required");
            return false;
        }
        
        if (!indexDir.exists()) {
            System.err.println("ERROR: Index directory does not exist: " + indexDir.getAbsolutePath());
            return false;
        }
        
        if (!indexDir.isDirectory()) {
            System.err.println("ERROR: Index path is not a directory: " + indexDir.getAbsolutePath());
            return false;
        }
        
        if (maxFileSize < 1024 * 1024) {
            System.err.println("ERROR: max-file-size must be at least 1MB");
            return false;
        }
        
        if (threads < 1) {
            System.err.println("ERROR: threads must be at least 1");
            return false;
        }
        
        // Create output dir if it doesn't exist
        if (!outputDir.exists()) {
            if (!outputDir.mkdirs()) {
                System.err.println("ERROR: Cannot create output directory: " + outputDir.getAbsolutePath());
                return false;
            }
        }
        
        return true;
    }

    // Getters
    public File getIndexDir() {
        return indexDir;
    }

    public String getBlobPattern() {
        return blobPattern;
    }

    public long getMaxFileSize() {
        return maxFileSize;
    }

    public int getThreads() {
        return threads;
    }

    public File getOutputDir() {
        return outputDir;
    }

    @Override
    public String toString() {
        return "OptimizerConfig{" +
                "indexDir=" + indexDir +
                ", blobPattern='" + blobPattern + '\'' +
                ", maxFileSize=" + maxFileSize +
                ", threads=" + threads +
                ", outputDir=" + outputDir +
                '}';
    }
}
