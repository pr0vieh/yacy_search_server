package net.yacy.rocksdb;

public final class WordUrlRefRecord {

    private final byte[] wordHash;
    private final byte[] urlHash;
    private final byte[] meta;

    public WordUrlRefRecord(final byte[] wordHash, final byte[] urlHash, final byte[] meta) {
        this.wordHash = wordHash;
        this.urlHash = urlHash;
        this.meta = meta;
    }

    public byte[] wordHash() {
        return this.wordHash;
    }

    public byte[] urlHash() {
        return this.urlHash;
    }

    public byte[] meta() {
        return this.meta;
    }
}
