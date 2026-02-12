package net.yacy.tools.optimizer;

import java.io.*;

/** Defragments BLOB by removing gaps from deleted records */
public class BlobDefragmenter {

    private final OptimizerConfig config;
    private final ProgressReporter progress;

    public BlobDefragmenter(OptimizerConfig config, ProgressReporter progress) {
        this.config = config;
        this.progress = progress;
    }

    public File defragment(File optimizedBlob) throws IOException {
        progress.startPhase(4, "Defragment BLOB");

        byte[] data = readAllBytes(optimizedBlob);
        byte[] defrag = defragment(data);

        progress.updateProgress(0.8, String.format("Removed %,d bytes of gaps", data.length - defrag.length));

        File out = new File(config.getOutputDir(), "megablob.defragmented");
        try (FileOutputStream fos = new FileOutputStream(out)) {
            fos.write(defrag);
        }

        progress.completePhase(String.format("Defragmented: %s -> %s (%.1f%% freed)",
            formatSize(data.length), formatSize(defrag.length),
            100.0 * (data.length - defrag.length) / data.length));

        return out;
    }

    private byte[] readAllBytes(File f) throws IOException {
        try (FileInputStream fis = new FileInputStream(f)) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = fis.read(buf)) > 0) {
                baos.write(buf, 0, n);
            }
            return baos.toByteArray();
        }
    }

    private byte[] defragment(byte[] data) {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        int pos = 0;

        while (pos < data.length - 4) {
            int len = bytesToInt(data, pos);
            if (len <= 0 || pos + 4 + len > data.length) break;

            result.write(data, pos, 4 + len);
            pos += 4 + len;
        }

        return result.toByteArray();
    }

    private int bytesToInt(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16) |
               ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    private static String formatSize(long b) {
        if (b <= 0) return "0B";
        String[] u = {"B", "KB", "MB", "GB", "TB"};
        int d = (int) (Math.log10(b) / Math.log10(1024));
        return String.format("%.1f %s", b / Math.pow(1024, d), u[d]);
    }
}
