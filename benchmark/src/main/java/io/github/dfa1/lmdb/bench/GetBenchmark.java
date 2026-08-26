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
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.lmdbjava.DbiFlags.MDB_CREATE;

/// Random-key point-lookup throughput: this project (FFM — `byte[]`,
/// zero-copy `MemorySegment`, and `Mapper`) against lmdbjava (JNR-FFI).
///
/// Both bindings wrap the same LMDB C library and read the same pre-populated
/// database, so any difference is JVM-to-native call overhead, not LMDB
/// itself. lmdbjava bundles native liblmdb for every classifier this project
/// supports except windows-aarch64 — see README.md — so on that one host the
/// `lmdbjava` benchmark fails fast with `UnsatisfiedLinkError`; the
/// `lmdbJava*` ones are unaffected.
///
/// One read transaction is opened per trial and reused for every invocation
/// (renewed once per iteration) rather than begun fresh per call, matching
/// the typical "long-lived reader" LMDB idiom both projects document — a
/// fresh-txn-per-get variant would mostly measure `mdb_txn_begin`/`_commit`,
/// not the get path itself.
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
public class GetBenchmark {

    @Param({"1000", "100000"})
    private int entries;

    private byte[][] keys;
    private final AtomicInteger cursor = new AtomicInteger();

    private Path lmdbJavaDir;
    private LmdbEnv env;
    private LmdbDbi dbi;
    private LmdbTxn readTxn;

    private Path lmdbjavaDir;
    private Env<ByteBuffer> lmdbjavaEnv;
    private Dbi<ByteBuffer> lmdbjavaDbi;
    private Txn<ByteBuffer> lmdbjavaReadTxn;
    private ByteBuffer lmdbjavaKeyBuf;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        keys = BenchData.keys(entries);
        byte[] value = BenchData.value();
        long mapSize = (long) entries * 4096 + (64L << 20);

        lmdbJavaDir = BenchSupport.tempDir("lmdb-java-get");
        env = LmdbEnv.create().mapSize(mapSize).maxDatabases(1).open(lmdbJavaDir, Set.of());
        try (LmdbTxn txn = env.beginTxn()) {
            dbi = txn.openDatabase(EnumSet.of(LmdbDbiFlag.CREATE));
            for (byte[] k : keys) {
                txn.put(dbi, k, value, Set.of());
            }
            txn.commit();
        }
        readTxn = env.beginTxn(EnumSet.of(LmdbEnvFlag.RDONLY));

        lmdbjavaDir = BenchSupport.tempDir("lmdbjava-get");
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
        lmdbjavaReadTxn = lmdbjavaEnv.txnRead();
        lmdbjavaKeyBuf = ByteBuffer.allocateDirect(BenchData.KEY_SIZE);
    }

    @TearDown(Level.Trial)
    public void tearDown() throws Exception {
        readTxn.close();
        env.close();
        BenchSupport.deleteRecursively(lmdbJavaDir);

        lmdbjavaReadTxn.close();
        lmdbjavaEnv.close();
        BenchSupport.deleteRecursively(lmdbjavaDir);
    }

    private byte[] nextKey() {
        return keys[Math.floorMod(cursor.getAndIncrement(), keys.length)];
    }

    @Benchmark
    public Optional<byte[]> lmdbJavaBytes() {
        return readTxn.get(dbi, nextKey());
    }

    @Benchmark
    public Optional<MemorySegment> lmdbJavaSegment() {
        return readTxn.getSegment(dbi, nextKey());
    }

    @Benchmark
    public Optional<Long> lmdbJavaMapper() {
        return readTxn.get(dbi, nextKey(), MemorySegment::byteSize);
    }

    @Benchmark
    public ByteBuffer lmdbjava() {
        lmdbjavaKeyBuf.clear();
        lmdbjavaKeyBuf.put(nextKey()).flip();
        return lmdbjavaDbi.get(lmdbjavaReadTxn, lmdbjavaKeyBuf);
    }
}
