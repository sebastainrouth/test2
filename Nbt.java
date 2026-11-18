package com.schembuilder.schem;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Minimal standalone NBT reader (no Minecraft classes, so it is testable without the game).
 * Compounds become Map&lt;String,Object&gt;, lists become List&lt;Object&gt;, arrays become primitive arrays.
 */
public final class Nbt {
    private Nbt() {}

    public static Map<String, Object> readFile(Path path) throws IOException {
        byte[] raw = Files.readAllBytes(path);
        InputStream in = new ByteArrayInputStream(raw);
        if (raw.length > 2 && (raw[0] & 0xFF) == 0x1F && (raw[1] & 0xFF) == 0x8B) {
            in = new GZIPInputStream(in);
        }
        try (DataInputStream d = new DataInputStream(new BufferedInputStream(in))) {
            int type = d.readUnsignedByte();
            if (type != 10) {
                throw new IOException("Root tag is not a compound (is this really an NBT file?)");
            }
            d.readUTF(); // root name
            return readCompound(d, 0);
        }
    }

    private static Map<String, Object> readCompound(DataInputStream d, int depth) throws IOException {
        if (depth > 256) throw new IOException("NBT is nested too deeply");
        Map<String, Object> map = new LinkedHashMap<>();
        while (true) {
            int type = d.readUnsignedByte();
            if (type == 0) return map;
            String name = d.readUTF();
            map.put(name, readPayload(type, d, depth + 1));
        }
    }

    private static int checkLen(int n) throws IOException {
        if (n < 0 || n > (1 << 28)) throw new IOException("Bad NBT array length " + n);
        return n;
    }

    private static Object readPayload(int type, DataInputStream d, int depth) throws IOException {
        switch (type) {
            case 1: return d.readByte();
            case 2: return d.readShort();
            case 3: return d.readInt();
            case 4: return d.readLong();
            case 5: return d.readFloat();
            case 6: return d.readDouble();
            case 7: {
                byte[] b = new byte[checkLen(d.readInt())];
                d.readFully(b);
                return b;
            }
            case 8: return d.readUTF();
            case 9: {
                int elementType = d.readUnsignedByte();
                int n = checkLen(d.readInt());
                List<Object> list = new ArrayList<>(Math.min(n, 1024));
                for (int i = 0; i < n; i++) list.add(readPayload(elementType, d, depth + 1));
                return list;
            }
            case 10: return readCompound(d, depth);
            case 11: {
                int[] a = new int[checkLen(d.readInt())];
                for (int i = 0; i < a.length; i++) a[i] = d.readInt();
                return a;
            }
            case 12: {
                long[] a = new long[checkLen(d.readInt())];
                for (int i = 0; i < a.length; i++) a[i] = d.readLong();
                return a;
            }
            default:
                throw new IOException("Unknown NBT tag type " + type);
        }
    }
}
