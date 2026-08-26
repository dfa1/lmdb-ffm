package io.github.dfa1.lmdb;

import java.lang.foreign.MemorySegment;

/// Callback invoked by [LmdbTxn#get(LmdbDbi, byte[], Mapper)] (and its
/// `MemorySegment`-key overload) with a zero-copy view of the stored value —
/// no `byte[]` copy, no allocation for the value itself.
///
/// The segment is read-only and bound to an arena that closes as soon as
/// [#map(MemorySegment)] returns, so it — and any view derived from it — must
/// not be retained past the call. This is tighter than
/// [LmdbTxn#getSegment(LmdbDbi, byte[])]'s segment, which stays valid for the
/// rest of the transaction: use `Mapper` when the value only needs to be
/// parsed into a plain Java result (a `String`, a record, a checksum, ...) and
/// there is no reason to keep a raw `MemorySegment` around afterward.
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
