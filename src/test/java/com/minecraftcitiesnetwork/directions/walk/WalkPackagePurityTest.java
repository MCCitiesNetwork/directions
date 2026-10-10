package com.minecraftcitiesnetwork.directions.walk;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** The walking core must stay testable without a server: no Bukkit, Paper or WorldGuard imports. */
class WalkPackagePurityTest {
    private static final List<String> FORBIDDEN = List.of("import org.bukkit", "import io.papermc", "import com.sk89q");

    @Test
    void corePackageHasNoServerImports() throws IOException {
        Path dir = Path.of("src/main/java/com/minecraftcitiesnetwork/directions/walk");
        try (Stream<Path> files = Files.list(dir)) {
            List<String> offenders = files
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(WalkPackagePurityTest::importsServerApi)
                    .map(p -> p.getFileName().toString())
                    .toList();
            assertTrue(offenders.isEmpty(), "server imports in walk/: " + offenders);
        }
    }

    private static boolean importsServerApi(Path file) {
        try {
            String source = Files.readString(file);
            return FORBIDDEN.stream().anyMatch(source::contains);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
