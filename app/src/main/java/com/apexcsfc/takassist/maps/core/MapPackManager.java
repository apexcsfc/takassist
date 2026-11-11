package com.apexcsfc.takassist.maps.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Installs regions from a catalog and makes a set of installed regions the active map.
 * Blocking: call from a worker thread. One operation at a time across the whole process.
 */
public final class MapPackManager {

    public interface Listener {
        void onStatus(String text, double fraction); // fraction < 0 = indeterminate
    }

    public static final Object LOCK = new Object();

    /**
     * Cross-process lock (the plugin runs inside ATAK, the Maps app in its own process), held
     * while map files are being replaced. Re-entrant within a thread.
     */
    public static final class FileLock implements AutoCloseable {
        private static final ThreadLocal<Integer> DEPTH = new ThreadLocal<>();
        private java.io.RandomAccessFile raf;
        private java.nio.channels.FileLock lock;

        public static FileLock take() {
            FileLock f = new FileLock();
            Integer d = DEPTH.get();
            if (d == null || d == 0) {
                try {
                    MapPacks.regionsDir().mkdirs();
                    f.raf = new java.io.RandomAccessFile(new File(MapPacks.regionsDir(), ".lock"), "rw");
                    f.lock = f.raf.getChannel().lock();
                } catch (Exception ignored) {
                    // no locking on this storage; the in-process lock still applies
                }
            }
            DEPTH.set(d == null ? 1 : d + 1);
            return f;
        }

        @Override
        public void close() {
            Integer d = DEPTH.get();
            DEPTH.set(d == null ? 0 : d - 1);
            try {
                if (lock != null) lock.release();
                if (raf != null) raf.close();
            } catch (Exception ignored) {
            }
        }
    }
    private final Listener l;

    public MapPackManager(Listener l) {
        this.l = l;
    }

    void say(String s, double f) {
        if (l != null) l.onStatus(s, f);
    }

    /** Deletes an installed region (not allowed while it is part of the active map). */
    public void remove(String id) throws IOException {
        synchronized (LOCK) {
            if (MapPacks.activeIds().contains(id)) throw new IOException("Turn this region off first.");
            deleteTree(MapPacks.regionDir(id));
        }
    }

    /** Size the active road file would be for these regions. */
    public static long routeBytes(List<String> ids) throws IOException {
        List<File> f = new ArrayList<>();
        for (String id : ids) f.add(new File(MapPacks.regionDir(id), "route.bin"));
        if (f.size() == 1) return f.get(0).length();
        return RouteMerger.estimateBytes(f);
    }

    /** Makes these installed regions the active map that TAK Assist reads. */
    public void activate(List<String> ids) throws Exception {
        synchronized (LOCK) {
          try (FileLock fl = FileLock.take()) {
            if (ids.isEmpty()) throw new IOException("Pick at least one region.");
            List<File> areas = new ArrayList<>(), routes = new ArrayList<>();
            List<double[]> boxes = new ArrayList<>();
            List<Region> inst = MapPacks.installed();
            for (String id : ids) {
                if (!MapPacks.isInstalled(id)) throw new IOException(id + " isn't downloaded yet.");
                areas.add(new File(MapPacks.regionDir(id), "area.sqlite"));
                routes.add(new File(MapPacks.regionDir(id), "route.bin"));
                for (Region r : inst) if (r.id.equals(id) && r.bbox != null) boxes.add(r.bbox);
            }
            File root = MapPacks.root();
            root.mkdirs();
            if (ids.size() == 1) {
                say("Copying places…", 0.1);
                copyAtomic(areas.get(0), new File(root, "area.sqlite"));
                say("Copying roads…", 0.5);
                copyAtomic(routes.get(0), new File(root, "route.bin"));
            } else {
                long est = RouteMerger.estimateBytes(routes);
                if (est > RouteMerger.MAX_BYTES) {
                    throw new IOException(String.format(Locale.US,
                            "Those regions together need a %,d MB road file; the limit is 2,147 MB. Choose fewer regions.",
                            est / 1_000_000));
                }
                say("Joining places…", 0.05);
                AreaMerger.merge(areas, boxes, new File(root, "area.sqlite"));
                RouteMerger.merge(routes, new File(root, "route.bin"), new RouteMerger.Progress() {
                    @Override
                    public void onProgress(String what, double f) {
                        say(what + "…", 0.3 + 0.7 * f);
                    }
                });
            }
            StringBuilder cov = new StringBuilder();
            for (double[] b : boxes) {
                cov.append(String.format(Locale.US, "%.6f,%.6f,%.6f,%.6f\n", b[0], b[1], b[2], b[3]));
            }
            MapPacks.writeTextAtomic(MapPacks.coverageFile(), cov.toString());
            MapPacks.setActiveIds(ids);
            say("Ready", 1);
          }
        }
    }

    private static void copyAtomic(File from, File to) throws IOException {
        File part = new File(to.getPath() + ".part");
        try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(part)) {
            byte[] buf = new byte[1 << 20];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        if (to.exists() && !to.delete()) throw new IOException("Can't replace " + to);
        if (!part.renameTo(to)) throw new IOException("Can't save " + to);
    }

    static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }

    public static String mb(long bytes) {
        if (bytes >= 1_000_000_000L) return String.format(Locale.US, "%.1f GB", bytes / 1e9);
        return String.format(Locale.US, "%d MB", Math.round(bytes / 1e6));
    }
}
