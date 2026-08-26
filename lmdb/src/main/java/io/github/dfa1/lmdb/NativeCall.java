package io.github.dfa1.lmdb;

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/// Package-private helpers shared by the binding classes for LMDB's error
/// convention. Deliberately *not* a generic "run this call" wrapper taking a
/// functional-interface callback: every `Bindings.X.invokeExact(...)` call
/// stays inline at its own call site, in its own `try`/`catch`, so the JIT
/// sees one direct polymorphic-signature call per site instead of one
/// indirected through a lambda's `run()`. These are just the bits that would
/// otherwise be duplicated: turning a raw `int` result code into an
/// [LmdbException], and laundering `invokeExact`'s checked `Throwable`.
final class NativeCall {

    /// Throws [LmdbException] unless `code` is `MDB_SUCCESS` (`0`).
    ///
    /// @param code the native return code to check
    static void check(int code) {
        if (code != 0) {
            throw new LmdbException(errorMessage(code), code);
        }
    }

    /// Like [#check], but treats `MDB_NOTFOUND` as a normal "absent" outcome
    /// rather than an error — for read/cursor calls.
    ///
    /// @param code the native return code to check
    /// @return `true` if `code` is `MDB_SUCCESS`, `false` on `MDB_NOTFOUND`
    /// @throws LmdbException if `code` is any other non-success value
    static boolean checkFound(int code) {
        if (code == LmdbErrorCode.NOTFOUND.value()) {
            return false;
        }
        check(code);
        return true;
    }

    /// Guards a zero-copy entry point: the segment handed to LMDB must be
    /// backed by native (off-heap) memory, since its address is dereferenced
    /// in C. Fails fast with a clear message instead of the FFM linker's
    /// cryptic error (or worse, a crash) from trying to derive a native
    /// address from heap-backed memory.
    ///
    /// @param seg  the segment to check
    /// @param name the parameter name, for the exception message
    static void requireNative(MemorySegment seg, String name) {
        Objects.requireNonNull(seg, name);
        if (!seg.isNative()) {
            throw new IllegalArgumentException(
                    name + " must be a native (off-heap) MemorySegment; got a heap segment");
        }
    }

    @SuppressWarnings("restricted") // reinterpret needed to read a C string of unknown length
    private static String errorMessage(int code) {
        try {
            MemorySegment p = (MemorySegment) Bindings.STRERROR.invokeExact(code);
            return p.reinterpret(Long.MAX_VALUE).getString(0, StandardCharsets.US_ASCII);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /// Rethrows any `Throwable` as if unchecked, laundering the checked
    /// `Throwable` that [java.lang.invoke.MethodHandle#invokeExact] declares.
    /// The shared sink for every binding class's native-call catch blocks.
    @SuppressWarnings("unchecked")
    static <E extends Throwable> RuntimeException rethrow(Throwable t) throws E {
        throw (E) t;
    }

    private NativeCall() {
        // no instances
    }
}
