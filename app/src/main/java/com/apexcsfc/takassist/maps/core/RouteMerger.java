package com.apexcsfc.takassist.maps.core;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Stitches several route.bin v1 files (one per region) into one, so directions can run
 * from one region into the next.
 *
 * Regions are joined where they share road nodes: a road that crosses a state line is in
 * both state packs (complete ways), and its end and junction nodes have identical
 * coordinates in both. A node of a later region that sits inside an earlier region's
 * grid and exactly matches one of its nodes becomes that node; everything else is kept
 * as is. Duplicate edges along the overlap are harmless to the A* router.
 *
 * Pure Java (no Android classes), so it is unit-tested on a desktop JVM.
 *
 * route.bin v1 (little endian), written by scripts/build_offline_map.py:
 *   header 64 B: "LLMR", version=1, nNodes, nEdges, nShape, nNames, minLatE7, minLonE7,
 *                cellE7, gridW, gridH, nCellNodes
 *   int lat[nNodes], int lon[nNodes], int first[nNodes+1]
 *   edges[nEdges] x 20 B: int dst, int lenDm, int nameId, int shapeStart, u16 shapeCount,
 *                         u8 flags, u8 cls
 *   int shapeLat[nShape], int shapeLon[nShape], int nameOff[nNames+1], name bytes,
 *   pad to 4, int cellStart[gridW*gridH+1], int cellNodes[nCellNodes]
 */
public final class RouteMerger {

    public static final int EDGE_BYTES = 20;
    /** Largest file the plugin can open (one mapped buffer, int offsets). */
    public static final long MAX_BYTES = Integer.MAX_VALUE;

    /** One opened input file. */
    static final class Src {
        final ByteBuffer b;
        final int nNodes, nEdges, nShape, nNames, minLat, minLon, cell, gridW, gridH, nCellNodes;
        final int offLat, offLon, offFirst, offEdges, offShapeLat, offShapeLon, offNameOff,
                offNameBytes, nameLen, offCellStart, offCellNodes;

        Src(File f) throws IOException {
            try (RandomAccessFile raf = new RandomAccessFile(f, "r");
                 FileChannel ch = raf.getChannel()) {
                if (ch.size() > MAX_BYTES) throw new IOException(f + " is larger than 2 GB");
                MappedByteBuffer m = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size());
                b = m.order(ByteOrder.LITTLE_ENDIAN);
            }
            byte[] magic = new byte[4];
            b.position(0);
            b.get(magic);
            if (!"LLMR".equals(new String(magic, StandardCharsets.US_ASCII)) || b.getInt(4) != 1) {
                throw new IOException(f + " is not a route.bin v1 file");
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
            nCellNodes = b.getInt(44);
            offLat = 64;
            offLon = offLat + 4 * nNodes;
            offFirst = offLon + 4 * nNodes;
            offEdges = offFirst + 4 * (nNodes + 1);
            offShapeLat = offEdges + EDGE_BYTES * nEdges;
            offShapeLon = offShapeLat + 4 * nShape;
            offNameOff = offShapeLon + 4 * nShape;
            offNameBytes = offNameOff + 4 * (nNames + 1);
            nameLen = b.getInt(offNameOff + 4 * nNames);
            int afterNames = offNameBytes + nameLen;
            offCellStart = afterNames + ((4 - (afterNames % 4)) % 4);
            offCellNodes = offCellStart + 4 * (gridW * gridH + 1);
            if (offCellNodes + 4L * nCellNodes > b.capacity()) throw new IOException(f + " is truncated");
        }

        int lat(int i) { return b.getInt(offLat + 4 * i); }
        int lon(int i) { return b.getInt(offLon + 4 * i); }
        int first(int i) { return b.getInt(offFirst + 4 * i); }

        boolean inGrid(int la, int lo) {
            return la >= minLat && lo >= minLon
                    && la < minLat + (long) gridH * cell && lo < minLon + (long) gridW * cell;
        }

        /** Index of the node at exactly (la, lo), or -1. */
        int find(int la, int lo) {
            if (!inGrid(la, lo)) return -1;
            int cy = (int) (((long) la - minLat) / cell);
            int cx = (int) (((long) lo - minLon) / cell);
            int c = cy * gridW + cx;
            int s = b.getInt(offCellStart + 4 * c), e = b.getInt(offCellStart + 4 * (c + 1));
            for (int k = s; k < e; k++) {
                int n = b.getInt(offCellNodes + 4 * k);
                if (lat(n) == la && lon(n) == lo) return n;
            }
            return -1;
        }
    }

    /** Result counts, for logging and the UI. */
    public static final class Stats {
        public int nodes, edges, joined;
        public long bytes;

        @Override
        public String toString() {
            return nodes + " nodes, " + edges + " edges, " + joined + " shared border nodes, "
                    + (bytes / 1_000_000) + " MB";
        }
    }

    /** Progress callback, 0..1. */
    public interface Progress {
        void onProgress(String what, double fraction);
    }

    /** Estimated output size in bytes, without merging. Used to warn before stitching. */
    public static long estimateBytes(List<File> inputs) throws IOException {
        List<Src> s = open(inputs);
        long nodes = 0, edges = 0, shape = 0, names = 0, nameBytes = 0;
        int minLat = Integer.MAX_VALUE, minLon = Integer.MAX_VALUE;
        long maxLat = Long.MIN_VALUE, maxLon = Long.MIN_VALUE;
        for (Src r : s) {
            nodes += r.nNodes; edges += r.nEdges; shape += r.nShape; names += r.nNames;
            nameBytes += r.nameLen;
            minLat = Math.min(minLat, r.minLat); minLon = Math.min(minLon, r.minLon);
            maxLat = Math.max(maxLat, r.minLat + (long) r.gridH * r.cell);
            maxLon = Math.max(maxLon, r.minLon + (long) r.gridW * r.cell);
        }
        int cs = s.get(0).cell;
        long gw = (maxLon - minLon) / cs + 1, gh = (maxLat - minLat) / cs + 1;
        return size(nodes, edges, shape, names, nameBytes, gw * gh, nodes);
    }

    private static long size(long n, long e, long sh, long names, long nameBytes, long cells, long cellNodes) {
        long afterNames = 64 + 8 * n + 4 * (n + 1) + (long) EDGE_BYTES * e + 8 * sh + 4 * (names + 1) + nameBytes;
        long pad = (4 - (afterNames % 4)) % 4;
        return afterNames + pad + 4 * (cells + 1) + 4 * cellNodes;
    }

    private static List<Src> open(List<File> inputs) throws IOException {
        if (inputs.isEmpty()) throw new IOException("No regions to stitch");
        List<Src> s = new ArrayList<>();
        for (File f : inputs) s.add(new Src(f));
        int cs = s.get(0).cell;
        for (Src r : s) if (r.cell != cs) throw new IOException("Regions use different grid sizes");
        return s;
    }

    /** Stitches inputs into out (written to out + ".part", then renamed). */
    public static Stats merge(List<File> inputs, File out, Progress p) throws IOException {
        List<Src> src = open(inputs);
        final int R = src.size();

        // 1. Global node ids. map[r][i] = global id of node i of region r.
        int[][] map = new int[R][];
        List<int[]> owner = new ArrayList<>(); // per region: global id range start
        int next = 0, joined = 0;
        int[] base = new int[R];
        // Global node -> (region, index) of its first copy, kept as two parallel arrays.
        long total = 0;
        for (Src r : src) total += r.nNodes;
        if (total > Integer.MAX_VALUE / 8) throw new IOException("Too many road nodes to stitch");
        int[] gReg = new int[(int) total], gIdx = new int[(int) total];
        for (int r = 0; r < R; r++) {
            Src s = src.get(r);
            int[] m = new int[s.nNodes];
            base[r] = next;
            for (int i = 0; i < s.nNodes; i++) {
                int la = s.lat(i), lo = s.lon(i);
                int g = -1;
                for (int q = 0; q < r && g < 0; q++) {
                    int k = src.get(q).find(la, lo);
                    if (k >= 0) g = map[q][k];
                }
                if (g >= 0) {
                    joined++;
                } else {
                    g = next++;
                    gReg[g] = r;
                    gIdx[g] = i;
                }
                m[i] = g;
                if (p != null && (i & 0xFFFF) == 0) p.onProgress("Matching roads", (r + (double) i / Math.max(1, s.nNodes)) / R * 0.3);
            }
            map[r] = m;
        }
        final int N = next;

        // 2. Out-degree per global node -> first[].
        long E = 0, S = 0, NM = 0, NB = 0;
        int[] first = new int[N + 1];
        for (int r = 0; r < R; r++) {
            Src s = src.get(r);
            E += s.nEdges; S += s.nShape; NM += s.nNames; NB += s.nameLen;
            for (int i = 0; i < s.nNodes; i++) first[map[r][i] + 1] += s.first(i + 1) - s.first(i);
        }
        for (int i = 0; i < N; i++) first[i + 1] += first[i];

        // 3. Union grid.
        int minLat = Integer.MAX_VALUE, minLon = Integer.MAX_VALUE, maxLat = Integer.MIN_VALUE, maxLon = Integer.MIN_VALUE;
        for (int g = 0; g < N; g++) {
            Src s = src.get(gReg[g]);
            int la = s.lat(gIdx[g]), lo = s.lon(gIdx[g]);
            if (la < minLat) minLat = la;
            if (lo < minLon) minLon = lo;
            if (la > maxLat) maxLat = la;
            if (lo > maxLon) maxLon = lo;
        }
        final int cs = src.get(0).cell;
        if (N == 0) { minLat = 0; minLon = 0; maxLat = 0; maxLon = 0; }
        long gwL = ((long) maxLon - minLon) / cs + 1, ghL = ((long) maxLat - minLat) / cs + 1;
        if (gwL * ghL > Integer.MAX_VALUE / 8) throw new IOException("Regions are too far apart to stitch into one file");
        final int gw = (int) gwL, gh = (int) ghL;

        long bytes = size(N, E, S, NM, NB, (long) gw * gh, N);
        if (bytes > MAX_BYTES) {
            throw new IOException("The stitched road file would be " + (bytes / 1_000_000)
                    + " MB; the limit is 2,147 MB. Choose fewer regions.");
        }

        // Section offsets in the output.
        final long oLat = 64, oLon = oLat + 4L * N, oFirst = oLon + 4L * N, oEdges = oFirst + 4L * (N + 1);
        final long oShLat = oEdges + (long) EDGE_BYTES * E, oShLon = oShLat + 4 * S, oNameOff = oShLon + 4 * S;
        final long oNameBytes = oNameOff + 4 * (NM + 1);
        long afterNames = oNameBytes + NB;
        final long oCellStart = afterNames + ((4 - (afterNames % 4)) % 4);
        final long oCellNodes = oCellStart + 4L * ((long) gw * gh + 1);

        File part = new File(out.getPath() + ".part");
        if (part.exists() && !part.delete()) throw new IOException("Can't replace " + part);
        try (RandomAccessFile raf = new RandomAccessFile(part, "rw");
             FileChannel ch = raf.getChannel()) {
            raf.setLength(bytes);
            ByteBuffer o = ch.map(FileChannel.MapMode.READ_WRITE, 0, bytes).order(ByteOrder.LITTLE_ENDIAN);

            o.put(0, (byte) 'L'); o.put(1, (byte) 'L'); o.put(2, (byte) 'M'); o.put(3, (byte) 'R');
            o.putInt(4, 1); o.putInt(8, N); o.putInt(12, (int) E); o.putInt(16, (int) S);
            o.putInt(20, (int) NM); o.putInt(24, minLat); o.putInt(28, minLon); o.putInt(32, cs);
            o.putInt(36, gw); o.putInt(40, gh); o.putInt(44, N);

            // Nodes.
            for (int g = 0; g < N; g++) {
                Src s = src.get(gReg[g]);
                o.putInt((int) (oLat + 4L * g), s.lat(gIdx[g]));
                o.putInt((int) (oLon + 4L * g), s.lon(gIdx[g]));
            }
            for (int i = 0; i <= N; i++) o.putInt((int) (oFirst + 4L * i), first[i]);

            // Edges: each global node's out-edges from every region it appears in.
            int[] fill = new int[N];
            long shapeBase = 0, nameBase = 0;
            for (int r = 0; r < R; r++) {
                Src s = src.get(r);
                int[] m = map[r];
                for (int i = 0; i < s.nNodes; i++) {
                    int g = m[i];
                    int e0 = s.first(i), e1 = s.first(i + 1);
                    for (int e = e0; e < e1; e++) {
                        int in = s.offEdges + EDGE_BYTES * e;
                        int at = (int) (oEdges + (long) EDGE_BYTES * (first[g] + fill[g]++));
                        o.putInt(at, m[s.b.getInt(in)]);                       // dst
                        o.putInt(at + 4, s.b.getInt(in + 4));                  // lenDm
                        o.putInt(at + 8, (int) (s.b.getInt(in + 8) + nameBase));   // nameId
                        o.putInt(at + 12, (int) (s.b.getInt(in + 12) + shapeBase)); // shapeStart
                        o.putShort(at + 16, s.b.getShort(in + 16));            // shapeCount
                        o.put(at + 18, s.b.get(in + 18));                      // flags
                        o.put(at + 19, s.b.get(in + 19));                      // cls
                    }
                    if (p != null && (i & 0xFFFF) == 0) p.onProgress("Joining roads", 0.3 + (r + (double) i / Math.max(1, s.nNodes)) / R * 0.4);
                }
                shapeBase += s.nShape;
                nameBase += s.nNames;
            }

            // Shapes and names, region after region.
            long sh = 0, nm = 0, nb = 0;
            for (Src s : src) {
                copy(s.b, s.offShapeLat, o, oShLat + 4 * sh, 4L * s.nShape);
                copy(s.b, s.offShapeLon, o, oShLon + 4 * sh, 4L * s.nShape);
                for (int k = 0; k < s.nNames; k++) {
                    o.putInt((int) (oNameOff + 4 * (nm + k)), (int) (nb + s.b.getInt(s.offNameOff + 4 * k)));
                }
                copy(s.b, s.offNameBytes, o, oNameBytes + nb, s.nameLen);
                sh += s.nShape; nm += s.nNames; nb += s.nameLen;
            }
            o.putInt((int) (oNameOff + 4 * NM), (int) nb);
            if (p != null) p.onProgress("Building the search grid", 0.75);

            // Snapping grid over the global nodes.
            int[] cnt = new int[gw * gh + 1];
            for (int g = 0; g < N; g++) {
                Src s = src.get(gReg[g]);
                int c = (int) (((long) s.lat(gIdx[g]) - minLat) / cs) * gw + (int) (((long) s.lon(gIdx[g]) - minLon) / cs);
                cnt[c + 1]++;
            }
            for (int c = 0; c < gw * gh; c++) cnt[c + 1] += cnt[c];
            for (int c = 0; c <= gw * gh; c++) o.putInt((int) (oCellStart + 4L * c), cnt[c]);
            for (int g = 0; g < N; g++) {
                Src s = src.get(gReg[g]);
                int c = (int) (((long) s.lat(gIdx[g]) - minLat) / cs) * gw + (int) (((long) s.lon(gIdx[g]) - minLon) / cs);
                o.putInt((int) (oCellNodes + 4L * cnt[c]++), g);
            }
            if (o instanceof MappedByteBuffer) ((MappedByteBuffer) o).force();
        }
        if (!part.renameTo(out)) throw new IOException("Couldn't move the stitched file into place");
        if (p != null) p.onProgress("Done", 1);

        Stats st = new Stats();
        st.nodes = N;
        st.edges = (int) E;
        st.joined = joined;
        st.bytes = bytes;
        return st;
    }

    private static void copy(ByteBuffer from, int fromOff, ByteBuffer to, long toOff, long len) {
        ByteBuffer a = from.duplicate();
        a.position(fromOff).limit((int) (fromOff + len));
        ByteBuffer z = to.duplicate();
        z.position((int) toOff);
        z.put(a);
    }
}
