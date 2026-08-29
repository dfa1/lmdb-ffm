package io.github.dfa1.lmdb;

import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.VarHandle;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

// Backing for MDB_val, a two-field { size_t mv_size; void* mv_data; } struct
// passed and returned by value-in-place (LMDB writes through the pointer for
// out-parameters like mdb_get's data). The struct shape, size and field
// offsets all come from Bindings.VAL_LAYOUT rather than hardcoded numbers.
final class LmdbVal {

    private static final MemoryLayout.PathElement MV_SIZE = MemoryLayout.PathElement.groupElement("mv_size");
    private static final MemoryLayout.PathElement MV_DATA = MemoryLayout.PathElement.groupElement("mv_data");

    private static final VarHandle MV_SIZE_HANDLE = Bindings.VAL_LAYOUT.varHandle(MV_SIZE);
    private static final VarHandle MV_DATA_HANDLE = Bindings.VAL_LAYOUT.varHandle(MV_DATA);

    /// Allocates an uninitialized MDB_val in `arena`, for an out-parameter LMDB
    /// itself fills in (e.g. the `data` of `mdb_get`).
    static MemorySegment allocate(Arena arena) {
        return arena.allocate(Bindings.VAL_LAYOUT);
    }

    /// Wraps `data` (native memory the caller owns) as an MDB_val, zero-copy.
    /// `data` must stay valid for the duration of the native call this value is
    /// passed to.
    static MemorySegment of(Arena arena, MemorySegment data) {
        MemorySegment val = allocate(arena);
        set(val, data);
        return val;
    }

    /// Writes `data`'s address/size into the already-allocated MDB_val `val`,
    /// without allocating a new struct — for reusing a persistent out-param
    /// slot across calls instead of [#of(Arena, MemorySegment)]'s fresh one.
    static void set(MemorySegment val, MemorySegment data) {
        MV_SIZE_HANDLE.set(val, 0L, data.byteSize());
        MV_DATA_HANDLE.set(val, 0L, data);
    }

    /// Returns `current` if it is already at least `minSize` bytes, otherwise
    /// a larger buffer freshly allocated from `arena` (grown by doubling).
    /// `arena` is a bump allocator with no per-allocation free, so a
    /// persistent, rarely-growing buffer (the common case once a caller's key
    /// sizes stabilize) wastes only the doubling's own overhead over the
    /// buffer's lifetime, not one allocation per call.
    static MemorySegment growBuffer(Arena arena, MemorySegment current, long minSize) {
        if (current != null && current.byteSize() >= minSize) {
            return current;
        }
        long newSize = current == null ? minSize : Math.max(minSize, current.byteSize() * 2);
        return arena.allocate(newSize);
    }

    /// Copies `bytes` into `arena`-owned native memory and wraps it as an
    /// MDB_val. `Math.max(bytes.length, 1)` sidesteps a zero-size native
    /// allocation for an empty array; the MDB_val still reports size `0`.
    static MemorySegment of(Arena arena, byte[] bytes) {
        MemorySegment data = arena.allocate(Math.max(bytes.length, 1));
        MemorySegment.copy(bytes, 0, data, JAVA_BYTE, 0, bytes.length);
        return of(arena, data.asSlice(0, bytes.length));
    }

    static long size(MemorySegment val) {
        return (long) MV_SIZE_HANDLE.get(val, 0L);
    }

    /// Writes just `size` into the already-allocated MDB_val `val`'s
    /// `mv_size` field, leaving `mv_data` untouched (already `NULL` from
    /// [#allocate]'s zeroed allocation) — for `MDB_RESERVE`, where LMDB reads
    /// only `mv_size` on the way in and overwrites `mv_data` on the way out
    /// with a pointer to the space it reserved.
    static void setSize(MemorySegment val, long size) {
        MV_SIZE_HANDLE.set(val, 0L, size);
    }

    /// The `mv_data` pointer, widened to its `mv_size` byte length and marked
    /// read-only, with no lifetime attached beyond the pointer's own
    /// (effectively unbounded) scope. Safe only for a transient read that is
    /// fully consumed before the current native call returns (e.g.
    /// [#toByteArray], or a comparator upcall's synchronous compare) — never
    /// for a segment handed back to a caller, which must go through
    /// [#data(MemorySegment, Arena)] instead so a stale read fails fast
    /// rather than dereferencing memory LMDB may since have unmapped (see
    /// dfa1/lmdb-ffm#5).
    @SuppressWarnings("restricted") // reinterpret needed: mv_data has no declared size until widened
    static MemorySegment data(MemorySegment val) {
        MemorySegment p = (MemorySegment) MV_DATA_HANDLE.get(val, 0L);
        return p.reinterpret(size(val)).asReadOnly();
    }

    /// Like [#data(MemorySegment)], but the returned segment's scope is
    /// attached to `arena` — the owning transaction's lifetime arena, for
    /// every zero-copy read result that escapes to a caller ([LmdbTxn]'s
    /// `getSegment`/`get(..., Mapper)`, [LmdbCursor]'s `get`/`getValue`) —
    /// rather than the pointer's own effectively-global scope. `arena` is
    /// always the *transaction's* arena, even when called through a
    /// [LmdbCursor]: closing a cursor (`mdb_cursor_close`) does not unmap
    /// anything, so a segment a cursor handed back stays valid until the
    /// transaction itself ends or the entry is overwritten/deleted, exactly
    /// as documented — not until the narrower cursor object happens to
    /// close. Once `arena` closes, further access throws
    /// [IllegalStateException] instead of the SIGSEGV a global-scoped
    /// segment would eventually hit.
    @SuppressWarnings("restricted") // reinterpret needed: mv_data has no declared size until widened
    static MemorySegment data(MemorySegment val, Arena arena) {
        MemorySegment p = (MemorySegment) MV_DATA_HANDLE.get(val, 0L);
        return p.reinterpret(size(val), arena, null).asReadOnly();
    }

    /// The `mv_data` pointer written back by a successful `MDB_RESERVE`
    /// write, widened to its `mv_size` byte length and left writable —
    /// unlike [#data(MemorySegment, Arena)], since this is the one path this
    /// binding hands back memory specifically for the caller to write into
    /// (see [LmdbTxn#put(LmdbDbi, byte[], int, java.util.function.Consumer)]).
    /// Scoped to `arena` like every other escaping view, so a stale
    /// reference after the owning transaction ends fails fast.
    @SuppressWarnings("restricted") // reinterpret needed: mv_data has no declared size until widened
    static MemorySegment reserved(MemorySegment val, Arena arena) {
        MemorySegment p = (MemorySegment) MV_DATA_HANDLE.get(val, 0L);
        return p.reinterpret(size(val), arena, null);
    }

    static byte[] toByteArray(MemorySegment val) {
        int n = (int) size(val);
        byte[] out = new byte[n];
        MemorySegment.copy(data(val), JAVA_BYTE, 0, out, 0, n);
        return out;
    }

    private LmdbVal() {
        // no instances
    }
}
