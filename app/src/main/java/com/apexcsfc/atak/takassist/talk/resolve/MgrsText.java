package com.apexcsfc.atak.takassist.talk.resolve;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cleans up a spoken MGRS grid. People usually drop the zone and 100 km square
 * ("grid two three four eight, zero six four seven"), so those are taken from the
 * user's own position, the way a radio operator would assume them.
 */
public final class MgrsText {

    private static final Pattern FULL = Pattern.compile("^(\\d{1,2})([C-HJ-NP-X])([A-HJ-NP-Z]{2})(\\d*)$");
    private static final Pattern SQUARE = Pattern.compile("^([A-HJ-NP-Z]{2})(\\d+)$");
    private static final Pattern DIGITS = Pattern.compile("^(\\d+)$");

    private MgrsText() {
    }

    /**
     * Returns a full, compact MGRS string (e.g. "18SUJ23480647").
     *
     * @throws IllegalArgumentException with a say-again question when it can't be completed
     */
    public static String complete(String spoken, String selfMgrs) {
        final String g = compact(spoken);
        if (g.isEmpty()) throw new IllegalArgumentException("What's the grid?");

        Matcher m = FULL.matcher(g);
        if (m.matches()) {
            checkDigits(m.group(4));
            return g;
        }

        final String self = compact(selfMgrs);
        final Matcher sm = FULL.matcher(self);
        final boolean haveSelf = sm.matches();

        m = SQUARE.matcher(g);
        if (m.matches()) {
            checkDigits(m.group(2));
            if (!haveSelf) throw new IllegalArgumentException("I need the grid zone too. Say again the full grid?");
            return sm.group(1) + sm.group(2) + g;
        }

        m = DIGITS.matcher(g);
        if (m.matches()) {
            checkDigits(m.group(1));
            if (!haveSelf) throw new IllegalArgumentException("I need the grid zone and square too. Say again the full grid?");
            return sm.group(1) + sm.group(2) + sm.group(3) + g;
        }

        throw new IllegalArgumentException("I couldn't read grid \"" + spoken.trim() + "\". Say again the grid?");
    }

    /** "18SUJ23480647" → "18S UJ 2348 0647". */
    public static String pretty(String compactGrid) {
        final Matcher m = FULL.matcher(compact(compactGrid));
        if (!m.matches()) return compactGrid;
        final String d = m.group(4);
        final int h = d.length() / 2;
        return m.group(1) + m.group(2) + " " + m.group(3)
                + (d.isEmpty() ? "" : " " + d.substring(0, h) + " " + d.substring(h));
    }

    private static void checkDigits(String d) {
        if (d.length() < 2 || d.length() > 10 || d.length() % 2 != 0) {
            throw new IllegalArgumentException("The grid needs an even number of digits (4, 6, 8 or 10). Say again the grid?");
        }
    }

    private static String compact(String s) {
        return s == null ? "" : s.replaceAll("[^0-9A-Za-z]", "").toUpperCase(Locale.US);
    }
}
