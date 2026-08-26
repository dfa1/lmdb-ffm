package io.github.dfa1.lmdb;

import java.lang.foreign.MemorySegment;

/// Callback invoked by [LmdbTxn#get(LmdbDbi, byte[], Mapper)] (and its
/// `MemorySegment`-key overload) with a zero-copy view of the stored value —
/// no `byte[]` copy for the value itself.
///
/// The segment is read-only and, like [LmdbTxn#getSegment(LmdbDbi, byte[])]'s,
/// stays valid for the rest of the transaction (or until the entry is
/// overwritten/deleted) — nothing stops [#map(MemorySegment)] from retaining
/// it past the call if a caller chooses to. Use `Mapper` when the value only
/// needs to be parsed into a plain Java result (a `String`, a record, a
/// checksum, ...) right there in the callback, with no separate `get`/`byte[]`
/// round trip.
///
/// @param <R> the type produced from mapping the value
@FunctionalInterface
public interface Mapper<R> {

    /// Maps `value` to a result. Rejects a `null` return with
    /// [NullPointerException] rather than accepting it silently.
    ///
    /// @param value zero-copy, read-only view of the stored value
    /// @return the value produced from `value`; must not be `null`
    R map(MemorySegment value);
}
