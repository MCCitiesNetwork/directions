package com.minecraftcitiesnetwork.directions.pathfinding.bukkit;

import org.bukkit.Chunk;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;

/**
 * Keeps cached chunk flags honest. Single-block edits are patched in place; multi-block events drop
 * the affected chunks. All handlers run on the main thread after the change is final.
 */
final class BlockChangeListener implements Listener {
    private final ChunkFlagCache cache;

    BlockChangeListener(ChunkFlagCache cache) {
        this.cache = cache;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        patch(e.getBlockPlaced());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        cache.blockChanged(b.getWorld(), b.getX(), b.getY(), b.getZ(), org.bukkit.Material.AIR);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) {
        Block b = e.getBlock();
        cache.blockChanged(b.getWorld(), b.getX(), b.getY(), b.getZ(), org.bukkit.Material.AIR);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFade(BlockFadeEvent e) {
        Block b = e.getBlock();
        cache.blockChanged(b.getWorld(), b.getX(), b.getY(), b.getZ(), e.getNewState().getType());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onForm(BlockFormEvent e) {
        Block b = e.getBlock();
        cache.blockChanged(b.getWorld(), b.getX(), b.getY(), b.getZ(), e.getNewState().getType());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGrow(BlockGrowEvent e) {
        Block b = e.getBlock();
        cache.blockChanged(b.getWorld(), b.getX(), b.getY(), b.getZ(), e.getNewState().getType());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFluid(BlockFromToEvent e) {
        Block to = e.getToBlock();
        cache.blockChanged(to.getWorld(), to.getX(), to.getY(), to.getZ(), e.getBlock().getType());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        dropChunks(e.getBlocks());
        drop(e.getBlock().getChunk());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        dropChunks(e.getBlocks());
        drop(e.getBlock().getChunk());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        dropChunks(e.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        dropChunks(e.blockList());
    }

    private void patch(Block b) {
        cache.blockChanged(b.getWorld(), b.getX(), b.getY(), b.getZ(), b.getType());
    }

    private void dropChunks(Iterable<Block> blocks) {
        for (Block b : blocks) {
            drop(b.getChunk());
        }
    }

    private void drop(Chunk c) {
        cache.chunkChanged(c.getWorld(), c.getX(), c.getZ());
    }
}
