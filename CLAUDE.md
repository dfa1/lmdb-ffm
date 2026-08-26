# lmdb-ffm

Java **Foreign Function & Memory (FFM)** bindings for [LMDB](https://github.com/LMDB/lmdb).
No JNI, no hand-written C. Targets **JDK 25+** (stable `java.lang.foreign`).

C API reference: [lmdb.tech/doc/group__mdb.html](http://www.lmdb.tech/doc/group__mdb.html)
— the authoritative doc for every `mdb_*` function/flag/struct this project
binds (semantics, parameters, valid flag combinations, return codes). When
adding or reviewing a binding, check the signature and behavior described
there against `third_party/lmdb/libraries/liblmdb/lmdb.h` (the vendored
source of truth for the exact signature at `LMDB_1.0.1`) — the two agree in
practice, but the header is what the linked binary actually implements.

The differentiator is the **zero-copy read path**: `mdb_get`/cursor reads hand
back a `MemorySegment` that points directly into the memory-mapped database —
no `byte[]` copy — valid for the lifetime of the enclosing read transaction.
`LmdbTxn#get(LmdbDbi, byte[], Mapper)` shares that same lifetime rather than a
separate, tighter one — useful when the value just needs parsing into a plain
result right there in the callback, with no separate `byte[]`/`MemorySegment`
to manage afterward.

## Layout

Multi-module Maven build (`io.github.dfa1.lmdb:lmdb-ffm`):

- `lmdb/` — the library module, artifactId `lmdb`, pure-Java FFM bindings
  (package `io.github.dfa1.lmdb`). The only module with Java sources.
- `native/<classifier>/` — one module per platform; each packages a
  `liblmdb.{dylib,so,dll}` built from the `third_party/lmdb` submodule. No
  Java. Classifiers: `osx-aarch64`, `osx-x86_64`, `linux-x86_64`,
  `linux-aarch64`, `windows-x86_64`, `windows-aarch64`.
- `lmdb-platform/` — convenience jar pulling in the bindings plus every
  native classifier.
- `bom/` — dependency BOM.
- `benchmark/` — JMH microbenchmarks against `lmdbjava` (JNR-FFI, not JNA);
  not published, run via `.github/workflows/benchmark.yml` (`workflow_dispatch`)
  or locally — see `benchmark/README.md`.
- `third_party/lmdb` — vendored `LMDB/lmdb` git submodule (the C source of
  truth), pinned to the latest stable tag (currently `LMDB_1.0.1`).

## Native build

`scripts/build-lmdb.sh <output-resources-dir> <classifier>` compiles LMDB's
two source files (`mdb.c`, `midl.c`) directly with **`zig cc`** — no
Makefile. Zig bundles clang + libc for every target, so any host
cross-compiles any of the six classifiers hermetically. The Maven `exec`
plugin runs it in `generate-resources`; it is idempotent (skips if the
library already exists). `<executable>bash</executable>` in each native
module's pom is deliberate: a `.sh` can't run via its shebang on Windows, but
GitHub's `windows-latest` runners ship Git Bash on `PATH`.

LMDB itself branches on `__APPLE__`/`__linux__`/`_WIN32`/etc. inside `mdb.c`
for its locking strategy (POSIX mutexes + robust recovery on Linux, named
POSIX semaphores on Darwin, native `CreateMutex`/`LockFileEx` on Windows — no
pthreads there at all), so the build script does not need to pick that per
platform — it only conditions `-pthread` (POSIX targets only) and, for
Windows, `-Wl,--export-all-symbols`: PE/COFF exports nothing by default
(unlike ELF/Mach-O, which export every non-static global automatically), and
`lmdb.h` has no `__declspec(dllexport)` annotation to opt in per-symbol, so
lld is told to export everything instead. No `-fvisibility=hidden` on any
platform — see the no-`ZSTDLIB_VISIBLE`-equivalent note in the script.

Built `.dylib`/`.so`/`.dll` are git-ignored; they are regenerated from the submodule.

## Code conventions

- Checkstyle-clean (`./mvnw validate` runs it); see the Code style section below.
- Native pointers wrap in `NativeObject` (`AutoCloseable`, idempotent close):
  `LmdbEnv` and `LmdbCursor`. `LmdbTxn` also extends it since `mdb_txn_commit`/
  `mdb_txn_abort` free the underlying `MDB_txn*` exactly once.
- `LmdbCursor#get(LmdbCursorOp[, key])` and `LmdbTxn#getSegment`/`get`/
  `get(..., Mapper)` all reuse two `MDB_val` out-param slots allocated once,
  in the owning `LmdbCursor`/`LmdbTxn`'s lifetime `Arena`, instead of opening
  a fresh confined `Arena` per call — allocating two 16-byte structs from a
  new `Arena.ofConfined()` on every call was, per `benchmark/ReadBenchmark`'s
  `readSeq`, ~300x slower than lmdbjava's equivalent scan on an identical
  database. The keyed overloads additionally reuse a `keyBuffer`
  (`LmdbVal.growBuffer`) for the copied key content instead of allocating a
  fresh one per call, grown by doubling only when a caller's key exceeds the
  current size — `benchmark/ReadBenchmark`'s `readKey` originally paid this
  same per-call-`Arena` cost per key (not just once per scan), closing nearly
  the entire gap to lmdbjava's equivalent point lookup once fixed. Safe to
  reuse: LMDB only ever writes through these pointers, never reads stale
  content from them, and the `MemorySegment`s handed back (an `Entry`, or a
  read result) are read out of the slots (pointing at the mmap, not at the
  slots themselves) before the next call overwrites them — true even for a
  `Mapper` callback that reenters the same cursor/txn, since its result is
  extracted before the callback runs, not after.
  `LmdbTxn#get(..., Mapper)` used to additionally open one small per-call
  `Arena` purely to make the value view handed to `mapper` inaccessible the
  instant the call returned. `benchmark/ReadBenchmark` showed that Arena's
  open/close cost more than the tiny `MemorySegment` it was scoping (`readKeySegmentMapper`
  measured slower than plain `readKeySegment`, despite allocating far less),
  so it was dropped — the callback's view now shares the same
  transaction-length lifetime as every other zero-copy read (see the
  segment-lifetime bullet below), and a caller may retain it past the call if
  it chooses to. Write-side keyed overloads (`put`/`delete`) still keep their
  per-call `Arena` — key *and* data content both vary by call there, and the
  write path wasn't the diagnosed bottleneck (`benchmark/WriteBenchmark`
  already tracked lmdbjava within ~2–15%).
- `Bindings#CURSOR_GET` and `#GET` (only — not `PUT`/`DEL`/any other binding)
  link with `Linker.Option.critical(false)`, skipping the JVM's thread-state
  transition normally paid around every downcall. `benchmark/ReadBenchmark`'s
  `readSeq`/`readRev` (`mdb_cursor_get` in a tight loop, `CURSOR_GET`'s
  hottest caller) showed that transition — not the ~40 B/op `MemorySegment`
  `LmdbVal.data()` allocates per read — was the dominant cost versus
  lmdbjava's JNR-FFI path: removing it took those benchmarks from ~0.012ms to
  ~0.008–0.009ms (lmdbjava: ~0.010ms), faster than lmdbjava despite still
  allocating. `GET` (`mdb_get`, backing `getSegment`/`get`/the `Mapper`
  overloads) got the same treatment for the same reason, but the win is
  smaller — ~2–8% (e.g. `ffmGetSegment` 0.091ms -> 0.089ms, already far ahead
  of `lmdbjavaGet`'s 0.152ms before this) rather than `CURSOR_GET`'s
  ~25–33%: `mdb_get` does a full B-tree search (`mdb_page_search`) per call,
  real work the fixed transition cost is a smaller fraction of, unlike
  `mdb_cursor_get`'s cheap `NEXT`/`PREV` (one step within an
  already-positioned page). Both accepted deliberately, with a real known
  risk: `critical` requires "an extremely short running time in all cases"
  (JDK's own `Linker.Option` javadoc), warning that violating this "is likely
  to have adverse effects, such as loss of performance or JVM crashes." A
  page fault against a cold, memory-mapped page — normal for a database
  larger than RAM, or after eviction, exactly the scale this project's
  `LmdbEnv#mapSize` targets — sits inside a call the JVM now assumes never
  blocks. The benchmarks that justified this can't surface that risk; their
  dataset stays page-cache-resident for the whole run. Not extended to other
  bindings without their own measurement — `PUT`/`CURSOR_PUT`/`DEL`/
  `CURSOR_DEL` touch the mmap the same way but weren't a diagnosed bottleneck
  (`benchmark/WriteBenchmark` already tracked lmdbjava within ~2–15%), and
  everything else (`ENV_SYNC`, `TXN_COMMIT`, `TXN_BEGIN`, `STAT`/`ENV_STAT`,
  environment/transaction/cursor/dbi lifecycle calls) is either not called
  often enough to matter or, worse, can genuinely run long by design (a
  writer-mutex wait, an `fsync`, a full-tree stat walk) — `critical` there
  would violate its own precondition routinely, not just on a cold page.
- All native handles live in `Bindings`; `size_t` maps to `JAVA_LONG` (LP64).
  LMDB return codes are `int`: `0` (`MDB_SUCCESS`) or an error — positive
  values are `errno` codes, negative values are LMDB's own `MDB_*` codes.
  `NativeCall.check(int)` throws `LmdbException` (message from `mdb_strerror`)
  on anything but `MDB_SUCCESS`; `NativeCall.checkFound(int)` additionally
  treats `MDB_NOTFOUND` as a normal outcome, returning `false` instead of
  throwing — a missing key is an expected result, not a failure. Every
  binding method inlines its own `Bindings.X.invokeExact(...)` in its own
  `try`/`catch` rather than passing a lambda into a generic "run this call"
  wrapper: one direct polymorphic-signature call per site is what the JIT can
  actually inline, not one indirected through a functional interface's `run()`.
  Read/cursor methods that can miss (`get`, `getSegment`, `LmdbCursor#get`)
  return the result directly and use `null` for "not present" — not
  `Optional` — since this is a hot path where the extra box has a real cost;
  `Mapper.map` forbids a `null` result precisely so the wrapping `get(...,
  Mapper)` can reuse the same `null`-means-absent convention unambiguously.
- Native structs (`MDB_val`, `MDB_stat`, `MDB_envinfo`): model the layout as a
  named `StructLayout` and access fields through `static final VarHandle`s
  from it (`LAYOUT.varHandle(groupElement("…"))`), deriving size from
  `LAYOUT.byteSize()`. No hardcoded offsets/sizes.
- Types whose only valid values come from a native call (`LmdbStat`,
  `LmdbEnvInfo`, `LmdbVersion`, `LmdbDbi`) are plain classes with a
  package-private constructor, not public records: a public canonical
  constructor would let a caller fabricate one LMDB never actually produced —
  a fake `LmdbVersion` feeding version-gated logic, or worse, an `LmdbDbi`
  wrapping a handle `mdb_dbi_open` never assigned, then passed to `mdb_get`/
  `mdb_put` as if it were real. `LmdbStat`/`LmdbEnvInfo` parse a
  `MemorySegment`, so their constructor is `private` behind a
  package-private `of(MemorySegment)` factory; `LmdbVersion`/`LmdbDbi` take
  already-parsed `int`s with no segment to parse, so their constructor is
  package-private directly, no factory needed. Compare
  [LmdbCursor.Entry], which stays a public record: it is only ever handed
  *out* by [LmdbCursor#get(LmdbCursorOp)], never accepted as an input
  parameter anywhere, so a fabricated one has nothing to corrupt.
- API is **segment-first, with thin `byte[]` overloads** for heap callers, on
  both directions: `getSegment`/`get(..., Mapper)` read zero-copy,
  `put`/`delete` also take `MemorySegment` key/data so a caller whose bytes
  are already off-heap (an mmap slice, an arena buffer) never bounces through
  a `byte[]` on the write path either. `LmdbTxn#getSegment` returns a segment
  backed directly by the mmap; never copy it to a `byte[]` unless the caller
  asked for the `byte[]` overload. Every `MemorySegment`-accepting method
  guards with `NativeCall.requireNative` — a heap segment has no address FFM
  can hand to C, so this fails fast with a clear message instead of a
  cryptic linker error.
- A third key/data flavor, `ByteBuffer`, exists purely as a thin conversion
  over the `MemorySegment` overloads (`MemorySegment.ofBuffer(buffer)`, then
  delegate) — no separate code path, no separate struct-marshalling logic.
  Two consequences worth knowing when adding a new one: (1) `ofBuffer` covers
  `[position, limit)`, not the buffer's full capacity, so a caller can stage a
  key inside a larger scratch buffer via position/limit without slicing; (2)
  a heap-backed (non-direct) `ByteBuffer` converts to a heap `MemorySegment`,
  which the delegated-to method's own `requireNative` then rejects — so
  `ByteBuffer` overloads need no `requireNative` call of their own, the
  `MemorySegment` overload they forward to already has it.
- Every zero-copy read shares one lifetime: `getSegment`, `get(..., Mapper)`,
  and `LmdbCursor#get`/`#getValue` all hand back a segment (or, for `Mapper`,
  pass one to the callback) that stays valid for the rest of the transaction
  (or until the entry is overwritten/deleted) — no separate, tighter-scoped
  variant. An earlier version bound `get(..., Mapper)`'s view to a call-scoped
  `Arena` (`LmdbVal.dataScoped`, using the 3-arg `MemorySegment#reinterpret`
  overload with a `null` cleanup, since the view borrows from the mmap rather
  than owning it) so it became inaccessible the instant the callback
  returned; that Arena's own open/close overhead outweighed the allocation it
  was meant to save, so it was removed in favor of plain `LmdbVal.data`, same
  as every other zero-copy read. `LmdbVal.data` applies one hardening across
  all of them: the returned segment is always `.asReadOnly()`, since writing
  through it would corrupt the mmap'd file in place (`MDB_WRITEMAP`) or
  segfault the JVM (without it — the mapping is `PROT_READ`).
- Run with `--enable-native-access=ALL-UNNAMED`.
- `MDB_dbi` is a native `unsigned int` handle, not a pointer — `LmdbDbi` wraps
  it directly (see above), not a `NativeObject`; it needs no close, only
  `mdb_dbi_close` on the env (rarely used — see LMDB's own docs on why
  closing DBIs is discouraged in a multi-threaded environment).
- Environment/database/write flags (`MDB_RDONLY`, `MDB_CREATE`, `MDB_NOOVERWRITE`,
  …) are OR-able bitmasks in C, but each *group* is still a closed set of named
  values, so each is a real Java `enum` (`LmdbEnvFlag`, `LmdbDbiFlag`,
  `LmdbWriteFlag`) implementing the package-private `LmdbFlag` marker
  interface (`int bits()`), combined by the caller with `EnumSet.of(...)`
  (`Set.of()`/`EnumSet.noneOf(X.class)` for none) and folded back to the
  native bitmask once, at the FFI boundary, via `LmdbFlag.toBits(Set)`. This
  beats plain `int` constants: the compiler rejects passing an `LmdbDbiFlag`
  where an `LmdbEnvFlag` is expected, and `EnumSet` renders/iterates as a real
  collection instead of an opaque bit pattern. `MDB_cursor_op` is a single
  non-combinable choice per call and is a plain enum, not an `LmdbFlag`
  (`LmdbCursorOp`).

## Testing

- Cover happy path, negative cases (invalid input / errors), and corners (empty, zero, max,
  boundaries). Unit tests must be fast — no network or sleep; a `@TempDir` LMDB environment is
  fine since LMDB itself requires a filesystem path to `mmap`.
- JUnit 5 + Mockito (BDDMockito) + AssertJ. Class under test named `sut`. Every test has
  `// Given` / `// When` / `// Then`. BDDMockito only: `given(mock.m()).willReturn(v)` /
  `then(...)` (static-import only `given`/`then`, never `willReturn`/`willThrow`).
  For exception assertions, capture the action under `// When` as a
  `ThrowingCallable result = () -> sut.m(...);` and assert it under `// Then` with
  `assertThatThrownBy(result)` — the callable is the When, the assertion is the Then.
- Prefer `@ParameterizedTest` over copy-paste (`@ValueSource`, else `@ArgumentsSource`/named cases).
- `@Nested` groups related scenarios (`@BeforeEach` in a nested class applies only to it). Private
  helpers go after all `@Test` methods.

# Code style

- 4-space indent, no `sun.misc.Unsafe` or internal JDK APIs.
- Prefer explicit over clever; fail fast on unhandled cases.
- Always braces for `if`/`else`/`for`/`while`, even one-liners (`if (c) { return a; }`).
- **Time quantities use `java.time.Duration`, never `long`** (no `long timeoutMs`).
  Exception: low-level JDK interop taking `long ns` (`Thread.sleep`, `System.nanoTime` math) —
  convert at the call site via `duration.toNanos()`/`toMillis()`.

### Javadoc (build-enforced: `failOnError` + `failOnWarnings`)

- Every public method: main prose description, `@param` per parameter, `@return` (unless `void`).
  Every public record: `@param` per component on the class doc. `@see`-only counts as no description.
- All `///` Markdown — **no HTML** (checkstyle `RegexpSingleline` blocks `<p>`,`<ul>`,`<li>`,
  `<strong>`,`<pre>`,`<table>`, …). Use blank `///` for paragraphs, `- ` lists, ` ```java ``` `,
  `**bold**`. Cross-refs `[ClassName#method(ParamType)]` — verify the target exists (wrong refs are
  **errors**).
- **American English everywhere** (javadoc, comments, identifiers): `recognize`/`optimize`/
  `finalize`/`serialize`/`behavior`/`color`, never `-ise`/`-isation`/`-our`.
- Check: `./mvnw javadoc:javadoc -pl lmdb` must produce zero output.
