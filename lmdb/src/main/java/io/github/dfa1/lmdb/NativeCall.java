package io.github.dfa1.lmdb;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/// Package-private helpers that adapt raw FFM downcalls to LMDB's error
/// convention: run a native call, decode an `int` return code into an
/// [LmdbException] (skipping `MDB_SUCCESS`), and read back native
/// out-parameters. Shared by the binding classes so the conventions live in
/// one place.
final class NativeCall {

    /// A native call returning an LMDB `int` result code.
    @FunctionalInterface
    @SuppressWarnings("java:S112") // wraps MethodHandle.invokeExact, which is declared to throw Throwable
    interface LmdbCall {
        int run() throws Throwable;
    }

    /// A native call writing a fresh handle through an out-parameter pointer,
    /// returning an LMDB `int` result code (`mdb_env_create`, `mdb_txn_begin`,
    /// `mdb_dbi_open`, `mdb_cursor_open`).
    @FunctionalInterface
    @SuppressWarnings("java:S112") // wraps MethodHandle.invokeExact, which is declared to throw Throwable
    interface OutParamCall {
        int run(MemorySegment out) throws Throwable;
    }

    /// Invokes an LMDB call and throws [LmdbException] unless it returned
    /// `MDB_SUCCESS` (`0`).
    ///
    /// @param c the call to run
    static void check(LmdbCall c) {
        int code = invoke(c);
        if (code != 0) {
            throw new LmdbException(errorMessage(code), code);
        }
    }

    /// Invokes an LMDB read/cursor call, treating `MDB_NOTFOUND` as a normal
    /// "absent" outcome rather than an error.
    ///
    /// @param c the call to run
    /// @return `true` if the call succeeded, `false` on `MDB_NOTFOUND`
    /// @throws LmdbException if the call failed with any other code
    static boolean checkFound(LmdbCall c) {
        int code = invoke(c);
        if (code == LmdbErrorCode.NOTFOUND.value()) {
            return false;
        }
        if (code != 0) {
            throw new LmdbException(errorMessage(code), code);
        }
        return true;
    }

    /// Runs an `mdb_*_create`/`_open`/`_begin`-style call that writes a fresh
    /// native pointer through its out-parameter, and returns that pointer.
    ///
    /// @param arena the arena to allocate the temporary out-parameter in
    /// @param c     the call to run
    /// @return the pointer the call wrote
    /// @throws LmdbException if the call failed
    static MemorySegment createHandle(Arena arena, OutParamCall c) {
        MemorySegment out = arena.allocate(ADDRESS);
        check(() -> c.run(out));
        return out.get(ADDRESS, 0);
    }

    /// Like [#createHandle], for `mdb_dbi_open`, whose out-parameter is an
    /// `unsigned int` (`MDB_dbi`) rather than a pointer.
    ///
    /// @param arena the arena to allocate the temporary out-parameter in
    /// @param c     the call to run
    /// @return the `MDB_dbi` handle the call wrote
    /// @throws LmdbException if the call failed
    static int createIntHandle(Arena arena, OutParamCall c) {
        MemorySegment out = arena.allocate(JAVA_INT);
        check(() -> c.run(out));
        return out.get(JAVA_INT, 0);
    }

    private static int invoke(LmdbCall c) {
        try {
            return c.run();
        } catch (Throwable t) {
            throw rethrow(t);
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
