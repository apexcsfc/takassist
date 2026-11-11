package com.apexcsfc.atak.takassist.talk.resolve;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** A named place found in the offline map data. */
public final class Place {

    /** Kinds that cover an area; a marker "in" one lands on its centre point. */
    private static final Set<String> AREA_KINDS = new HashSet<>(Arrays.asList(
            "neighbourhood", "suburb", "quarter", "locality", "city", "town", "village",
            "park", "garden", "nature reserve", "university", "college", "cemetery",
            "stadium", "zoo", "island", "lake", "reservoir", "river"));

    public final String name;
    public final String kind;
    public final double lat;
    public final double lon;
    /** False when found by spelling tolerance (shown to the user). */
    public final boolean exactMatch;

    public Place(String name, String kind, double lat, double lon, boolean exactMatch) {
        this.name = name;
        this.kind = kind == null ? "" : kind;
        this.lat = lat;
        this.lon = lon;
        this.exactMatch = exactMatch;
    }

    public boolean isArea() {
        return AREA_KINDS.contains(kind);
    }

    /** "Columbia Heights (neighbourhood)". */
    public String label() {
        return kind.isEmpty() ? name : name + " (" + kind + ")";
    }
}
