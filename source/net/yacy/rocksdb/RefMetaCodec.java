package net.yacy.rocksdb;

import java.util.ArrayList;
import java.util.List;

public final class RefMetaCodec {

    public static final int HEADER_SIZE = 14;
    public static final int REF_SIZE = 40;
    public static final int URL_HASH_OFFSET_IN_REF = 0;

    private RefMetaCodec() {
    }

    public static int readRefCount(final byte[] value) {
        if (value == null || value.length < 4) return 0;
        return ((value[0] & 0xFF) << 24)
             | ((value[1] & 0xFF) << 16)
             | ((value[2] & 0xFF) << 8)
             | (value[3] & 0xFF);
    }

    public static List<byte[]> splitRefMetas(final byte[] value) {
        final List<byte[]> metas = new ArrayList<byte[]>();
        if (value == null || value.length < HEADER_SIZE) return metas;

        final int refCount = readRefCount(value);
        final int expectedBytes = HEADER_SIZE + Math.max(0, refCount) * REF_SIZE;
        if (refCount < 0 || value.length < expectedBytes) return metas;

        int offset = HEADER_SIZE;
        for (int i = 0; i < refCount; i++) {
            final byte[] ref = new byte[REF_SIZE];
            System.arraycopy(value, offset, ref, 0, REF_SIZE);
            metas.add(ref);
            offset += REF_SIZE;
        }
        return metas;
    }

    public static byte[] extractUrlHash(final byte[] refMeta) {
        if (refMeta == null || refMeta.length < URL_HASH_OFFSET_IN_REF + WordUrlKeyCodec.URL_HASH_LENGTH) {
            throw new IllegalArgumentException("refMeta must contain 12-byte urlhash at offset 0");
        }
        final byte[] urlHash = new byte[WordUrlKeyCodec.URL_HASH_LENGTH];
        System.arraycopy(refMeta, URL_HASH_OFFSET_IN_REF, urlHash, 0, WordUrlKeyCodec.URL_HASH_LENGTH);
        return urlHash;
    }
}
