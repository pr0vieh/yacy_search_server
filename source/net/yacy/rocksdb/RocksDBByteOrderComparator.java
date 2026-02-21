// RocksDBByteOrderComparator.java
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

import java.nio.ByteBuffer;

import org.rocksdb.AbstractComparator;
import org.rocksdb.ComparatorOptions;

import net.yacy.cora.order.ByteOrder;

/**
 * Comparator that preserves YaCy ByteOrder semantics in RocksDB.
 */
public final class RocksDBByteOrderComparator extends AbstractComparator {

    private final ByteOrder ordering;

    public RocksDBByteOrderComparator(final ByteOrder ordering, final ComparatorOptions comparatorOptions) {
        super(comparatorOptions);
        this.ordering = ordering;
    }

    @Override
    public String name() {
        final String orderName = this.ordering == null ? "default" : this.ordering.getClass().getSimpleName();
        return "yacy-" + orderName;
    }

    @Override
    public int compare(final ByteBuffer a, final ByteBuffer b) {
        final byte[] left = toByteArray(a);
        final byte[] right = toByteArray(b);
        if (this.ordering != null) {
            return this.ordering.compare(left, right);
        }
        return compareUnsigned(left, right);
    }

    private static byte[] toByteArray(final ByteBuffer buffer) {
        final ByteBuffer duplicate = buffer.duplicate();
        final byte[] bytes = new byte[duplicate.remaining()];
        duplicate.get(bytes);
        return bytes;
    }

    private static int compareUnsigned(final byte[] left, final byte[] right) {
        final int length = Math.min(left.length, right.length);
        for (int i = 0; i < length; i++) {
            final int li = left[i] & 0xFF;
            final int ri = right[i] & 0xFF;
            if (li != ri) return li - ri;
        }
        return left.length - right.length;
    }
}
