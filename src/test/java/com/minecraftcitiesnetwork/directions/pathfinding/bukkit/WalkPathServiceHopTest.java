package com.minecraftcitiesnetwork.directions.pathfinding.bukkit;

import com.minecraftcitiesnetwork.directions.pathfinding.PathfindingSettings;
import com.minecraftcitiesnetwork.directions.pathfinding.Pos;
import com.minecraftcitiesnetwork.directions.pathfinding.WalkResult;
import org.bukkit.Material;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The service's contract: searches run off the main thread and complete on it. */
@Tag("bukkit")
class WalkPathServiceHopTest {
    private ServerMock server;
    private JavaPlugin plugin;
    private WorldMock world;
    private WalkPathService service;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin("Directions");
        world = server.addSimpleWorld("world");
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                world.getBlockAt(x, 64, z).setType(Material.STONE);
            }
        }
        boolean snapshots;
        try {
            // The pump takes snapshots and the decoder reads heights from them; MockBukkit implements
            // only part of that surface, so check the whole decode path before relying on it.
            var snapshot = world.getChunkAt(0, 0).getChunkSnapshot(true, false, false);
            new SnapshotDecoder(new MaterialFlags(), world.getMinHeight(), world.getMaxHeight() - 1, 64).decode(snapshot);
            snapshots = true;
        } catch (Throwable e) {
            snapshots = false;
        }
        assumeTrue(snapshots, "MockBukkit cannot decode chunk snapshots (getHighestBlockYAt or getBlockType unimplemented)");
        PathfindingSettings settings = PathfindingSettings.withEnabled(PathfindingSettings.DISABLED, true, List.of());
        service = new WalkPathService(plugin, settings);
        service.start();
    }

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.shutdown();
        }
        MockBukkit.unmock();
    }

    @Test
    void resultArrivesOnTheMainThreadAfterTicks() throws Exception {
        AtomicReference<Thread> completedOn = new AtomicReference<>();
        CompletableFuture<WalkResult> future = service.find(world, new Pos(1, 65, 1), new Pos(10, 65, 10), 0, Duration.ofSeconds(5));
        future.thenAccept(r -> completedOn.set(Thread.currentThread()));

        // The search thread needs the pump to serve its snapshot, then hands the result back.
        long deadline = System.currentTimeMillis() + 5000;
        while (!future.isDone() && System.currentTimeMillis() < deadline) {
            server.getScheduler().performOneTick();
            Thread.sleep(5);
        }
        assertTrue(future.isDone(), "search did not complete");
        WalkResult result = future.get();
        assertEquals(Thread.currentThread(), completedOn.get(), "completion must run on the server thread");
        assertTrue(result.found(), result.toString());
        assertEquals(1, service.metrics().total());
    }
}
