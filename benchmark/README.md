# lmdb-java benchmarks

JMH microbenchmarks comparing **lmdb-java** against the established JVM LMDB binding:

| Contestant | Binding | Modes benchmarked |
|------------|---------|-------------------|
| **lmdb-java** (this project) | FFM (no JNI) | `byte[]`, zero-copy `MemorySegment`, and `Mapper` |
| **lmdbjava** (`org.lmdbjava:lmdbjava`) | JNR-FFI | direct `ByteBuffer` |

Both bindings wrap the same LMDB C library and operate on identically-shaped
databases, so any difference is JVM-to-native call overhead, not LMDB itself.

lmdbjava is **not** JNA, despite how it's sometimes described — its `pom.xml`
depends on `com.github.jnr:jnr-ffi`. It bundles native `liblmdb` directly
inside its one jar for `linux-x86_64`, `linux-aarch64`, `osx-x86_64`,
`osx-aarch64`, and `windows-x86_64` — every classifier this project ships
except `windows-aarch64`. On that one host the `lmdbjava` benchmark method
fails fast with `UnsatisfiedLinkError` at `@Setup`; this project's `ffm*`
benchmark methods are unaffected and still run.

The suite is deliberately shaped to match lmdbjava's own official benchmarks
(github.com/lmdbjava/benchmarks — `Common`, `CommonLmdbJava`,
`LmdbJavaByteBuffer`) rather than an independently-invented methodology, so
results are comparable to numbers published elsewhere for that project:
`SampleTime` (not `Throughput`), a `num` dataset-size param defaulting to
lmdbjava's own `1_000_000`, sequential zero-padded 16-byte string keys, a
fixed 100-byte value, `MDB_WRITEMAP`+`MDB_NOSYNC` on both environments, and
the same `mapSize = num * valSize * 32 / 10` formula.

Two suites:

- `ReadBenchmark` — one full-database pass per `@Benchmark` invocation
  against a pre-populated, pre-opened environment (state built once per
  trial, matching lmdbjava's own `Reader`):
  - `readSeq` / `readRev` — a forward/backward cursor scan touching only the
    value.
  - `readKey` — a point lookup of *every* key, one per invocation, via a
    cursor positioned with `SET`/`MDB_SET_KEY` (`ffmReadKey`/`lmdbjavaReadKey`)
    — the same call path lmdbjava's own `readKey` benchmarks. `ffmReadKeyMapper`
    is this project's zero-copy [`Mapper`](../lmdb/src/main/java/io/github/dfa1/lmdb/Mapper.java)
    read; it has no lmdbjava counterpart.
- `WriteBenchmark` — one `@Benchmark` invocation writes the *entire*
  `num`-entry dataset in a single transaction and cursor, with `MDB_APPEND`
  (both bindings insert strictly ascending keys). `@Setup`/`@TearDown` run at
  `Level.Invocation`, so every invocation gets a brand-new, empty
  environment — required because `MDB_APPEND` needs strictly increasing
  keys, matching lmdbjava's own `Writer` state exactly.

Keys are fixed-width, zero-padded 16-byte strings; values are a fixed 100
bytes (`BenchData`) — lmdbjava's own defaults.

## Build

```bash
./mvnw -q -pl benchmark -am package -DskipTests
```

Produces a self-contained `benchmark/target/benchmarks.jar`. The host's native
`liblmdb` jar is pulled in automatically by the platform profile.

## Run

```bash
# everything (num=1,000 and num=1,000,000 — the 1,000,000 WriteBenchmark
# invocations recreate the environment every single invocation, so this
# takes a while)
java -jar benchmark/target/benchmarks.jar

# one suite, one size
java -jar benchmark/target/benchmarks.jar ReadBenchmark -p num=1000

# quick smoke run
java -jar benchmark/target/benchmarks.jar -wi 1 -i 3 -p num=1000
```

`-f`/`-wi`/`-i` default to each class's own `@Fork(1)`/`@Warmup(3)`/
`@Measurement(3)`; `--enable-native-access=ALL-UNNAMED` and the `--add-opens`
flags lmdbjava needs for direct-`ByteBuffer` reflection are applied to forked
JVMs via `@Fork`, so no extra flags are needed on the command line.

## Reading results

`SampleTime` reports individual invocation latency in milliseconds (lower is
better; see each class's `@OutputTimeUnit`) — not an ops/time rate, since one
invocation is "scan/write `num` entries", not a small fixed unit of work.
Compare rows at the same `(num)`:

```
Benchmark                      (num)  Mode  Cnt    Score   Error  Units
ReadBenchmark.ffmReadSeq         1000  sample  ...    ...     ...     ms/op
ReadBenchmark.lmdbjavaReadSeq    1000  sample  ...    ...     ...     ms/op
```

Microbenchmark numbers are machine-specific; rebuild and run on the target
host. Heed JMH's own caveat printed after each run.

## Results

Apple M5 (`osx-aarch64`), JDK 25.0.2, default `@Fork(1)`/`@Warmup(3)`/
`@Measurement(3)` (10 s each). Lower is better (`SampleTime`, ms/op).

#### ReadBenchmark

| num | `ffmReadKey` | `ffmReadKeyMapper` | `ffmReadRev` | `ffmReadSeq` | `lmdbjavaReadKey` | `lmdbjavaReadRev` | `lmdbjavaReadSeq` |
|---|---:|---:|---:|---:|---:|---:|---:|
| 1,000 | 0.075 ± 0.000 | 0.092 ± 0.000 | 0.014 ± 0.000 | 0.014 ± 0.000 | **0.072 ± 0.000** | **0.010 ± 0.000** | **0.010 ± 0.000** |
| 1,000,000 | 78.442 ± 0.080 | 164.685 ± 0.133 | 15.723 ± 0.034 | 17.062 ± 0.044 | 78.200 ± 0.078 | **10.478 ± 0.016** | **11.963 ± 0.024** |

`ffmReadKey` now matches lmdbjava almost exactly. It didn't originally: an
earlier run of this same benchmark caught `LmdbCursor#get(LmdbCursorOp, byte[])`
and `LmdbTxn#getSegment`/`get`/`get(..., Mapper)` opening a fresh
`Arena.ofConfined()` (plus 2–3 native allocations and a `memcpy`) on *every
call* — the same anti-pattern already fixed once for the no-key
`readSeq`/`readRev` path, just never carried over to the keyed one. Before
the fix, `ffmReadKey` was 134.739 ms/op at 1,000,000 (~1.7x slower than
lmdbjava); reusing persistent out-param slots and a growable key buffer
(`LmdbVal.growBuffer`) closed nearly the entire gap. `ffmReadKeyMapper`
improved by the same amount but remains genuinely slower than `ffmReadKey`
by design: its zero-copy [`Mapper`](../lmdb/src/main/java/io/github/dfa1/lmdb/Mapper.java)
callback still pays one small per-call `Arena` to guarantee the value view
becomes inaccessible the instant the call returns — see `CLAUDE.md`'s code
conventions section for why that one is intentionally not reused across
calls.

#### WriteBenchmark

| num | `ffmWriteBytes` | `ffmWriteSegment` | `lmdbjava` |
|---|---:|---:|---:|
| 1,000 | 0.128 ± 0.000 | 0.099 ± 0.000 | 0.108 ± 0.001 |
| 1,000,000 | 119.254 ± 1.310 | 105.604 ± 1.351 | **78.933 ± 1.771** |

Unaffected by the read-path fix above (write paths weren't touched). Within
noise at 1,000; lmdbjava pulls ahead at 1,000,000 — the write path wasn't
root-caused as part of this investigation and remains an open question, not
yet a diagnosed bottleneck the way the read path was.

Regenerate with:

```bash
java -jar benchmark/target/benchmarks.jar '\.ReadBenchmark\.' '\.WriteBenchmark\.' \
    -rf json -rff results.json
python3 .github/scripts/format-results.py results.json
```
