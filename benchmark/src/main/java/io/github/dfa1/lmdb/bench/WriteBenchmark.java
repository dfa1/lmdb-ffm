package io.github.dfa1.lmdb.bench;

import io.github.dfa1.lmdb.LmdbCursor;
import io.github.dfa1.lmdb.LmdbDbi;
import io.github.dfa1.lmdb.LmdbDbiFlag;
import io.github.dfa1.lmdb.LmdbEnv;
import io.github.dfa1.lmdb.LmdbEnvFlag;
import io.github.dfa1.lmdb.LmdbTxn;
import io.github.dfa1.lmdb.LmdbWriteFlag;
import org.lmdbjava.Cursor;
import org.lmdbjava.Dbi;
import org.lmdbjava.Env;
import org.lmdbjava.Txn;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.concurrent.TimeUnit;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static org.lmdbjava.DbiFlags.MDB_CREATE;
import static org.lmdbjava.EnvFlags.MDB_NOSYNC;
import static org.lmdbjava.EnvFlags.MDB_WRITEMAP;
import static org.lmdbjava.PutFlags.MDB_APPEND;

/// Write throughput: this project (FFM) against lmdbjava (JNR-FFI), shaped to
/// match lmdbjava's own benchmark suite
/// (github.com/lmdbjava/benchmarks — `LmdbJavaByteBuffer.Writer`):
///
/// - One `@Benchmark` invocation writes the *entire* `num`-entry dataset in a
///   single transaction and cursor, with `MDB_APPEND` (both bindings insert
///   strictly ascending keys — [BenchData#keys(int)] is sequential) — not a
///   fixed-size batch as an unrelated, arbitrary unit of work.
/// - `@Setup`/`@TearDown(Level.Invocation)`: every single invocation gets a
///   brand-new, empty environment. Matches lmdbjava's own `Writer` state
///   exactly, and for the same reason — `MDB_APPEND` requires strictly
///   increasing keys, so reusing one environment across invocations would
///   have to either grow forever or fall back to non-append inserts after
///   the first invocation, corrupting the comparison.
/// - `SampleTime`/milliseconds, not `Throughput`: one invocation is "write
///   `num` entries", not a fast, repeatable unit worth an ops/time rate.
/// - Both environments open with `MDB_WRITEMAP` + `MDB_NOSYNC` — lmdbjava's
///   own defaults (`CommonLmdbJava.writeMap`, `Writer.sync`).
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
@Fork(value = 1, jvmArgsAppend = {
    "--enable-native-access=ALL-UNNAMED",
    "--add-opens=java.base/java.nio=ALL-UNNAMED",
    "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED"
})
@Warmup(iterations = 3)
@Measurement(iterations = 3)
public class WriteBenchmark {

    /// `1_000_000` matches lmdbjava's own `Common.num` default; `1_000` is
    /// kept as a fast local/CI sanity size.
    @Param({"1000", "1000000"})
    private int num;

    private byte[][] keys;
    private byte[] value;
    private long mapSize;

    private Path lmdbJavaDir;
    private LmdbEnv env;
    private LmdbDbi dbi;

    private Path lmdbjavaDir;
    private Env<ByteBuffer> lmdbjavaEnv;
    private Dbi<ByteBuffer> lmdbjavaDbi;
    private ByteBuffer lmdbjavaKeyBuf;
    private ByteBuffer lmdbjavaValBuf;

    @Setup(Level.Trial)
    public void setupTrial() {
        keys = BenchData.keys(num);
        value = BenchData.value();
        mapSize = BenchData.mapSize(num, BenchData.VALUE_SIZE);
    }

    @Setup(Level.Invocation)
    public void setupInvocation() throws Exception {
        lmdbJavaDir = BenchSupport.tempDir("lmdb-java-write");
        env = LmdbEnv.create().mapSize(mapSize).maxDatabases(1)
                .open(lmdbJavaDir, EnumSet.of(LmdbEnvFlag.WRITEMAP, LmdbEnvFlag.NOSYNC));
        try (LmdbTxn txn = env.beginTxn()) {
            dbi = txn.openDatabase(EnumSet.of(LmdbDbiFlag.CREATE));
            txn.commit();
        }

        lmdbjavaDir = BenchSupport.tempDir("lmdbjava-write");
        lmdbjavaEnv = Env.create().setMapSize(mapSize).setMaxDbs(1)
                .open(lmdbjavaDir.toFile(), MDB_WRITEMAP, MDB_NOSYNC);
        lmdbjavaDbi = lmdbjavaEnv.openDbi("bench", MDB_CREATE);
        lmdbjavaKeyBuf = ByteBuffer.allocateDirect(BenchData.KEY_SIZE);
        lmdbjavaValBuf = ByteBuffer.allocateDirect(BenchData.VALUE_SIZE);
    }

    @TearDown(Level.Invocation)
    public void tearDownInvocation() throws Exception {
        env.close();
        BenchSupport.deleteRecursively(lmdbJavaDir);

        lmdbjavaEnv.close();
        BenchSupport.deleteRecursively(lmdbjavaDir);
    }

    @Benchmark
    public void ffmWriteBytes() {
        try (LmdbTxn txn = env.beginTxn()) {
            try (LmdbCursor cursor = txn.openCursor(dbi)) {
                for (byte[] k : keys) {
                    cursor.put(k, value, EnumSet.of(LmdbWriteFlag.APPEND));
                }
            }
            txn.commit();
        }
    }

    @Benchmark
    public void ffmWriteSegment() {
        try (Arena arena = Arena.ofConfined(); LmdbTxn txn = env.beginTxn()) {
            MemorySegment valSeg = arena.allocate(value.length);
            MemorySegment.copy(value, 0, valSeg, JAVA_BYTE, 0, value.length);
            try (LmdbCursor cursor = txn.openCursor(dbi)) {
                for (byte[] k : keys) {
                    MemorySegment keySeg = arena.allocate(k.length);
                    MemorySegment.copy(k, 0, keySeg, JAVA_BYTE, 0, k.length);
                    cursor.put(keySeg, valSeg, EnumSet.of(LmdbWriteFlag.APPEND));
                }
            }
            txn.commit();
        }
    }

    @Benchmark
    public void lmdbjava() {
        try (Txn<ByteBuffer> txn = lmdbjavaEnv.txnWrite()) {
            try (Cursor<ByteBuffer> c = lmdbjavaDbi.openCursor(txn)) {
                for (byte[] k : keys) {
                    lmdbjavaKeyBuf.clear();
                    lmdbjavaKeyBuf.put(k).flip();
                    lmdbjavaValBuf.clear();
                    lmdbjavaValBuf.put(value).flip();
                    c.put(lmdbjavaKeyBuf, lmdbjavaValBuf, MDB_APPEND);
                }
            }
            txn.commit();
        }
    }
}
