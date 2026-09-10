package com.minecraftcitiesnetwork.directions.pathfinding.bukkit;

import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("bukkit")
class BukkitMainThreadTest {
    private ServerMock server;
    private JavaPlugin plugin;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin("Directions");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void runsInlineOnTheMainThread() {
        BukkitMainThread main = new BukkitMainThread(plugin);
        AtomicBoolean ran = new AtomicBoolean();
        main.run(() -> ran.set(true));
        assertTrue(ran.get(), "already on the main thread: task runs immediately");
    }

    @Test
    void hopsFromAnotherThreadToTheNextTick() throws Exception {
        BukkitMainThread main = new BukkitMainThread(plugin);
        AtomicReference<Thread> ranOn = new AtomicReference<>();
        CountDownLatch queued = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            main.run(() -> ranOn.set(Thread.currentThread()));
            queued.countDown();
        }, "worker");
        worker.start();
        assertTrue(queued.await(5, TimeUnit.SECONDS));
        assertFalse(ranOn.get() != null, "must not run on the worker thread");
        server.getScheduler().performOneTick();
        assertEquals(Thread.currentThread(), ranOn.get(), "runs on the server thread on the next tick");
    }
}
