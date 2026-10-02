package com.minecraftcitiesnetwork.directions.navigation;

/** How the player travels a leg. */
public enum LegKind {
    /** On foot: gets a walking trail. */
    WALK,
    /** On a line, stop to stop by teleport: no trail. */
    RIDE
}
