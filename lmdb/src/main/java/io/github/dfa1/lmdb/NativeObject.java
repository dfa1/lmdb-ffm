package io.github.dfa1.lmdb;

import java.lang.foreign.MemorySegment;
import java.util.concurrent.atomic.AtomicReference;

/// Base class for wrappers that own a native LMDB pointer.
///
/// The pointer lives in an [AtomicReference] that is swapped to
/// [MemorySegment#NULL] on [#close()], so [#tryClose] runs
/// exactly once even under concurrent or repeated close calls.
public abstract class NativeObject implements AutoCloseable {

    private final AtomicReference<MemorySegment> ptr;

    /// Takes ownership of a freshly created native pointer.
    ///
    /// @param owningPointer the non-NULL native pointer this object now owns
    protected NativeObject(MemorySegment owningPointer) {
        this.ptr = new AtomicReference<>(owningPointer);
    }

    /// Returns the live native pointer, failing if this object is already closed.
    ///
    /// @return the live native pointer
    /// @throws IllegalStateException if this object has been closed
    protected final MemorySegment ptr() {
        MemorySegment p = ptr.get();
        if (MemorySegment.NULL.equals(p)) {
            throw new IllegalStateException("native object is closed");
        }
        return p;
    }

    @Override
    public final void close() {
        MemorySegment p = ptr.getAndSet(MemorySegment.NULL);
        if (!MemorySegment.NULL.equals(p)) {
            try {
                tryClose(p);
            } catch (LmdbContractException e) {
                // The one Throwable this deliberately does not swallow: a
                // detected violation of this binding's own resource-lifetime
                // contract, not an ordinary native-call failure — see
                // LmdbContractException's own doc for why "destructors must
                // not throw" does not extend to it.
                throw e;
            } catch (Throwable _) {
                // destructors must not throw
            }
        }
    }

    /// Atomically takes ownership of the live pointer away from this object,
    /// for a subclass whose terminal operation is not [#tryClose] itself but
    /// still must run exactly once — e.g. a transaction's `commit`, distinct
    /// from the `abort` that [#tryClose] runs as the close-without-committing
    /// fallback. After this call, [#ptr()] and [#close()] behave as if this
    /// object were already closed.
    ///
    /// @return the native pointer this object owned
    /// @throws IllegalStateException if this object was already closed or taken
    protected final MemorySegment take() {
        MemorySegment p = ptr.getAndSet(MemorySegment.NULL);
        if (MemorySegment.NULL.equals(p)) {
            throw new IllegalStateException("native object is closed");
        }
        return p;
    }

    /// Releases the native resource. Called exactly once with a non-NULL pointer.
    ///
    /// @param ptr the non-NULL native pointer to free
    /// @throws Throwable if the native free call fails; the exception is
    ///                    swallowed by [#close()] — except [LmdbContractException],
    ///                    which [#close()] lets escape instead
    @SuppressWarnings("java:S112") // implementations wrap MethodHandle.invokeExact, declared to throw Throwable
    protected abstract void tryClose(MemorySegment ptr) throws Throwable;
}
