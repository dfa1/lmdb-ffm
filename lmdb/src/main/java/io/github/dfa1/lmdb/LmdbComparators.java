package io.github.dfa1.lmdb;

import java.lang.foreign.Arena;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

// Builds the native upcall stub (MDB_cmp_func*) LMDB calls back into for an
// LmdbComparator installed via LmdbTxn#setComparator/#setDupComparator. Kept
// separate from LmdbComparator itself: an interface's fields are implicitly
// public static final, so the MethodHandle trampoline below (an
// implementation detail, not API) needs a real class to be private in.
final class LmdbComparators {

    // (const MDB_val *a, const MDB_val *b) -> int — bound per-comparator via
    // bindTo, then handed to Linker#upcallStub with Bindings#CMP_FUNC_DESCRIPTOR.
    private static final MethodHandle TRAMPOLINE;

    static {
        try {
            TRAMPOLINE = MethodHandles.lookup().findStatic(LmdbComparators.class, "invoke",
                    MethodType.methodType(int.class, LmdbComparator.class, MemorySegment.class, MemorySegment.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    // upcallStub is a restricted FFM method, same as NativeLibrary's downcall
    // construction; arena controls how long LMDB may keep calling back into
    // the returned stub (see LmdbEnv#upcallStub, its caller).
    @SuppressWarnings("restricted")
    static MemorySegment upcallStub(Arena arena, LmdbComparator comparator) {
        MethodHandle bound = TRAMPOLINE.bindTo(comparator);
        return Linker.nativeLinker().upcallStub(bound, Bindings.CMP_FUNC_DESCRIPTOR, arena);
    }

    // LMDB calls back with pointers to the two MDB_val structs being
    // compared, not to their raw key/value bytes directly, so each must be
    // reinterpreted to the struct's size before unpacking via LmdbVal, same
    // as every other MDB_val the native side hands back.
    private static int invoke(LmdbComparator comparator, MemorySegment a, MemorySegment b) {
        try {
            return comparator.compare(asValue(a), asValue(b));
        } catch (Throwable t) {
            // An exception cannot cross this upcall back into LMDB's C code:
            // the JDK's own Linker#upcallStub documents that one escaping
            // here terminates the JVM outright, not just this call. There is
            // also no caller-visible LmdbTxn/LmdbCursor method to propagate
            // it through — LMDB itself is mid-B+tree-traversal when this
            // runs. The best available fallback is to not crash: report it
            // and treat the pair as equal, which is wrong but survivable —
            // see LmdbComparator's class documentation.
            t.printStackTrace();
            return 0;
        }
    }

    @SuppressWarnings("restricted") // reinterpret needed: the upcall parameter has no declared size
    private static MemorySegment asValue(MemorySegment mdbVal) {
        return LmdbVal.data(mdbVal.reinterpret(Bindings.VAL_LAYOUT.byteSize()));
    }

    private LmdbComparators() {
        // no instances
    }
}
