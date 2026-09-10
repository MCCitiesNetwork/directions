package com.minecraftcitiesnetwork.directions.pathfinding.bukkit;

import com.minecraftcitiesnetwork.directions.pathfinding.BlockClassifier;
import org.bukkit.Material;

/**
 * Per-{@link Material} flag lookup for the live server, memoised by ordinal. Uses the shared
 * name-based classifier with Paper's {@code isSolid} as the oracle for anything the tables miss.
 */
public final class MaterialFlags {
    private final BlockClassifier classifier;
    private final byte[] byOrdinal = new byte[Material.values().length];

    public MaterialFlags() {
        this(BlockClassifier.withSolidOracle(name -> {
            Material m = Material.getMaterial(name);
            return m != null && m.isBlock() && m.isSolid();
        }));
    }

    public MaterialFlags(BlockClassifier classifier) {
        this.classifier = classifier;
    }

    public BlockClassifier classifier() {
        return classifier;
    }

    public byte flags(Material material) {
        int i = material.ordinal();
        byte f = byOrdinal[i];
        if (f == 0) {
            f = classifier.classify(material.name());
            byOrdinal[i] = f;
        }
        return f;
    }
}
