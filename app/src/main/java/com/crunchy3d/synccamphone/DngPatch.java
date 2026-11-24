package com.crunchy3d.synccamphone;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayDeque;
import java.util.HashSet;

/**
 * Multiplies the BlackLevel and WhiteLevel tags of a DNG in place (every IFD and SubIFD).
 * Used for the merged HDR DNG: its pixels are written scaled by a power of 2 (more bits than
 * the sensor has, the merge's extra precision), but DngCreator always writes the sensor's own
 * levels. Android-free (also used by the bench).
 */
final class DngPatch {
    private DngPatch() {}

    static final int TAG_SUBIFDS = 330, TAG_BLACK = 50714, TAG_WHITE = 50717;

    /** Returns the number of patched tags (2 expected). */
    static int scaleLevels(String path, int scale) throws IOException {
        int patched = 0;
        try (RandomAccessFile f = new RandomAccessFile(path, "rw")) {
            byte[] h = new byte[8];
            f.readFully(h);
            boolean le;
            if (h[0] == 'I' && h[1] == 'I') le = true;
            else if (h[0] == 'M' && h[1] == 'M') le = false;
            else throw new IOException("not a TIFF/DNG");
            ArrayDeque<Long> todo = new ArrayDeque<>();
            HashSet<Long> seen = new HashSet<>();
            todo.add(u32(h, 4, le));
            while (!todo.isEmpty()) {
                long ifd = todo.poll();
                if (ifd == 0 || !seen.add(ifd) || ifd >= f.length()) continue;
                f.seek(ifd);
                byte[] c = new byte[2];
                f.readFully(c);
                int n = u16(c, 0, le);
                byte[] e = new byte[12 * n + 4];
                f.readFully(e);
                for (int i = 0; i < n; i++) {
                    int o = 12 * i;
                    int tag = u16(e, o, le), type = u16(e, o + 2, le);
                    long count = u32(e, o + 4, le);
                    int size = type == 3 ? 2 : type == 4 ? 4 : type == 5 ? 8 : type == 13 ? 4 : 0;
                    boolean inline = size * count <= 4;
                    long at = inline ? ifd + 2 + o + 8 : u32(e, o + 8, le);
                    if (tag == TAG_SUBIFDS && (type == 4 || type == 13)) {
                        if (inline) {
                            todo.add(u32(e, o + 8, le));
                        } else {
                            byte[] v = read(f, at, 4 * (int) count);
                            for (int k = 0; k < count; k++) todo.add(u32(v, 4 * k, le));
                        }
                    } else if (tag == TAG_BLACK || tag == TAG_WHITE) {
                        if (size == 0 || type == 13) throw new IOException("level tag type " + type);
                        byte[] v = read(f, at, size * (int) count);
                        for (int k = 0; k < count; k++) {
                            if (type == 3) {
                                long x = u16(v, 2 * k, le) * (long) scale;
                                if (x > 0xFFFF) throw new IOException("level overflow");
                                put16(v, 2 * k, (int) x, le);
                            } else if (type == 4) {
                                put32(v, 4 * k, u32(v, 4 * k, le) * scale, le);
                            } else {                                 // RATIONAL: scale the numerator
                                put32(v, 8 * k, u32(v, 8 * k, le) * scale, le);
                            }
                        }
                        f.seek(at);
                        f.write(v);
                        patched++;
                    }
                }
                todo.add(u32(e, 12 * n, le));                        // next IFD
            }
        }
        return patched;
    }

    /**
     * Embeds a JPEG as the DNG's preview (DNG spec: a SubIFD with NewSubfileType 1, JPEG
     * compression, sRGB), so viewers show the app's rendering and raw editors can match it
     * (RawTherapee "auto-matched tone curve"). The JPEG must have the raw's orientation
     * (sensor orientation; the DNG Orientation tag applies to both). Appended at the end:
     * JPEG data, the preview IFD and a copy of IFD0 with a SubIFDs entry; the header then
     * points to the new IFD0 (the old one stays as unused bytes). Expects IFD0 = raw image
     * without SubIFDs (as DngCreator writes it without a thumbnail).
     */
    static void addPreview(String path, byte[] jpeg) throws IOException {
        jpeg = stripApp1(jpeg);
        int[] wh = jpegSize(jpeg);
        try (RandomAccessFile f = new RandomAccessFile(path, "rw")) {
            byte[] h = new byte[8];
            f.readFully(h);
            boolean le = h[0] == 'I';
            if (!le && h[0] != 'M') throw new IOException("not a TIFF/DNG");
            long ifd0 = u32(h, 4, le);
            byte[] c = read(f, ifd0, 2);
            int n = u16(c, 0, le);
            byte[] e = read(f, ifd0 + 2, 12 * n + 4);
            for (int i = 0; i < n; i++) {
                if (u16(e, 12 * i, le) == TAG_SUBIFDS) throw new IOException("IFD0 already has SubIFDs");
            }
            long end = f.length();
            long jpegAt = end + (end & 1);
            long bpsAt = jpegAt + jpeg.length + (jpeg.length & 1);
            long pAt = bpsAt + 6 + 2;                                // BitsPerSample 8,8,8 (+2 pad)
            int pn = 13;
            long q0 = pAt + 2 + 12L * pn + 4;
            // preview IFD (tags ascending)
            byte[] p = new byte[2 + 12 * pn + 4];
            put16(p, 0, pn, le);
            int k = 0;
            k = entry(p, k, 254, 4, 1, 1, le);                      // NewSubfileType: reduced-res
            k = entry(p, k, 256, 4, 1, wh[0], le);
            k = entry(p, k, 257, 4, 1, wh[1], le);
            k = entry(p, k, 258, 3, 3, bpsAt, le);
            k = entry16(p, k, 259, 7, le);                          // JPEG
            k = entry16(p, k, 262, 6, le);                          // YCbCr
            k = entry(p, k, 273, 4, 1, jpegAt, le);
            k = entry16(p, k, 277, 3, le);
            k = entry(p, k, 278, 4, 1, wh[1], le);
            k = entry(p, k, 279, 4, 1, jpeg.length, le);
            k = entry16(p, k, 284, 1, le);
            int sub = wh[2];                                         // YCbCrSubSampling from the JPEG
            k = entry(p, k, 530, 3, 2, 0, le);
            put16(p, 2 + 12 * (k - 1) + 8, sub >> 4, le);
            put16(p, 2 + 12 * (k - 1) + 10, sub & 15, le);
            k = entry(p, k, 50970, 4, 1, 2, le);                    // PreviewColorSpace: sRGB
            // IFD0 copy + SubIFDs (330), kept in tag order
            byte[] q = new byte[2 + 12 * (n + 1) + 4];
            put16(q, 0, n + 1, le);
            int o = 2;
            boolean added = false;
            for (int i = 0; i < n; i++) {
                int tag = u16(e, 12 * i, le);
                if (!added && tag > TAG_SUBIFDS) {
                    entryAt(q, o, TAG_SUBIFDS, 4, 1, pAt, le);
                    o += 12;
                    added = true;
                }
                System.arraycopy(e, 12 * i, q, o, 12);
                o += 12;
            }
            if (!added) {
                entryAt(q, o, TAG_SUBIFDS, 4, 1, pAt, le);
                o += 12;
            }
            System.arraycopy(e, 12 * n, q, o, 4);                   // next IFD
            f.seek(end);
            if (jpegAt > end) f.write(0);
            f.write(jpeg);
            if ((jpeg.length & 1) != 0) f.write(0);
            byte[] bps = new byte[8];
            for (int i = 0; i < 3; i++) put16(bps, 2 * i, 8, le);
            f.write(bps);
            f.write(p);
            f.write(q);
            byte[] hp = new byte[4];
            put32(hp, 0, q0, le);
            f.seek(4);
            f.write(hp);
        }
    }

    private static int entry(byte[] b, int k, int tag, int type, long count, long value, boolean le) {
        entryAt(b, 2 + 12 * k, tag, type, count, value, le);
        return k + 1;
    }

    private static int entry16(byte[] b, int k, int tag, int value, boolean le) {
        entryAt(b, 2 + 12 * k, tag, 3, 1, 0, le);
        put16(b, 2 + 12 * k + 8, value, le);
        return k + 1;
    }

    private static void entryAt(byte[] b, int o, int tag, int type, long count, long value, boolean le) {
        put16(b, o, tag, le);
        put16(b, o + 2, type, le);
        put32(b, o + 4, count, le);
        put32(b, o + 8, value, le);
    }

    /** JPEG without APP1 (EXIF/XMP: the DNG has its own metadata). */
    static byte[] stripApp1(byte[] j) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(j.length);
        out.write(j, 0, 2);
        int i = 2;
        while (i + 4 <= j.length && (j[i] & 0xFF) == 0xFF) {
            int m = j[i + 1] & 0xFF;
            if (m == 0xDA) break;                                    // start of scan: rest as is
            int len = (j[i + 2] & 0xFF) << 8 | (j[i + 3] & 0xFF);
            if (m != 0xE1) out.write(j, i, 2 + len);
            i += 2 + len;
        }
        out.write(j, i, j.length - i);
        return out.toByteArray();
    }

    /** {width, height, luma sampling (h << 4 | v)} from the JPEG's SOF marker. */
    static int[] jpegSize(byte[] j) throws IOException {
        int i = 2;
        while (i + 4 <= j.length && (j[i] & 0xFF) == 0xFF) {
            int m = j[i + 1] & 0xFF;
            int len = (j[i + 2] & 0xFF) << 8 | (j[i + 3] & 0xFF);
            if (m >= 0xC0 && m <= 0xC3) {
                int hh = (j[i + 5] & 0xFF) << 8 | (j[i + 6] & 0xFF);
                int ww = (j[i + 7] & 0xFF) << 8 | (j[i + 8] & 0xFF);
                return new int[] {ww, hh, j[i + 11] & 0xFF};
            }
            i += 2 + len;
        }
        throw new IOException("no JPEG SOF");
    }

    private static byte[] read(RandomAccessFile f, long at, int len) throws IOException {
        byte[] v = new byte[len];
        f.seek(at);
        f.readFully(v);
        return v;
    }

    private static int u16(byte[] b, int o, boolean le) {
        int a = b[o] & 0xFF, c = b[o + 1] & 0xFF;
        return le ? a | c << 8 : a << 8 | c;
    }

    private static long u32(byte[] b, int o, boolean le) {
        long a = b[o] & 0xFF, c = b[o + 1] & 0xFF, d = b[o + 2] & 0xFF, g = b[o + 3] & 0xFF;
        return le ? a | c << 8 | d << 16 | g << 24 : a << 24 | c << 16 | d << 8 | g;
    }

    private static void put16(byte[] b, int o, int x, boolean le) {
        b[o + (le ? 0 : 1)] = (byte) x;
        b[o + (le ? 1 : 0)] = (byte) (x >> 8);
    }

    private static void put32(byte[] b, int o, long x, boolean le) {
        for (int k = 0; k < 4; k++) b[o + (le ? k : 3 - k)] = (byte) (x >> (8 * k));
    }
}
