package net.yacy.rocksdb;

import java.util.Arrays;

public final class WordUrlKeyCodec {

    public static final int WORD_HASH_LENGTH = 12;
    public static final int URL_HASH_LENGTH = 12;
    public static final int COMPOSITE_KEY_LENGTH = WORD_HASH_LENGTH + URL_HASH_LENGTH;

    private WordUrlKeyCodec() {
    }

    public static byte[] compose(final byte[] wordHash, final byte[] urlHash) {
        if (wordHash == null || wordHash.length != WORD_HASH_LENGTH) {
            throw new IllegalArgumentException("wordHash must be 12 bytes");
        }
        if (urlHash == null || urlHash.length != URL_HASH_LENGTH) {
            throw new IllegalArgumentException("urlHash must be 12 bytes");
        }
        final byte[] key = new byte[COMPOSITE_KEY_LENGTH];
        System.arraycopy(wordHash, 0, key, 0, WORD_HASH_LENGTH);
        System.arraycopy(urlHash, 0, key, WORD_HASH_LENGTH, URL_HASH_LENGTH);
        return key;
    }

    public static byte[] wordPrefix(final byte[] wordHash) {
        if (wordHash == null || wordHash.length != WORD_HASH_LENGTH) {
            throw new IllegalArgumentException("wordHash must be 12 bytes");
        }
        return Arrays.copyOf(wordHash, WORD_HASH_LENGTH);
    }

    public static byte[] extractWordHash(final byte[] compositeKey) {
        if (compositeKey == null || compositeKey.length < WORD_HASH_LENGTH) {
            throw new IllegalArgumentException("invalid composite key");
        }
        return Arrays.copyOfRange(compositeKey, 0, WORD_HASH_LENGTH);
    }

    public static byte[] extractUrlHash(final byte[] compositeKey) {
        if (compositeKey == null || compositeKey.length < COMPOSITE_KEY_LENGTH) {
            throw new IllegalArgumentException("invalid composite key");
        }
        return Arrays.copyOfRange(compositeKey, WORD_HASH_LENGTH, COMPOSITE_KEY_LENGTH);
    }

    public static boolean hasWordPrefix(final byte[] compositeKey, final byte[] wordHash) {
        if (compositeKey == null || wordHash == null) return false;
        if (wordHash.length != WORD_HASH_LENGTH || compositeKey.length < WORD_HASH_LENGTH) return false;
        for (int i = 0; i < WORD_HASH_LENGTH; i++) {
            if (compositeKey[i] != wordHash[i]) return false;
        }
        return true;
    }
}
