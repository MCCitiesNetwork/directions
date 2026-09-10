package com.minecraftcitiesnetwork.directions.pathfinding.bukkit;

import com.minecraftcitiesnetwork.directions.pathfinding.Pos;
import com.minecraftcitiesnetwork.directions.pathfinding.grid.ArrayBlockGrid;
import com.minecraftcitiesnetwork.directions.pathfinding.grid.ChunkFlags;
import com.minecraftcitiesnetwork.directions.pathfinding.grid.GridCodec;
import org.bukkit.World;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Writes the flags of an area around a point to a {@code .grid.gz} file, translated to a local
 * origin. Flags only: no materials, names or world identity leave the server.
 */
public final class WorldCapture {
    public record Result(Path file, int originX, int originZ, int minY, int maxY, int sizeX, int sizeZ, long bytes) {
        public Pos toLocal(Pos world) {
            return new Pos(world.x() - originX, world.y(), world.z() - originZ);
        }
    }

    private final WalkPathService service;

    public WorldCapture(WalkPathService service) {
        this.service = service;
    }

    /** Runs off the main thread; completes on the main thread. */
    public CompletableFuture<Result> capture(World world, Pos centre, int radius, Path file) {
        CompletableFuture<Result> future = new CompletableFuture<>();
        service.submit(() -> {
            try {
                Result r = captureBlocking(world, centre, radius, file);
                service.mainThread().run(() -> future.complete(r));
            } catch (Exception e) {
                service.mainThread().run(() -> future.completeExceptionally(e));
            }
        });
        return future;
    }

    public Result captureBlocking(World world, Pos centre, int radius, Path file) throws IOException {
        int minX = centre.x() - radius;
        int maxX = centre.x() + radius;
        int minZ = centre.z() - radius;
        int maxZ = centre.z() + radius;
        int cx0 = minX >> 4;
        int cx1 = maxX >> 4;
        int cz0 = minZ >> 4;
        int cz1 = maxZ >> 4;
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();

        List<ChunkFlags[]> rows = new ArrayList<>();
        int bandMin = Integer.MAX_VALUE;
        int bandMax = Integer.MIN_VALUE;
        for (int cz = cz0; cz <= cz1; cz++) {
            ChunkFlags[] row = new ChunkFlags[cx1 - cx0 + 1];
            for (int cx = cx0; cx <= cx1; cx++) {
                ChunkFlags f = service.cache().resolve(world, cx, cz, deadline);
                row[cx - cx0] = f;
                if (!f.isUnloaded()) {
                    bandMin = Math.min(bandMin, f.bandMinY());
                    bandMax = Math.max(bandMax, f.bandMaxY());
                }
            }
            rows.add(row);
        }
        if (bandMin == Integer.MAX_VALUE) {
            throw new IOException("no chunk data available in the requested area");
        }
        int sizeX = maxX - minX + 1;
        int sizeZ = maxZ - minZ + 1;
        int sizeY = bandMax - bandMin + 1;
        ArrayBlockGrid grid = new ArrayBlockGrid(0, bandMin, 0, sizeX, sizeY, sizeZ);
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                ChunkFlags f = rows.get((z >> 4) - cz0)[(x >> 4) - cx0];
                for (int y = bandMin; y <= bandMax; y++) {
                    grid.set(x - minX, y, z - minZ, f.flags(x & 15, y, z & 15));
                }
            }
        }
        GridCodec.write(grid, file);
        return new Result(file, minX, minZ, bandMin, bandMax, sizeX, sizeZ, file.toFile().length());
    }
}
