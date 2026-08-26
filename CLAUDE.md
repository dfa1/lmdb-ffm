# lmdb-java

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
`LmdbTxn#get(LmdbDbi, byte[], Mapper)` narrows that further: the view is bound
to a `mapper`-scoped arena, so it becomes inaccessible the instant the call
returns rather than merely being documented as such — useful when the value
just needs parsing into a plain result, not a `MemorySegment` to manage.

## Layout

Multi-module Maven build (`io.github.dfa1.lmdb:lmdb-java`):

- `lmdb/` — the library module, artifactId `lmdb`, pure-Java FFM bindings
  (package `io.github.dfa1.lmdb`). The only module with Java sources.
- `native/<classifier>/` — one module per platform; each packages a
  `liblmdb.{dylib,so,dll}` built from the `third_party/lmdb` submodule. No
  Java. Classifiers: `osx-aarch64`, `osx-x86_64`, `linux-x86_64`,
  `linux-aarch64`, `windows-x86_64`, `windows-aarch64`.
- `lmdb-platform/` — convenience jar pulling in the bindings plus every
  native classifier.
- `bom/` — dependency BOM.
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
- All native handles live in `Bindings`; `size_t` maps to `JAVA_LONG` (LP64).
  LMDB return codes are `int`: `0` (`MDB_SUCCESS`) or an error — positive
  values are `errno` codes, negative values are LMDB's own `MDB_*` codes.
  `NativeCall.checkReturnValue` throws `LmdbException` (message from
  `mdb_strerror`) on anything but `MDB_SUCCESS`, except `MDB_NOTFOUND`, which
  the read/cursor APIs surface as `Optional.empty()` rather than an exception
  — a missing key is an expected outcome, not a failure.
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
- Zero-copy reads return two different lifetimes for two different needs:
  `getSegment`'s segment stays valid for the rest of the transaction (or
  until the entry is overwritten/deleted), matching how long the caller might
  reasonably want to hold a raw view; `get(..., Mapper)`'s segment is bound to
  a call-scoped arena and becomes inaccessible the moment the callback
  returns (`LmdbVal.dataScoped`, using the 3-arg `MemorySegment#reinterpret`
  overload with a `null` cleanup — this view borrows from the mmap, it does
  not own it, so the arena closing must not attempt to free it). Both share
  one more hardening `LmdbVal.data` applies: the returned segment is always
  `.asReadOnly()`, since writing through it would corrupt the mmap'd file in
  place (`MDB_WRITEMAP`) or segfault the JVM (without it — the mapping is
  `PROT_READ`).
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
