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
        MV_SIZE_HANDLE.set(val, 0L, data.byteSize());
        MV_DATA_HANDLE.set(val, 0L, data);
        return val;
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

    /// The `mv_data` pointer, widened to its `mv_size` byte length. Zero-copy:
    /// for a value LMDB itself populated (a read result), this segment points
    /// directly into the memory-mapped database and is valid only until the
    /// enclosing transaction ends or the entry is overwritten/deleted.
    @SuppressWarnings("restricted") // reinterpret needed: mv_data has no declared size until widened
    static MemorySegment data(MemorySegment val) {
        MemorySegment p = (MemorySegment) MV_DATA_HANDLE.get(val, 0L);
        return p.reinterpret(size(val));
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
