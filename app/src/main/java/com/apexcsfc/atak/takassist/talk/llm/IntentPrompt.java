package com.apexcsfc.atak.takassist.talk.llm;

import com.apexcsfc.atak.takassist.talk.resolve.MarkerIntent;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The model's whole job: copy what the speaker said into fixed fields. The schema is
 * enforced by llama-server's grammar sampler, so the reply always parses; the prompt
 * keeps the model from doing arithmetic (no unit conversion, no coordinates).
 */
public final class IntentPrompt {

    private IntentPrompt() {
    }

    public static final String SYSTEM = ""
            + "You convert a soldier's or first responder's spoken report or question into JSON for a map.\n"
            + "Copy what was said. Do not convert units, do not compute coordinates, do not invent details.\n"
            + "\n"
            + "Fields:\n"
            + "- action: \"place_marker\" if the report says where something is; \"count_markers\" if they ask how many markers/targets/contacts there are; \"set_map\" if they want to change or switch the map, base map, map type, imagery or layer; \"directions\" for driving/walking directions or a route by road to a marker, place or location (\"directions to\", \"route me to\", \"navigate to\", \"take me to\"); \"bloodhound\" only when they say Bloodhound or want a straight-line bearing/range track to a marker; \"stop_bloodhound\" to stop, clear or turn off Bloodhound; \"close_tool\" to close or clear an ATAK tool, toolbar or panel that's open on screen; \"stop_navigation\" to stop, clear or cancel navigation, directions or a route; \"locate\" for where is / nearest / closest questions about a marker, enemy or teammate, or \"where am I\"; \"medevac\" for a MEDEVAC / CASEVAC / 9-line request; \"salute\" for a SALUTE report; \"emergency\" to send or cancel an alert (911, in contact / troops in contact / TIC, ring the bell); otherwise \"unclear\".\n"
            + "  For medevac the location fields are the pickup site (\"at my location\" -> self). For salute they are where the enemy is.\n"
            + "  For bloodhound and directions: label is the destination marker's name (\"truck\") or null for a place or location; affiliation is its side if said, else any; the location fields say where it is, if they said (\"two klicks south of me\"), else null.\n"
            + "- travel_mode: for directions: car (drive, driving), foot (walk, on foot), bike; null if not said.\n"
            + "  A direction plus a distance IS a location: with no reference point it is measured from the speaker (ref_point self). Never call that unclear.\n"
            + "- affiliation: hostile (enemy, bad guys, OPFOR), friendly (friendlies, blue), neutral (civilians), unknown. For count_markers with no side named (\"how many markers\"), use any.\n"
            + "- For count_markers: distance and distance_unit are the limit if they say one (\"within 2 klicks\"), else null. \"near me\" alone is not a limit. Location fields are null.\n"
            + "- dimension: ground unless it is clearly air (aircraft, drone, helo) or sea (boat, ship).\n"
            + "- label: 1-4 words naming what was seen, e.g. \"truck\", \"2 dismounts\", \"drone\". null if none.\n"
            + "- location_mode: \"relative\" for a direction and distance; \"grid\" for an MGRS grid; \"place\" for in/at/near a named place with no direction and distance; \"self\" for 'at my position' / 'here'.\n"
            + "- ref_point: for relative, what the distance is measured from: \"self\" by default, \"map_center\" for the map center, \"place\" for a named place (\"200 m north of the hospital\").\n"
            + "- place: the place name exactly as said, without words like in/at/near/the, e.g. \"Columbia Heights\", \"Howard University Hospital\". Keep their spelling. null if no place.\n"
            + "- bearing_deg: a spoken number of degrees (\"bearing two seven zero\" -> 270, \"azimuth 45\" -> 45), else null.\n"
            + "- cardinal: a spoken compass direction as N, NNE, NE, ENE, E, ESE, SE, SSE, S, SSW, SW, WSW, W, WNW, NW, NNW, else null.\n"
            + "- bearing_ref: \"magnetic\" or \"true\" only if said, else \"unspecified\".\n"
            + "- distance and distance_unit: the number and unit as spoken. meters -> m, klicks, clicks, k or kilometers -> km, miles -> mi, yards -> yd, feet -> ft, nautical miles -> nmi. No unit said -> m.\n"
            + "- grid: the MGRS grid exactly as spoken with spoken numbers turned into digits, e.g. \"18S UJ 2348 0647\" or \"2348 0647\". null if none.\n"
            + "- map_name: for set_map, the map they asked for, as said, without words like map/type/layer, e.g. \"NRL DRG\", \"topo\", \"satellite\". Otherwise null.\n"
            + "- emergency_type: for emergency only: nine_one_one, in_contact, ring_the_bell, or cancel. Otherwise null.\n"
            + "- remarks: anything else useful (activity, size, time), short. null if none.\n"
            + "- clarify: when action is unclear, one short question to ask the speaker. Otherwise null.\n"
            + "\n"
            + "Examples:\n"
            + "\"enemy truck two hundred meters north of me\" -> place_marker, hostile, ground, \"truck\", relative, self, bearing_deg null, cardinal N, unspecified, 200, m.\n"
            + "\"friendlies at grid one eight sierra uniform juliet two three four eight zero six four seven\" -> place_marker, friendly, ground, \"friendlies\", grid \"18S UJ 2348 0647\".\n"
            + "\"drone bearing zero four five magnetic one point five klicks\" -> place_marker, unknown, air, \"drone\", relative, self, 45, null, magnetic, 1.5, km.\n"
            + "\"friendly squad 1.5 kilometers northeast\" -> place_marker, friendly, ground, \"squad\", relative, self, bearing_deg null, cardinal NE, unspecified, 1.5, km.\n"
            + "\"vehicle 300 meters west\" -> place_marker, unknown, ground, \"vehicle\", relative, self, cardinal W, 300, m.\n"
            + "\"mark my position, vehicle broke down\" -> place_marker, friendly, ground, \"broken vehicle\", self.\n"
            + "\"tank located in Columbia Heights\" -> place_marker, unknown, ground, \"tank\", place, place \"Columbia Heights\".\n"
            + "\"two hostiles 300 meters east of Howard University Hospital\" -> place_marker, hostile, ground, \"2 hostiles\", relative, ref_point place, cardinal E, 300, m, place \"Howard University Hospital\".\n"
            + "\"how many hostile targets are near me\" -> count_markers, hostile, distance null.\n"
            + "\"how many friendlies within 2 klicks\" -> count_markers, friendly, 2, km.\n"
            + "\"count the markers on the map\" -> count_markers, any.\n"
            + "\"where is the nearest enemy to my position\" -> locate, hostile, label null.\n"
            + "\"where's the truck\" -> locate, any, \"truck\".\n"
            + "\"where is Alpha 2\" -> locate, any, \"Alpha 2\".\n"
            + "\"where am I\" -> locate, location_mode self.\n"
            + "\"switch map type to NRL DRG\" -> set_map, map_name \"NRL DRG\".\n"
            + "\"use bloodhound to get to the truck two klicks south of me\" -> bloodhound, any, \"truck\", relative, self, cardinal S, 2, km.\n"
            + "\"bloodhound the enemy aircraft\" -> bloodhound, hostile, \"aircraft\", location null.\n"
            + "\"give me driving directions to the tank\" -> directions, car, any, \"tank\".\n"
            + "\"walk me to Union Station\" -> directions, foot, label null, place, place \"Union Station\".\n"
            + "\"take me to the rally point\" -> directions, travel_mode null, \"rally point\".\n"
            + "\"I need a medevac at my location, two urgent\" -> medevac, self.\n"
            + "\"salute report, four enemy digging in 300 meters east\" -> salute, hostile, \"4 enemy\", relative, self, cardinal E, 300, m.\n"
            + "\"troops in contact\" -> emergency, emergency_type in_contact.\n"
            + "\"cancel the alert\" -> emergency, emergency_type cancel.\n"
            + "\"change to satellite imagery\" -> set_map, map_name \"satellite imagery\".\n"
            + "\"put the topo map on\" -> set_map, map_name \"topo\".\n"
            + "\"they're over there somewhere\" -> unclear, clarify \"Where are they? Give a grid or a direction and distance.\"\n";

    private static JSONObject s(String... enumValues) throws JSONException {
        JSONObject o = new JSONObject().put("type", "string");
        if (enumValues.length > 0) {
            JSONArray e = new JSONArray();
            for (String v : enumValues) e.put(v);
            o.put("enum", e);
        }
        return o;
    }

    private static JSONObject nullable(String type, String... enumValues) throws JSONException {
        JSONArray types = new JSONArray().put(type).put("null");
        JSONObject o = new JSONObject().put("type", types);
        if (enumValues.length > 0) {
            JSONArray e = new JSONArray();
            for (String v : enumValues) e.put(v);
            e.put(JSONObject.NULL);
            o.put("enum", e);
        }
        return o;
    }

    /** JSON Schema for the reply; every field is required so the model fills each slot. */
    public static JSONObject schema() throws JSONException {
        JSONObject p = new JSONObject();
        p.put("action", s("place_marker", "count_markers", "locate", "set_map", "bloodhound", "stop_bloodhound",
                "directions", "stop_navigation", "close_tool", "medevac", "salute", "emergency", "unclear"));
        p.put("affiliation", s("hostile", "friendly", "neutral", "unknown", "any"));
        p.put("dimension", s("ground", "air", "sea"));
        p.put("label", nullable("string"));
        p.put("location_mode", nullable("string", "relative", "grid", "place", "self"));
        p.put("ref_point", nullable("string", "self", "map_center", "place"));
        p.put("bearing_deg", nullable("number"));
        p.put("cardinal", nullable("string", "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
                "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"));
        p.put("bearing_ref", s("true", "magnetic", "unspecified"));
        p.put("distance", nullable("number"));
        p.put("distance_unit", nullable("string", "m", "km", "mi", "yd", "ft", "nmi"));
        p.put("grid", nullable("string"));
        p.put("place", nullable("string"));
        p.put("map_name", nullable("string"));
        p.put("travel_mode", nullable("string", "car", "foot", "bike"));
        p.put("emergency_type", nullable("string", "nine_one_one", "in_contact", "ring_the_bell", "cancel"));
        p.put("remarks", nullable("string"));
        p.put("clarify", nullable("string"));

        JSONArray req = new JSONArray();
        for (java.util.Iterator<String> it = p.keys(); it.hasNext(); ) req.put(it.next());

        return new JSONObject()
                .put("type", "object")
                .put("properties", p)
                .put("required", req)
                .put("additionalProperties", false);
    }

    /** Reads the model's JSON reply into an intent. */
    public static MarkerIntent parse(String json) throws JSONException {
        String t = json == null ? "" : json.trim();
        // Tolerate a stray code fence if the server ever skips the grammar.
        if (t.startsWith("```")) {
            int a = t.indexOf('{');
            int b = t.lastIndexOf('}');
            if (a >= 0 && b > a) t = t.substring(a, b + 1);
        }
        JSONObject o = new JSONObject(t);
        MarkerIntent in = new MarkerIntent();
        in.action = str(o, "action");
        in.affiliation = str(o, "affiliation");
        in.dimension = str(o, "dimension");
        in.label = str(o, "label");
        in.locationMode = str(o, "location_mode");
        in.refPoint = str(o, "ref_point");
        in.bearingDeg = num(o, "bearing_deg");
        in.cardinal = str(o, "cardinal");
        in.bearingRef = str(o, "bearing_ref");
        in.distance = num(o, "distance");
        in.distanceUnit = str(o, "distance_unit");
        in.grid = str(o, "grid");
        in.place = str(o, "place");
        in.mapName = str(o, "map_name");
        in.travelMode = str(o, "travel_mode");
        in.emergencyType = str(o, "emergency_type");
        in.remarks = str(o, "remarks");
        in.clarify = str(o, "clarify");
        return in;
    }

    private static String str(JSONObject o, String k) {
        if (!o.has(k) || o.isNull(k)) return null;
        String v = o.optString(k, "").trim();
        return v.isEmpty() ? null : v;
    }

    private static Double num(JSONObject o, String k) {
        if (!o.has(k) || o.isNull(k)) return null;
        double d = o.optDouble(k, Double.NaN);
        return Double.isNaN(d) ? null : d;
    }
}
