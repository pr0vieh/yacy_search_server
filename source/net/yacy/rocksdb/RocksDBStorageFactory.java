// RocksDBStorageFactory.java
// (C) 2026 by YaCy Contributors
// first published 21.02.2026 on http://yacy.net
//
// LICENSE
//
// This program is free software; you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation; either version 2 of the License, or
// (at your option) any later version.

package net.yacy.rocksdb;

import java.io.File;
import java.io.IOException;
import java.util.Properties;

import net.yacy.cora.order.ByteOrder;
import net.yacy.cora.util.ConcurrentLog;
import net.yacy.kelondro.index.Row;
import net.yacy.kelondro.rwi.IndexCellBackend;
import net.yacy.kelondro.rwi.IODispatcher;
import net.yacy.kelondro.rwi.Reference;
import net.yacy.kelondro.rwi.ReferenceFactory;
import net.yacy.rocksdb.migration.HeapBlobImporter;
import net.yacy.search.SwitchboardConstants;

/**
 * Factory for RocksDB storage selection based on configuration.
 */
public final class RocksDBStorageFactory {

    private RocksDBStorageFactory() {
        // Utility class
    }

    public static boolean isEnabled(final Properties config) {
        return getBoolean(config, SwitchboardConstants.INDEX_ROCKSDB_ENABLED, false);
    }

    public static boolean isAutoImportEnabled(final Properties config) {
        return getBoolean(config, SwitchboardConstants.INDEX_ROCKSDB_IMPORT_AUTO, false);
    }

    public static RocksDBBlobStore createBlobStore(final Properties config,
                                                   final File dbPath,
                                                   final Row mergeRow,
                                                   final ByteOrder ordering) {
        if (!isEnabled(config)) return null;
        if (dbPath == null) return null;
        return new RocksDBBlobStore(dbPath, mergeRow, ordering);
    }

    public static <ReferenceType extends Reference> IndexCellBackend<ReferenceType> createRwiCell(
            final Properties config,
            final File cellPath,
            final String prefix,
            final ReferenceFactory<ReferenceType> factory,
            final ByteOrder termOrder,
            final int termSize,
            final int maxRamEntries,
            final long targetFileSize,
            final long maxFileSize,
            final int writeBufferSize,
            final IODispatcher merger) {
        if (!isEnabled(config)) return null;
        if (cellPath == null || factory == null || termOrder == null) return null;
        final RocksDBIndexCell<ReferenceType> cell = new RocksDBIndexCell<ReferenceType>(
            cellPath,
            prefix,
            factory,
            termOrder,
            termSize,
            maxRamEntries,
            targetFileSize,
            maxFileSize,
            writeBufferSize,
            merger);
        tryAutoImportRwiBlobs(config, cellPath, prefix, termSize, factory, termOrder);
        return cell;
    }

    private static <ReferenceType extends Reference> void tryAutoImportRwiBlobs(
            final Properties config,
            final File cellPath,
            final String prefix,
            final int termSize,
            final ReferenceFactory<ReferenceType> factory,
            final ByteOrder termOrder) {
        if (!isAutoImportEnabled(config)) return;
        if (cellPath == null || factory == null || termOrder == null) return;

        final String safePrefix = (prefix == null || prefix.isEmpty()) ? "rwi" : prefix;
        final File dbPath = new File(cellPath, safePrefix + ".rocksdb");
        RocksDBBlobStore store = null;
        try {
            store = new RocksDBBlobStore(dbPath, factory.getRow(), termOrder);
            final long total = HeapBlobImporter.importBlobDirectory(cellPath, safePrefix, termSize, store);
            if (total > 0) {
                ConcurrentLog.info("RocksDBStorageFactory", "auto-imported " + total
                        + " entries from heap blobs (" + safePrefix + ") in " + cellPath.getAbsolutePath());
            }
        } catch (final IOException e) {
            ConcurrentLog.warn("RocksDBStorageFactory", "auto-import failed for " + cellPath.getAbsolutePath()
                    + ": " + e.getMessage());
        } finally {
            if (store != null) {
                store.close();
            }
        }
    }

    private static boolean getBoolean(final Properties config, final String key, final boolean defaultValue) {
        if (config == null || key == null) return defaultValue;
        final String value = config.getProperty(key);
        if (value == null) return defaultValue;
        return "true".equalsIgnoreCase(value)
            || "1".equals(value)
            || "yes".equalsIgnoreCase(value);
    }
}
