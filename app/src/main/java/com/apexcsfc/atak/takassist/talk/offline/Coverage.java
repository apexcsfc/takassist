package com.apexcsfc.atak.takassist.talk.offline;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

/**
 * The areas the active offline map was built from, one "south,west,north,east" line each in
 * coverage.txt next to area.sqlite and route.bin (written by Map packs). When several regions
 * are joined, the files' overall extent includes gaps between them; this keeps "covered" to
 * the regions themselves, so lookups in a gap fall back to online maps. No file = no limit.
 */
final class Coverage {
    private final File file;
    private long stamp = -1;
    private List<double[]> boxes = new ArrayList<>();

    Coverage(File dataFile) {
        file = new File(dataFile.getParentFile(), "coverage.txt");
    }

    synchronized boolean allows(double lat, double lon) {
        long s = file.isFile() ? file.lastModified() ^ file.length() : 0;
        if (s != stamp) {
            stamp = s;
            List<double[]> b = new ArrayList<>();
            if (s != 0) {
                try (BufferedReader r = new BufferedReader(new FileReader(file))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        String[] p = line.trim().split(",");
                        if (p.length != 4) continue;
                        b.add(new double[]{Double.parseDouble(p[0]), Double.parseDouble(p[1]),
                                Double.parseDouble(p[2]), Double.parseDouble(p[3])});
                    }
                } catch (Exception ignored) {
                    b.clear();
                }
            }
            boxes = b;
        }
        if (boxes.isEmpty()) return true;
        for (double[] x : boxes) {
            if (lat >= x[0] && lat <= x[2] && lon >= x[1] && lon <= x[3]) return true;
        }
        return false;
    }
}
