package io.github.dfa1.lmdb;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NativeObjectTest {

    // A non-NULL stand-in pointer; never dereferenced, only compared by identity.
    private static final MemorySegment POINTER = MemorySegment.ofAddress(0x1234);

    @Nested
    class Close {

        @Test
        void ptrReturnsTheOwnedPointerWhileOpen() {
            // Given an open native object
            TestObject sut = new TestObject(POINTER);

            // When its pointer is read
            MemorySegment p = sut.pointer();

            // Then it is the pointer it was constructed with
            assertThat(p).isEqualTo(POINTER);
        }

        @Test
        void closeCallsTryCloseExactlyOnceWithThePointer() {
            // Given an open native object
            TestObject sut = new TestObject(POINTER);

            // When closed
            sut.close();

            // Then tryClose ran once, with the owned pointer
            assertThat(sut.closeCount.get()).isEqualTo(1);
            assertThat(sut.closedWith).isEqualTo(POINTER);
        }

        @Test
        void closeIsIdempotent() {
            // Given an open native object
            TestObject sut = new TestObject(POINTER);

            // When closed repeatedly
            sut.close();
            sut.close();
            sut.close();

            // Then the resource is released only once
            assertThat(sut.closeCount.get()).isEqualTo(1);
        }

        @Test
        void ptrFailsAfterClose() {
            // Given a closed native object
            TestObject sut = new TestObject(POINTER);
            sut.close();

            // When its pointer is read
            ThrowingCallable result = sut::pointer;

            // Then it fails fast
            assertThatThrownBy(result)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("closed");
        }

        @Test
        void closeSwallowsTryCloseFailures() {
            // Given an object whose native free throws
            TestObject sut = new TestObject(POINTER);
            sut.failOnClose = true;

            // When closed
            ThrowingCallable result = sut::close;

            // Then the exception does not escape (destructors must not throw)
            assertThatCode(result).doesNotThrowAnyException();
            // And it is still considered closed
            assertThat(sut.closeCount.get()).isEqualTo(1);
            assertThatThrownBy(sut::pointer).isInstanceOf(IllegalStateException.class);
        }

        @Test
        void closeLetsLmdbContractExceptionEscape() {
            // Given an object whose tryClose detects a contract violation
            TestObject sut = new TestObject(POINTER);
            sut.failWithContractViolationOnClose = true;

            // When closed
            ThrowingCallable result = sut::close;

            // Then, unlike every other Throwable, this one is not swallowed
            assertThatThrownBy(result).isInstanceOf(LmdbContractException.class);
            // And it is still considered closed
            assertThat(sut.closeCount.get()).isEqualTo(1);
            assertThatThrownBy(sut::pointer).isInstanceOf(IllegalStateException.class);
        }

        @Test
        void constructingWithNullNeverCallsTryClose() {
            // Given an object that owns no pointer
            TestObject sut = new TestObject(MemorySegment.NULL);

            // When closed
            sut.close();

            // Then there is nothing to release, and the pointer is unavailable
            assertThat(sut.closeCount.get()).isZero();
            assertThatThrownBy(sut::pointer).isInstanceOf(IllegalStateException.class);
        }

        @Test
        void concurrentCloseReleasesExactlyOnce() throws Exception {
            // Given an open object and many threads poised to close it at once
            TestObject sut = new TestObject(POINTER);
            int threads = 32;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch go = new CountDownLatch(1);
            try {
                List<Future<?>> futures = new ArrayList<>();
                for (int i = 0; i < threads; i++) {
                    futures.add(pool.submit(() -> {
                        ready.countDown();
                        awaitUninterruptibly(go);
                        sut.close();
                    }));
                }
                ready.await();

                // When they all fire
                go.countDown();
                for (Future<?> f : futures) {
                    f.get();
                }

                // Then the resource is released exactly once
                assertThat(sut.closeCount.get()).isEqualTo(1);
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Nested
    class Take {

        @Test
        void takeReturnsTheOwnedPointerAndClosesWithoutCallingTryClose() {
            // Given an open native object
            TestObject sut = new TestObject(POINTER);

            // When its pointer is taken (a commit-like terminal operation)
            MemorySegment taken = sut.take();

            // Then the caller gets the real pointer, and tryClose never ran for it
            assertThat(taken).isEqualTo(POINTER);
            assertThat(sut.closeCount.get()).isZero();
        }

        @Test
        void closeAfterTakeIsANoOp() {
            // Given an object whose pointer was already taken
            TestObject sut = new TestObject(POINTER);
            sut.take();

            // When close() runs as the try-with-resources safety net
            sut.close();

            // Then it does not call tryClose a second time
            assertThat(sut.closeCount.get()).isZero();
        }

        @Test
        void takeTwiceFailsOnTheSecondCall() {
            // Given an object whose pointer was already taken
            TestObject sut = new TestObject(POINTER);
            sut.take();

            // When taken again
            ThrowingCallable result = sut::take;

            // Then it fails fast rather than double-releasing
            assertThatThrownBy(result)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("closed");
        }

        @Test
        void takeAfterCloseFails() {
            // Given a closed object
            TestObject sut = new TestObject(POINTER);
            sut.close();

            // When its pointer is taken
            ThrowingCallable result = sut::take;

            // Then it fails fast
            assertThatThrownBy(result).isInstanceOf(IllegalStateException.class);
        }
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /// Minimal concrete [NativeObject] that records how its native resource was released.
    private static final class TestObject extends NativeObject {

        private final AtomicInteger closeCount = new AtomicInteger();
        private volatile MemorySegment closedWith;
        private volatile boolean failOnClose;
        private volatile boolean failWithContractViolationOnClose;

        private TestObject(MemorySegment owningPointer) {
            super(owningPointer);
        }

        private MemorySegment pointer() {
            return ptr();
        }

        @Override
        protected void tryClose(MemorySegment ptr) {
            closeCount.incrementAndGet();
            closedWith = ptr;
            if (failWithContractViolationOnClose) {
                throw new LmdbContractException("contract violation");
            }
            if (failOnClose) {
                throw new RuntimeException("native free failed");
            }
        }
    }
}
