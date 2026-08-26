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
  - `readKey` — a point lookup of *every* key, one per invocation. Cursor
    `SET`/`MDB_SET_KEY` (`ffmReadKey`/`lmdbjavaReadKey`) is the same call path
    lmdbjava's own `readKey` benchmarks; `ffmGetSegment`/`lmdbjavaGet` is a
    second, additional shape — `mdb_get`/`Dbi#get` directly, no cursor. Every
    `ffm*` point lookup has a `byte[]`-key and a zero-copy `...Segment`
    (`MemorySegment`-key) variant: `ffmReadKey`/`ffmReadKeySegment`,
    `ffmReadKeyMapper`/`ffmReadKeySegmentMapper`, `ffmGetSegment`/
    `ffmGetSegmentKeySegment`. `ffmReadKeyMapper`/`ffmReadKeySegmentMapper`
    exercise this project's zero-copy
    [`Mapper`](../lmdb/src/main/java/io/github/dfa1/lmdb/Mapper.java) read;
    lmdbjava has no counterpart for those two.
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

| num | `ffmReadKey` | `ffmReadKeySegment` | `ffmReadKeyMapper` | `ffmReadKeySegmentMapper` | `ffmGetSegment` | `ffmGetSegmentKeySegment` | `ffmReadRev` | `ffmReadSeq` | `lmdbjavaReadKey` | `lmdbjavaGet` | `lmdbjavaReadRev` | `lmdbjavaReadSeq` |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 1,000 | 0.074 ± 0.000 | 0.073 ± 0.000 | 0.092 ± 0.000 | 0.085 ± 0.000 | 0.093 ± 0.000 | 0.092 ± 0.000 | 0.015 ± 0.000 | 0.015 ± 0.000 | **0.072 ± 0.000** | 0.158 ± 0.000 | **0.010 ± 0.000** | **0.010 ± 0.000** |
| 1,000,000 | 79.746 ± 0.050 | 76.548 ± 0.054 | 166.073 ± 0.228 | 169.650 ± 0.118 | 168.800 ± 0.418 | 171.873 ± 0.187 | 15.583 ± 0.030 | 18.137 ± 0.047 | 76.843 ± 0.078 | 257.076 ± 1.698 | **10.630 ± 0.017** | 15.844 ± 0.169 |

Two call paths, not one, and the split matters more than `byte[]` vs
`MemorySegment` key does:

- **Cursor `SET`, one cursor reused across all 1,000,000 lookups**
  (`ffmReadKey`/`ffmReadKeySegment`, `lmdbjavaReadKey`): ~77–80ms. This is
  the path both bindings' own official benchmarks measure, and the one this
  project's earlier per-call-`Arena` bug (fixed in a prior commit) used to
  cost ~1.7x on. `ffmReadKeySegment`'s zero-copy key edges out
  `ffmReadKey`'s `byte[]` key slightly, as expected.
- **Direct `mdb_get`/`Dbi#get`, no cursor** (`ffmGetSegment`/
  `ffmGetSegmentKeySegment`/`ffmReadKeyMapper`/`ffmReadKeySegmentMapper`,
  `lmdbjavaGet`): ~166–257ms, over **2x slower in both bindings** for this
  sequential-key workload. `mdb_get` opens and discards an internal cursor
  on every call, so it can never benefit from the position locality a
  reused, explicit cursor gets from ascending keys the way `MDB_SET` does —
  an LMDB characteristic, not an artifact of either binding's FFI layer.
  lmdbjava pays this penalty far more sharply (257ms, ~3.3x its own
  `readKey`) than this project does (~167–172ms, ~2.1x `ffmReadKey`) — this
  project's direct-`get` path is ~35% faster than lmdbjava's equivalent.
- Within the direct-`mdb_get` cluster, `ffmGetSegment` (no
  [`Mapper`](../lmdb/src/main/java/io/github/dfa1/lmdb/Mapper.java) at all)
  sits right alongside `ffmReadKeyMapper` (168.8ms vs 166.1ms) — correcting
  an earlier, incomplete read of this same data that attributed
  `ffmReadKeyMapper`'s gap over `ffmReadKey` to `Mapper`'s one small
  per-call scoping `Arena`. That arena's actual cost is negligible (a few ms
  over 1,000,000 calls, not tens); the real driver is the call path
  (`mdb_get` vs cursor `SET`), and `ffmGetSegment` — which has no `Mapper`
  and no scoping arena at all — isolates that.

#### WriteBenchmark

| num | `ffmWriteBytes` | `ffmWriteSegment` | `lmdbjava` |
|---|---:|---:|---:|
| 1,000 | 0.108 ± 0.000 | 0.104 ± 0.000 | 0.111 ± 0.000 |
| 1,000,000 | 112.777 ± 1.290 | 103.312 ± 1.716 | 110.008 ± 1.479 |

Unaffected by the read-path work above (write paths weren't touched). All
three track closely at both sizes, run to run — the ~15–20% lmdbjava lead
seen in an earlier run of this same suite didn't reproduce here, i.e. it was
noise, not a real, repeatable gap.

Regenerate with:

```bash
java -jar benchmark/target/benchmarks.jar '\.ReadBenchmark\.' '\.WriteBenchmark\.' \
    -rf json -rff results.json
python3 .github/scripts/format-results.py results.json
```
