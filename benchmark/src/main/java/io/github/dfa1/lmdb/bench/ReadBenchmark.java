package io.github.dfa1.lmdb.bench;

import io.github.dfa1.lmdb.LmdbCursor;
import io.github.dfa1.lmdb.LmdbCursorOp;
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
import org.openjdk.jmh.infra.Blackhole;

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
import static org.lmdbjava.GetOp.MDB_SET_KEY;
import static org.lmdbjava.PutFlags.MDB_APPEND;
import static org.lmdbjava.SeekOp.MDB_FIRST;
import static org.lmdbjava.SeekOp.MDB_LAST;
import static org.lmdbjava.SeekOp.MDB_NEXT;
import static org.lmdbjava.SeekOp.MDB_PREV;

/// Read throughput: this project (FFM) against lmdbjava (JNR-FFI), shaped to
/// match lmdbjava's own benchmark suite
/// (github.com/lmdbjava/benchmarks — `LmdbJavaByteBuffer`) so results are
/// directly comparable to its published methodology:
///
/// - `readSeq`/`readRev`: one forward/backward cursor scan of the whole
///   database *per invocation*, touching only the value (`SeekOp.MDB_FIRST`/
///   `MDB_NEXT`/`MDB_LAST`/`MDB_PREV` there; [LmdbCursorOp#FIRST]/[#NEXT]/
///   [#LAST]/[#PREV] here).
/// - `readKey`: one point lookup of *every* key per invocation, via a cursor
///   positioned with `SET`/`MDB_SET_KEY` — that is the exact call path
///   lmdbjava's own `readKey` benchmarks. `get`/`getSegment` (`mdb_get`
///   directly, no cursor) is a second, additional point-lookup shape this
///   project also supports; `lmdbjavaGet` benchmarks lmdbjava's matching
///   `Dbi#get(Txn, T)` for a fair comparison, even though lmdbjava's own
///   official suite doesn't include it.
/// - `SampleTime`/milliseconds, not `Throughput`: lmdbjava reports a
///   full-pass latency distribution, not an ops-per-time rate.
/// - Both environments open with `MDB_WRITEMAP` + `MDB_NOSYNC`, and the
///   database is populated with `MDB_APPEND` (sequential keys) — lmdbjava's
///   own defaults (`CommonLmdbJava.writeMap`, `Common.sequential`).
///
/// Every `ffm*` point lookup below has a `byte[]`-key variant and a
/// `...Segment` (zero-copy [MemorySegment]) key variant, covering this
/// project's full read surface: `ffmReadKey`/`ffmReadKeySegment` (cursor
/// `SET`), `ffmReadKeyMapper`/`ffmReadKeySegmentMapper` (`mdb_get` through a
/// [Mapper][io.github.dfa1.lmdb.Mapper]), and `ffmGetSegment`/
/// `ffmGetSegmentKeySegment` (`mdb_get`, raw — no `Mapper`, no cursor).
/// `lmdbjavaGet` is `ffmGetSegment`'s counterpart, lmdbjava's `Dbi#get(Txn,
/// T)`, even though lmdbjava's own official suite doesn't benchmark it.
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
public class ReadBenchmark {

    /// `1_000_000` matches lmdbjava's own `Common.num` default; `1_000` is
    /// kept as a fast local/CI sanity size.
    @Param({"1000", "1000000"})
    private int num;

    private byte[][] keys;
    // Off-heap mirror of keys, for the *Segment zero-copy-key benchmarks —
    // built once at setup so those benchmarks measure the lookup itself, not
    // a byte[]-to-native copy on every iteration.
    private Arena keySegmentArena;
    private MemorySegment[] keySegments;

    private Path lmdbJavaDir;
    private LmdbEnv env;
    private LmdbDbi dbi;
    private LmdbTxn readTxn;
    private LmdbCursor cursor;

    private Path lmdbjavaDir;
    private Env<ByteBuffer> lmdbjavaEnv;
    private Dbi<ByteBuffer> lmdbjavaDbi;
    private Txn<ByteBuffer> lmdbjavaReadTxn;
    private Cursor<ByteBuffer> lmdbjavaCursor;
    private ByteBuffer lmdbjavaKeyBuf;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        keys = BenchData.keys(num);
        byte[] value = BenchData.value();
        long mapSize = BenchData.mapSize(num, BenchData.VALUE_SIZE);

        keySegmentArena = Arena.ofConfined();
        keySegments = new MemorySegment[num];
        for (int i = 0; i < num; i++) {
            keySegments[i] = keySegmentArena.allocate(keys[i].length);
            MemorySegment.copy(keys[i], 0, keySegments[i], JAVA_BYTE, 0, keys[i].length);
        }

        lmdbJavaDir = BenchSupport.tempDir("lmdb-ffm-read");
        env = LmdbEnv.create().mapSize(mapSize).maxDatabases(1)
                .open(lmdbJavaDir, EnumSet.of(LmdbEnvFlag.WRITEMAP, LmdbEnvFlag.NOSYNC));
        try (LmdbTxn txn = env.beginTxn()) {
            dbi = txn.openDatabase(EnumSet.of(LmdbDbiFlag.CREATE));
            populate(txn, dbi, keys, value);
            txn.commit();
        }
        readTxn = env.beginTxn(EnumSet.of(LmdbEnvFlag.RDONLY));
        cursor = readTxn.openCursor(dbi);

        lmdbjavaDir = BenchSupport.tempDir("lmdbjava-read");
        lmdbjavaEnv = Env.create().setMapSize(mapSize).setMaxDbs(1)
                .open(lmdbjavaDir.toFile(), MDB_WRITEMAP, MDB_NOSYNC);
        lmdbjavaDbi = lmdbjavaEnv.openDbi("bench", MDB_CREATE);
        ByteBuffer kb = ByteBuffer.allocateDirect(BenchData.KEY_SIZE);
        ByteBuffer vb = ByteBuffer.allocateDirect(BenchData.VALUE_SIZE);
        try (Txn<ByteBuffer> txn = lmdbjavaEnv.txnWrite()) {
            try (Cursor<ByteBuffer> c = lmdbjavaDbi.openCursor(txn)) {
                for (byte[] k : keys) {
                    kb.clear();
                    kb.put(k).flip();
                    vb.clear();
                    vb.put(value).flip();
                    c.put(kb, vb, MDB_APPEND);
                }
            }
            txn.commit();
        }
        lmdbjavaReadTxn = lmdbjavaEnv.txnRead();
        lmdbjavaCursor = lmdbjavaDbi.openCursor(lmdbjavaReadTxn);
        lmdbjavaKeyBuf = ByteBuffer.allocateDirect(BenchData.KEY_SIZE);
    }

    private static void populate(LmdbTxn txn, LmdbDbi dbi, byte[][] keys, byte[] value) {
        try (LmdbCursor c = txn.openCursor(dbi)) {
            for (byte[] k : keys) {
                c.put(k, value, EnumSet.of(LmdbWriteFlag.APPEND));
            }
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() throws Exception {
        cursor.close();
        readTxn.close();
        env.close();
        BenchSupport.deleteRecursively(lmdbJavaDir);
        keySegmentArena.close();

        lmdbjavaCursor.close();
        lmdbjavaReadTxn.close();
        lmdbjavaEnv.close();
        BenchSupport.deleteRecursively(lmdbjavaDir);
    }

    @Benchmark
    public void ffmReadSeq(Blackhole bh) {
        for (LmdbCursor.Entry e = cursor.get(LmdbCursorOp.FIRST); e != null; e = cursor.get(LmdbCursorOp.NEXT)) {
            bh.consume(e.data());
        }
    }

    @Benchmark
    public void lmdbjavaReadSeq(Blackhole bh) {
        bh.consume(lmdbjavaCursor.seek(MDB_FIRST));
        do {
            bh.consume(lmdbjavaReadTxn.val());
        } while (lmdbjavaCursor.seek(MDB_NEXT));
    }

    @Benchmark
    public void ffmReadRev(Blackhole bh) {
        for (LmdbCursor.Entry e = cursor.get(LmdbCursorOp.LAST); e != null; e = cursor.get(LmdbCursorOp.PREV)) {
            bh.consume(e.data());
        }
    }

    @Benchmark
    public void lmdbjavaReadRev(Blackhole bh) {
        bh.consume(lmdbjavaCursor.seek(MDB_LAST));
        do {
            bh.consume(lmdbjavaReadTxn.val());
        } while (lmdbjavaCursor.seek(MDB_PREV));
    }

    @Benchmark
    public void ffmReadKey(Blackhole bh) {
        for (byte[] k : keys) {
            bh.consume(cursor.get(LmdbCursorOp.SET, k));
        }
    }

    @Benchmark
    public void ffmReadKeySegment(Blackhole bh) {
        for (MemorySegment k : keySegments) {
            bh.consume(cursor.get(LmdbCursorOp.SET, k));
        }
    }

    @Benchmark
    public void ffmReadKeyMapper(Blackhole bh) {
        for (byte[] k : keys) {
            bh.consume(readTxn.get(dbi, k, MemorySegment::byteSize));
        }
    }

    @Benchmark
    public void ffmReadKeySegmentMapper(Blackhole bh) {
        for (MemorySegment k : keySegments) {
            bh.consume(readTxn.get(dbi, k, MemorySegment::byteSize));
        }
    }

    @Benchmark
    public void ffmGetSegment(Blackhole bh) {
        for (byte[] k : keys) {
            bh.consume(readTxn.getSegment(dbi, k));
        }
    }

    @Benchmark
    public void ffmGetSegmentKeySegment(Blackhole bh) {
        for (MemorySegment k : keySegments) {
            bh.consume(readTxn.getSegment(dbi, k));
        }
    }

    @Benchmark
    public void lmdbjavaReadKey(Blackhole bh) {
        for (byte[] k : keys) {
            lmdbjavaKeyBuf.clear();
            lmdbjavaKeyBuf.put(k).flip();
            bh.consume(lmdbjavaCursor.get(lmdbjavaKeyBuf, MDB_SET_KEY));
            bh.consume(lmdbjavaReadTxn.val());
        }
    }

    @Benchmark
    public void lmdbjavaGet(Blackhole bh) {
        for (byte[] k : keys) {
            lmdbjavaKeyBuf.clear();
            lmdbjavaKeyBuf.put(k).flip();
            bh.consume(lmdbjavaDbi.get(lmdbjavaReadTxn, lmdbjavaKeyBuf));
        }
    }
}
