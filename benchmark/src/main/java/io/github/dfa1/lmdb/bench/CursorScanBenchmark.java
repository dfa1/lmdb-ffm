package io.github.dfa1.lmdb.bench;

import io.github.dfa1.lmdb.LmdbCursor;
import io.github.dfa1.lmdb.LmdbCursorOp;
import io.github.dfa1.lmdb.LmdbDbi;
import io.github.dfa1.lmdb.LmdbDbiFlag;
import io.github.dfa1.lmdb.LmdbEnv;
import io.github.dfa1.lmdb.LmdbEnvFlag;
import io.github.dfa1.lmdb.LmdbTxn;
import org.lmdbjava.CursorIterable;
import org.lmdbjava.CursorIterable.KeyVal;
import org.lmdbjava.Dbi;
import org.lmdbjava.Env;
import org.lmdbjava.KeyRange;
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

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.lmdbjava.DbiFlags.MDB_CREATE;

/// Full-table cursor scan throughput: this project (FFM) against lmdbjava
/// (JNR-FFI). Each invocation opens a fresh read transaction and cursor,
/// walks every entry in key order, and sums the value lengths (forcing the
/// JIT to actually touch each value rather than folding the loop away) —
/// the natural unit of a "scan everything" operation, so both variants pay
/// an identical `mdb_txn_begin`/cursor-open/`_commit` overhead per call and
/// only the per-entry step cost differs.
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
@Fork(value = 1, jvmArgsAppend = {
    "--enable-native-access=ALL-UNNAMED",
    "--add-opens=java.base/java.nio=ALL-UNNAMED",
    "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED"
})
@Warmup(iterations = 3)
@Measurement(iterations = 5)
public class CursorScanBenchmark {

    @Param({"1000", "100000"})
    private int entries;

    private Path lmdbJavaDir;
    private LmdbEnv env;
    private LmdbDbi dbi;

    private Path lmdbjavaDir;
    private Env<ByteBuffer> lmdbjavaEnv;
    private Dbi<ByteBuffer> lmdbjavaDbi;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        byte[][] keys = BenchData.keys(entries);
        byte[] value = BenchData.value();
        long mapSize = (long) entries * 4096 + (64L << 20);

        lmdbJavaDir = BenchSupport.tempDir("lmdb-java-scan");
        env = LmdbEnv.create().mapSize(mapSize).maxDatabases(1).open(lmdbJavaDir, Set.of());
        try (LmdbTxn txn = env.beginTxn()) {
            dbi = txn.openDatabase(EnumSet.of(LmdbDbiFlag.CREATE));
            for (byte[] k : keys) {
                txn.put(dbi, k, value, Set.of());
            }
            txn.commit();
        }

        lmdbjavaDir = BenchSupport.tempDir("lmdbjava-scan");
        lmdbjavaEnv = Env.create().setMapSize(mapSize).setMaxDbs(1).open(lmdbjavaDir.toFile());
        lmdbjavaDbi = lmdbjavaEnv.openDbi("bench", MDB_CREATE);
        ByteBuffer kb = ByteBuffer.allocateDirect(BenchData.KEY_SIZE);
        ByteBuffer vb = ByteBuffer.allocateDirect(BenchData.VALUE_SIZE);
        try (Txn<ByteBuffer> txn = lmdbjavaEnv.txnWrite()) {
            for (byte[] k : keys) {
                kb.clear();
                kb.put(k).flip();
                vb.clear();
                vb.put(value).flip();
                lmdbjavaDbi.put(txn, kb, vb);
            }
            txn.commit();
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() throws Exception {
        env.close();
        BenchSupport.deleteRecursively(lmdbJavaDir);

        lmdbjavaEnv.close();
        BenchSupport.deleteRecursively(lmdbjavaDir);
    }

    @Benchmark
    public long lmdbJavaCursor() {
        long sum = 0;
        try (LmdbTxn txn = env.beginTxn(EnumSet.of(LmdbEnvFlag.RDONLY));
                LmdbCursor cursor = txn.openCursor(dbi)) {
            for (Optional<LmdbCursor.Entry> e = cursor.get(LmdbCursorOp.FIRST);
                    e.isPresent();
                    e = cursor.get(LmdbCursorOp.NEXT)) {
                sum += e.orElseThrow().data().byteSize();
            }
        }
        return sum;
    }

    @Benchmark
    public long lmdbjava() {
        long sum = 0;
        try (Txn<ByteBuffer> txn = lmdbjavaEnv.txnRead();
                CursorIterable<ByteBuffer> ci = lmdbjavaDbi.iterate(txn, KeyRange.all())) {
            for (KeyVal<ByteBuffer> kv : ci) {
                sum += kv.val().remaining();
            }
        }
        return sum;
    }
}
