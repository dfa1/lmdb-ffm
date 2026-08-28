/// lmdb-ffm bindings bundled with every platform's native library.
///
/// No Java code of its own — a pure aggregator. `requires transitive` on
/// `io.github.dfa1.lmdb` means a consumer whose own module only
/// `requires io.github.dfa1.lmdb.platform;` reads the real API automatically;
/// the plain (non-transitive) `requires` on every native classifier module
/// pulls each one into the resolved module graph so its bundled library is
/// present at run time, without granting (or needing) any readability of
/// them — they export nothing to read.
@SuppressWarnings("module") // dfa1 is my username in github
module io.github.dfa1.lmdb.platform {
    requires transitive io.github.dfa1.lmdb;
    requires io.github.dfa1.lmdb.natives.osx.aarch64;
    requires io.github.dfa1.lmdb.natives.osx.x86_64;
    requires io.github.dfa1.lmdb.natives.linux.x86_64;
    requires io.github.dfa1.lmdb.natives.linux.aarch64;
    requires io.github.dfa1.lmdb.natives.windows.x86_64;
    requires io.github.dfa1.lmdb.natives.windows.aarch64;
}
