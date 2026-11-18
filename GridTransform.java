package com.schembuilder.schem;

/**
 * Rotation/mirroring of a schematic footprint (pure Java, so it can be tested without the game).
 *
 * <p>Coordinates: +X is east, +Z is south. Rotation is clockwise when viewed from above, matching
 * Minecraft's {@code Rotation} (north becomes east for 90 degrees). Mirror is applied BEFORE rotation.
 *
 * <p>rot: 0 = none, 1 = clockwise 90, 2 = 180, 3 = counter-clockwise 90.
 * mirror: 0 = none, 1 = flip north/south (Z), 2 = flip east/west (X).
 */
public final class GridTransform {
    private GridTransform() {}

    /** Maps schematic-relative (x, z) in a w-by-l footprint to its position in the transformed footprint. */
    public static int[] apply(int x, int z, int w, int l, int rot, int mirror) {
        int tx = x;
        int tz = z;
        if (mirror == 1) tz = l - 1 - tz;
        else if (mirror == 2) tx = w - 1 - tx;
        switch (rot) {
            case 1: return new int[]{l - 1 - tz, tx};
            case 2: return new int[]{w - 1 - tx, l - 1 - tz};
            case 3: return new int[]{tz, w - 1 - tx};
            default: return new int[]{tx, tz};
        }
    }

    public static int outWidth(int w, int l, int rot) {
        return (rot == 1 || rot == 3) ? l : w;
    }

    public static int outLength(int w, int l, int rot) {
        return (rot == 1 || rot == 3) ? w : l;
    }
}
