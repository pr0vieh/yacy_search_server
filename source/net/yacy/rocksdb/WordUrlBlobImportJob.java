package net.yacy.rocksdb;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import net.yacy.cora.util.ConcurrentLog;

public final class WordUrlBlobImportJob {

    private WordUrlBlobImportJob() {
    }

    public static long importBlobDirectory(final File heapDir,
                                           final String prefix,
                                           final int keyLength,
                                           final WordUrlRefStore store,
                                           final int batchSize) throws IOException {
        final List<File> blobs = listBlobFiles(heapDir, prefix);
        long refs = 0L;

        for (final File blob : blobs) {
            final long importedRefs = WordUrlBlobImporter.importBlobFile(blob, keyLength, store, batchSize);
            refs += importedRefs;
            archiveImportedBlob(blob);
        }

        ConcurrentLog.info("WordUrlBlobImportJob", "imported blobs=" + blobs.size() + " refs=" + refs);
        return refs;
    }

    private static List<File> listBlobFiles(final File heapDir, final String prefix) {
        if (heapDir == null || !heapDir.isDirectory()) return new ArrayList<File>();
        final File[] files = heapDir.listFiles((dir, name) -> name.startsWith(prefix + ".") && name.endsWith(".blob"));

        final List<File> result = new ArrayList<File>();
        if (files != null) {
            result.addAll(Arrays.asList(files));
            result.sort(Comparator.comparing(File::getName));
        }
        return result;
    }

    private static void archiveImportedBlob(final File blobFile) {
        if (blobFile == null || !blobFile.exists()) return;
        final File archived = new File(blobFile.getParentFile(), blobFile.getName() + ".imported");
        if (archived.exists()) return;
        if (!blobFile.renameTo(archived)) {
            ConcurrentLog.warn("WordUrlBlobImportJob", "could not rename imported blob "
                    + blobFile.getAbsolutePath() + " to " + archived.getName());
        }
    }
}
