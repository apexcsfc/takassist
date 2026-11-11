package com.apexcsfc.takassist.maps.core;

import android.os.Environment;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Single-file map packs ("<area>.takmap": a zip of area.sqlite, route.bin and region.json),
 * so an administrator can push maps with MDM file deployment (for example Knox Manage
 * Content, which writes into Download/). Any .takmap anywhere under Download/ is installed
 * automatically and switched on.
 */
public final class PackFiles {

    public static final String EXT = ".takmap";

    /** Finds new or changed packs under Download/, installs them, and switches them on. Returns ids installed. */
    public static List<String> importAll(MapPackManager.Listener l) {
        synchronized (MapPackManager.LOCK) {
            try (MapPackManager.FileLock fl = MapPackManager.FileLock.take()) {
                return importLocked(l);
            }
        }
    }

    private static List<String> importLocked(MapPackManager.Listener l) {
        List<String> got = new ArrayList<>();
        MapPacks.migrate();
        List<File> found = new ArrayList<>();
        scan(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), 0, found);
        for (File f : found) {
            String stamp = f.length() + ":" + f.lastModified();
            if (stamp.equals(MapPacks.importedStamp(f.getAbsolutePath()))) continue;
            try {
                String id = install(f, l);
                MapPacks.setImported(f.getAbsolutePath(), stamp);
                got.add(id);
            } catch (Exception e) {
                android.util.Log.w("TAKAssistMaps", "import " + f, e);
                if (l != null) l.onStatus("Couldn't install " + f.getName() + ": " + e.getMessage(), -1);
            }
        }
        if (!got.isEmpty()) {
            LinkedHashSet<String> want = new LinkedHashSet<>(MapPacks.activeIds());
            for (String id : want.toArray(new String[0])) if (!MapPacks.isInstalled(id)) want.remove(id);
            want.addAll(got);
            try {
                new MapPackManager(l).activate(new ArrayList<>(want));
            } catch (Exception e) {
                android.util.Log.w("TAKAssistMaps", "activate after import", e);
                if (l != null) l.onStatus("Couldn't switch on the new maps: " + e.getMessage(), -1);
            }
        }
        return got;
    }

    private static void scan(File dir, int depth, List<File> out) {
        File[] kids = dir == null ? null : dir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (k.isDirectory()) {
                if (depth < 4 && !k.getName().startsWith(".")) scan(k, depth + 1, out);
            } else if (k.getName().toLowerCase(java.util.Locale.US).endsWith(EXT)) {
                out.add(k);
            }
        }
    }

    /** Unpacks one .takmap into regions/<id>/. */
    static String install(File pack, MapPackManager.Listener l) throws Exception {
        if (l != null) l.onStatus("Installing " + pack.getName() + "…", -1);
        File stage = new File(MapPacks.regionsDir(), "." + System.nanoTime() + ".unpack");
        stage.mkdirs();
        try {
            try (ZipInputStream z = new ZipInputStream(new BufferedInputStream(new FileInputStream(pack), 1 << 16))) {
                ZipEntry e;
                byte[] buf = new byte[1 << 16];
                while ((e = z.getNextEntry()) != null) {
                    String n = e.getName();
                    if (!n.equals("area.sqlite") && !n.equals("route.bin") && !n.equals("region.json")) continue;
                    try (OutputStream o = new BufferedOutputStream(new FileOutputStream(new File(stage, n)), 1 << 16)) {
                        int r;
                        while ((r = z.read(buf)) > 0) o.write(buf, 0, r);
                    }
                }
            }
            if (!new File(stage, "area.sqlite").isFile() || !new File(stage, "route.bin").isFile()) {
                throw new IOException("not a TAK Assist map pack");
            }
            Region r;
            File rj = new File(stage, "region.json");
            if (rj.isFile()) {
                r = Region.fromJson(new JSONObject(MapPacks.readText(rj)));
            } else {
                String id = pack.getName().substring(0, pack.getName().length() - EXT.length())
                        .toLowerCase(java.util.Locale.US).replaceAll("[^a-z0-9_-]", "-");
                r = new Region(id, States.nameFor(id), "", RouteBox.of(new File(stage, "route.bin")));
                MapPacks.writeTextAtomic(rj, r.toJson().toString());
            }
            synchronized (MapPackManager.LOCK) {
                File dir = MapPacks.regionDir(r.id), old = new File(MapPacks.regionsDir(), "." + r.id + ".old");
                MapPackManager.deleteTree(old);
                if (dir.exists() && !dir.renameTo(old)) throw new IOException("can't replace " + r.id);
                if (!stage.renameTo(dir)) throw new IOException("can't save " + r.id);
                MapPackManager.deleteTree(old);
            }
            return r.id;
        } finally {
            MapPackManager.deleteTree(stage);
        }
    }

    /** Writes Download/TAKAssist/packs/<id>.takmap for an area on the phone. */
    public static File export(String id) throws IOException {
        File dir = MapPacks.regionDir(id);
        File outDir = new File(MapPacks.root(), "packs");
        outDir.mkdirs();
        File out = new File(outDir, id + EXT), part = new File(outDir, id + EXT + ".part");
        try (ZipOutputStream z = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(part), 1 << 16))) {
            for (String n : new String[]{"region.json", "area.sqlite", "route.bin"}) {
                File f = new File(dir, n);
                if (!f.isFile()) {
                    if (n.equals("region.json")) continue;
                    throw new IOException(id + " is missing " + n);
                }
                z.putNextEntry(new ZipEntry(n));
                try (InputStream in = new BufferedInputStream(new FileInputStream(f), 1 << 16)) {
                    byte[] buf = new byte[1 << 16];
                    int r;
                    while ((r = in.read(buf)) > 0) z.write(buf, 0, r);
                }
                z.closeEntry();
            }
        }
        out.delete();
        if (!part.renameTo(out)) throw new IOException("can't save " + out);
        // It's on this phone already; don't re-import our own export.
        MapPacks.setImported(out.getAbsolutePath(), out.length() + ":" + out.lastModified());
        return out;
    }

    static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
