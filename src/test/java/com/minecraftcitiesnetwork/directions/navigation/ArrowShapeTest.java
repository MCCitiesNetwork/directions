package com.minecraftcitiesnetwork.directions.navigation;

import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArrowShapeTest {
    private static final float EPSILON = 1.0E-5F;
    private static final Vector EAST = new Vector(1, 0, 0);
    private static final Vector SOUTH = new Vector(0, 0, 1);

    @Test
    void orientationTurnsForwardOntoThePointingDirection() {
        for (Vector pointing : List.of(EAST, new Vector(-3, 2, 5), new Vector(0, 1, 0), new Vector(0, -4, 0))) {
            Vector3f forward = ArrowShape.orientation(pointing).transform(new Vector3f(0, 0, 1));
            Vector expected = pointing.clone().normalize();
            assertEquals(expected.getX(), forward.x, EPSILON);
            assertEquals(expected.getY(), forward.y, EPSILON);
            assertEquals(expected.getZ(), forward.z, EPSILON);
        }
    }

    @Test
    void headOpensLevelEvenWhenPointingUphill() {
        Vector3f side = ArrowShape.orientation(new Vector(2, 3, 1)).transform(new Vector3f(1, 0, 0));
        assertEquals(0F, side.y, EPSILON);
    }

    @Test
    void arrowFloatsAheadOfWhereThePlayerLooks() {
        // Looking south, target east: the arrow sits south of the player and points east.
        Transformation shaft = ArrowShape.parts(EAST, SOUTH).getFirst();
        Vector3f translation = shaft.getTranslation();
        assertEquals(-ArrowShape.THICKNESS / 2F, translation.x, EPSILON);
        assertEquals(ArrowShape.RISE, translation.y, EPSILON);
        assertEquals(ArrowShape.DISTANCE, translation.z, EPSILON);
    }

    @Test
    void shaftRunsTowardTheTarget() {
        Transformation shaft = ArrowShape.parts(EAST, SOUTH).getFirst();
        Vector3f along = shaft.getLeftRotation().transform(new Vector3f(0, 0, 1));
        assertEquals(1F, along.x, EPSILON);
        assertEquals(ArrowShape.LENGTH - ArrowShape.THICKNESS, shaft.getScale().z, EPSILON);
        assertEquals(ArrowShape.THICKNESS, shaft.getScale().x, EPSILON);
    }

    @Test
    void headBranchesMirrorEachOtherAndCloseThePoint() {
        List<Transformation> parts = ArrowShape.parts(EAST, SOUTH);
        Vector3f centre = new Vector3f(0, ArrowShape.RISE, ArrowShape.DISTANCE);
        Vector3f right = farEnd(parts.get(1)).sub(centre);
        Vector3f left = farEnd(parts.get(2)).sub(centre);
        float tipX = ArrowShape.LENGTH / 2F;
        // Both branches reach just past the tip, on opposite sides of the shaft.
        assertEquals(right.x, left.x, EPSILON);
        assertEquals(-right.z, left.z, EPSILON);
        assertEquals(0F, right.y, EPSILON);
        assertTrue(right.x > tipX - ArrowShape.THICKNESS && right.x < tipX + ArrowShape.THICKNESS);
        // And trail back from it.
        assertTrue(parts.get(1).getTranslation().x < tipX && parts.get(2).getTranslation().x < tipX);
    }

    @Test
    void arrowStaysInOnePieceWhileTheClientBlendsATurn() {
        // Swing all the way round, the hardest case for the client's blending.
        Vector pointing = EAST;
        List<Transformation> previous = ArrowShape.parts(pointing, SOUTH);
        float[] joins = joins(previous);
        for (int tick = 0; tick < 40; tick++) {
            pointing = ArrowShape.turnToward(pointing, new Vector(-1, 0, 0));
            List<Transformation> next = ArrowShape.parts(pointing, SOUTH);
            for (float progress : new float[]{0.25F, 0.5F, 0.75F}) {
                float[] blended = joins(blend(previous, next, progress));
                assertEquals(joins[0], blended[0], 0.05F, "shaft and right branch drift apart");
                assertEquals(joins[1], blended[1], 0.05F, "shaft and left branch drift apart");
            }
            previous = next;
        }
    }

    @Test
    void turnTowardStepsAtMostTheTurnLimitAndArrives() {
        Vector pointing = EAST;
        Vector desired = new Vector(-1, 0.2, 0);
        for (int tick = 0; tick < 40; tick++) {
            Vector next = ArrowShape.turnToward(pointing, desired);
            assertTrue(Math.toDegrees(pointing.angle(next)) <= ArrowShape.TURN_LIMIT_DEGREES + 1.0E-6);
            pointing = next;
        }
        assertEquals(0.0, pointing.angle(desired), 1.0E-6);
    }

    @Test
    void nothingToPointAtDrawsNothing() {
        assertTrue(ArrowShape.parts(new Vector(), SOUTH).isEmpty());
    }

    /** Distances from the shaft's front end to each branch's far end; fixed while the arrow is rigid. */
    private static float[] joins(List<Transformation> parts) {
        Vector3f shaftFront = farEnd(parts.get(0));
        return new float[]{shaftFront.distance(farEnd(parts.get(1))), shaftFront.distance(farEnd(parts.get(2)))};
    }

    /** Blends two arrows the way the client does (Transformation.slerp): straight-line moves, arced turns. */
    private static List<Transformation> blend(List<Transformation> from, List<Transformation> to, float progress) {
        return java.util.stream.IntStream.range(0, from.size()).mapToObj(i -> new Transformation(
                from.get(i).getTranslation().lerp(to.get(i).getTranslation(), progress, new Vector3f()),
                from.get(i).getLeftRotation().slerp(to.get(i).getLeftRotation(), progress, new org.joml.Quaternionf()),
                from.get(i).getScale().lerp(to.get(i).getScale(), progress, new Vector3f()),
                from.get(i).getRightRotation()
        )).toList();
    }

    /** The end of a bar furthest along its own +Z, measured from the passenger seat. */
    private static Vector3f farEnd(Transformation bar) {
        Vector3f halfLength = bar.getLeftRotation().transform(new Vector3f(0, 0, bar.getScale().z / 2F));
        return halfLength.add(bar.getTranslation());
    }
}
