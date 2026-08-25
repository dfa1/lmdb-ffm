/// Java Foreign Function & Memory (FFM) bindings for LMDB.
///
/// Exports the single public API package; the native `liblmdb` is loaded at
/// runtime from the platform `lmdb-native-<classifier>` artifact on the path.
/// Requires `--enable-native-access=io.github.dfa1.lmdb` (or `ALL-UNNAMED` on
/// the classpath) since FFM downcalls are a restricted operation.
@SuppressWarnings("module") // dfa1 is my username in github
module io.github.dfa1.lmdb {
    exports io.github.dfa1.lmdb;
}
