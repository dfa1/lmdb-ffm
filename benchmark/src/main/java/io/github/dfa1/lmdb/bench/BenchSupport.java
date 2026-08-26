package io.github.dfa1.lmdb.bench;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/// Temp-directory plumbing shared by every benchmark: each LMDB environment
/// (either binding) needs its own directory for `data.mdb`/`lock.mdb`.
final class BenchSupport {

    static Path tempDir(String prefix) throws IOException {
        return Files.createTempDirectory(prefix);
    }

    static void deleteRecursively(Path dir) throws IOException {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (var paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }

    private BenchSupport() {
        // no instances
    }
}
