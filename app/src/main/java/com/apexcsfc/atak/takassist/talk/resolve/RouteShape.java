package com.apexcsfc.atak.takassist.talk.resolve;

import java.util.ArrayList;
import java.util.List;

/**
 * Thins a road route to the points that change its shape (Douglas–Peucker), so the
 * ATAK route stays light while still following the roads. Plain Java, unit-tested.
 */
public final class RouteShape {

    private RouteShape() {
    }

    /**
     * @param pts        {lat, lon} points in order
     * @param toleranceM how far a dropped point may be from the kept line
     * @param maxPoints  hard cap; the tolerance grows until the route fits
     */
    public static List<double[]> simplify(List<double[]> pts, double toleranceM, int maxPoints) {
        if (pts.size() <= 2) return new ArrayList<>(pts);
        double tol = toleranceM;
        List<double[]> out = dp(pts, tol);
        while (out.size() > maxPoints && tol < 5000) {
            tol *= 1.5;
            out = dp(pts, tol);
        }
        return out;
    }

    private static List<double[]> dp(List<double[]> pts, double tol) {
        final boolean[] keep = new boolean[pts.size()];
        keep[0] = true;
        keep[pts.size() - 1] = true;
        // Iterative, so long routes can't overflow the stack.
        final java.util.ArrayDeque<int[]> stack = new java.util.ArrayDeque<>();
        stack.push(new int[]{0, pts.size() - 1});
        while (!stack.isEmpty()) {
            final int[] s = stack.pop();
            double worst = -1;
            int idx = -1;
            for (int i = s[0] + 1; i < s[1]; i++) {
                double d = offLine(pts.get(i), pts.get(s[0]), pts.get(s[1]));
                if (d > worst) {
                    worst = d;
                    idx = i;
                }
            }
            if (idx >= 0 && worst > tol) {
                keep[idx] = true;
                stack.push(new int[]{s[0], idx});
                stack.push(new int[]{idx, s[1]});
            }
        }
        final List<double[]> out = new ArrayList<>();
        for (int i = 0; i < pts.size(); i++) if (keep[i]) out.add(pts.get(i));
        return out;
    }

    /** Metres from p to segment a-b, on a local flat projection (fine at route scale). */
    static double offLine(double[] p, double[] a, double[] b) {
        final double k = Math.cos(Math.toRadians(a[0]));
        final double mPerDeg = 111_320.0;
        final double ax = 0, ay = 0;
        final double bx = (b[1] - a[1]) * k * mPerDeg, by = (b[0] - a[0]) * mPerDeg;
        final double px = (p[1] - a[1]) * k * mPerDeg, py = (p[0] - a[0]) * mPerDeg;
        final double dx = bx - ax, dy = by - ay;
        final double len2 = dx * dx + dy * dy;
        double t = len2 == 0 ? 0 : ((px - ax) * dx + (py - ay) * dy) / len2;
        t = Math.max(0, Math.min(1, t));
        final double cx = ax + t * dx, cy = ay + t * dy;
        return Math.hypot(px - cx, py - cy);
    }
}
