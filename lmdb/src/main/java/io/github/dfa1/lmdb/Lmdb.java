package io.github.dfa1.lmdb;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.JAVA_INT;

/// Library-wide entry points that don't belong to an environment, transaction
/// or cursor.
public final class Lmdb {

    /// The version of the linked `liblmdb`.
    ///
    /// @return the linked library's version
    public static LmdbVersion version() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment major = arena.allocate(JAVA_INT);
            MemorySegment minor = arena.allocate(JAVA_INT);
            MemorySegment patch = arena.allocate(JAVA_INT);
            try {
                var _ = (MemorySegment) Bindings.VERSION.invokeExact(major, minor, patch);
            } catch (Throwable t) {
                throw NativeCall.rethrow(t);
            }
            return new LmdbVersion(major.get(JAVA_INT, 0L), minor.get(JAVA_INT, 0L), patch.get(JAVA_INT, 0L));
        }
    }

    private Lmdb() {
        // no instances
    }
}
