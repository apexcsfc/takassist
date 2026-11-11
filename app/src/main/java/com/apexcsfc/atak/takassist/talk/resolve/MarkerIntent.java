package com.apexcsfc.atak.takassist.talk.resolve;

/**
 * What the model understood from one spoken or typed report. It holds only what the
 * user said (words, numbers, units); it never holds coordinates the model worked out.
 * Unset fields are null.
 */
public final class MarkerIntent {

    /** "place_marker" or "unclear". */
    public String action;
    /** hostile, friendly, neutral, unknown. */
    public String affiliation;
    /** ground, air, sea. */
    public String dimension;
    /** Short name for the marker, e.g. "truck" or "2 dismounts". */
    public String label;

    /**
     * "relative" (bearing and distance from a point), "grid" (MGRS), "place" (at a named
     * place), or "self" (my position).
     */
    public String locationMode;
    /** For relative: "self", "map_center" or "place". */
    public String refPoint;
    /** Place name as spoken, for location "place" or ref point "place". */
    public String place;
    /** Numeric bearing as spoken, degrees. */
    public Double bearingDeg;
    /** Compass word as spoken: N, NNE, NE, ... */
    public String cardinal;
    /** "true", "magnetic" or "unspecified", as spoken. */
    public String bearingRef;
    public Double distance;
    /** m, km, mi, yd, ft, nmi. */
    public String distanceUnit;
    /** MGRS as spoken, any spacing, possibly without zone or 100 km square. */
    public String grid;

    public String remarks;
    /** For action "set_map": the map name as spoken, e.g. "NRL DRG". */
    public String mapName;
    /** For action "directions": car, foot or bike. */
    public String travelMode;
    /** For action "emergency": nine_one_one, in_contact, ring_the_bell or cancel. */
    public String emergencyType;
    /** When action is "unclear": the question to ask the user. */
    public String clarify;
}
