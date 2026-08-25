# lmdb-java

Java **Foreign Function & Memory (FFM)** bindings for [LMDB](https://github.com/LMDB/lmdb).
No JNI, no hand-written C. Targets **JDK 25+** (stable `java.lang.foreign`).

The differentiator is the **zero-copy read path**: `mdb_get`/cursor reads hand
back a `MemorySegment` that points directly into the memory-mapped database —
no `byte[]` copy — valid for the lifetime of the enclosing read transaction.

## Layout

Multi-module Maven build (`io.github.dfa1.lmdb:lmdb-java`):

- `lmdb/` — the library module, artifactId `lmdb`, pure-Java FFM bindings
  (package `io.github.dfa1.lmdb`). The only module with Java sources.
- `native/<classifier>/` — one module per platform; each packages a
  `liblmdb.{dylib,so}` built from the `third_party/lmdb` submodule. No Java.
  Classifiers so far: `osx-aarch64`, `osx-x86_64`, `linux-x86_64`,
  `linux-aarch64`. Windows is not yet supported (LMDB's Windows locking path
  needs its own porting pass — see the README).
- `lmdb-platform/` — convenience jar pulling in the bindings plus every
  native classifier.
- `bom/` — dependency BOM.
- `third_party/lmdb` — vendored `LMDB/lmdb` git submodule (the C source of
  truth), pinned to the latest stable tag (currently `LMDB_1.0.1`).

## Native build

`scripts/build-lmdb.sh <output-resources-dir> <classifier>` compiles LMDB's
two source files (`mdb.c`, `midl.c`) directly with **`zig cc`** — no
Makefile. Zig bundles clang + libc for every target, so any host
cross-compiles any of the four classifiers hermetically. The Maven `exec`
plugin runs it in `generate-resources`; it is idempotent (skips if the
library already exists).

LMDB itself branches on `__APPLE__`/`__linux__`/etc. inside `mdb.c` for its
locking strategy (POSIX mutexes + robust recovery on Linux, named POSIX
semaphores on Darwin), so the build script does not need to pick that per
platform — only `-pthread` and `-fvisibility=hidden` are set explicitly.

Built `.dylib/.so` are git-ignored; they are regenerated from the submodule.

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
- API is **segment-first for the zero-copy read path, with thin `byte[]`
  overloads** for heap callers. `LmdbTxn#get` returns a `MemorySegment` backed
  directly by the mmap; never copy it to a `byte[]` unless the caller asked
  for the `byte[]` overload.
- Run with `--enable-native-access=ALL-UNNAMED`.
- `MDB_dbi` is a native `unsigned int` handle, not a pointer — `LmdbDbi` is a
  plain `record LmdbDbi(int handle)`, not a `NativeObject`; it needs no close,
  only `mdb_dbi_close` on the env (rarely used — see LMDB's own docs on why
  closing DBIs is discouraged in a multi-threaded environment).
- Environment/database/write flags (`MDB_RDONLY`, `MDB_CREATE`, `MDB_NOOVERWRITE`,
  …) are OR-able bitmasks, not a single validated enum — kept as named `int`
  constants (`LmdbEnvFlags`, `LmdbDbiFlags`, `LmdbWriteFlags`), mirroring the
  C API. `MDB_cursor_op` is a closed, non-combinable set and is a real Java
  enum (`LmdbCursorOp`).

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
