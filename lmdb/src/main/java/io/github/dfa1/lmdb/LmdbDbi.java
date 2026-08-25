package io.github.dfa1.lmdb;

import java.util.Objects;

/// A handle to an individual database within an environment, from
/// `mdb_dbi_open`. Unlike [LmdbEnv], [LmdbTxn] and [LmdbCursor], this wraps no
/// pointer — `MDB_dbi` is a native `unsigned int` — so it needs no
/// [NativeObject]/`close()`. LMDB itself discourages closing DBI handles in a
/// multi-threaded environment (a concurrent transaction may still be using
/// it); a DBI is normally opened once per database and kept for the life of
/// the environment. To close one anyway, see [LmdbEnv#closeDatabase(LmdbDbi)].
///
/// A plain class rather than a public record: a public canonical constructor
/// would let a caller fabricate a handle LMDB never actually assigned (e.g.
/// `new LmdbDbi(999)`) and pass it off as real, where LMDB's own C API can
/// only ever hand one back from `mdb_dbi_open`.
public final class LmdbDbi {

    private final int handle;

    LmdbDbi(int handle) {
        this.handle = handle;
    }

    /// The native `MDB_dbi` value.
    ///
    /// @return the native handle
    public int handle() {
        return handle;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof LmdbDbi other && handle == other.handle;
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(handle);
    }

    @Override
    public String toString() {
        return "LmdbDbi[handle=" + handle + "]";
    }
}
