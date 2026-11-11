package com.apexcsfc.takassist.maps.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Areas that can be built on the phone from Geofabrik's free OpenStreetMap downloads:
 * every US state, DC and the territories. Any other Geofabrik area can be given by its path
 * (for example "europe/germany").
 */
public final class States {

    public static final String DEFAULT_MIRROR = "https://download.geofabrik.de/";

    private static final String[][] US = {
            {"alabama", "Alabama"}, {"alaska", "Alaska"}, {"arizona", "Arizona"}, {"arkansas", "Arkansas"},
            {"california", "California"}, {"colorado", "Colorado"}, {"connecticut", "Connecticut"},
            {"delaware", "Delaware"}, {"district-of-columbia", "District of Columbia"}, {"florida", "Florida"},
            {"georgia", "Georgia"}, {"hawaii", "Hawaii"}, {"idaho", "Idaho"}, {"illinois", "Illinois"},
            {"indiana", "Indiana"}, {"iowa", "Iowa"}, {"kansas", "Kansas"}, {"kentucky", "Kentucky"},
            {"louisiana", "Louisiana"}, {"maine", "Maine"}, {"maryland", "Maryland"},
            {"massachusetts", "Massachusetts"}, {"michigan", "Michigan"}, {"minnesota", "Minnesota"},
            {"mississippi", "Mississippi"}, {"missouri", "Missouri"}, {"montana", "Montana"},
            {"nebraska", "Nebraska"}, {"nevada", "Nevada"}, {"new-hampshire", "New Hampshire"},
            {"new-jersey", "New Jersey"}, {"new-mexico", "New Mexico"}, {"new-york", "New York"},
            {"north-carolina", "North Carolina"}, {"north-dakota", "North Dakota"}, {"ohio", "Ohio"},
            {"oklahoma", "Oklahoma"}, {"oregon", "Oregon"}, {"pennsylvania", "Pennsylvania"},
            {"rhode-island", "Rhode Island"}, {"south-carolina", "South Carolina"},
            {"south-dakota", "South Dakota"}, {"tennessee", "Tennessee"}, {"texas", "Texas"},
            {"utah", "Utah"}, {"vermont", "Vermont"}, {"virginia", "Virginia"},
            {"washington", "Washington"}, {"west-virginia", "West Virginia"}, {"wisconsin", "Wisconsin"},
            {"wyoming", "Wyoming"}, {"puerto-rico", "Puerto Rico"}, {"us-virgin-islands", "US Virgin Islands"},
    };

    public static List<Region> all() {
        List<Region> out = new ArrayList<>();
        for (String[] s : US) out.add(new Region(s[0], s[1], "", null));
        return out;
    }

    /** Normalises what an administrator typed ("New Jersey", "new_jersey", "NJ"?) to an id. */
    public static String idFor(String typed) {
        String t = typed.trim().toLowerCase(Locale.US).replace('_', '-').replace(' ', '-');
        if (t.contains("/")) return t.replace('/', '~'); // a Geofabrik path, e.g. europe/germany
        if (t.equals("dc") || t.equals("washington-dc")) return "district-of-columbia";
        for (String[] s : US) if (s[0].equals(t)) return s[0];
        return t;
    }

    public static String nameFor(String id) {
        for (String[] s : US) if (s[0].equals(id)) return s[1];
        if (id.contains("~")) {
            String last = id.substring(id.lastIndexOf('~') + 1);
            return MapPacks.prettyName(last);
        }
        return MapPacks.prettyName(id);
    }

    /** Download address of the area's .osm.pbf on the mirror. */
    public static String pbfUrl(String mirror, String id) {
        String base = mirror == null || mirror.trim().isEmpty() ? DEFAULT_MIRROR : mirror.trim();
        if (!base.endsWith("/")) base += "/";
        String path = id.contains("~") ? id.replace('~', '/') : "north-america/us/" + id;
        return base + path + "-latest.osm.pbf";
    }
}
