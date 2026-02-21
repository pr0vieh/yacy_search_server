package net.yacy.kelondro.rwi;

import java.io.IOException;

import net.yacy.cora.storage.HandleSet;
import net.yacy.cora.util.SpaceExceededException;


public interface IndexCellBackend<ReferenceType extends Reference> extends BufferedIndex<ReferenceType> {

    void clearCache();

    boolean isEmpty();

    int deleteOld(int minsize, long maxtime) throws IOException;

    int sizesMax();

    int getSegmentCount();

    TermSearch<ReferenceType> query(
            HandleSet queryHashes,
            HandleSet excludeHashes,
            HandleSet urlselection,
            ReferenceFactory<ReferenceType> termFactory,
            int maxDistance) throws SpaceExceededException;
}
