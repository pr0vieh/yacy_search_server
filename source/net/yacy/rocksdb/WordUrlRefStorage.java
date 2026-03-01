package net.yacy.rocksdb;

import java.io.IOException;
import java.util.List;

import org.rocksdb.RocksIterator;

public interface WordUrlRefStorage extends AutoCloseable {

    String mode();

    void setDisableWAL(boolean disableWAL);

    void upsertBatch(List<WordUrlRefRecord> records) throws IOException;

    List<WordUrlRefRecord> scanWord(byte[] termHash, int limit);

    boolean delete(byte[] termHash, byte[] urlHash) throws IOException;

    void deleteWord(byte[] termHash) throws IOException;

    void clear() throws IOException;

    long size();

    boolean isEmpty();

    long distinctWordCount();

    RocksIterator newWordIterator();

    int wordsInCache();

    int referencesInCache();

    @Override
    void close() throws IOException;
}