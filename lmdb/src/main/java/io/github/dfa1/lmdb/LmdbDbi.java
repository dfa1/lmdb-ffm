package io.github.dfa1.lmdb;

/// A handle to an individual database within an environment, from
/// `mdb_dbi_open`. Unlike [LmdbEnv], [LmdbTxn] and [LmdbCursor], this wraps no
/// pointer — `MDB_dbi` is a native `unsigned int` — so it needs no
/// [NativeObject]/`close()`. LMDB itself discourages closing DBI handles in a
/// multi-threaded environment (a concurrent transaction may still be using
/// it); a DBI is normally opened once per database and kept for the life of
/// the environment. To close one anyway, see [LmdbEnv#closeDatabase(LmdbDbi)].
///
/// @param handle the native `MDB_dbi` value
public record LmdbDbi(int handle) {
}
