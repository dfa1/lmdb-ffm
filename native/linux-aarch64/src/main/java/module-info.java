/// Native LMDB library for Linux aarch64.
///
/// No Java code of its own — only bundled resources (`liblmdb.so`), found via
/// [ClassLoader#getResourceAsStream(String)] from `io.github.dfa1.lmdb`
/// regardless of any `requires` edge (resource lookup that way is not subject
/// to module read restrictions). This module exists on the module path so a
/// consumer's own module can `requires` it, pulling it into the resolved
/// module graph.
///
/// Named `natives`, not `native`: the latter is a reserved Java keyword and
/// cannot appear as a module-name segment.
@SuppressWarnings("module") // dfa1 is my username in github; aarch64 ends in a digit
module io.github.dfa1.lmdb.natives.linux.aarch64 {
}
