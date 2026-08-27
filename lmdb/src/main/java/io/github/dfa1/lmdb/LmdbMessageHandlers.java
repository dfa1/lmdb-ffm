package io.github.dfa1.lmdb;

import java.lang.foreign.Arena;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;

// Builds the native upcall stub (MDB_msg_func*) LMDB calls back into for a
// LmdbMessageHandler passed to LmdbEnv#listReaders. Unlike LmdbComparators'
// stub (which must outlive the call that installs it, since LMDB keeps
// calling back into it for as long as the dbi is used), mdb_reader_list
// calls back into this stub only synchronously, within the one native call
// that receives it, and never again afterward — so the stub is built in a
// call-scoped Arena at each call site instead of one tied to LmdbEnv.
final class LmdbMessageHandlers {

    private static final MethodHandle TRAMPOLINE;

    static {
        try {
            TRAMPOLINE = MethodHandles.lookup().findStatic(LmdbMessageHandlers.class, "invoke",
                    MethodType.methodType(int.class, LmdbMessageHandler.class, MemorySegment.class,
                            MemorySegment.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @SuppressWarnings("restricted") // upcallStub is a restricted FFM method
    static MemorySegment upcallStub(Arena arena, LmdbMessageHandler handler) {
        MethodHandle bound = TRAMPOLINE.bindTo(handler);
        return Linker.nativeLinker().upcallStub(bound, Bindings.MSG_FUNC_DESCRIPTOR, arena);
    }

    // ctx (MDB_msg_func's second parameter) is unused: the Java-side handler
    // is already bound into this trampoline via bindTo, so there is nothing
    // for a native context pointer to carry that the closure doesn't already
    // have. LmdbEnv#listReaders passes MemorySegment.NULL for it.
    private static int invoke(LmdbMessageHandler handler, MemorySegment msg, MemorySegment ctx) {
        try {
            return handler.onMessage(asString(msg)) ? 0 : -1;
        } catch (Throwable t) {
            // Same constraint as LmdbComparators#invoke: an exception cannot
            // cross this upcall back into LMDB's C code without crashing the
            // JVM, and there is no caller-visible method to propagate it
            // through either. Report it and stop the dump, treating a
            // throwing handler the same as one that asked to stop.
            t.printStackTrace();
            return -1;
        }
    }

    @SuppressWarnings("restricted") // reinterpret needed to read a C string of unknown length
    private static String asString(MemorySegment msg) {
        return msg.reinterpret(Long.MAX_VALUE).getString(0, StandardCharsets.US_ASCII);
    }

    private LmdbMessageHandlers() {
        // no instances
    }
}
