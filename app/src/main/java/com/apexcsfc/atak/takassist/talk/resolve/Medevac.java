package com.apexcsfc.atak.takassist.talk.resolve;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A 9-line MEDEVAC as heard, and how it maps onto ATAK's own CASEVAC marker fields
 * (the ones ATAK's MEDEVAC screen reads and sends). Line 1 is the marker's position.
 * Unset fields are null and are left for the user to fill in ATAK's screen.
 */
public final class Medevac {

    public static final String COT_TYPE = "b-r-f-h-c";

    // Line 2
    public String callsign;
    public String frequency;
    // Line 3: patients by precedence
    public Integer urgent, urgentSurgical, priority, routine, convenience;
    // Line 4: special equipment
    public Boolean equipmentNone, hoist, extraction, ventilator;
    public String equipmentOther;
    // Line 5: patients by type
    public Integer litter, ambulatory;
    /** Line 6: N, P, E or X. */
    public String security;
    /** Line 7: panels, pyro, smoke, none, other. */
    public String marking;
    public String markingOther;
    // Line 8: patient nationality and status
    public Integer usMilitary, usCivilian, nonUsMilitary, nonUsCivilian, epw, child;
    // Line 9 (peacetime: terrain and obstacles)
    public String obstacles;
    public String remarks;

    /** Line 6 choices in ATAK's order. */
    private static final String[] SECURITY = {"N", "P", "E", "X"};
    /** Line 7 choices in ATAK's order. */
    private static final String[] MARKING = {"panels", "pyro", "smoke", "none", "other"};

    /** Marker metadata for ATAK's MEDEVAC screen: String or Boolean values. */
    public Map<String, Object> toMeta(String title) {
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("casevac", Boolean.TRUE);
        m.put("title", title);
        if (frequency != null) m.put("freq", frequency);
        num(m, "urgent", urgent);
        num(m, "urgent_surgical", urgentSurgical);
        num(m, "priority", priority);
        num(m, "routine", routine);
        num(m, "convenience", convenience);
        bool(m, "equipment_none", equipmentNone);
        bool(m, "hoist", hoist);
        bool(m, "extraction_equipment", extraction);
        bool(m, "ventilator", ventilator);
        if (equipmentOther != null) {
            m.put("equipment_other", Boolean.TRUE);
            m.put("equipment_detail", equipmentOther);
        }
        num(m, "litter", litter);
        num(m, "ambulatory", ambulatory);
        final int sec = indexOf(SECURITY, security);
        if (sec >= 0) m.put("security", String.valueOf(sec));
        final int mark = indexOf(MARKING, marking);
        if (mark >= 0) m.put("hlz_marking", String.valueOf(mark));
        if (markingOther != null) m.put("hlz_other", markingOther);
        num(m, "us_military", usMilitary);
        num(m, "us_civilian", usCivilian);
        num(m, "nonus_military", nonUsMilitary);
        num(m, "nonus_civilian", nonUsCivilian);
        num(m, "epw", epw);
        num(m, "child", child);
        if (obstacles != null) m.put("obstacles", obstacles);
        if (remarks != null) m.put("medline_remarks", remarks);
        return m;
    }

    /** Lines that weren't heard, so the user knows what to fill in. */
    public List<String> missingLines() {
        final List<String> out = new ArrayList<>();
        if (frequency == null && callsign == null) out.add("Line 2 (frequency and call sign)");
        if (urgent == null && urgentSurgical == null && priority == null && routine == null && convenience == null) {
            out.add("Line 3 (patients by precedence)");
        }
        if (equipmentNone == null && hoist == null && extraction == null && ventilator == null && equipmentOther == null) {
            out.add("Line 4 (special equipment)");
        }
        if (litter == null && ambulatory == null) out.add("Line 5 (litter / ambulatory)");
        if (indexOf(SECURITY, security) < 0) out.add("Line 6 (security at pickup site)");
        if (indexOf(MARKING, marking) < 0) out.add("Line 7 (marking method)");
        if (usMilitary == null && usCivilian == null && nonUsMilitary == null && nonUsCivilian == null
                && epw == null && child == null) {
            out.add("Line 8 (nationality and status)");
        }
        return out;
    }

    /** Total patients from Line 3 (or Line 5 if Line 3 is empty), or 0. */
    public int patients() {
        int a = sum(urgent, urgentSurgical, priority, routine, convenience);
        return a > 0 ? a : sum(litter, ambulatory);
    }

    private static int sum(Integer... v) {
        int s = 0;
        for (Integer i : v) if (i != null && i > 0) s += i;
        return s;
    }

    private static void num(Map<String, Object> m, String k, Integer v) {
        if (v != null && v >= 0) m.put(k, String.valueOf(v));
    }

    private static void bool(Map<String, Object> m, String k, Boolean v) {
        if (v != null) m.put(k, v);
    }

    private static int indexOf(String[] a, String v) {
        if (v == null) return -1;
        for (int i = 0; i < a.length; i++) if (a[i].equalsIgnoreCase(v.trim())) return i;
        return -1;
    }
}
