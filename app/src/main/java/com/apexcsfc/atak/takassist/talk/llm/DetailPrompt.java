package com.apexcsfc.atak.takassist.talk.llm;

import com.apexcsfc.atak.takassist.talk.resolve.Medevac;
import com.apexcsfc.atak.takassist.talk.resolve.Salute;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Second, report-specific request for MEDEVAC and SALUTE. Kept out of the main schema
 * so ordinary commands stay short and fast on the phone. Same rule as the main prompt:
 * copy what was said, never invent.
 */
public final class DetailPrompt {

    private DetailPrompt() {
    }

    // ---------------------------------------------------------------- MEDEVAC

    public static final String MEDEVAC_SYSTEM = ""
            + "Fill in a 9-line MEDEVAC request from what the speaker said. Copy only what was said; "
            + "anything not said is null. Turn spoken numbers into digits.\n"
            + "- callsign, frequency: Line 2, as said (\"freq 32.55\", \"call sign Dustoff 6\").\n"
            + "- Line 3 counts by precedence: urgent, urgent_surgical, priority, routine, convenience.\n"
            + "- Line 4 equipment: equipment_none true if they say none; hoist, extraction (extraction equipment), "
            + "ventilator true if asked for; equipment_other for anything else.\n"
            + "- Line 5: litter (stretcher, non-ambulatory) and ambulatory (walking wounded) counts.\n"
            + "- Line 6 security: N no enemy, P possible enemy, E enemy in area, X enemy in area, armed escort required.\n"
            + "- Line 7 marking: panels, pyro (pyrotechnic, flare), smoke, none, other (+ marking_other).\n"
            + "- Line 8 counts: us_military, us_civilian, nonus_military, nonus_civilian, epw (enemy prisoner), child.\n"
            + "- obstacles: terrain or obstacles at the landing zone. remarks: anything else short.\n"
            + "If they give only a total number of casualties, leave Line 3 and Line 5 null and put it in remarks.\n"
            + "Example: \"medevac at my location, two urgent one priority, one litter two ambulatory, no enemy, "
            + "marked with smoke, three US military, need a hoist, freq 34.5\" -> urgent 2, priority 1, litter 1, "
            + "ambulatory 2, security N, marking smoke, us_military 3, hoist true, frequency \"34.5\".\n";

    public static JSONObject medevacSchema() throws JSONException {
        JSONObject p = new JSONObject();
        for (String k : new String[]{"callsign", "frequency", "equipment_other", "marking_other", "obstacles", "remarks"}) {
            p.put(k, nullable("string"));
        }
        for (String k : new String[]{"urgent", "urgent_surgical", "priority", "routine", "convenience",
                "litter", "ambulatory", "us_military", "us_civilian", "nonus_military", "nonus_civilian", "epw", "child"}) {
            p.put(k, nullable("integer"));
        }
        for (String k : new String[]{"equipment_none", "hoist", "extraction", "ventilator"}) {
            p.put(k, nullable("boolean"));
        }
        p.put("security", nullableEnum("N", "P", "E", "X"));
        p.put("marking", nullableEnum("panels", "pyro", "smoke", "none", "other"));
        return object(p);
    }

    public static Medevac parseMedevac(String json) throws JSONException {
        JSONObject o = new JSONObject(strip(json));
        Medevac m = new Medevac();
        m.callsign = str(o, "callsign");
        m.frequency = str(o, "frequency");
        m.urgent = integer(o, "urgent");
        m.urgentSurgical = integer(o, "urgent_surgical");
        m.priority = integer(o, "priority");
        m.routine = integer(o, "routine");
        m.convenience = integer(o, "convenience");
        m.equipmentNone = bool(o, "equipment_none");
        m.hoist = bool(o, "hoist");
        m.extraction = bool(o, "extraction");
        m.ventilator = bool(o, "ventilator");
        m.equipmentOther = str(o, "equipment_other");
        m.litter = integer(o, "litter");
        m.ambulatory = integer(o, "ambulatory");
        m.security = str(o, "security");
        m.marking = str(o, "marking");
        m.markingOther = str(o, "marking_other");
        m.usMilitary = integer(o, "us_military");
        m.usCivilian = integer(o, "us_civilian");
        m.nonUsMilitary = integer(o, "nonus_military");
        m.nonUsCivilian = integer(o, "nonus_civilian");
        m.epw = integer(o, "epw");
        m.child = integer(o, "child");
        m.obstacles = str(o, "obstacles");
        m.remarks = str(o, "remarks");
        return m;
    }

    // ----------------------------------------------------------------- SALUTE

    public static final String SALUTE_SYSTEM = ""
            + "Fill in a SALUTE report from what the speaker said. Copy only what was said, short; "
            + "anything not said is null. The location is handled separately: don't repeat it.\n"
            + "- size: how many people or vehicles (\"3 personnel\", \"2 trucks\").\n"
            + "- activity: what they are doing (\"digging in\", \"moving north\").\n"
            + "- unit: uniform, insignia or who they are (\"black uniforms, no insignia\").\n"
            + "- time: when it was seen, as said (\"10 minutes ago\", \"1430\"); null if not said.\n"
            + "- equipment: weapons and vehicles (\"AK rifles, one technical\").\n"
            + "Example: \"salute report, four enemy in black uniforms setting up a mortar 300 meters east, "
            + "seen 5 minutes ago, with a pickup truck\" -> size \"4 personnel\", activity \"setting up a mortar\", "
            + "unit \"black uniforms\", time \"5 minutes ago\", equipment \"mortar, pickup truck\".\n";

    public static JSONObject saluteSchema() throws JSONException {
        JSONObject p = new JSONObject();
        for (String k : new String[]{"size", "activity", "unit", "time", "equipment"}) p.put(k, nullable("string"));
        return object(p);
    }

    public static Salute parseSalute(String json) throws JSONException {
        JSONObject o = new JSONObject(strip(json));
        Salute s = new Salute();
        s.size = str(o, "size");
        s.activity = str(o, "activity");
        s.unit = str(o, "unit");
        s.time = str(o, "time");
        s.equipment = str(o, "equipment");
        return s;
    }

    // ---------------------------------------------------------------- helpers

    private static JSONObject object(JSONObject props) throws JSONException {
        JSONArray req = new JSONArray();
        for (java.util.Iterator<String> it = props.keys(); it.hasNext(); ) req.put(it.next());
        return new JSONObject().put("type", "object").put("properties", props)
                .put("required", req).put("additionalProperties", false);
    }

    private static JSONObject nullable(String type) throws JSONException {
        return new JSONObject().put("type", new JSONArray().put(type).put("null"));
    }

    private static JSONObject nullableEnum(String... values) throws JSONException {
        JSONArray e = new JSONArray();
        for (String v : values) e.put(v);
        e.put(JSONObject.NULL);
        return new JSONObject().put("type", new JSONArray().put("string").put("null")).put("enum", e);
    }

    private static String strip(String json) {
        String t = json == null ? "" : json.trim();
        int a = t.indexOf('{');
        int b = t.lastIndexOf('}');
        return a >= 0 && b > a ? t.substring(a, b + 1) : t;
    }

    private static String str(JSONObject o, String k) {
        if (!o.has(k) || o.isNull(k)) return null;
        String v = o.optString(k, "").trim();
        return v.isEmpty() ? null : v;
    }

    private static Integer integer(JSONObject o, String k) {
        if (!o.has(k) || o.isNull(k)) return null;
        double d = o.optDouble(k, Double.NaN);
        return Double.isNaN(d) || d < 0 ? null : (int) Math.round(d);
    }

    private static Boolean bool(JSONObject o, String k) {
        if (!o.has(k) || o.isNull(k)) return null;
        return o.optBoolean(k, false);
    }
}
