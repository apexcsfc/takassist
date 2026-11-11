package com.apexcsfc.atak.takassist.talk.resolve;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fixes predictable speech-to-text spellings before the report reaches the model, so
 * a small model never has to guess what "1.5 clicks" means. Only rewrites a word when
 * a number comes right before it.
 */
public final class Transcript {

    private static final String NUM = "(\\d+(?:\\.\\d+)?|a|one|two|three|four|five|six|seven|eight|nine|ten"
            + "|eleven|twelve|fifteen|twenty|thirty|forty|fifty|half(?: a)?)";

    /** "1.5 clicks", "two klicks", "3 kliks", "a click" → kilometers. */
    private static final Pattern KLICKS = Pattern.compile(
            "(?i)\\b" + NUM + "(\\s*-?\\s*)(?:klicks?|clicks?|kliks?|klics?)\\b");
    /** "2k", "1.5 k" → kilometers (but not "2k20" or "k9"). */
    private static final Pattern K = Pattern.compile("(?i)\\b(\\d+(?:\\.\\d+)?)\\s?k\\b(?![-\\d])");
    /** "300m", "300 mike" → meters. */
    private static final Pattern M = Pattern.compile("(?i)\\b(\\d+(?:\\.\\d+)?)\\s?(?:m|mikes?)\\b(?![-\\d])");

    private Transcript() {
    }

    public static String clean(String text) {
        if (text == null) return "";
        String t = text.trim();
        t = replace(KLICKS, t, "$1 kilometers");
        t = replace(K, t, "$1 kilometers");
        t = replace(M, t, "$1 meters");
        return t.replaceAll("\\s{2,}", " ");
    }

    private static String replace(Pattern p, String s, String with) {
        Matcher m = p.matcher(s);
        return m.replaceAll(with);
    }
}
