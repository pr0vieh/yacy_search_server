package net.yacy.rocksdb;

import java.io.File;
import java.io.IOException;
import java.util.List;

import org.rocksdb.RocksIterator;

public final class PostingWordUrlRefStorage implements WordUrlRefStorage {

    private final WordUrlRefStore store;

    public PostingWordUrlRefStorage(final File dbPath) {
        this.store = new WordUrlRefStore(dbPath);
    }

    @Override
    public String mode() {
        return "posting";
    }

    @Override
    public void setDisableWAL(final boolean disableWAL) {
        this.store.setDisableWAL(disableWAL);
    }

    @Override
    public void upsertBatch(final List<WordUrlRefRecord> records) throws IOException {
        this.store.upsertBatch(records);
    }

    @Override
    public List<WordUrlRefRecord> scanWord(final byte[] termHash, final int limit) {
        return this.store.scanWord(termHash, limit);
    }

    @Override
    public boolean delete(final byte[] termHash, final byte[] urlHash) throws IOException {
        return this.store.delete(termHash, urlHash);
    }

    @Override
    public void deleteWord(final byte[] termHash) throws IOException {
        this.store.deleteWord(termHash);
    }

    @Override
    public void clear() throws IOException {
        this.store.clear();
    }

    @Override
    public long size() {
        return this.store.size();
    }

    @Override
    public boolean isEmpty() {
        return this.store.isEmpty();
    }

    @Override
    public long distinctWordCount() {
        return this.store.distinctWordCount();
    }

    @Override
    public RocksIterator newWordIterator() {
        return this.store.newWordIterator();
    }

    @Override
    public int wordsInCache() {
        return this.store.wordsInCache();
    }

    @Override
    public int referencesInCache() {
        return this.store.referencesInCache();
    }

    @Override
    public void close() throws IOException {
        this.store.close();
    }
}