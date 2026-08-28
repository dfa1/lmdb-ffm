package io.github.dfa1.lmdb;

import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.VarHandle;
import java.util.Objects;

/// Information about an environment (from `mdb_env_info`) — LMDB's
/// `MDB_envinfo` struct.
///
/// A plain class rather than a public record: every instance is a snapshot
/// read from a live native call, and a public canonical constructor would let
/// callers fabricate one with arbitrary values that were never actually
/// reported by LMDB.
public final class LmdbEnvInfo {

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

    private final MemorySegment mapAddress;
    private final LmdbByteSize mapSize;
    private final long lastPageNo;
    private final long lastTxnId;
    private final int maxReaders;
    private final int numReaders;

    private LmdbEnvInfo(MemorySegment mapAddress, LmdbByteSize mapSize, long lastPageNo, long lastTxnId,
            int maxReaders, int numReaders) {
        this.mapAddress = mapAddress;
        this.mapSize = mapSize;
        this.lastPageNo = lastPageNo;
        this.lastTxnId = lastTxnId;
        this.maxReaders = maxReaders;
        this.numReaders = numReaders;
    }

    static LmdbEnvInfo of(MemorySegment info) {
        return new LmdbEnvInfo(
                (MemorySegment) MAPADDR.get(info, 0L),
                LmdbByteSize.ofBytes((long) MAPSIZE.get(info, 0L)),
                (long) LAST_PGNO.get(info, 0L),
                (long) LAST_TXNID.get(info, 0L),
                (int) MAXREADERS.get(info, 0L),
                (int) NUMREADERS.get(info, 0L));
    }

    /// The address of the memory map, or [MemorySegment#NULL] if unconstrained.
    ///
    /// @return the map address
    public MemorySegment mapAddress() {
        return mapAddress;
    }

    /// The size of the data memory map.
    ///
    /// @return the map size
    public LmdbByteSize mapSize() {
        return mapSize;
    }

    /// The ID of the last used page.
    ///
    /// @return the last page ID
    public long lastPageNo() {
        return lastPageNo;
    }

    /// The ID of the last committed transaction.
    ///
    /// @return the last transaction ID
    public long lastTxnId() {
        return lastTxnId;
    }

    /// The maximum number of reader slots in the environment.
    ///
    /// @return the reader slot limit
    public int maxReaders() {
        return maxReaders;
    }

    /// The number of reader slots currently in use.
    ///
    /// @return the number of reader slots in use
    public int numReaders() {
        return numReaders;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof LmdbEnvInfo other)) {
            return false;
        }
        return mapSize.equals(other.mapSize)
                && lastPageNo == other.lastPageNo
                && lastTxnId == other.lastTxnId
                && maxReaders == other.maxReaders
                && numReaders == other.numReaders
                && mapAddress.equals(other.mapAddress);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mapAddress, mapSize, lastPageNo, lastTxnId, maxReaders, numReaders);
    }

    @Override
    public String toString() {
        return "LmdbEnvInfo[mapAddress=" + mapAddress + ", mapSize=" + mapSize + ", lastPageNo=" + lastPageNo
                + ", lastTxnId=" + lastTxnId + ", maxReaders=" + maxReaders + ", numReaders=" + numReaders + "]";
    }
}
