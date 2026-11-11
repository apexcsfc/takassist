package com.apexcsfc.atak.takassist.talk.resolve;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Finds the existing marker a user means ("the truck two klicks south of me") so a tool
 * like Bloodhound can be pointed at it. Name and side narrow it down; an optional
 * location hint picks between markers with the same name. Plain Java, unit-tested.
 */
public final class TargetFinder {

    /** A marker on the map. */
    public static final class Item {
        public final String uid;
        public final String name;
        public final String cotType;
        public final double lat;
        public final double lon;

        public Item(String uid, String name, String cotType, double lat, double lon) {
            this.uid = uid;
            this.name = name == null || name.trim().isEmpty() ? "(unnamed)" : name.trim();
            this.cotType = cotType == null ? "" : cotType;
            this.lat = lat;
            this.lon = lon;
        }
    }

    /** Candidates best first; ask = true when the user should choose. */
    public static final class Found {
        public final List<Item> options;
        public final boolean ask;

        Found(List<Item> options, boolean ask) {
            this.options = options;
            this.ask = ask;
        }
    }

    private static final int MAX_OPTIONS = 5;
    /** Words that describe the side or are filler, not part of a marker's name. */
    private static final Set<String> IGNORE = new HashSet<>(Arrays.asList(
            "the", "a", "an", "that", "this", "my", "our", "marker", "point", "target", "contact",
            "enemy", "hostile", "hostiles", "friendly", "friendlies", "neutral", "unknown"));

    private TargetFinder() {
    }

    /**
     * @param label       what was named ("truck"), may be null
     * @param affiliation hostile/friendly/neutral/unknown, or null/"any"
     * @param hint        {lat, lon} where the user said it is, or null
     * @param self        {lat, lon} of the user, or null
     */
    public static Found find(List<Item> items, String label, String affiliation, double[] hint, double[] self) {
        final List<String> words = nameWords(label);
        final List<Item> cands = new ArrayList<>();
        for (Item it : items) {
            if (affiliation != null && !"any".equals(affiliation)
                    && !MarkerCounter.matches(it.cotType, affiliation)) continue;
            if (!words.isEmpty() && !nameMatches(words, it.name)) continue;
            // Nothing to go on: no name, no place, no side named.
            if (words.isEmpty() && hint == null && (affiliation == null || "any".equals(affiliation))) continue;
            cands.add(it);
        }

        if (hint != null) {
            // How far off the spoken position may be: generous, since it was estimated by eye.
            final double fromSelf = self == null ? 0 : Geodesy.inverse(self[0], self[1], hint[0], hint[1])[0];
            final double tolerance = Math.max(750, 0.35 * fromSelf);
            final List<Item> near = new ArrayList<>();
            for (Item it : cands) if (dist(hint, it) <= tolerance) near.add(it);
            sortByDistance(near, hint);
            if (near.isEmpty()) return new Found(near, false);
            // One clear winner: the next one is at least twice as far from the spot.
            final boolean clear = near.size() == 1 || dist(hint, near.get(1)) > 2 * dist(hint, near.get(0));
            return new Found(cap(near), !clear);
        }

        if (self != null) sortByDistance(cands, self);
        // "the nearest enemy": just a side, no name, so the nearest one is the answer.
        final boolean nearestOfSide = words.isEmpty() && self != null;
        return new Found(cap(cands), cands.size() > 1 && !nearestOfSide);
    }

    /**
     * Markers matching a name and/or side, nearest first ("where is the nearest enemy",
     * "where's the truck"). With neither, every marker counts.
     */
    public static List<Item> nearest(List<Item> items, String label, String affiliation, double[] self, int max) {
        final List<String> words = nameWords(label);
        final List<Item> out = new ArrayList<>();
        for (Item it : items) {
            if (affiliation != null && !"any".equals(affiliation) && !MarkerCounter.matches(it.cotType, affiliation)) continue;
            if (!words.isEmpty() && !nameMatches(words, it.name)) continue;
            out.add(it);
        }
        if (self != null) sortByDistance(out, self);
        return out.size() > max ? new ArrayList<>(out.subList(0, max)) : out;
    }

    static List<String> nameWords(String label) {
        final List<String> out = new ArrayList<>();
        for (String w : (label == null ? "" : label.toLowerCase(Locale.US)).split("[^a-z0-9]+")) {
            if (!w.isEmpty() && !IGNORE.contains(w)) out.add(w);
        }
        return out;
    }

    /** Every spoken word is in the name, allowing a plural and a small misspelling. */
    static boolean nameMatches(List<String> words, String name) {
        final List<String> nw = nameWords(name);
        for (String w : words) {
            boolean hit = false;
            for (String x : nw) {
                if (x.equals(w) || stem(x).equals(stem(w))
                        || MapNames.editDistance(w, x) <= (w.length() >= 6 ? 2 : w.length() >= 4 ? 1 : 0)) {
                    hit = true;
                    break;
                }
            }
            if (!hit) return false;
        }
        return true;
    }

    private static String stem(String w) {
        return w.length() > 3 && w.endsWith("s") ? w.substring(0, w.length() - 1) : w;
    }

    private static double dist(double[] p, Item it) {
        return Geodesy.inverse(p[0], p[1], it.lat, it.lon)[0];
    }

    private static void sortByDistance(List<Item> l, final double[] from) {
        Collections.sort(l, new Comparator<Item>() {
            @Override
            public int compare(Item a, Item b) {
                return Double.compare(dist(from, a), dist(from, b));
            }
        });
    }

    private static List<Item> cap(List<Item> l) {
        return l.size() > MAX_OPTIONS ? new ArrayList<>(l.subList(0, MAX_OPTIONS)) : l;
    }
}
