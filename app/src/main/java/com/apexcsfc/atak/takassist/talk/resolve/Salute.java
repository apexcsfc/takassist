package com.apexcsfc.atak.takassist.talk.resolve;

/**
 * A SALUTE report (Size, Activity, Location, Unit, Time, Equipment). ATAK-CIV has no
 * SALUTE form, so it goes on the marker as structured remarks that read cleanly on
 * every TAK client. Location is the marker itself.
 */
public final class Salute {

    public String size;
    public String activity;
    public String unit;
    /** As reported ("ten minutes ago", "1430"), or null for now. */
    public String time;
    public String equipment;

    /**
     * @param mgrs  the marker's grid, for the L line
     * @param nowDtg the current time, used when no time was reported
     */
    public String remarks(String mgrs, String nowDtg) {
        return "SALUTE"
                + "\nS: " + or(size, "not reported")
                + "\nA: " + or(activity, "not reported")
                + "\nL: " + or(mgrs, "see marker")
                + "\nU: " + or(unit, "not reported")
                + "\nT: " + (blank(time) ? nowDtg + " (time of report)" : time.trim())
                + "\nE: " + or(equipment, "not reported");
    }

    /** Lines that weren't reported. */
    public java.util.List<String> missing() {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (blank(size)) out.add("Size");
        if (blank(activity)) out.add("Activity");
        if (blank(unit)) out.add("Unit");
        if (blank(equipment)) out.add("Equipment");
        return out;
    }

    private static boolean blank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String or(String s, String d) {
        return blank(s) ? d : s.trim();
    }
}
