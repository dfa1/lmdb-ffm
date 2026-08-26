package io.github.dfa1.lmdb.bench;

import io.github.dfa1.lmdb.LmdbDbi;
import io.github.dfa1.lmdb.LmdbDbiFlag;
import io.github.dfa1.lmdb.LmdbEnv;
import io.github.dfa1.lmdb.LmdbEnvFlag;
import io.github.dfa1.lmdb.LmdbTxn;
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
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static org.lmdbjava.DbiFlags.MDB_CREATE;
import static org.lmdbjava.EnvFlags.MDB_NOSYNC;

/// Write throughput: this project (FFM — `byte[]` and zero-copy
/// `MemorySegment`) against lmdbjava (JNR-FFI).
///
/// Each `@Benchmark` invocation is a batch of [#BATCH] puts committed as one
/// transaction, not a single put: LMDB commits (`mdb_txn_commit`) dominate a
/// single-put-per-commit benchmark, drowning out the JVM-to-native call
/// overhead this is meant to isolate. Every variant here does the identical
/// amount of native work per invocation (`BATCH` puts, one commit), so
/// throughput numbers are directly comparable — "ops/ms" means
/// "batches/ms", not "puts/ms".
///
/// Both environments open with `NOSYNC` (both bindings, identically) so
/// `fsync` latency and variance don't swamp the binding-overhead comparison
/// this benchmark exists to measure. The batch keys are drawn from a fixed
/// pool and wrap around, so this is a steady-state overwrite workload with a
/// bounded map size, not an ever-growing insert.
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
@Fork(value = 1, jvmArgsAppend = {
    "--enable-native-access=ALL-UNNAMED",
    "--add-opens=java.base/java.nio=ALL-UNNAMED",
    "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED"
})
@Warmup(iterations = 3)
@Measurement(iterations = 5)
public class PutBenchmark {

    private static final int BATCH = 100;
    private static final int POOL_SIZE = 100_000;

    private byte[][] keys;
    private byte[] value;
    private final AtomicInteger cursor = new AtomicInteger();

    private Path lmdbJavaDir;
    private LmdbEnv env;
    private LmdbDbi dbi;

    private Path lmdbjavaDir;
    private Env<ByteBuffer> lmdbjavaEnv;
    private Dbi<ByteBuffer> lmdbjavaDbi;
    private ByteBuffer lmdbjavaKeyBuf;
    private ByteBuffer lmdbjavaValBuf;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        keys = BenchData.keys(POOL_SIZE);
        value = BenchData.value();
        long mapSize = (long) POOL_SIZE * 4096 + (64L << 20);

        lmdbJavaDir = BenchSupport.tempDir("lmdb-java-put");
        env = LmdbEnv.create().mapSize(mapSize).maxDatabases(1)
                .open(lmdbJavaDir, EnumSet.of(LmdbEnvFlag.NOSYNC));
        try (LmdbTxn txn = env.beginTxn()) {
            dbi = txn.openDatabase(EnumSet.of(LmdbDbiFlag.CREATE));
            txn.commit();
        }

        lmdbjavaDir = BenchSupport.tempDir("lmdbjava-put");
        lmdbjavaEnv = Env.create().setMapSize(mapSize).setMaxDbs(1).open(lmdbjavaDir.toFile(), MDB_NOSYNC);
        lmdbjavaDbi = lmdbjavaEnv.openDbi("bench", MDB_CREATE);
        lmdbjavaKeyBuf = ByteBuffer.allocateDirect(BenchData.KEY_SIZE);
        lmdbjavaValBuf = ByteBuffer.allocateDirect(BenchData.VALUE_SIZE);
    }

    @TearDown(Level.Trial)
    public void tearDown() throws Exception {
        env.close();
        BenchSupport.deleteRecursively(lmdbJavaDir);

        lmdbjavaEnv.close();
        BenchSupport.deleteRecursively(lmdbjavaDir);
    }

    private int batchStart() {
        return Math.floorMod(cursor.getAndAdd(BATCH), keys.length);
    }

    @Benchmark
    public void lmdbJavaBytes() {
        int start = batchStart();
        try (LmdbTxn txn = env.beginTxn()) {
            for (int i = 0; i < BATCH; i++) {
                txn.put(dbi, keys[(start + i) % keys.length], value, Set.of());
            }
            txn.commit();
        }
    }

    @Benchmark
    public void lmdbJavaSegment() {
        int start = batchStart();
        try (Arena arena = Arena.ofConfined(); LmdbTxn txn = env.beginTxn()) {
            MemorySegment valSeg = arena.allocate(value.length);
            MemorySegment.copy(value, 0, valSeg, JAVA_BYTE, 0, value.length);
            for (int i = 0; i < BATCH; i++) {
                byte[] k = keys[(start + i) % keys.length];
                MemorySegment keySeg = arena.allocate(k.length);
                MemorySegment.copy(k, 0, keySeg, JAVA_BYTE, 0, k.length);
                txn.put(dbi, keySeg, valSeg, Set.of());
            }
            txn.commit();
        }
    }

    @Benchmark
    public void lmdbjava() {
        int start = batchStart();
        try (Txn<ByteBuffer> txn = lmdbjavaEnv.txnWrite()) {
            for (int i = 0; i < BATCH; i++) {
                byte[] k = keys[(start + i) % keys.length];
                lmdbjavaKeyBuf.clear();
                lmdbjavaKeyBuf.put(k).flip();
                lmdbjavaValBuf.clear();
                lmdbjavaValBuf.put(value).flip();
                lmdbjavaDbi.put(txn, lmdbjavaKeyBuf, lmdbjavaValBuf);
            }
            txn.commit();
        }
    }
}
