// RocksDBStore.java
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

import net.yacy.cora.order.CloneableIterator;

/**
 * Minimal storage interface for RocksDB-backed blob stores.
 */
public interface RocksDBStore {

    byte[] get(byte[] key);

    void put(byte[] key, byte[] value, long version);

    void putOverwrite(byte[] key, byte[] value, long version);

    void putImportFast(byte[] key, byte[] value, long version);

    void remove(byte[] key);

    long size();

    CloneableIterator<byte[]> keyIterator(boolean ascending);

    void close();
}
