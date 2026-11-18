package com.schembuilder.schem;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/**
 * A parsed Sponge schematic (.schem, versions 2 and 3, as written by WorldEdit/FAWE and many converters).
 * Pure Java: block states are kept as palette strings such as "minecraft:oak_stairs[facing=north,half=bottom]".
 */
public final class SchematicFile {
    public final String name;
    public final int width;
    public final int height;
    public final int length;
    public final String[] palette;
    private final int[] blocks;

    SchematicFile(String name, int width, int height, int length, String[] palette, int[] blocks) {
        this.name = name;
        this.width = width;
        this.height = height;
        this.length = length;
        this.palette = palette;
        this.blocks = blocks;
    }

    /** Palette index of the block at schematic-relative x/y/z. */
    public int paletteIndexAt(int x, int y, int z) {
        return blocks[(y * length + z) * width + x];
    }

    @SuppressWarnings("unchecked")
    public static SchematicFile read(Path path) throws IOException {
        String lower = path.getFileName() == null ? "" : path.getFileName().toString().toLowerCase();
        if (lower.endsWith(".litematic")) {
            return LitematicReader.read(path);
        }
        Map<String, Object> root = Nbt.readFile(path);

        // v3 wraps everything in a "Schematic" compound; v2 puts the fields directly in the root.
        Map<String, Object> s = root.get("Schematic") instanceof Map<?, ?> m ? (Map<String, Object>) m : root;

        if (!s.containsKey("Version")) {
            throw new IOException("Not a Sponge .schem file (no Version tag). Legacy .schematic files are not "
                    + "supported - open it in WorldEdit and re-save it as .schem.");
        }
        int width = intOf(s, "Width");
        int height = intOf(s, "Height");
        int length = intOf(s, "Length");

        Map<String, Object> paletteTag;
        byte[] data;
        if (s.get("Blocks") instanceof Map<?, ?> b) {
            Map<String, Object> blocksTag = (Map<String, Object>) b;
            paletteTag = (Map<String, Object>) blocksTag.get("Palette");
            data = (byte[]) blocksTag.get("Data");
        } else {
            paletteTag = (Map<String, Object>) s.get("Palette");
            data = (byte[]) s.get("BlockData");
        }
        if (paletteTag == null || data == null) {
            throw new IOException("Schematic has no block palette/data");
        }

        int maxId = -1;
        for (Object v : paletteTag.values()) maxId = Math.max(maxId, ((Number) v).intValue());
        String[] palette = new String[maxId + 1];
        for (Map.Entry<String, Object> e : paletteTag.entrySet()) {
            palette[((Number) e.getValue()).intValue()] = e.getKey();
        }

        long totalLong = (long) width * height * length;
        if (totalLong <= 0 || totalLong > 200_000_000L) {
            throw new IOException("Unreasonable schematic size " + width + "x" + height + "x" + length);
        }
        int total = (int) totalLong;
        int[] blocks = new int[total];
        int idx = 0;
        for (int i = 0; i < total; i++) {
            int value = 0;
            int shift = 0;
            while (true) {
                if (idx >= data.length) throw new IOException("Block data is truncated");
                int b = data[idx++];
                value |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) break;
                shift += 7;
                if (shift > 35) throw new IOException("Corrupt block data (bad varint)");
            }
            if (value < 0 || value >= palette.length || palette[value] == null) {
                throw new IOException("Block data references missing palette entry " + value);
            }
            blocks[i] = value;
        }

        String fileName = path.getFileName() == null ? "schematic" : path.getFileName().toString();
        return new SchematicFile(fileName, width, height, length, palette, blocks);
    }

    private static int intOf(Map<String, Object> m, String key) throws IOException {
        Object o = m.get(key);
        if (o == null) throw new IOException("Missing " + key);
        if (o instanceof Short sh) return sh & 0xFFFF; // Width/Height/Length are unsigned shorts
        return ((Number) o).intValue();
    }
}
