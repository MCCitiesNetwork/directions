package com.minecraftcitiesnetwork.directions.walk.bukkit;

import com.minecraftcitiesnetwork.directions.walk.BlockKind;
import com.minecraftcitiesnetwork.directions.walk.BlockKinds;
import org.bukkit.Material;

/** {@link BlockKinds} memoised per material ordinal. Immutable after construction; thread-safe. */
public final class MaterialKinds {
    private final BlockKind[] byOrdinal;

    @SuppressWarnings("deprecation")
    public MaterialKinds() {
        Material[] all = Material.values();
        byOrdinal = new BlockKind[all.length];
        for (Material m : all) {
            byOrdinal[m.ordinal()] = m.isLegacy() || !m.isBlock()
                    ? BlockKind.OPEN
                    : BlockKinds.classify(m.name(), m.isSolid());
        }
    }

    public BlockKind of(Material material) {
        return byOrdinal[material.ordinal()];
    }
}
