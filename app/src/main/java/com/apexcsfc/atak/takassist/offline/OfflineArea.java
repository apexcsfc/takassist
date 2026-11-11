package com.apexcsfc.atak.takassist.offline;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Offline place / street / water lookups over area.sqlite (built by
 * scripts/build_offline_map.py). Uses a 0.01-degree grid index, so it needs no
 * SQLite extensions. Read-only; safe to call from any background thread.
 */
public class OfflineArea {

    /** One lookup result. distM is from the query's reference point. */
    public static final class Hit {
        public String name, kind, cat;
        public double lat, lon, distM;
        double score;

        public boolean isRoad() {
            return "road".equals(cat);
        }
    }

    private static final double EARTH_R = 6371008.8;
    private static final String[] ABBREV_FROM = {"street", "avenue", "road", "boulevard", "drive",
            "place", "court", "terrace", "lane", "parkway", "highway", "circle", "square",
            "northwest", "northeast", "southwest", "southeast", "north", "south", "east", "west",
            "saint", "mount", "fort", "expressway", "freeway"};
    private static final String[] ABBREV_TO = {"st", "ave", "rd", "blvd", "dr", "pl", "ct", "ter",
            "ln", "pkwy", "hwy", "cir", "sq", "nw", "ne", "sw", "se", "n", "s", "e", "w", "st",
            "mt", "ft", "expy", "fwy"};
    private static final Map<String, String> ABBREV = new HashMap<>();
    /** Words too generic to identify a place on their own. */
    private static final Set<String> GENERIC = new HashSet<>(Arrays.asList(
            "the", "and", "of", "at", "in", "on", "st", "ave", "rd", "blvd", "dr", "pl", "ct",
            "ter", "ln", "pkwy", "hwy", "cir", "sq", "nw", "ne", "sw", "se", "n", "s", "e", "w",
            "park", "station", "metro", "center", "centre", "building", "dc", "plaza", "mt", "ft"));

    static {
        for (int i = 0; i < ABBREV_FROM.length; i++) ABBREV.put(ABBREV_FROM[i], ABBREV_TO[i]);
    }

    private final SQLiteDatabase db;
    private final double south, west, north, east, cell;
    private final File file;

    public static OfflineArea open(File f) {
        SQLiteDatabase db = SQLiteDatabase.openDatabase(f.getAbsolutePath(), null,
                SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS);
        return new OfflineArea(f, db);
    }

    private final Coverage coverage;

    private OfflineArea(File f, SQLiteDatabase db) {
        this.file = f;
        this.coverage = new Coverage(f);
        this.db = db;
        Map<String, String> meta = new HashMap<>();
        try (Cursor c = db.rawQuery("SELECT k, v FROM meta", null)) {
            while (c.moveToNext()) meta.put(c.getString(0), c.getString(1));
        }
        south = Double.parseDouble(meta.get("south"));
        west = Double.parseDouble(meta.get("west"));
        north = Double.parseDouble(meta.get("north"));
        east = Double.parseDouble(meta.get("east"));
        cell = Double.parseDouble(meta.containsKey("cell") ? meta.get("cell") : "0.01");
    }

    public void close() {
        db.close();
    }

    public File getFile() {
        return file;
    }

    public boolean covers(double lat, double lon) {
        return lat >= south && lat <= north && lon >= west && lon <= east && coverage.allows(lat, lon);
    }

    public String extent() {
        return String.format(Locale.US, "%.3f,%.3f to %.3f,%.3f", south, west, north, east);
    }

    // ------------------------------------------------------------- name search

    /** Same normalization as the builder: lowercase words, common US street abbreviations. */
    public static String norm(String s) {
        StringBuilder out = new StringBuilder();
        StringBuilder word = new StringBuilder();
        String lower = s == null ? "" : s.toLowerCase(Locale.US);
        for (int i = 0; i <= lower.length(); i++) {
            char ch = i < lower.length() ? lower.charAt(i) : ' ';
            if (Character.isLetterOrDigit(ch)) {
                word.append(ch);
            } else if (word.length() > 0) {
                String w = word.toString();
                String a = ABBREV.get(w);
                if (out.length() > 0) out.append(' ');
                out.append(a != null ? a : w);
                word.setLength(0);
            }
        }
        return out.toString();
    }

    /**
     * Name lookup near (lat, lon): exact/prefix/substring matches first, then
     * similar names within 10 km (e.g. "Logan Square" finds "Logan Circle").
     * Returns at most `limit` hits, best first. matchOut[0] is set to
     * "exact" or "similar" (or "none").
     */
    public List<Hit> search(String query, double lat, double lon, int limit, String[] matchOut) {
        String q = norm(query);
        matchOut[0] = "none";
        if (q.isEmpty()) return new ArrayList<>();

        List<Hit> hits = new ArrayList<>();
        collectNamed("%" + q + "%", lat, lon, hits);
        int qWords = q.split(" ").length;
        String qBase = stripQuadrant(q);
        for (Hit h : hits) {
            String n = norm(h.name);
            boolean equal = n.equals(q) || (h.isRoad() && stripQuadrant(n).equals(qBase));
            h.score = (equal ? 30 : n.startsWith(q) ? 20 : 10) + kindBonus(h) + hintBonus(q, h)
                    + (n.split(" ").length == qWords ? 3 : 0) - distancePenalty(h);
        }
        if (!hits.isEmpty()) {
            matchOut[0] = "exact";
            return best(hits, limit, Double.MAX_VALUE);
        }

        // Similar names: any distinctive word in common.
        hits.clear();
        List<String> words = new ArrayList<>();
        for (String w : q.split(" ")) {
            if (w.length() >= 3 && !GENERIC.contains(w)) words.add(w);
        }
        for (String w : words) collectNamed("%" + w + "%", lat, lon, hits);
        for (Hit h : hits) {
            String n = norm(h.name);
            int sc = 0;
            for (String w : words) if (n.contains(w)) sc += 10;
            for (String w : q.split(" ")) if (w.length() >= 2 && (" " + n + " ").contains(" " + w + " ")) sc += 2;
            if (n.split(" ").length == qWords) sc += 3;
            h.score = sc + kindBonus(h) + hintBonus(q, h) - distancePenalty(h);
        }
        List<Hit> out = best(hits, limit, 10_000);
        if (!out.isEmpty()) matchOut[0] = "similar";
        return out;
    }

    private static final Set<String> LANDMARK_KINDS = new HashSet<>(Arrays.asList(
            "neighbourhood", "suburb", "quarter", "locality", "square", "city", "town", "village",
            "park", "garden", "nature reserve", "attraction", "museum", "monument", "memorial",
            "viewpoint", "stadium", "university", "college", "hospital", "subway station",
            "station", "light rail station", "train station", "lake", "reservoir", "river", "zoo",
            "townhall", "courthouse", "library", "island"));
    private static final Set<String> MINOR_KINDS = new HashSet<>(Arrays.asList(
            "bus stop", "doctors", "dentist", "restaurant", "fast food", "cafe", "bar", "pub",
            "atm", "bank", "parking", "toilets", "drinking water"));

    /** Street names without a trailing DC-style quadrant ("14th st nw" -> "14th st"). */
    private static String stripQuadrant(String n) {
        return n.replaceAll(" (nw|ne|sw|se)$", "");
    }

    /** Nearer is better: lose up to 10 points over the first 10 km. */
    private static double distancePenalty(Hit h) {
        return Math.min(10, h.distM / 1000.0);
    }

    /** Words in the query that say what kind of place is meant. */
    private static double hintBonus(String q, Hit h) {
        String k = h.kind == null ? "" : h.kind;
        String sp = " " + q + " ";
        if ((sp.contains(" metro ") || sp.contains(" station ") || sp.contains(" subway ")
                || sp.contains(" train ")) && k.contains("station")) return 8;
        if (sp.contains(" park ") && (k.equals("park") || k.equals("garden"))) return 6;
        if (sp.contains(" hospital ") && k.equals("hospital")) return 6;
        if ((sp.contains(" school ") || sp.contains(" university ") || sp.contains(" college "))
                && (k.equals("school") || k.equals("university") || k.equals("college"))) return 6;
        if (sp.contains(" church ") && k.equals("place of worship")) return 6;
        if (sp.contains(" museum ") && k.equals("museum")) return 6;
        return 0;
    }

    /** Prefer landmarks/areas over shops and street furniture with similar names. */
    private static int kindBonus(Hit h) {
        if (h.isRoad()) return 1;
        String k = h.kind == null ? "" : h.kind;
        if (LANDMARK_KINDS.contains(k)) return 8;
        if (MINOR_KINDS.contains(k) || k.endsWith(" shop")) return -2;
        return 3;
    }

    private void collectNamed(String like, double lat, double lon, List<Hit> out) {
        try (Cursor c = db.rawQuery(
                "SELECT name, kind, cat, lat, lon FROM features WHERE name_lc LIKE ? AND name <> '' LIMIT 500",
                new String[]{like})) {
            while (c.moveToNext()) {
                Hit h = new Hit();
                h.name = c.getString(0);
                h.kind = c.getString(1);
                h.cat = c.getString(2);
                h.lat = c.getDouble(3);
                h.lon = c.getDouble(4);
                h.distM = dist(lat, lon, h.lat, h.lon);
                out.add(h);
            }
        }
        try (Cursor c = db.rawQuery(
                "SELECT name, kind, coords FROM shapes WHERE cat = 'road' AND name_lc LIKE ? LIMIT 800",
                new String[]{like})) {
            while (c.moveToNext()) {
                Hit h = nearestOnChunk(c.getBlob(2), lat, lon);
                h.name = c.getString(0);
                h.kind = c.getString(1);
                h.cat = "road";
                out.add(h);
            }
        }
    }

    /** Dedupe by name+kind keeping the nearest, sort by score then distance. */
    private static List<Hit> best(List<Hit> hits, int limit, double maxDist) {
        Map<String, Hit> byKey = new HashMap<>();
        for (Hit h : hits) {
            if (h.distM > maxDist) continue;
            String key = h.name.toLowerCase(Locale.US) + "|" + (h.isRoad() ? "road" : h.kind);
            Hit cur = byKey.get(key);
            if (cur == null || h.distM < cur.distM) byKey.put(key, h);
        }
        List<Hit> list = new ArrayList<>(byKey.values());
        Collections.sort(list, new Comparator<Hit>() {
            @Override
            public int compare(Hit a, Hit b) {
                int c = Double.compare(b.score, a.score);
                return c != 0 ? c : Double.compare(a.distM, b.distM);
            }
        });
        return list.size() > limit ? new ArrayList<>(list.subList(0, limit)) : list;
    }

    // ---------------------------------------------------------- spatial lookups

    /** Named features (not streets) within radius, nearest first, one per name. */
    public List<Hit> nearby(double lat, double lon, int radiusM, int limit) {
        List<Hit> hits = new ArrayList<>();
        int[] r = cellRange(lat, lon, radiusM);
        try (Cursor c = db.rawQuery(
                "SELECT name, kind, cat, lat, lon FROM features WHERE cx BETWEEN ? AND ? "
                        + "AND cy BETWEEN ? AND ? AND name <> ''",
                strings(r))) {
            while (c.moveToNext()) {
                Hit h = new Hit();
                h.name = c.getString(0);
                h.kind = c.getString(1);
                h.cat = c.getString(2);
                h.lat = c.getDouble(3);
                h.lon = c.getDouble(4);
                h.distM = dist(lat, lon, h.lat, h.lon);
                if (h.distM <= radiusM) hits.add(h);
            }
        }
        return byDistance(hits, limit);
    }

    /** Named streets within radius, one row per street at its closest point. */
    public List<Hit> roads(double lat, double lon, int radiusM, int limit) {
        return shapesNear("road", lat, lon, radiusM, limit, true);
    }

    /** Nearest neighborhood/suburb label within 2.5 km, or null. */
    public Hit neighborhood(double lat, double lon) {
        int[] r = cellRange(lat, lon, 2500);
        Hit best = null;
        try (Cursor c = db.rawQuery(
                "SELECT name, kind, lat, lon FROM features WHERE cx BETWEEN ? AND ? AND cy BETWEEN ? AND ? "
                        + "AND kind IN ('neighbourhood','suburb','quarter','village','town','city')",
                strings(r))) {
            while (c.moveToNext()) {
                Hit h = new Hit();
                h.name = c.getString(0);
                h.kind = c.getString(1);
                h.lat = c.getDouble(2);
                h.lon = c.getDouble(3);
                h.distM = dist(lat, lon, h.lat, h.lon);
                // prefer the smallest kind of area when roughly as close
                double rank = h.distM * ("city".equals(h.kind) || "town".equals(h.kind) ? 3 : 1);
                if (h.distM <= 2500 && (best == null || rank < best.score)) {
                    best = h;
                    best.score = (int) rank;
                }
            }
        }
        return best;
    }

    /**
     * Closest features of a find_nearest category, searching outward.
     * Water and parks are measured to their outline, other categories to their point.
     */
    public List<Hit> nearest(String category, double lat, double lon, int[] radii, int limit,
                             int[] searchedOut) {
        boolean outlines = "water".equals(category) || "park".equals(category);
        for (int radius : radii) {
            searchedOut[0] = radius;
            List<Hit> hits = new ArrayList<>();
            if (outlines) hits.addAll(shapesNear(category, lat, lon, radius, 50, false));
            int[] r = cellRange(lat, lon, radius);
            String[] args = new String[]{category, String.valueOf(r[0]), String.valueOf(r[1]),
                    String.valueOf(r[2]), String.valueOf(r[3])};
            try (Cursor c = db.rawQuery(
                    "SELECT name, kind, lat, lon FROM features WHERE cat = ? AND cx BETWEEN ? AND ? "
                            + "AND cy BETWEEN ? AND ?", args)) {
                while (c.moveToNext()) {
                    Hit h = new Hit();
                    h.name = c.getString(0);
                    h.kind = c.getString(1);
                    h.cat = category;
                    h.lat = c.getDouble(2);
                    h.lon = c.getDouble(3);
                    h.distM = dist(lat, lon, h.lat, h.lon);
                    if (h.distM <= radius) hits.add(h);
                }
            }
            if (outlines) {
                // A named water/park feature also has a label point; keep its outline distance.
                List<Hit> keep = new ArrayList<>();
                Set<String> outlined = new HashSet<>();
                for (Hit h : hits) if (h.cat == null || "outline".equals(h.cat)) outlined.add(h.name);
                for (Hit h : hits) {
                    if (!"outline".equals(h.cat) && !h.name.isEmpty() && outlined.contains(h.name)) continue;
                    keep.add(h);
                }
                hits = keep;
            }
            for (Hit h : hits) {
                if (h.name == null || h.name.isEmpty()) h.name = "unnamed " + h.kind;
                h.cat = category;
            }
            if (!hits.isEmpty()) return byDistance(hits, limit);
        }
        return new ArrayList<>();
    }

    private static final String MAJOR_WATER_KINDS =
            "('river','lake','reservoir','canal','bay','lagoon','oxbow','harbour','basin')";

    /**
     * Nearest named, significant water body (river, lake, reservoir, canal, bay),
     * searching outward to 25 km. Skips fountains, pools and stormwater ponds.
     */
    public List<Hit> nearestMajorWater(double lat, double lon, int limit) {
        for (int radius : new int[]{2000, 6000, 15000, 25000}) {
            int[] r = cellRange(lat, lon, radius + 1500);
            Map<String, Hit> best = new HashMap<>();
            try (Cursor c = db.rawQuery(
                    "SELECT name, kind, coords FROM shapes WHERE cat = 'water' AND name <> '' "
                            + "AND kind IN " + MAJOR_WATER_KINDS
                            + " AND cx BETWEEN ? AND ? AND cy BETWEEN ? AND ?", strings(r))) {
                while (c.moveToNext()) {
                    Hit h = nearestOnChunk(c.getBlob(2), lat, lon);
                    if (h.distM > radius) continue;
                    h.name = c.getString(0);
                    h.kind = c.getString(1);
                    h.cat = "water";
                    Hit cur = best.get(h.name);
                    if (cur == null || h.distM < cur.distM) best.put(h.name, h);
                }
            }
            if (!best.isEmpty()) return byDistance(new ArrayList<>(best.values()), limit);
        }
        return new ArrayList<>();
    }

    private List<Hit> shapesNear(String cat, double lat, double lon, int radiusM, int limit,
                                 boolean namedOnly) {
        int[] r = cellRange(lat, lon, radiusM + 1500); // chunks may extend past their cell
        Map<String, Hit> best = new HashMap<>();
        String sql = "SELECT name, kind, coords FROM shapes WHERE cat = ? AND cx BETWEEN ? AND ? "
                + "AND cy BETWEEN ? AND ?" + (namedOnly ? " AND name <> ''" : "");
        String[] args = new String[]{cat, String.valueOf(r[0]), String.valueOf(r[1]),
                String.valueOf(r[2]), String.valueOf(r[3])};
        try (Cursor c = db.rawQuery(sql, args)) {
            while (c.moveToNext()) {
                Hit h = nearestOnChunk(c.getBlob(2), lat, lon);
                if (h.distM > radiusM) continue;
                h.name = c.getString(0);
                h.kind = c.getString(1);
                h.cat = namedOnly ? cat : "outline";
                String key = h.name;
                if (h.name.isEmpty()) {
                    // merge unnamed chunks that are close together into one
                    key = "~" + h.kind + Math.round(h.lat * 200) + "," + Math.round(h.lon * 200);
                }
                Hit cur = best.get(key);
                if (cur == null || h.distM < cur.distM) best.put(key, h);
            }
        }
        return byDistance(new ArrayList<>(best.values()), limit);
    }

    // ------------------------------------------------------------------ geometry

    /** Closest point on a stored chunk (int32 lat/lon *1e7 pairs) to (lat, lon). */
    static Hit nearestOnChunk(byte[] blob, double lat, double lon) {
        ByteBuffer bb = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN);
        int n = blob.length / 8;
        double kx = Math.cos(Math.toRadians(lat));
        double px = lon * kx, py = lat;
        double ax = bb.getInt(4) / 1e7 * kx, ay = bb.getInt(0) / 1e7;
        double bestD = Double.MAX_VALUE, bx = ax, by = ay;
        if (n == 1) {
            bestD = 0;
        }
        for (int i = 1; i < n; i++) {
            double cy = bb.getInt(8 * i) / 1e7, cx = bb.getInt(8 * i + 4) / 1e7 * kx;
            double dx = cx - ax, dy = cy - ay;
            double len2 = dx * dx + dy * dy;
            double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / len2));
            double qx = ax + t * dx, qy = ay + t * dy;
            double d = (qx - px) * (qx - px) + (qy - py) * (qy - py);
            if (d < bestD) {
                bestD = d;
                bx = qx;
                by = qy;
            }
            ax = cx;
            ay = cy;
        }
        Hit h = new Hit();
        h.lat = by;
        h.lon = bx / kx;
        h.distM = dist(lat, lon, h.lat, h.lon);
        return h;
    }

    private int[] cellRange(double lat, double lon, int radiusM) {
        double dLat = radiusM / 111_320.0;
        double dLon = dLat / Math.max(0.2, Math.cos(Math.toRadians(lat)));
        return new int[]{
                (int) Math.floor((lon - dLon) / cell), (int) Math.floor((lon + dLon) / cell),
                (int) Math.floor((lat - dLat) / cell), (int) Math.floor((lat + dLat) / cell)};
    }

    private static String[] strings(int[] r) {
        return new String[]{String.valueOf(r[0]), String.valueOf(r[1]),
                String.valueOf(r[2]), String.valueOf(r[3])};
    }

    private static List<Hit> byDistance(List<Hit> hits, int limit) {
        Map<String, Hit> byName = new HashMap<>();
        List<Hit> unnamed = new ArrayList<>();
        for (Hit h : hits) {
            if (h.name == null || h.name.isEmpty() || h.name.startsWith("unnamed ")) {
                unnamed.add(h);
                continue;
            }
            Hit cur = byName.get(h.name);
            if (cur == null || h.distM < cur.distM) byName.put(h.name, h);
        }
        List<Hit> list = new ArrayList<>(byName.values());
        list.addAll(unnamed);
        Collections.sort(list, new Comparator<Hit>() {
            @Override
            public int compare(Hit a, Hit b) {
                return Double.compare(a.distM, b.distM);
            }
        });
        return list.size() > limit ? new ArrayList<>(list.subList(0, limit)) : list;
    }

    static double dist(double lat1, double lon1, double lat2, double lon2) {
        return OfflineRouter.dist(lat1, lon1, lat2, lon2);
    }
}
