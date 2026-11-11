package com.apexcsfc.atak.takassist.talk.offline;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Offline A* router over route.bin (built by scripts/build_offline_map.py).
 * The file is memory-mapped, so it costs almost no Java heap.
 * Pure Java (no Android APIs) so it can be tested on a desktop.
 *
 * route.bin layout (little-endian):
 *   header (64 B): "LLMR", version, nNodes, nEdges, nShape, nNames,
 *                  minLatE7, minLonE7, cellE7, gridW, gridH, nCellNodes
 *   int  lat[nNodes], lon[nNodes]          (degrees * 1e7)
 *   int  firstEdge[nNodes + 1]             (CSR: edges of node i are [first[i], first[i+1]) )
 *   edge[nEdges] 20 B: target i32, lenDm i32, nameId i32, shapeStart i32,
 *                      shapeCount u16, flags u8, roadClass u8
 *        flags: bit0 foot, bit1 bike, bit2 car, bit3 shape stored reversed
 *   int  shapeLat[nShape], shapeLon[nShape]
 *   int  nameOff[nNames + 1], then UTF-8 bytes, padded to 4
 *   int  cellStart[gridW*gridH + 1], cellNodes[nCellNodes]
 */
public class OfflineRouter {

    public static final int FOOT = 0, BIKE = 1, CAR = 2;
    private static final int EDGE_BYTES = 20;
    private static final double EARTH_R = 6371008.8;

    public static final class Step {
        public final String text;
        public final double distanceM;

        Step(String text, double distanceM) {
            this.text = text;
            this.distanceM = distanceM;
        }
    }

    public static final class Route {
        public double distanceM;
        public double seconds;
        public double snapFromM, snapToM;
        public final List<double[]> points = new ArrayList<>(); // {lat, lon}
        public final List<Step> steps = new ArrayList<>();
    }

    private final ByteBuffer b;
    private final int nNodes, nEdges, nShape, nNames;
    private final int minLat, minLon, cell, gridW, gridH;
    private final int offLat, offLon, offFirst, offEdges, offShapeLat, offShapeLon,
            offNameOff, offNameBytes, offCellStart, offCellNodes;

    // A* scratch arrays, reused between queries (guarded by synchronized route()).
    private float[] g;
    private int[] prevEdge;
    private int[] stamp;
    private int generation;

    public static OfflineRouter open(File f) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(f, "r");
             FileChannel ch = raf.getChannel()) {
            MappedByteBuffer m = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size());
            OfflineRouter r = new OfflineRouter(m);
            r.coverage = new Coverage(f);
            return r;
        }
    }

    OfflineRouter(ByteBuffer buf) throws IOException {
        b = buf.order(ByteOrder.LITTLE_ENDIAN);
        byte[] magic = new byte[4];
        b.position(0);
        b.get(magic);
        if (!"LLMR".equals(new String(magic, StandardCharsets.US_ASCII)) || b.getInt(4) != 1) {
            throw new IOException("Not a route.bin v1 file");
        }
        nNodes = b.getInt(8);
        nEdges = b.getInt(12);
        nShape = b.getInt(16);
        nNames = b.getInt(20);
        minLat = b.getInt(24);
        minLon = b.getInt(28);
        cell = b.getInt(32);
        gridW = b.getInt(36);
        gridH = b.getInt(40);
        int nCellNodes = b.getInt(44);

        offLat = 64;
        offLon = offLat + 4 * nNodes;
        offFirst = offLon + 4 * nNodes;
        offEdges = offFirst + 4 * (nNodes + 1);
        offShapeLat = offEdges + EDGE_BYTES * nEdges;
        offShapeLon = offShapeLat + 4 * nShape;
        offNameOff = offShapeLon + 4 * nShape;
        offNameBytes = offNameOff + 4 * (nNames + 1);
        int nameLen = b.getInt(offNameOff + 4 * nNames);
        int afterNames = offNameBytes + nameLen;
        offCellStart = afterNames + ((4 - (afterNames % 4)) % 4);
        offCellNodes = offCellStart + 4 * (gridW * gridH + 1);
        if (offCellNodes + 4L * nCellNodes > b.capacity()) {
            throw new IOException("route.bin is truncated");
        }
    }

    public int nodeCount() {
        return nNodes;
    }

    /** True when the point is inside the graph's extent. */
    public boolean covers(double lat, double lon) {
        int la = (int) Math.round(lat * 1e7), lo = (int) Math.round(lon * 1e7);
        return la >= minLat && lo >= minLon
                && la < minLat + (long) gridH * cell && lo < minLon + (long) gridW * cell
                && (coverage == null || coverage.allows(lat, lon));
    }

    /** Set by open(); limits covers() to the joined regions (see Coverage). */
    private Coverage coverage;

    // ------------------------------------------------------------------ access

    private double lat(int n) { return b.getInt(offLat + 4 * n) / 1e7; }
    private double lon(int n) { return b.getInt(offLon + 4 * n) / 1e7; }
    private int first(int n) { return b.getInt(offFirst + 4 * n); }
    private int eTarget(int e) { return b.getInt(offEdges + EDGE_BYTES * e); }
    private int eLenDm(int e) { return b.getInt(offEdges + EDGE_BYTES * e + 4); }
    private int eName(int e) { return b.getInt(offEdges + EDGE_BYTES * e + 8); }
    private int eShapeStart(int e) { return b.getInt(offEdges + EDGE_BYTES * e + 12); }
    private int eShapeCount(int e) { return b.getShort(offEdges + EDGE_BYTES * e + 16) & 0xFFFF; }
    private int eFlags(int e) { return b.get(offEdges + EDGE_BYTES * e + 18) & 0xFF; }
    private int eClass(int e) { return b.get(offEdges + EDGE_BYTES * e + 19) & 0xFF; }

    private String name(int id) {
        int s = b.getInt(offNameOff + 4 * id), t = b.getInt(offNameOff + 4 * (id + 1));
        byte[] bytes = new byte[t - s];
        for (int i = 0; i < bytes.length; i++) bytes[i] = b.get(offNameBytes + s + i);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static boolean allowed(int flags, int mode) {
        return (flags & (1 << mode)) != 0;
    }

    /** Travel speed in m/s for an edge, or 0 if not usable. */
    private static double speed(int cls, int mode) {
        if (mode == FOOT) return 1.35;
        if (mode == BIKE) return (cls >= 11) ? 3.0 : 4.5;
        switch (cls) {
            case 1: return 27.8;  // motorway ~100 km/h
            case 2: return 22.2;  // trunk 80
            case 3: return 15.3;  // primary 55
            case 4: return 13.9;  // secondary 50
            case 5: return 12.5;  // tertiary 45
            case 6: return 11.1;  // unclassified 40
            case 7: return 9.7;   // residential 35
            case 8: case 9: return 4.2; // living street / service 15
            default: return 0;
        }
    }

    private static double maxSpeed(int mode) {
        return mode == FOOT ? 1.35 : mode == BIKE ? 4.5 : 27.8;
    }

    // --------------------------------------------------------------- snapping

    /** Nearest graph node that has an outgoing edge usable in this mode; -1 if none nearby. */
    int snap(double lat, double lon, int mode, double[] distOut) {
        int la = (int) Math.round(lat * 1e7), lo = (int) Math.round(lon * 1e7);
        int cx = (int) Math.floor((lo - (double) minLon) / cell);
        int cy = (int) Math.floor((la - (double) minLat) / cell);
        int best = -1;
        double bestD = Double.MAX_VALUE;
        for (int ring = 0; ring <= 4; ring++) {
            for (int y = cy - ring; y <= cy + ring; y++) {
                for (int x = cx - ring; x <= cx + ring; x++) {
                    if (Math.max(Math.abs(x - cx), Math.abs(y - cy)) != ring) continue;
                    if (x < 0 || y < 0 || x >= gridW || y >= gridH) continue;
                    int c = y * gridW + x;
                    int s = b.getInt(offCellStart + 4 * c), t = b.getInt(offCellStart + 4 * (c + 1));
                    for (int k = s; k < t; k++) {
                        int n = b.getInt(offCellNodes + 4 * k);
                        if (!hasEdge(n, mode)) continue;
                        double d = dist(lat, lon, lat(n), lon(n));
                        if (d < bestD) {
                            bestD = d;
                            best = n;
                        }
                    }
                }
            }
            // A node found in ring r is only guaranteed nearest after checking ring r+1.
            if (best >= 0 && bestD < ring * cellMeters()) break;
        }
        distOut[0] = bestD;
        return best;
    }

    private double cellMeters() {
        return cell / 1e7 * 111_320.0 * 0.75;
    }

    private boolean hasEdge(int n, int mode) {
        for (int e = first(n), end = first(n + 1); e < end; e++) {
            if (allowed(eFlags(e), mode)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ A*

    /** Up to k nearest usable nodes, within (nearest + 300 m), as {node, distance}. */
    private List<double[]> snapCandidates(double lat, double lon, int mode, int k) {
        return snapCandidates(lat, lon, mode, k, 250);
    }

    private List<double[]> snapCandidates(double lat, double lon, int mode, int k, double extra) {
        int la = (int) Math.round(lat * 1e7), lo = (int) Math.round(lon * 1e7);
        int cx = (int) Math.floor((lo - (double) minLon) / cell);
        int cy = (int) Math.floor((la - (double) minLat) / cell);
        List<double[]> found = new ArrayList<>();
        for (int y = cy - 3; y <= cy + 3; y++) {
            for (int x = cx - 3; x <= cx + 3; x++) {
                if (x < 0 || y < 0 || x >= gridW || y >= gridH) continue;
                int c = y * gridW + x;
                int s = b.getInt(offCellStart + 4 * c), t = b.getInt(offCellStart + 4 * (c + 1));
                for (int q = s; q < t; q++) {
                    int n = b.getInt(offCellNodes + 4 * q);
                    if (hasEdge(n, mode)) found.add(new double[]{n, dist(lat, lon, lat(n), lon(n))});
                }
            }
        }
        if (found.isEmpty()) {
            double[] d = new double[1];
            int n = snap(lat, lon, mode, d);
            if (n >= 0) found.add(new double[]{n, d[0]});
            return found;
        }
        java.util.Collections.sort(found, new java.util.Comparator<double[]>() {
            @Override
            public int compare(double[] p, double[] q) {
                return Double.compare(p[1], q[1]);
            }
        });
        double limit = found.get(0)[1] + extra;
        List<double[]> out = new ArrayList<>();
        for (double[] f : found) {
            if (out.size() >= k || f[1] > limit) break;
            out.add(f);
        }
        return out;
    }

    /**
     * Shortest-time route. Several road points near each end are tried at once
     * (multi-source / multi-target A*), so an end that lies next to a restricted or
     * disconnected road still connects through the next-closest usable one.
     */
    public synchronized Route route(double fromLat, double fromLon, double toLat, double toLon,
                                    int mode) {
        Route r = route(fromLat, fromLon, toLat, toLon, mode,
                snapCandidates(fromLat, fromLon, mode, 8), snapCandidates(toLat, toLon, mode, 8));
        if (r != null) return r;
        // Ends may sit in a place this mode can't use (e.g. no bikes in a cemetery):
        // retry with road points up to ~1.5 km away.
        return route(fromLat, fromLon, toLat, toLon, mode,
                snapCandidates(fromLat, fromLon, mode, 40, 1500),
                snapCandidates(toLat, toLon, mode, 40, 1500));
    }

    private Route route(double fromLat, double fromLon, double toLat, double toLon, int mode,
                        List<double[]> srcs, List<double[]> dsts) {
        if (srcs.isEmpty() || dsts.isEmpty()) return null;

        if (g == null) {
            g = new float[nNodes];
            prevEdge = new int[nNodes];
            stamp = new int[nNodes];
        }
        if (++generation == Integer.MAX_VALUE) {
            Arrays.fill(stamp, 0);
            generation = 1;
        }
        final int gen = generation;
        final double vmax = maxSpeed(mode);
        final double walk = 1.35;
        final double offRoad = 4.0; // off-road distance at the ends counts 4x, so we stay on roads

        java.util.Map<Integer, Double> targetSnap = new java.util.HashMap<>();
        for (double[] d : dsts) targetSnap.put((int) d[0], d[1]);

        Heap heap = new Heap();
        for (double[] sd : srcs) {
            int n = (int) sd[0];
            float g0 = (float) (sd[1] * offRoad / walk);
            if (stamp[n] == gen && g[n] <= g0) continue;
            stamp[n] = gen;
            g[n] = g0;
            prevEdge[n] = -1;
            heap.push(n, (float) (g0 + dist(lat(n), lon(n), toLat, toLon) / vmax));
        }

        // A target's true cost includes walking from it to the destination point, so keep
        // searching until nothing left in the heap could beat the best target found.
        int dst = -1;
        double bestTotal = Double.MAX_VALUE;
        int settled = 0;
        while (heap.size > 0) {
            int u = heap.popNode();
            float fu = heap.lastKey;
            if (fu >= bestTotal) break;
            double hu = dist(lat(u), lon(u), toLat, toLon) / vmax;
            if (fu > g[u] + hu + 1e-3) continue; // stale entry
            Double ts = targetSnap.get(u);
            if (ts != null) {
                double total = g[u] + ts * offRoad / walk;
                if (total < bestTotal) {
                    bestTotal = total;
                    dst = u;
                }
            }
            if (++settled > 3_000_000) break;
            for (int e = first(u), end = first(u + 1); e < end; e++) {
                int fl = eFlags(e);
                if (!allowed(fl, mode)) continue;
                double v = speed(eClass(e), mode);
                if (v <= 0) continue;
                int w = eTarget(e);
                float cost = (float) (g[u] + eLenDm(e) / 10.0 / v);
                if (stamp[w] != gen || cost < g[w]) {
                    stamp[w] = gen;
                    g[w] = cost;
                    prevEdge[w] = e;
                    heap.push(w, (float) (cost + dist(lat(w), lon(w), toLat, toLon) / vmax));
                }
            }
        }
        if (dst < 0) return null;

        List<int[]> path = new ArrayList<>(); // {edge, sourceNode}
        int cur = dst;
        while (prevEdge[cur] >= 0) {
            int e = prevEdge[cur];
            int srcNode = edgeSource(e);
            path.add(new int[]{e, srcNode});
            cur = srcNode;
        }
        java.util.Collections.reverse(path);
        int src = cur;
        double snapFrom = dist(fromLat, fromLon, lat(src), lon(src));
        double snapTo = targetSnap.get(dst);

        Route r = new Route();
        r.snapFromM = snapFrom;
        r.snapToM = snapTo;
        r.points.add(new double[]{fromLat, fromLon});
        r.points.add(new double[]{lat(src), lon(src)});
        List<Seg> segs = new ArrayList<>();
        for (int[] pe : path) {
            int e = pe[0], u = pe[1];
            List<double[]> pts = edgePoints(e, u);
            for (int q = 1; q < pts.size(); q++) r.points.add(pts.get(q));
            double len = eLenDm(e) / 10.0;
            r.distanceM += len;
            r.seconds += len / speed(eClass(e), mode);
            segs.add(new Seg(eName(e), eClass(e), len, pts));
        }
        r.points.add(new double[]{toLat, toLon});
        r.distanceM += snapFrom + snapTo;
        r.seconds += (snapFrom + snapTo) / walk;
        buildSteps(r, segs);
        return r;
    }

    /** Source node of edge e (binary search in the CSR first[] array). */
    private int edgeSource(int e) {
        int lo = 0, hi = nNodes - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (first(mid) <= e) lo = mid;
            else hi = mid - 1;
        }
        // skip nodes with no edges that share the same first[] value
        while (lo + 1 < nNodes && first(lo + 1) <= e) lo++;
        return lo;
    }

    /** Points along edge e from u to its target, including both ends. */
    private List<double[]> edgePoints(int e, int u) {
        List<double[]> pts = new ArrayList<>();
        pts.add(new double[]{lat(u), lon(u)});
        int s = eShapeStart(e), c = eShapeCount(e);
        boolean rev = (eFlags(e) & 8) != 0;
        for (int i = 0; i < c; i++) {
            int k = rev ? s + c - 1 - i : s + i;
            pts.add(new double[]{b.getInt(offShapeLat + 4 * k) / 1e7, b.getInt(offShapeLon + 4 * k) / 1e7});
        }
        int v = eTarget(e);
        pts.add(new double[]{lat(v), lon(v)});
        return pts;
    }

    // ---------------------------------------------------------- instructions

    private static final class Seg {
        final int nameId, cls;
        final double len;
        final List<double[]> pts;

        Seg(int nameId, int cls, double len, List<double[]> pts) {
            this.nameId = nameId;
            this.cls = cls;
            this.len = len;
            this.pts = pts;
        }

        double startBearing() {
            return bearing(pts.get(0), pts.get(Math.min(1, pts.size() - 1)));
        }

        double endBearing() {
            return bearing(pts.get(Math.max(0, pts.size() - 2)), pts.get(pts.size() - 1));
        }
    }

    private void buildSteps(Route r, List<Seg> segs) {
        if (segs.isEmpty()) {
            r.steps.add(new Step("You're already at the destination", 0));
            return;
        }
        // Group consecutive segments on the same street.
        List<List<Seg>> groups = new ArrayList<>();
        for (Seg s : segs) {
            if (!groups.isEmpty()) {
                List<Seg> last = groups.get(groups.size() - 1);
                Seg prev = last.get(last.size() - 1);
                boolean sameName = s.nameId == prev.nameId;
                boolean bothUnnamedPaths = s.nameId == 0 && prev.nameId == 0;
                if (sameName || bothUnnamedPaths) {
                    last.add(s);
                    continue;
                }
            }
            List<Seg> grp = new ArrayList<>();
            grp.add(s);
            groups.add(grp);
        }
        // Fold short connectors and crosswalks (<40 m) into the following street, then
        // merge neighbours that end up on the same street (sidewalk - crossing - sidewalk).
        for (int i = 1; i < groups.size() - 1; i++) {
            List<Seg> grp = groups.get(i);
            int nm = grp.get(0).nameId;
            boolean connector = nm == 0 || "the crosswalk".equals(name(nm));
            if ((connector && groupLen(grp) < 40) || groupLen(grp) < 25) {
                groups.get(i + 1).addAll(0, grp);
                groups.remove(i);
                i--;
            }
        }
        for (int i = 1; i < groups.size(); i++) {
            List<Seg> prev = groups.get(i - 1), grp = groups.get(i);
            if (lastNamed(prev) == lastNamed(grp)) {
                prev.addAll(grp);
                groups.remove(i);
                i--;
            }
        }

        for (int i = 0; i < groups.size(); i++) {
            List<Seg> grp = groups.get(i);
            Seg main = grp.get(0);
            for (Seg x : grp) if (x.len > main.len) main = x;
            String street = label(main);
            double len = groupLen(grp);
            String text;
            if (i == 0) {
                text = "Head " + cardinal(grp.get(0).startBearing()) + " on " + street;
            } else {
                List<Seg> prev = groups.get(i - 1);
                double turn = normalize(grp.get(0).startBearing() - prev.get(prev.size() - 1).endBearing());
                text = turnWord(turn) + " onto " + street;
            }
            r.steps.add(new Step(text, len));
        }
        r.steps.add(new Step("Arrive at the destination", 0));
    }

    /** Name of the group's longest segment (folded connectors don't rename a street). */
    private static int lastNamed(List<Seg> grp) {
        Seg best = grp.get(0);
        for (Seg x : grp) if (x.len > best.len) best = x;
        return best.nameId;
    }

    private static double groupLen(List<Seg> grp) {
        double s = 0;
        for (Seg x : grp) s += x.len;
        return s;
    }

    private String label(Seg s) {
        if (s.nameId != 0) return name(s.nameId);
        if (s.cls >= 11) return "the path";
        if (s.cls <= 2) return "the ramp";
        if (s.cls == 9) return "the alley";
        return "the unnamed road";
    }

    private static String turnWord(double t) {
        double a = Math.abs(t);
        String side = t < 0 ? "left" : "right";
        if (a < 25) return "Continue";
        if (a < 60) return "Bear " + side;
        if (a < 150) return "Turn " + side;
        return "Make a U-turn";
    }

    // ---------------------------------------------------------------- geo

    static double dist(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double dp = p2 - p1, dl = Math.toRadians(lon2 - lon1);
        double h = Math.sin(dp / 2) * Math.sin(dp / 2)
                + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * EARTH_R * Math.asin(Math.min(1.0, Math.sqrt(h)));
    }

    static double bearing(double[] a, double[] c) {
        double p1 = Math.toRadians(a[0]), p2 = Math.toRadians(c[0]);
        double dl = Math.toRadians(c[1] - a[1]);
        double y = Math.sin(dl) * Math.cos(p2);
        double x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl);
        return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360;
    }

    private static double normalize(double a) {
        a = ((a % 360) + 360) % 360;
        return a > 180 ? a - 360 : a;
    }

    static String cardinal(double deg) {
        String[] dirs = {"north", "northeast", "east", "southeast", "south", "southwest", "west", "northwest"};
        return dirs[(int) Math.round(((deg % 360) + 360) % 360 / 45.0) % 8];
    }

    public String describe() {
        return String.format(Locale.US, "%d nodes, %d edges", nNodes, nEdges);
    }

    // --------------------------------------------------------------- heap

    /** Binary min-heap of (node, key) with lazy deletion. */
    private static final class Heap {
        int[] nodes = new int[1024];
        float[] keys = new float[1024];
        int size;
        float lastKey;

        void push(int n, float k) {
            if (size == nodes.length) {
                nodes = Arrays.copyOf(nodes, size * 2);
                keys = Arrays.copyOf(keys, size * 2);
            }
            int i = size++;
            while (i > 0) {
                int p = (i - 1) >>> 1;
                if (keys[p] <= k) break;
                nodes[i] = nodes[p];
                keys[i] = keys[p];
                i = p;
            }
            nodes[i] = n;
            keys[i] = k;
        }

        int popNode() {
            int top = nodes[0];
            lastKey = keys[0];
            int n = nodes[--size];
            float k = keys[size];
            int i = 0;
            while (true) {
                int l = 2 * i + 1;
                if (l >= size) break;
                int c = (l + 1 < size && keys[l + 1] < keys[l]) ? l + 1 : l;
                if (keys[c] >= k) break;
                nodes[i] = nodes[c];
                keys[i] = keys[c];
                i = c;
            }
            nodes[i] = n;
            keys[i] = k;
            return top;
        }
    }
}
