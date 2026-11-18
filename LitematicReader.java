package com.schembuilder.schem;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads Litematica .litematic files (pure Java, no Minecraft classes).
 *
 * <p>A .litematic holds one or more named regions. Each region has a corner position, a size (which may be
 * negative, meaning it extends back from the corner), a block state palette, and a bit-packed long array.
 * All regions are merged into one grid so the rest of the mod sees an ordinary {@link SchematicFile}.
 * Cells not covered by any region are air.
 */
final class LitematicReader {
    private LitematicReader() {}

    private static final class Region {
        int minX, minY, minZ;
        int sx, sy, sz; // absolute sizes
        String[] palette;
        long[] states;
    }

    static SchematicFile read(Path path) throws IOException {
        Map<String, Object> root = Nbt.readFile(path);
        Map<String, Object> regionsTag = asMap(root.get("Regions"));
        if (regionsTag == null || regionsTag.isEmpty()) {
            throw new IOException("Not a Litematica file (no Regions tag)");
        }

        List<Region> regions = new ArrayList<>();
        for (Map.Entry<String, Object> e : regionsTag.entrySet()) {
            Map<String, Object> r = asMap(e.getValue());
            if (r == null) continue;
            regions.add(readRegion(e.getKey(), r));
        }
        if (regions.isEmpty()) throw new IOException("Litematic has no readable regions");

        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (Region g : regions) {
            minX = Math.min(minX, g.minX);
            minY = Math.min(minY, g.minY);
            minZ = Math.min(minZ, g.minZ);
            maxX = Math.max(maxX, g.minX + g.sx);
            maxY = Math.max(maxY, g.minY + g.sy);
            maxZ = Math.max(maxZ, g.minZ + g.sz);
        }
        int width = maxX - minX;
        int height = maxY - minY;
        int length = maxZ - minZ;
        long totalLong = (long) width * height * length;
        if (totalLong <= 0 || totalLong > 200_000_000L) {
            throw new IOException("Unreasonable schematic size " + width + "x" + height + "x" + length);
        }
        int[] blocks = new int[(int) totalLong]; // 0 = air

        Map<String, Integer> global = new LinkedHashMap<>();
        global.put("minecraft:air", 0);

        for (Region g : regions) {
            int[] remap = new int[g.palette.length];
            for (int i = 0; i < g.palette.length; i++) {
                remap[i] = global.computeIfAbsent(g.palette[i], k -> global.size());
            }
            int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(g.palette.length - 1));
            long mask = (1L << bits) - 1L;
            long cells = (long) g.sx * g.sy * g.sz;
            if ((cells * bits + 63) / 64 > g.states.length) {
                throw new IOException("Region block data is truncated");
            }
            int i = 0;
            for (int y = 0; y < g.sy; y++) {
                for (int z = 0; z < g.sz; z++) {
                    for (int x = 0; x < g.sx; x++, i++) {
                        long bit = (long) i * bits;
                        int li = (int) (bit >>> 6);
                        int off = (int) (bit & 63);
                        long v = g.states[li] >>> off;
                        if (off + bits > 64) v |= g.states[li + 1] << (64 - off);
                        int idx = (int) (v & mask);
                        if (idx >= g.palette.length) {
                            throw new IOException("Block data references missing palette entry " + idx);
                        }
                        int gid = remap[idx];
                        if (gid == 0) continue; // don't let one region's air erase another region's blocks
                        int gx = g.minX - minX + x;
                        int gy = g.minY - minY + y;
                        int gz = g.minZ - minZ + z;
                        blocks[(gy * length + gz) * width + gx] = gid;
                    }
                }
            }
        }

        String[] palette = new String[global.size()];
        for (Map.Entry<String, Integer> e : global.entrySet()) palette[e.getValue()] = e.getKey();

        String fileName = path.getFileName() == null ? "schematic" : path.getFileName().toString();
        return new SchematicFile(fileName, width, height, length, palette, blocks);
    }

    private static Region readRegion(String name, Map<String, Object> r) throws IOException {
        Map<String, Object> pos = asMap(r.get("Position"));
        Map<String, Object> size = asMap(r.get("Size"));
        if (pos == null || size == null) throw new IOException("Region '" + name + "' has no Position/Size");
        int px = intOf(pos, "x"), py = intOf(pos, "y"), pz = intOf(pos, "z");
        int sx = intOf(size, "x"), sy = intOf(size, "y"), sz = intOf(size, "z");

        Region g = new Region();
        // A negative size means the region extends backwards from its corner.
        g.minX = px + (sx < 0 ? sx + 1 : 0);
        g.minY = py + (sy < 0 ? sy + 1 : 0);
        g.minZ = pz + (sz < 0 ? sz + 1 : 0);
        g.sx = Math.abs(sx);
        g.sy = Math.abs(sy);
        g.sz = Math.abs(sz);

        Object palObj = r.get("BlockStatePalette");
        if (!(palObj instanceof List<?> palList) || palList.isEmpty()) {
            throw new IOException("Region '" + name + "' has no block palette");
        }
        g.palette = new String[palList.size()];
        for (int i = 0; i < palList.size(); i++) {
            Map<String, Object> entry = asMap(palList.get(i));
            if (entry == null || !(entry.get("Name") instanceof String blockName)) {
                throw new IOException("Bad palette entry in region '" + name + "'");
            }
            StringBuilder sb = new StringBuilder(blockName);
            Map<String, Object> props = asMap(entry.get("Properties"));
            if (props != null && !props.isEmpty()) {
                sb.append('[');
                boolean first = true;
                for (Map.Entry<String, Object> p : props.entrySet()) {
                    if (!first) sb.append(',');
                    sb.append(p.getKey()).append('=').append(p.getValue());
                    first = false;
                }
                sb.append(']');
            }
            g.palette[i] = sb.toString();
        }

        if (!(r.get("BlockStates") instanceof long[] states)) {
            throw new IOException("Region '" + name + "' has no BlockStates");
        }
        g.states = states;
        return g;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    private static int intOf(Map<String, Object> m, String key) throws IOException {
        Object o = m.get(key);
        if (!(o instanceof Number n)) throw new IOException("Missing " + key);
        return n.intValue();
    }
}
