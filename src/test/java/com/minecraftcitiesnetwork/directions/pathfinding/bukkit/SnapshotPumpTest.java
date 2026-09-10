package com.minecraftcitiesnetwork.directions.pathfinding.bukkit;

import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Tag("bukkit")
class SnapshotPumpTest {
    private ServerMock server;
    private JavaPlugin plugin;
    private WorldMock world;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin("Directions");
        world = server.addSimpleWorld("world");
        world.getBlockAt(3, 64, 3).setType(Material.STONE);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private static boolean snapshotsSupported(World world) {
        try {
            return world.getChunkAt(0, 0).getChunkSnapshot(true, false, false) != null;
        } catch (RuntimeException e) {
            return false; // the mock does not implement snapshots in this version
        }
    }

    @Test
    void servesAtMostTheConfiguredNumberPerTick() throws Exception {
        assumeTrue(snapshotsSupported(world), "MockBukkit cannot snapshot chunks");
        SnapshotPump pump = new SnapshotPump(plugin, 3);
        pump.start();

        List<CompletableFuture<ChunkSnapshot>> futures = new ArrayList<>();
        CountDownLatch queued = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            for (int i = 0; i < 7; i++) {
                futures.add(pump.request(world, i, 0));
            }
            queued.countDown();
        });
        worker.start();
        assertTrue(queued.await(5, TimeUnit.SECONDS));

        server.getScheduler().performOneTick();
        assertEquals(3, futures.stream().filter(CompletableFuture::isDone).count(), "first tick serves the slice");
        server.getScheduler().performOneTick();
        assertEquals(6, futures.stream().filter(CompletableFuture::isDone).count());
        server.getScheduler().performOneTick();
        assertEquals(7, futures.stream().filter(CompletableFuture::isDone).count());
        assertEquals(7, pump.served());
        pump.stop();
    }

    @Test
    void mainThreadRequestsAreImmediate() {
        assumeTrue(snapshotsSupported(world), "MockBukkit cannot snapshot chunks");
        SnapshotPump pump = new SnapshotPump(plugin, 1);
        CompletableFuture<ChunkSnapshot> f = pump.request(world, 0, 0);
        assertTrue(f.isDone());
        assertFalse(f.isCompletedExceptionally());
    }

    @Test
    void stopCompletesPendingRequestsWithNull() throws Exception {
        SnapshotPump pump = new SnapshotPump(plugin, 1);
        pump.start();
        CompletableFuture<ChunkSnapshot>[] holder = new CompletableFuture[1];
        Thread worker = new Thread(() -> holder[0] = pump.request(world, 9, 9));
        worker.start();
        worker.join(5000);
        pump.stop();
        assertTrue(holder[0].isDone());
        assertEquals(null, holder[0].get());
    }
}
