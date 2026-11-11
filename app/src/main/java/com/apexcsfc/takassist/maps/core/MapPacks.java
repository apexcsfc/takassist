package com.apexcsfc.takassist.maps.core;

import android.os.Environment;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Where map packs live on the phone, and what is installed and active. Shared by the TAK
 * Assist plugin and the TAK Assist Maps app: both read and write the same folder, so either
 * one can install a region and the other sees it.
 *
 * <pre>
 * Download/TAKAssist/area.sqlite, route.bin   the ACTIVE map (what TAK Assist reads)
 * Download/TAKAssist/coverage.txt             one "south,west,north,east" line per active area
 * Download/TAKAssist/regions/&lt;id&gt;/          an area on the phone: area.sqlite, route.bin, region.json
 * Download/TAKAssist/regions/state.json       {"active": [...], "imported": {...}}
 * Download/** /*.takmap                        single-file map packs (e.g. pushed by Knox Manage);
 *                                             picked up and installed automatically
 * </pre>
 * The Download folder is used so maps are easy to find and so MDM file deployment (which on
 * Android 10+ can only write to public folders) can drop packs straight onto the phone.
 * Older installs used /sdcard/atak/llm; they are moved here on first run.
 */
public final class MapPacks {

    public static File root() {
        return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "TAKAssist");
    }

    /** Where earlier versions kept maps (next to the models). */
    public static File legacyRoot() {
        return new File(Environment.getExternalStorageDirectory(), "atak/llm");
    }

    /** The folder holding the active area.sqlite / route.bin that TAK Assist should read. */
    public static File activeDir() {
        File r = root();
        if (new File(r, "route.bin").isFile() || new File(r, "area.sqlite").isFile()) return r;
        File l = legacyRoot();
        if (new File(l, "route.bin").isFile() || new File(l, "area.sqlite").isFile()) return l;
        return r;
    }

    public static File activeFile(String name) {
        return new File(activeDir(), name);
    }

    /** Moves maps from the old atak/llm folder to Download/TAKAssist, once. */
    public static synchronized void migrate() {
        File r = root(), l = legacyRoot();
        File oldRegions = new File(l, "regions");
        boolean hasOld = oldRegions.isDirectory() || new File(l, "route.bin").isFile();
        if (!hasOld || new File(r, "regions").isDirectory()) return;
        r.mkdirs();
        if (oldRegions.isDirectory()) oldRegions.renameTo(new File(r, "regions"));
        for (String n : new String[]{"area.sqlite", "route.bin", "coverage.txt"}) {
            File f = new File(l, n);
            if (f.isFile()) f.renameTo(new File(r, n));
        }
    }

    public static File regionsDir() {
        return new File(root(), "regions");
    }

    public static File regionDir(String id) {
        return new File(regionsDir(), id);
    }

    static File stateFile() {
        return new File(regionsDir(), "state.json");
    }

    public static File coverageFile() {
        return new File(root(), "coverage.txt");
    }

    // ----------------------------------------------------------------- state.json

    /** "size:mtime" recorded when a .takmap file was imported, or "". */
    static synchronized String importedStamp(String path) {
        JSONObject im = readState().optJSONObject("imported");
        return im == null ? "" : im.optString(path, "");
    }

    static synchronized void setImported(String path, String stamp) throws IOException {
        JSONObject s = readState();
        try {
            JSONObject im = s.optJSONObject("imported");
            if (im == null) {
                im = new JSONObject();
                s.put("imported", im);
            }
            im.put(path, stamp);
        } catch (Exception ignored) {
        }
        writeState(s);
    }

    public static synchronized List<String> activeIds() {
        JSONArray a = readState().optJSONArray("active");
        List<String> out = new ArrayList<>();
        if (a != null) for (int i = 0; i < a.length(); i++) out.add(a.optString(i));
        return out;
    }

    static synchronized void setActiveIds(List<String> ids) throws IOException {
        JSONObject s = readState();
        try {
            s.put("active", new JSONArray(ids));
        } catch (Exception ignored) {
        }
        writeState(s);
    }

    private static JSONObject readState() {
        try {
            return new JSONObject(readText(stateFile()));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static void writeState(JSONObject s) throws IOException {
        regionsDir().mkdirs();
        writeTextAtomic(stateFile(), s.toString());
    }

    // ------------------------------------------------------------- installed regions

    /** Regions whose folder holds both files. Older folders without region.json get one from route.bin. */
    public static List<Region> installed() {
        List<Region> out = new ArrayList<>();
        File[] dirs = regionsDir().listFiles();
        if (dirs == null) return out;
        for (File d : dirs) {
            if (!d.isDirectory()) continue;
            if (!new File(d, "area.sqlite").isFile() || !new File(d, "route.bin").isFile()) continue;
            Region r = null;
            try {
                r = Region.fromJson(new JSONObject(readText(new File(d, "region.json"))));
            } catch (Exception ignored) {
            }
            if (r == null) {
                double[] bbox = null;
                try {
                    bbox = RouteBox.of(new File(d, "route.bin"));
                } catch (IOException ignored) {
                }
                r = new Region(d.getName(), prettyName(d.getName()), "", bbox);
            }
            out.add(r);
        }
        Collections.sort(out, new Comparator<Region>() {
            @Override
            public int compare(Region a, Region b) {
                return a.name.compareToIgnoreCase(b.name);
            }
        });
        return out;
    }

    public static boolean isInstalled(String id) {
        File d = regionDir(id);
        return new File(d, "area.sqlite").isFile() && new File(d, "route.bin").isFile();
    }

    public static long installedBytes(String id) {
        File d = regionDir(id);
        return new File(d, "area.sqlite").length() + new File(d, "route.bin").length();
    }

    static String prettyName(String id) {
        if ("dc-md-va".equals(id)) return "Washington DC, Maryland & Virginia";
        StringBuilder sb = new StringBuilder();
        for (String w : id.split("[-_]")) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(w.length() <= 2 ? w.toUpperCase(Locale.US) : Character.toUpperCase(w.charAt(0)) + w.substring(1));
        }
        return sb.toString();
    }

    /** Regions in the coverage file, i.e. what the active map was built from. */
    public static List<double[]> activeBoxes() {
        List<double[]> out = new ArrayList<>();
        try {
            for (String line : readText(coverageFile()).split("\n")) {
                String[] p = line.trim().split(",");
                if (p.length != 4) continue;
                out.add(new double[]{Double.parseDouble(p[0]), Double.parseDouble(p[1]),
                        Double.parseDouble(p[2]), Double.parseDouble(p[3])});
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    // ------------------------------------------------------------------ small I/O

    static String readText(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) Math.min(f.length(), 1 << 20)];
            int n = 0, r;
            while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
            return new String(buf, 0, n, StandardCharsets.UTF_8);
        }
    }

    static void writeTextAtomic(File f, String text) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        try (OutputStream o = new FileOutputStream(tmp)) {
            o.write(text.getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(f)) {
            f.delete();
            if (!tmp.renameTo(f)) throw new IOException("Couldn't write " + f);
        }
    }

    static Set<String> set(List<String> ids) {
        return new LinkedHashSet<>(ids);
    }
}
