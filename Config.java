package com.schembuilder;

import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;

/** Runtime settings, changed with /schem commands or the Right Shift screen. */
public final class Config {
    public static final double LEGIT_MAX_REACH = 4.5;
    /** Servers accept clicks up to (block interaction range + 1.0) from the eyes, so ~5.5 for the default 4.5. */
    public static final double EXPLOIT_MAX_REACH = 6.0;
    public static final double EXPLOIT_DEFAULT_REACH = 5.4;

    public static volatile double reach = 4.5;
    public static volatile boolean exploit = false;
    public static volatile int blocksPerTick = 2;
    /** Hotbar slot (0-8) used to hold the block being placed. Creative mode overwrites it. */
    public static volatile int hotbarSlot = 8;

    // Placement settings edited in the screen.
    public static volatile boolean originSet = false;
    public static volatile int originX;
    public static volatile int originY;
    public static volatile int originZ;
    /** 0 = none, 1 = clockwise 90, 2 = 180, 3 = counter-clockwise 90. */
    public static volatile int rotationIndex = 0;
    /** 0 = none, 1 = flip north/south (Z), 2 = flip east/west (X). */
    public static volatile int mirrorIndex = 0;
    public static volatile String selectedFile = "";

    // Hologram + progress display
    /** Draw the remaining blocks in the world. Only works if the hologram renderer compiled into this build. */
    public static volatile boolean hologram = true;
    public static volatile boolean hologramAvailable = false;
    public static volatile int hologramRange = 24;
    public static final int HOLOGRAM_MAX_BOXES = 6000;
    /** Show "Schem 42% ETA ..." above the hotbar while building. */
    public static volatile boolean actionBarProgress = true;

    private Config() {}

    public static Rotation rotation() {
        return switch (rotationIndex) {
            case 1 -> Rotation.CLOCKWISE_90;
            case 2 -> Rotation.CLOCKWISE_180;
            case 3 -> Rotation.COUNTERCLOCKWISE_90;
            default -> Rotation.NONE;
        };
    }

    public static Mirror mirror() {
        return switch (mirrorIndex) {
            case 1 -> Mirror.LEFT_RIGHT; // flips north <-> south
            case 2 -> Mirror.FRONT_BACK; // flips east <-> west
            default -> Mirror.NONE;
        };
    }

    public static void setExploit(boolean on) {
        exploit = on;
        if (on) reach = Math.max(reach, EXPLOIT_DEFAULT_REACH);
        else reach = Math.min(reach, LEGIT_MAX_REACH);
    }
}
