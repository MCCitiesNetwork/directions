package com.minecraftcitiesnetwork.directions.walk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CellTest {
    private static final int MAX = (1 << 25) - 1;

    private static void roundTrips(int x, int y, int z) {
        assertEquals(new Cell(x, y, z), Cell.fromKey(Cell.key(x, y, z)));
    }

    @Test
    void positiveValuesRoundTrip() {
        roundTrips(5, 64, 9);
    }

    @Test
    void negativeValuesRoundTrip() {
        roundTrips(-5, 64, 9);
        roundTrips(5, 64, -9);
        roundTrips(5, -30, 9);
        roundTrips(-1, -1, -1);
    }

    @Test
    void boundaryValuesRoundTrip() {
        roundTrips(MAX, 2047, MAX);
        roundTrips(-MAX, -2048, -MAX);
        roundTrips(MAX, -2048, -MAX);
        roundTrips(-MAX, 2047, MAX);
    }
}
