# lmdb-java benchmarks

JMH microbenchmarks comparing **lmdb-java** against the established JVM LMDB binding:

| Contestant | Binding | Modes benchmarked |
|------------|---------|-------------------|
| **lmdb-java** (this project) | FFM (no JNI) | `byte[]`, zero-copy `MemorySegment`, and `Mapper` |
| **lmdbjava** (`org.lmdbjava:lmdbjava`) | JNR-FFI | direct `ByteBuffer` |

Both bindings wrap the same LMDB C library and operate on identically-populated
databases, so any difference is JVM-to-native call overhead, not LMDB itself.

lmdbjava is **not** JNA, despite how it's sometimes described — its `pom.xml`
depends on `com.github.jnr:jnr-ffi`. It bundles native `liblmdb` directly
inside its one jar for `linux-x86_64`, `linux-aarch64`, `osx-x86_64`,
`osx-aarch64`, and `windows-x86_64` — every classifier this project ships
except `windows-aarch64`. On that one host the `lmdbjava` benchmark method
fails fast with `UnsatisfiedLinkError` at `@Setup`; the `lmdbJava*` (this
project's) benchmark methods are unaffected and still run.

Three suites:

- `GetBenchmark` — random-key point lookups against a pre-populated database,
  1,000 / 100,000 entries. One long-lived read transaction is reused across
  invocations (the idiomatic LMDB pattern both projects document), so this
  isolates the `mdb_get` call path itself, not transaction setup.
- `PutBenchmark` — write throughput. Each invocation is a batch of 100 puts
  committed as one transaction (a single-put-per-commit benchmark is
  dominated by `mdb_txn_commit`, not the call-path overhead this exists to
  measure); both environments open with `MDB_NOSYNC` so `fsync` latency
  doesn't swamp the comparison either. Keys are drawn from a fixed
  100,000-key pool and wrap around — a steady-state overwrite workload with a
  bounded map size, not an ever-growing insert.
- `CursorScanBenchmark` — full-table cursor scan, 1,000 / 100,000 entries.
  Each invocation opens a fresh read transaction and cursor and walks every
  entry in key order, summing value lengths so the JIT can't fold the loop
  away.

Keys are fixed-width, zero-padded 16-byte strings; values are a fixed 100
bytes (`BenchData`) — sized like a small OLTP row, not a bulk payload.

## Build

```bash
./mvnw -q -pl benchmark -am package -DskipTests
```

Produces a self-contained `benchmark/target/benchmarks.jar`. The host's native
`liblmdb` jar is pulled in automatically by the platform profile.

## Run

```bash
# everything (full warmup/measurement — takes several minutes)
java -jar benchmark/target/benchmarks.jar

# one suite, one size
java -jar benchmark/target/benchmarks.jar GetBenchmark -p entries=100000

# quick smoke run
java -jar benchmark/target/benchmarks.jar -f 1 -wi 1 -i 3 -p entries=1000
```

`--enable-native-access=ALL-UNNAMED` and the `--add-opens` flags lmdbjava
needs for direct-`ByteBuffer` reflection are applied to forked JVMs via
`@Fork`, so no extra flags are needed on the command line.

## Reading results

Throughput is reported as ops/µs or ops/ms (higher is better; see each
class's `@OutputTimeUnit`). Compare rows at the same `(entries)`:

```
Benchmark                       (entries)   Mode  Cnt   Score   Units
GetBenchmark.lmdbJavaSegment          1000  thrpt    3   ...    ops/us
GetBenchmark.lmdbJavaMapper           1000  thrpt    3   ...    ops/us
GetBenchmark.lmdbJavaBytes            1000  thrpt    3   ...    ops/us
GetBenchmark.lmdbjava                 1000  thrpt    3   ...    ops/us
```

Microbenchmark numbers are machine-specific; rebuild and run on the target
host. Heed JMH's own caveat printed after each run.

## Results

Apple M5 (`osx-aarch64`), JDK 25.0.2, `-f 1 -wi 2 -i 5` (5 measurement
iterations, 10 s each). lmdbjava does bundle a native for `osx-aarch64` (see
above), so this is a real head-to-head, not a fallback comparison.

#### CursorScanBenchmark (ops/ms, higher is better)

| entries | lmdb-java `lmdbJavaCursor` | lmdbjava |
|---|---:|---:|
| 1,000 | **77.676 ± 2.652** | 52.142 ± 1.240 |
| 100,000 | **0.763 ± 0.013** | 0.560 ± 0.007 |

lmdb-java is ~35–49% faster. This is the suite that originally caught a real
bug — `LmdbCursor#get(LmdbCursorOp)` was allocating a fresh `Arena` and two
`MDB_val` structs on every call, ~300x slower than lmdbjava before the fix
(see the git history for `LmdbCursor.java`). These numbers are post-fix.

#### GetBenchmark (ops/µs, higher is better)

| entries | `lmdbJavaBytes` | `lmdbJavaMapper` | `lmdbJavaSegment` | lmdbjava |
|---|---:|---:|---:|---:|
| 1,000 | 6.285 ± 0.278 | **7.169 ± 0.061** | 6.335 ± 0.059 | 6.233 ± 0.012 |
| 100,000 | **4.773 ± 0.077** | 4.786 ± 0.017 | 4.310 ± 0.033 | 4.134 ± 0.043 |

All three lmdb-java paths match or edge out lmdbjava; the zero-copy `Mapper`
path is the standout at small scale, with no `byte[]`/`MemorySegment` left
over to manage.

#### PutBenchmark (ops/µs of 100-put batches, higher is better)

| `lmdbJavaBytes` | `lmdbJavaSegment` | lmdbjava |
|---:|---:|---:|
| 0.032 ± 0.002 | **0.034 ± 0.001** | 0.029 ± 0.001 |

lmdb-java is ~10–17% faster.

Regenerate with:

```bash
java -jar benchmark/target/benchmarks.jar '\.GetBenchmark\.' '\.PutBenchmark\.' '\.CursorScanBenchmark\.' \
    -f 1 -wi 2 -i 5 -rf json -rff results.json
python3 .github/scripts/format-results.py results.json
```
