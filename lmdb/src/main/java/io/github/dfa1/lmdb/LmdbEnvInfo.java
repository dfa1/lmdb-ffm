package io.github.dfa1.lmdb;

import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.VarHandle;

/// Information about an environment (from `mdb_env_info`) — LMDB's
/// `MDB_envinfo` struct.
///
/// @param mapAddress  the address of the memory map, or [MemorySegment#NULL] if unconstrained
/// @param mapSize     the size of the data memory map, in bytes
/// @param lastPageNo  the ID of the last used page
/// @param lastTxnId   the ID of the last committed transaction
/// @param maxReaders  the maximum number of reader slots in the environment
/// @param numReaders  the number of reader slots currently in use
public record LmdbEnvInfo(
        MemorySegment mapAddress, long mapSize, long lastPageNo, long lastTxnId, int maxReaders, int numReaders) {

    private static final VarHandle MAPADDR =
            Bindings.ENVINFO_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("me_mapaddr"));
    private static final VarHandle MAPSIZE =
            Bindings.ENVINFO_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("me_mapsize"));
    private static final VarHandle LAST_PGNO =
            Bindings.ENVINFO_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("me_last_pgno"));
    private static final VarHandle LAST_TXNID =
            Bindings.ENVINFO_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("me_last_txnid"));
    private static final VarHandle MAXREADERS =
            Bindings.ENVINFO_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("me_maxreaders"));
    private static final VarHandle NUMREADERS =
            Bindings.ENVINFO_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("me_numreaders"));

    static LmdbEnvInfo of(MemorySegment info) {
        return new LmdbEnvInfo(
                (MemorySegment) MAPADDR.get(info, 0L),
                (long) MAPSIZE.get(info, 0L),
                (long) LAST_PGNO.get(info, 0L),
                (long) LAST_TXNID.get(info, 0L),
                (int) MAXREADERS.get(info, 0L),
                (int) NUMREADERS.get(info, 0L));
    }
}
