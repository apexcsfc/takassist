package com.apexcsfc.atak.takassist.llm;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Named-place lookups from OpenStreetMap, the same data most basemaps draw their
 * labels from. Needs internet. Blocking; call off the UI thread.
 *
 *  - Nominatim  (search by name)  https://nominatim.openstreetmap.org
 *  - Overpass   (features nearby) https://overpass-api.de
 *  - FOSSGIS OSRM (routing)       https://routing.openstreetmap.de
 *
 * Both are free public services: one request at a time, with an identifying
 * User-Agent, per their usage policies.
 */
public class OsmClient {

    private static final String USER_AGENT = "ATAK-LLMAssist-Plugin/0.1 (personal use)";
    private static final String NOMINATIM = "https://nominatim.openstreetmap.org/search";
    private static final String OVERPASS = "https://overpass-api.de/api/interpreter";
    private static final String ROUTING = "https://routing.openstreetmap.de";

    private long lastNominatimCall;

    /**
     * Search places by name, preferring results inside the given box (the map view).
     * Returns Nominatim's JSON array of results.
     */
    public synchronized JSONArray search(String query, double north, double south,
                                         double east, double west, int limit, boolean bounded)
            throws IOException, JSONException {
        // Nominatim allows 1 request/second.
        long wait = 1100 - (System.currentTimeMillis() - lastNominatimCall);
        if (wait > 0) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        lastNominatimCall = System.currentTimeMillis();

        String url = NOMINATIM
                + "?format=jsonv2&addressdetails=0&namedetails=0"
                + "&limit=" + limit
                + "&q=" + URLEncoder.encode(query, "UTF-8")
                + String.format(Locale.US, "&viewbox=%.6f,%.6f,%.6f,%.6f", west, north, east, south)
                + "&bounded=" + (bounded ? 1 : 0);
        return new JSONArray(http("GET", url, null));
    }

    /**
     * Named OSM features within radiusM of a point. Returns Overpass "elements".
     * Ways/relations come back with a "center" lat/lon.
     */
    public JSONArray nearby(double lat, double lon, int radiusM) throws IOException, JSONException {
        String around = String.format(Locale.US, "(around:%d,%.6f,%.6f)", radiusM, lat, lon);
        // Map-label kinds of things: places, parks, water, landmarks, transit, key amenities, terrain.
        String q = "[out:json][timeout:15];("
                + "nwr[\"name\"][\"place\"]" + around + ";"
                + "nwr[\"name\"][\"leisure\"~\"park|playground|garden|stadium|pitch\"]" + around + ";"
                + "nwr[\"name\"][\"natural\"]" + around + ";"
                + "nwr[\"name\"][\"water\"]" + around + ";"
                + "nwr[\"name\"][\"waterway\"~\"river|stream|canal\"]" + around + ";"
                + "nwr[\"name\"][\"tourism\"~\"attraction|museum|viewpoint|hotel\"]" + around + ";"
                + "nwr[\"name\"][\"historic\"]" + around + ";"
                + "nwr[\"name\"][\"railway\"=\"station\"]" + around + ";"
                + "nwr[\"name\"][\"amenity\"~\"school|university|hospital|police|fire_station|"
                + "place_of_worship|library|townhall|courthouse|embassy\"]" + around + ";"
                + ");out center tags 40;";
        String body = "data=" + URLEncoder.encode(q, "UTF-8");
        JSONObject o = new JSONObject(http("POST", OVERPASS, body));
        return o.optJSONArray("elements") == null ? new JSONArray() : o.getJSONArray("elements");
    }

    /** Overpass tag filters for each find_nearest category. */
    public static final java.util.LinkedHashMap<String, String[]> CATEGORIES = new java.util.LinkedHashMap<>();
    static {
        CATEGORIES.put("water", new String[]{"nwr[\"natural\"=\"water\"]", "nwr[\"landuse\"=\"reservoir\"]",
                "way[\"waterway\"~\"^(river|stream|canal)$\"]", "rel[\"waterway\"=\"river\"]",
                "nwr[\"natural\"=\"bay\"]"});
        CATEGORIES.put("park", new String[]{"nwr[\"leisure\"~\"^(park|nature_reserve|garden)$\"]"});
        CATEGORIES.put("hospital", new String[]{"nwr[\"amenity\"=\"hospital\"]"});
        CATEGORIES.put("clinic", new String[]{"nwr[\"amenity\"~\"^(clinic|doctors)$\"]"});
        CATEGORIES.put("pharmacy", new String[]{"nwr[\"amenity\"=\"pharmacy\"]"});
        CATEGORIES.put("police", new String[]{"nwr[\"amenity\"=\"police\"]"});
        CATEGORIES.put("fire_station", new String[]{"nwr[\"amenity\"=\"fire_station\"]"});
        CATEGORIES.put("metro_station", new String[]{"nwr[\"railway\"=\"station\"]",
                "nwr[\"public_transport\"=\"station\"][\"subway\"=\"yes\"]"});
        CATEGORIES.put("bus_stop", new String[]{"node[\"highway\"=\"bus_stop\"]"});
        CATEGORIES.put("school", new String[]{"nwr[\"amenity\"~\"^(school|college|university)$\"]"});
        CATEGORIES.put("fuel", new String[]{"nwr[\"amenity\"=\"fuel\"]"});
        CATEGORIES.put("parking", new String[]{"nwr[\"amenity\"=\"parking\"]"});
        CATEGORIES.put("restroom", new String[]{"nwr[\"amenity\"=\"toilets\"]"});
        CATEGORIES.put("drinking_water", new String[]{"node[\"amenity\"=\"drinking_water\"]"});
        CATEGORIES.put("shelter", new String[]{"nwr[\"amenity\"=\"shelter\"]"});
        CATEGORIES.put("grocery", new String[]{"nwr[\"shop\"~\"^(supermarket|convenience)$\"]"});
        CATEGORIES.put("food", new String[]{"nwr[\"amenity\"~\"^(restaurant|fast_food|cafe)$\"]"});
        CATEGORIES.put("atm", new String[]{"nwr[\"amenity\"~\"^(atm|bank)$\"]"});
        CATEGORIES.put("high_ground", new String[]{"node[\"natural\"~\"^(peak|hill)$\"]"});
    }

    /**
     * Features of one category within radiusM, with geometry clipped to the search box
     * (so distance to a lake or river is measured to its edge, not its middle).
     */
    public JSONArray category(String category, double lat, double lon, int radiusM)
            throws IOException, JSONException {
        String[] filters = CATEGORIES.get(category);
        if (filters == null) throw new IOException("Unknown category: " + category);
        String around = String.format(Locale.US, "(around:%d,%.6f,%.6f)", radiusM, lat, lon);
        double dLat = radiusM / 111_320.0;
        double dLon = dLat / Math.max(0.2, Math.cos(Math.toRadians(lat)));
        String bbox = String.format(Locale.US, "(%.6f,%.6f,%.6f,%.6f)",
                lat - dLat, lon - dLon, lat + dLat, lon + dLon);
        StringBuilder q = new StringBuilder("[out:json][timeout:15];(");
        for (String f : filters) q.append(f).append(around).append(';');
        q.append(");out tags geom").append(bbox).append(" 40;");
        String body = "data=" + URLEncoder.encode(q.toString(), "UTF-8");
        JSONObject o = new JSONObject(http("POST", OVERPASS, body));
        return o.optJSONArray("elements") == null ? new JSONArray() : o.getJSONArray("elements");
    }

    /**
     * Fuzzy, case-insensitive name search near a point: returns features whose name
     * contains any of the given words (regex-escaped by the caller). Used when an
     * exact search finds nothing locally, e.g. "Logan Square" -> "Logan Circle".
     */
    public JSONArray nameSearch(String wordsRegex, double lat, double lon, int radiusM)
            throws IOException, JSONException {
        String around = String.format(Locale.US, "(around:%d,%.6f,%.6f)", radiusM, lat, lon);
        String q = "[out:json][timeout:20];("
                + "nwr[\"name\"~\"" + wordsRegex + "\",i][!\"highway\"]" + around + ";"
                + "way[\"name\"~\"" + wordsRegex + "\",i][\"highway\"]" + around + ";"
                + ");out center tags 80;";
        String body = "data=" + URLEncoder.encode(q, "UTF-8");
        JSONObject o = new JSONObject(http("POST", OVERPASS, body));
        return o.optJSONArray("elements") == null ? new JSONArray() : o.getJSONArray("elements");
    }

    /**
     * Named roads/paths within radiusM, with their full geometry ("geometry": [{lat,lon},...])
     * so the caller can measure the true distance to each one.
     */
    public JSONArray roads(double lat, double lon, int radiusM) throws IOException, JSONException {
        String around = String.format(Locale.US, "(around:%d,%.6f,%.6f)", radiusM, lat, lon);
        String q = "[out:json][timeout:20];"
                + "way[\"highway\"~\"^(motorway|trunk|primary|secondary|tertiary|unclassified|"
                + "residential|living_street|service|pedestrian|footway|path|cycleway|track)$\"]"
                + "[\"name\"]" + around + ";out tags geom 80;";
        String body = "data=" + URLEncoder.encode(q, "UTF-8");
        JSONObject o = new JSONObject(http("POST", OVERPASS, body));
        return o.optJSONArray("elements") == null ? new JSONArray() : o.getJSONArray("elements");
    }

    /**
     * Turn-by-turn route on OSM roads. mode is "foot", "car" or "bike".
     * Returns the OSRM response (routes[0] has distance, duration, geometry, legs[0].steps).
     */
    public JSONObject route(double fromLat, double fromLon, double toLat, double toLon, String mode)
            throws IOException, JSONException {
        String profile = "car".equals(mode) ? "routed-car" : "bike".equals(mode) ? "routed-bike" : "routed-foot";
        String url = String.format(Locale.US,
                "%s/%s/route/v1/driving/%.6f,%.6f;%.6f,%.6f?overview=full&geometries=geojson&steps=true",
                ROUTING, profile, fromLon, fromLat, toLon, toLat);
        JSONObject o = new JSONObject(http("GET", url, null));
        if (!"Ok".equals(o.optString("code"))) {
            throw new IOException("Routing failed: " + o.optString("message", o.optString("code")));
        }
        return o;
    }

    /** A readable feature type from OSM tags, e.g. "park", "school", "metro station". */
    public static String kindOf(JSONObject tags) {
        if (tags == null) return "feature";
        String[] keys = {"railway", "amenity", "leisure", "natural", "water", "waterway",
                "tourism", "historic", "place"};
        for (String k : keys) {
            String v = tags.optString(k, "");
            if (!v.isEmpty()) {
                if ("railway".equals(k) && "station".equals(v)) {
                    return tags.has("station") ? tags.optString("station") + " station" : "station";
                }
                return v.replace('_', ' ');
            }
        }
        return "feature";
    }

    private static String http(String method, String url, String formBody) throws IOException {
        if (com.apexcsfc.atak.takassist.Privacy.offlineOnly()) {
            // Every online map lookup comes through here, so this one check covers them all.
            throw new IOException("Offline only is on, so online map lookups are turned off");
        }
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(8000);
            c.setReadTimeout(25000);
            c.setRequestProperty("User-Agent", USER_AGENT);
            c.setRequestProperty("Accept", "application/json");
            if (formBody != null) {
                byte[] b = formBody.getBytes(StandardCharsets.UTF_8);
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                c.setFixedLengthStreamingMode(b.length);
                try (OutputStream os = c.getOutputStream()) {
                    os.write(b);
                }
            }
            int code = c.getResponseCode();
            InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
            StringBuilder sb = new StringBuilder();
            if (is != null) {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                    char[] buf = new char[4096];
                    int n;
                    while ((n = r.read(buf)) != -1) sb.append(buf, 0, n);
                }
            }
            if (code >= 400) {
                throw new IOException("HTTP " + code + " from " + new URL(url).getHost());
            }
            return sb.toString();
        } finally {
            c.disconnect();
        }
    }
}
