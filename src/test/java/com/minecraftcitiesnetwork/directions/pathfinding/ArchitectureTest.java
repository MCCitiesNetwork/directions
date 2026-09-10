package com.minecraftcitiesnetwork.directions.pathfinding;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pure-Java core must stay pure, and only one class may touch the engine. */
class ArchitectureTest {
    private static final Path CORE = Path.of("src/main/java/com/minecraftcitiesnetwork/directions/pathfinding");

    @Test
    void coreDoesNotImportBukkit() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(CORE)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (file.toString().contains("/pathfinding/bukkit/")) {
                    continue;
                }
                for (String line : Files.readAllLines(file)) {
                    if (line.startsWith("import org.bukkit") || line.startsWith("import io.papermc") || line.startsWith("import com.sk89q")) {
                        offenders.add(file.getFileName() + ": " + line.trim());
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(), "Bukkit leaked into the pure core:\n" + String.join("\n", offenders));
    }

    @Test
    void onlyTheEnginePackageImportsPathetic() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (file.toString().contains("/pathfinding/engine/")) {
                    continue;
                }
                for (String line : Files.readAllLines(file)) {
                    if (line.startsWith("import de.bsommerfeld")) {
                        offenders.add(file.getFileName() + ": " + line.trim());
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(), "Pathetic imported outside pathfinding.engine:\n" + String.join("\n", offenders));
    }
}
