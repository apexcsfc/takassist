package com.apexcsfc.atak.takassist.talk.resolve;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Counts map markers by affiliation. The model only says what was asked
 * ("how many hostile within 2 km"); the number comes from the map, here.
 * Plain Java so it's unit-tested off-device.
 */
public final class MarkerCounter {

    /** One point on the map. */
    public static final class Item {
        public final String name;
        public final String cotType;
        public final double lat;
        public final double lon;

        public Item(String name, String cotType, double lat, double lon) {
            this.name = name == null || name.trim().isEmpty() ? "(unnamed)" : name.trim();
            this.cotType = cotType == null ? "" : cotType;
            this.lat = lat;
            this.lon = lon;
        }
    }

    /** The answer, ready for the card. */
    public static final class Answer {
        public final String title;
        public final List<String> lines;
        public final int count;

        Answer(String title, List<String> lines, int count) {
            this.title = title;
            this.lines = lines;
            this.count = count;
        }
    }

    private static final int NEAREST_SHOWN = 3;

    private MarkerCounter() {
    }

    /** True if a CoT type has the asked affiliation ("any" matches every a-* type). */
    public static boolean matches(String cotType, String affiliation) {
        if (cotType == null || !cotType.startsWith("a-") || cotType.length() < 3) return false;
        final char a = cotType.charAt(2);
        switch (affiliation == null ? "any" : affiliation) {
            case "hostile":  return a == 'h' || a == 'j' || a == 'k'; // hostile, joker, faker
            case "friendly": return a == 'f' || a == 'a';             // friend, assumed friend
            case "neutral":  return a == 'n';
            case "unknown":  return a == 'u' || a == 'p';             // unknown, pending
            case "suspect":  return a == 's';
            default:         return true;
        }
    }

    /**
     * @param self     {lat, lon} of the user, or null (then no distances are given)
     * @param radiusM  only count within this distance of the user; null for the whole map
     */
    public static Answer count(List<Item> items, String affiliation, double[] self, Double radiusM) {
        final String what = affiliation == null || "any".equals(affiliation) ? "" : affiliation + " ";
        final List<double[]> dist = new ArrayList<>(); // {index, metres, bearing}
        final List<Item> hits = new ArrayList<>();
        int suspects = 0;
        for (Item it : items) {
            final boolean match = matches(it.cotType, affiliation);
            if (!match && !("hostile".equals(affiliation) && matches(it.cotType, "suspect"))) continue;
            double[] db = self == null ? null : Geodesy.inverse(self[0], self[1], it.lat, it.lon);
            if (radiusM != null && (db == null || db[0] > radiusM)) continue;
            if (!match) {
                suspects++; // mentioned alongside hostiles, not counted as them
                continue;
            }
            hits.add(it);
            dist.add(new double[]{hits.size() - 1, db == null ? -1 : db[0], db == null ? 0 : db[1]});
        }

        final List<String> lines = new ArrayList<>();
        final int n = hits.size();
        final String scope = radiusM != null
                ? "within " + Resolver.distanceText(radiusM) + " of you"
                : "on the map";
        final String title = n + " " + what + (n == 1 ? "marker " : "markers ") + scope;

        if (radiusM == null && self != null && n > 0) {
            int in1 = 0, in5 = 0;
            for (double[] d : dist) {
                if (d[1] <= 1000) in1++;
                if (d[1] <= 5000) in5++;
            }
            lines.add(in1 + " within 1 km · " + in5 + " within 5 km");
        }
        if (self != null && n > 0) {
            Collections.sort(dist, new Comparator<double[]>() {
                @Override
                public int compare(double[] a, double[] b) {
                    return Double.compare(a[1], b[1]);
                }
            });
            lines.add(n == 1 ? "Where:" : "Nearest:");
            for (int i = 0; i < Math.min(NEAREST_SHOWN, dist.size()); i++) {
                double[] d = dist.get(i);
                Item it = hits.get((int) d[0]);
                lines.add(String.format(Locale.US, "• %s: %s %s (%03.0f°)", it.name,
                        Resolver.distanceText(d[1]), compass8(d[2]), d[2]));
            }
            if (n > NEAREST_SHOWN) lines.add("…and " + (n - NEAREST_SHOWN) + " more");
        }
        if (self == null && n > 0) lines.add("No GPS fix, so no distances.");
        if (suspects > 0) {
            lines.add("Plus " + suspects + " suspect " + (suspects == 1 ? "marker" : "markers")
                    + " (not counted as hostile).");
        }
        return new Answer(title, lines, n);
    }

    /**
     * Backstop for when the model calls a count question "unclear": recognises
     * "how many …" / "count …" and picks the side from keywords. Null if it isn't one.
     */
    public static String countQuestionAffiliation(String text) {
        final String t = " " + (text == null ? "" : text.toLowerCase(Locale.US)).replaceAll("[^a-z0-9 ]", " ") + " ";
        if (!t.contains(" how many ") && !t.contains(" count ") && !t.contains(" number of ")) return null;
        if (t.matches(".*\\b(hostiles?|enem(y|ies)|bad guys|opfor|bandits?|threats?)\\b.*")) return "hostile";
        if (t.matches(".*\\b(friendl(y|ies)|blue( force)?s?|ours)\\b.*")) return "friendly";
        if (t.matches(".*\\b(neutrals?|civilians?)\\b.*")) return "neutral";
        if (t.matches(".*\\b(unknowns?)\\b.*")) return "unknown";
        return "any";
    }

    static String compass8(double deg) {
        final String[] p = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
        return p[(int) Math.round(Geodesy.normalizeDeg(deg) / 45.0) % 8];
    }
}
