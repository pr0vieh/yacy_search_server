package net.yacy.tools.optimizer;

import java.io.File;
import java.io.IOException;
import java.util.*;

/** Scans for BLOB files matching pattern in index directory */
public class BlobScanner {

    public static class BlobFileInfo {
        public final File file;
        public final long size;

        public BlobFileInfo(File file) {
            this.file = file;
            this.size = file.length();
        }
    }

    private final OptimizerConfig config;
    private final ProgressReporter progress;

    public BlobScanner(OptimizerConfig config, ProgressReporter progress) {
        this.config = config;
        this.progress = progress;
    }

    public List<BlobFileInfo> scanBlobFiles() throws IOException {
        File indexDir = config.getIndexDir();
        String pattern = config.getBlobPattern();
        
        progress.startPhase(1, "Scan BLOB Files");

        File[] files = indexDir.listFiles((d, n) -> n.matches(globToRegex(pattern)));
        if (files == null || files.length == 0) {
            throw new IOException("No BLOB files found");
        }

        List<BlobFileInfo> result = new ArrayList<>();
        for (File f : files) {
            if (f.isFile()) {
                result.add(new BlobFileInfo(f));
            }
        }

        for (BlobFileInfo b : result) {
            progress.updateProgress(0.1, "Found: " + b.file.getName());
        }

        long total = result.stream().mapToLong(b -> b.size).sum();
        progress.completePhase(String.format("Found %d files (%s)", result.size(), formatSize(total)));
        
        return result;
    }

    private String globToRegex(String s) {
        StringBuilder r = new StringBuilder("^");
        for (char c : s.toCharArray()) {
            r.append(c == '*' ? ".*" : c == '?' ? "." : c == '.' ? "\\." : c);
        }
        return r.append("$").toString();
    }

    private static String formatSize(long b) {
        if (b <= 0) return "0B";
        String[] u = {"B", "KB", "MB", "GB", "TB"};
        int d = (int) (Math.log10(b) / Math.log10(1024));
        return String.format("%.1f %s", b / Math.pow(1024, d), u[d]);
    }
}
