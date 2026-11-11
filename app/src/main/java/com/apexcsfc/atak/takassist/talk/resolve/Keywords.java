package com.apexcsfc.atak.takassist.talk.resolve;

import java.util.Locale;

/**
 * Backstops for commands whose names are unmistakable. If the model labels one of these
 * as something else, the words decide. Plain Java, unit-tested.
 */
public final class Keywords {

    private Keywords() {
    }

    private static String norm(String text) {
        return " " + (text == null ? "" : text.toLowerCase(Locale.US)).replaceAll("[^a-z0-9]+", " ").trim() + " ";
    }

    /** "medevac", "salute", "emergency", "close_tool", "stop_bloodhound", "bloodhound", "stop_navigation", "directions", "locate", or null. */
    public static String action(String text) {
        final String t = norm(text);
        if (t.matches(".* (medevac|medivac|med evac|casevac|cas evac|9 line|nine line) .*")) return "medevac";
        if (emergencyType(text) != null) return "emergency";
        if (t.matches(".* salute (report )?.*") || t.startsWith(" salute ")) return "salute";
        if (t.matches(".* (close|clear|remove|hide|get rid of|exit|dismiss) (the |this |that )?"
                + "(tool|toolbar|checker ?board|checkered (flag|circle)|flag|widget|panel|that thing) .*")) {
            return "close_tool";
        }
        if (t.matches(".* blood ?hound.*")) {
            // "clear / stop / turn off Bloodhound" must never start it.
            return t.matches(".* (stop|clear|end|cancel|remove|close|exit|kill|quit|turn off|turn it off|get rid of|take off|hide) .*")
                    ? "stop_bloodhound" : "bloodhound";
        }
        // Checked before "directions": "clear driving directions" must never start a new route.
        if (t.matches(".* (stop|end|cancel|clear|remove|delete|close|exit|quit|turn off|get rid of|hide) "
                + "(the |my |that |this )?(driving |walking |turn by turn )?"
                + "(navigation|navigating|route|routes|directions|nav) .*")) {
            return "stop_navigation";
        }
        if (t.matches(".* (directions|route me|navigate (me )?to|drive me to|walk me to) .*")) return "directions";
        // "Zoom in on me", "center on my location", "zoom to the truck": show it on the map.
        if (isSelfRequest(text) || t.matches(".* (zoom|center|centre|focus|pan) (in |out |back )?(on|to|at|onto) .*")) {
            return "locate";
        }
        if (!t.contains(" how many ") && t.matches(".* (where is|where s|where are|where am i|locate|nearest|closest) .*")) {
            return "locate";
        }
        return null;
    }

    /** "Where am I", "zoom in on me", "center on my location", "show my position". */
    public static boolean isSelfRequest(String text) {
        final String t = norm(text);
        return t.matches(".* (where am i|where i am|my (current )?(location|position|gps|spot)) .*")
                || t.matches(".* (zoom|center|centre|focus|pan|go|jump|take me|bring me|show me) (in |out |back )?(on |to |at |onto )?(me|myself|my self|us) .*");
    }

    /** nine_one_one, in_contact, ring_the_bell, cancel, or null. */
    public static String emergencyType(String text) {
        final String t = norm(text);
        if (t.matches(".* cancel (the |my |that )?(alert|emergency|911|nine one one|in contact|beacon) .*")) return "cancel";
        if (t.matches(".* (troops in contact|in contact|tic) .*")) return "in_contact";
        if (t.contains(" ring the bell ")) return "ring_the_bell";
        if (t.matches(".* (911|nine one one) .*")) return "nine_one_one";
        return null;
    }
}
