package com.apexcsfc.atak.takassist.talk.resolve;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Matches a spoken map name ("NRL drg", "topo", "satellite") to the exact names of the
 * map sources ATAK has. Plain Java so it's unit-tested off-device.
 */
public final class MapNames {

    /** Matches, best first; ask = true when the user should pick from them. */
    public static final class Match {
        public final List<String> options;
        public final boolean ask;

        Match(List<String> options, boolean ask) {
            this.options = options;
            this.ask = ask;
        }
    }

    private static final int MAX_OPTIONS = 10;
    private static final Pattern NAME = Pattern.compile("<name>\\s*(.*?)\\s*</name>", Pattern.DOTALL);

    /** Words people say for a kind of map, mapped to words that appear in source names. */
    private static final String[][] SYNONYMS = {
            {"satellite", "imagery"}, {"aerial", "imagery"}, {"photo", "imagery"},
            {"topographic", "topo"}, {"terrain", "topo"}, {"street", "street"}, {"road", "street"},
            {"chart", "sectional"}, {"aviation", "sectional"}, {"faa", "faa"},
    };
    /** Filler that says nothing about which map. */
    private static final Set<String> FILLER = new LinkedHashSet<>();

    static {
        for (String w : new String[]{"the", "map", "maps", "layer", "type", "source", "view", "a", "to", "please"}) {
            FILLER.add(w);
        }
    }

    private MapNames() {
    }

    /** The &lt;name&gt; of an ATAK map-source XML, or null. */
    public static String nameFromXml(String xml) {
        if (xml == null) return null;
        Matcher m = NAME.matcher(xml);
        if (!m.find()) return null;
        String n = m.group(1).replace("&amp;", "&").trim();
        return n.isEmpty() ? null : n;
    }

    public static Match match(String spoken, List<String> names) {
        final String q = words(spoken);
        final List<String> out = new ArrayList<>();
        if (q.isEmpty() || names == null || names.isEmpty()) return new Match(out, false);

        // 1. Exact, ignoring case and spacing ("nrl drg" = "NRL DRG").
        for (String n : names) if (words(n).equals(q)) return new Match(single(n), false);

        // 2. Every spoken word appears in the name ("drg" → "NRL DRG", "NRL DRG Mosaic").
        final String[] qw = expand(q).split(" ");
        final List<String> containing = new ArrayList<>();
        for (String n : names) {
            final String nw = " " + words(n) + " ";
            boolean all = true;
            for (String w : qw) {
                if (!nw.contains(" " + w + " ")) {
                    all = false;
                    break;
                }
            }
            if (all) containing.add(n);
        }
        if (!containing.isEmpty()) {
            // Shortest first, so "NRL DRG" beats "NRL DRG Mosaic".
            Collections.sort(containing, new Comparator<String>() {
                @Override
                public int compare(String a, String b) {
                    return Integer.compare(a.length(), b.length());
                }
            });
            return new Match(cap(containing), containing.size() > 1);
        }

        // 3. Close spellings, word by word ("nrl drgg", "esri world topoo").
        final List<int[]> scored = new ArrayList<>(); // {index, edits}
        for (int i = 0; i < names.size(); i++) {
            final String[] nw = words(names.get(i)).split(" ");
            int edits = 0;
            boolean ok = true;
            for (String w : qw) {
                int best = Integer.MAX_VALUE;
                for (String x : nw) best = Math.min(best, editDistance(w, x));
                if (best > (w.length() >= 6 ? 2 : w.length() >= 3 ? 1 : 0)) {
                    ok = false;
                    break;
                }
                edits += best;
            }
            if (ok) scored.add(new int[]{i, edits});
        }
        Collections.sort(scored, new Comparator<int[]>() {
            @Override
            public int compare(int[] a, int[] b) {
                return a[1] != b[1] ? Integer.compare(a[1], b[1])
                        : Integer.compare(names.get(a[0]).length(), names.get(b[0]).length());
            }
        });
        for (int[] s : scored) out.add(names.get(s[0]));
        return new Match(cap(out), !out.isEmpty());
    }

    /** Lowercase words without filler: "Switch to the NRL-DRG map" → "switch nrl drg". */
    static String words(String s) {
        final StringBuilder b = new StringBuilder();
        for (String w : (s == null ? "" : s.toLowerCase(Locale.US)).split("[^a-z0-9]+")) {
            if (w.isEmpty() || FILLER.contains(w)) continue;
            if (b.length() > 0) b.append(' ');
            b.append(w);
        }
        return b.toString();
    }

    private static String expand(String q) {
        final StringBuilder b = new StringBuilder();
        for (String w : q.split(" ")) {
            String t = w;
            for (String[] s : SYNONYMS) if (s[0].equals(w)) t = s[1];
            if (b.length() > 0) b.append(' ');
            b.append(t);
        }
        return b.toString();
    }

    private static List<String> single(String n) {
        List<String> l = new ArrayList<>();
        l.add(n);
        return l;
    }

    private static List<String> cap(List<String> l) {
        return l.size() > MAX_OPTIONS ? new ArrayList<>(l.subList(0, MAX_OPTIONS)) : l;
    }

    static int editDistance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }
}
