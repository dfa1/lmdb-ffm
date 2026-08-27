package io.github.dfa1.lmdb;

/// A callback receiving one line of text at a time from an LMDB diagnostic
/// dump (`MDB_msg_func`) — currently only used by
/// [LmdbEnv#listReaders(LmdbMessageHandler)] (`mdb_reader_list`).
@FunctionalInterface
public interface LmdbMessageHandler {

    /// Receives one line of output, including its trailing newline exactly
    /// as LMDB wrote it (not stripped here, to stay a transparent passthrough).
    ///
    /// @param line the line, including its trailing newline
    /// @return `true` to keep receiving further lines, `false` to stop early
    boolean onMessage(String line);
}
