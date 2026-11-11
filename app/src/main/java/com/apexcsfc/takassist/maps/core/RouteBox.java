package com.apexcsfc.takassist.maps.core;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Reads the area a route.bin covers from its 64-byte header (south, west, north, east). */
final class RouteBox {
    static double[] of(File f) throws IOException {
        byte[] h = new byte[64];
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            r.readFully(h);
        }
        ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        if (b.get(0) != 'L' || b.get(1) != 'L' || b.get(2) != 'M' || b.get(3) != 'R') throw new IOException("not route.bin");
        int minLat = b.getInt(24), minLon = b.getInt(28), cell = b.getInt(32), gw = b.getInt(36), gh = b.getInt(40);
        return new double[]{minLat / 1e7, minLon / 1e7, (minLat + (long) gh * cell) / 1e7, (minLon + (long) gw * cell) / 1e7};
    }
}
