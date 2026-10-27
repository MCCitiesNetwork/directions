package com.minecraftcitiesnetwork.directions.navigation;

import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.List;

/**
 * Lays out the floating arrow shown in front of a navigating player.
 *
 * <p>The arrow is three bars (shaft and two head branches). Each bar is an entity riding the player,
 * so every offset here is measured from where a passenger sits: just above the player's head.
 * That point rises and falls with the player's eyes when they sneak or swim, so the arrow keeps
 * the same place in view.
 */
final class ArrowShape {
    /** How far ahead of the player's eyes, along where they look, the arrow floats. */
    static final float DISTANCE = 4.0F;
    /** How far above the passenger seat (about head height) the arrow floats. */
    static final float RISE = 0.9F;
    static final float LENGTH = 2.8F;
    static final float HEAD_LENGTH = 1.3F;
    static final float THICKNESS = 0.4F;
    static final double HEAD_ANGLE_DEGREES = 45.0;
    static final double TURN_LIMIT_DEGREES = 15.0;

    private ArrowShape() {
    }

    /**
     * Returns the shaft, right branch and left branch, each as a transformation of a unit block
     * measured from the passenger seat. Empty when {@code pointing} has no length.
     *
     * @param pointing from the player toward the target
     * @param looking  where the player looks
     */
    static List<Transformation> parts(Vector pointing, Vector looking) {
        if (pointing.lengthSquared() < 1.0E-8) {
            return List.of();
        }
        Vector3f centre = new Vector3f(0, RISE, 0).add(toVector3f(looking.clone().normalize()).mul(DISTANCE));
        Quaternionf facing = orientation(pointing);
        // In the arrow's own frame it points along +Z and its head opens across X.
        // The shaft stops short of the tip so its square end stays inside the head's point.
        Vector3f tip = new Vector3f(0, 0, LENGTH / 2F);
        return List.of(
                bar(centre, facing, new Vector3f(0, 0, -THICKNESS / 2F), new Quaternionf(), LENGTH - THICKNESS),
                headBranch(centre, facing, tip, 1),
                headBranch(centre, facing, tip, -1)
        );
    }

    /**
     * Turns {@code current} toward {@code desired} by at most {@link #TURN_LIMIT_DEGREES}, as one tick's step.
     *
     * <p>The client blends each bar's position in a straight line but its rotation along an arc, so a big
     * turn in one step pulls the bars apart mid-blend. Small steps keep the arrow in one piece.
     */
    static Vector turnToward(Vector current, Vector desired) {
        Vector from = current.clone().normalize();
        Vector to = desired.clone().normalize();
        double angle = from.angle(to);
        double limit = Math.toRadians(TURN_LIMIT_DEGREES);
        if (angle <= limit) {
            return to;
        }
        Vector axis = from.getCrossProduct(to);
        if (axis.lengthSquared() < 1.0E-9) {
            // Exactly opposite: swing round level unless pointing straight up or down.
            axis = Math.abs(from.getY()) > 0.99 ? new Vector(1, 0, 0) : new Vector(0, 1, 0);
        }
        return from.rotateAroundAxis(axis.normalize(), limit);
    }

    /** Turns +Z toward {@code pointing}, keeping the arrow's X axis level so the head opens sideways. */
    static Quaternionf orientation(Vector pointing) {
        Vector forward = pointing.clone().normalize();
        float yaw = (float) Math.atan2(forward.getX(), forward.getZ());
        float elevation = (float) Math.asin(Math.max(-1.0, Math.min(1.0, forward.getY())));
        return new Quaternionf().rotationY(yaw).rotateX(-elevation);
    }

    private static Transformation headBranch(Vector3f centre, Quaternionf facing, Vector3f tip, int side) {
        float angle = (float) Math.toRadians(HEAD_ANGLE_DEGREES);
        Quaternionf turn = new Quaternionf().rotationY(side * angle);
        // The branch's far end sits half a thickness past the tip so the two branches close the point.
        Vector3f backward = turn.transform(new Vector3f(0, 0, -1));
        Vector3f middle = backward.mul(HEAD_LENGTH / 2F - THICKNESS / 2F).add(tip);
        return bar(centre, facing, middle, turn, HEAD_LENGTH);
    }

    private static Transformation bar(Vector3f centre, Quaternionf facing, Vector3f middle, Quaternionf turn, float length) {
        return new Transformation(
                facing.transform(new Vector3f(middle)).add(centre),
                new Quaternionf(facing).mul(turn),
                new Vector3f(THICKNESS, THICKNESS, length),
                new Quaternionf()
        );
    }

    private static Vector3f toVector3f(Vector vector) {
        return new Vector3f((float) vector.getX(), (float) vector.getY(), (float) vector.getZ());
    }
}
