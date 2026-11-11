package com.apexcsfc.atak.takassist.llm;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Handler;
import android.os.Looper;

import com.atakmap.android.maps.MapGroup;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.Marker;
import com.atakmap.android.maps.PointMapItem;
import com.atakmap.android.maps.Polyline;
import com.apexcsfc.atak.takassist.offline.OfflineArea;
import com.apexcsfc.atak.takassist.offline.OfflineMaps;
import com.apexcsfc.atak.takassist.offline.OfflineRouter;
import com.atakmap.android.user.PlacePointTool;
import com.atakmap.coremap.conversions.CoordinateFormat;
import com.atakmap.coremap.conversions.CoordinateFormatUtilities;
import com.atakmap.coremap.maps.conversion.EGM96;
import com.atakmap.coremap.maps.coords.GeoBounds;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.coremap.maps.coords.GeoPointMetaData;
import com.atakmap.map.CameraController;
import com.atakmap.map.elevation.ElevationManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The tools the LLM can call. Everything numeric (distance, bearing, MGRS) is
 * computed here, so the model never has to do geodesy in its head.
 *
 * Tool execution happens on the chat worker thread; anything that touches the
 * map is marshalled onto the UI thread via {@link #onUi}.
 */
public class MapToolbox {

    /** Lets the pane decide at call time whether map edits need approval. */
    public interface ConfirmPolicy {
        boolean requireConfirm();
    }

    private static final int DEFAULT_RADIUS_M = 5000;
    private static final int MAX_RESULTS = 25;
    private static final double EARTH_RADIUS_M = 6371008.8;

    private final MapView mapView;
    private final ConfirmPolicy confirmPolicy;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final OsmClient osm = new OsmClient();
    private final OfflineMaps offline = new OfflineMaps(
            new java.io.File(android.os.Environment.getExternalStorageDirectory(), "atak/llm"));
    private Polyline routeLine; // last drawn route, replaced on each new route (UI thread only)

    public MapToolbox(MapView mapView, ConfirmPolicy confirmPolicy) {
        this.mapView = mapView;
        this.confirmPolicy = confirmPolicy;
    }

    // ------------------------------------------------------------------ schemas

    public static JSONArray toolSchemas() {
        JSONArray tools = new JSONArray();
        try {
            tools.put(tool("get_self_location",
                    "My position (MGRS, lat/lon) and callsign.",
                    params()));

            tools.put(tool("get_map_view",
                    "What the map screen shows: area covered, scale, markers on screen, terrain.",
                    params()));

            tools.put(tool("find_place",
                    "Find a named place, street or address, nearest first. Use for any name on the map that isn't an ATAK marker.",
                    params("query").put("query", str("Place name or address"))));

            JSONObject cat = str("What to look for");
            JSONArray cats = new JSONArray();
            for (String c : OsmClient.CATEGORIES.keySet()) cats.put(c);
            cat.put("enum", cats);
            tools.put(tool("find_nearest",
                    "Nearest thing of a kind (water, park, hospital, metro_station, ...), with distance and direction.",
                    params("category").put("category", cat)));

            tools.put(tool("get_nearby_places",
                    "Named places around me.",
                    params().put("radius_m", num("meters (default 600)"))));

            tools.put(tool("get_nearby_roads",
                    "The street I'm on and nearby streets.",
                    params().put("radius_m", num("meters (default 300)"))));

            JSONObject mode = str("Travel mode (default foot)");
            mode.put("enum", new JSONArray().put("foot").put("car").put("bike"));
            tools.put(tool("get_directions",
                    "Turn-by-turn route to a place name or lat/lon. Draws it on the map.",
                    params()
                            .put("destination", str("Place name or address to go to"))
                            .put("to_lat", num("Destination latitude (if no name)"))
                            .put("to_lon", num("Destination longitude (if no name)"))
                            .put("mode", mode)
                            .put("confirm_far", new JSONObject().put("type", "boolean")
                                    .put("description", "true only after user confirms a >50 km trip"))
                            .put("draw_on_map", new JSONObject().put("type", "boolean")
                                    .put("description", "Draw on map (default true)"))));

            tools.put(tool("get_elevation",
                    "Ground elevation at a lat/lon.",
                    params("lat", "lon")
                            .put("lat", num("Latitude in decimal degrees"))
                            .put("lon", num("Longitude in decimal degrees"))));

            tools.put(tool("get_nearby_markers",
                    "ATAK markers near me, nearest first.",
                    params()
                            .put("radius_m", num("meters (default 5000)"))
                            .put("limit", num("max results (default 25)"))));

            tools.put(tool("find_markers",
                    "Find ATAK markers by name.",
                    params("query").put("query", str("name text"))));

            tools.put(tool("distance_bearing",
                    "Distance and true bearing between two points.",
                    params("from_lat", "from_lon", "to_lat", "to_lon")
                            .put("from_lat", num("Start latitude"))
                            .put("from_lon", num("Start longitude"))
                            .put("to_lat", num("End latitude"))
                            .put("to_lon", num("End longitude"))));

            JSONObject affil = str("Affiliation of the point");
            affil.put("enum", new JSONArray()
                    .put("friendly").put("hostile").put("neutral").put("unknown"));
            tools.put(tool("place_marker",
                    "Drop a marker. Only when asked; coordinates must come from the user or a tool.",
                    params("lat", "lon", "name")
                            .put("lat", num("Latitude in decimal degrees"))
                            .put("lon", num("Longitude in decimal degrees"))
                            .put("name", str("Callsign/label for the marker"))
                            .put("affiliation", affil)
                            .put("remarks", str("Optional remarks text"))));

            tools.put(tool("pan_map",
                    "Center the map on a lat/lon.",
                    params("lat", "lon")
                            .put("lat", num("Latitude in decimal degrees"))
                            .put("lon", num("Longitude in decimal degrees"))));
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        return tools;
    }

    // ---------------------------------------------------------------- dispatch

    /** Runs a tool and returns a JSON string for the "tool" message. Never throws. */
    public String execute(String name, JSONObject args) {
        try {
            switch (name) {
                case "get_self_location":
                    return selfLocation().toString();
                case "find_place":
                    return findPlace(args.optString("query", "")).toString();
                case "find_nearest":
                    return findNearest(args.optString("category", "")).toString();
                case "get_nearby_places":
                    return nearbyPlaces(args.optInt("radius_m", 600)).toString();
                case "get_nearby_roads":
                    return nearbyRoads(args.optInt("radius_m", 300)).toString();
                case "get_directions":
                    return directions(args).toString();
                case "get_map_view":
                    return mapView().toString();
                case "get_elevation":
                    return elevationAt(args.getDouble("lat"), args.getDouble("lon")).toString();
                case "get_nearby_markers":
                    return nearby(args.optDouble("radius_m", DEFAULT_RADIUS_M),
                            args.optInt("limit", MAX_RESULTS)).toString();
                case "find_markers":
                    return findMarkersOrPlaces(args.optString("query", "")).toString();
                case "distance_bearing":
                    return distanceBearing(args).toString();
                case "place_marker":
                    return placeMarker(args).toString();
                case "pan_map":
                    return panMap(args).toString();
                default:
                    return error("Unknown tool: " + name);
            }
        } catch (Exception e) {
            return error(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** Short plain-text situation block that goes into the system prompt each turn. */
    public String situationSummary() {
        try {
            return onUi(new Callable<String>() {
                @Override
                public String call() {
                    StringBuilder sb = new StringBuilder();
                    sb.append("Device callsign: ").append(mapView.getDeviceCallsign()).append('\n');
                    GeoPoint self = selfPoint();
                    if (self != null) {
                        sb.append("Self position: ").append(fmt(self)).append('\n');
                    } else {
                        sb.append("Self position: no GPS fix\n");
                    }
                    GeoPoint center = mapView.getCenterPoint().get();
                    sb.append("Map center: ").append(fmt(center)).append('\n');
                    GeoPoint ref = self != null ? self : center;
                    int count = 0;
                    for (PointMapItem p : pointItems()) {
                        if (distanceM(ref, p.getPoint()) <= DEFAULT_RADIUS_M) count++;
                    }
                    sb.append("Markers within 5 km: ").append(count);
                    return sb.toString();
                }
            });
        } catch (Exception e) {
            return "Situation unavailable (" + e.getMessage() + ")";
        }
    }

    /**
     * Street and neighborhood at the reference point from offline data, or "" when
     * unavailable. Cheap (local SQLite), so it is included with every question and
     * answers "where am I" style questions without a tool call.
     */
    public String offlineContext() {
        try {
            OfflineArea a = offline.area();
            if (a == null) return "";
            ViewSnapshot v = snapshot();
            GeoPoint ref = v.reference();
            if (!a.covers(ref.getLatitude(), ref.getLongitude())) return "";
            StringBuilder sb = new StringBuilder();
            List<OfflineArea.Hit> roads = a.roads(ref.getLatitude(), ref.getLongitude(), 150, 3);
            if (!roads.isEmpty()) {
                OfflineArea.Hit r0 = roads.get(0);
                sb.append("Nearest street: ").append(r0.name).append(" (")
                        .append(Math.round(r0.distM)).append(" m)");
                if (roads.size() > 1) sb.append("; also ").append(roads.get(1).name);
                sb.append('\n');
            }
            OfflineArea.Hit hood = a.neighborhood(ref.getLatitude(), ref.getLongitude());
            if (hood != null) sb.append("Neighborhood: ").append(hood.name).append('\n');
            return sb.toString().trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static JSONObject hitJson(OfflineArea.Hit h, GeoPoint ref) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("name", h.name);
        o.put("kind", h.isRoad() ? "street" : h.kind);
        addPlaceGeometry(o, ref, new GeoPoint(h.lat, h.lon));
        return o;
    }

    /** The offline area if it covers this point, else null. */
    private OfflineArea areaFor(GeoPoint p) {
        OfflineArea a = offline.area();
        return a != null && a.covers(p.getLatitude(), p.getLongitude()) ? a : null;
    }

    // ------------------------------------------------------------------- tools

    private JSONObject selfLocation() throws Exception {
        return onUi(new Callable<JSONObject>() {
            @Override
            public JSONObject call() throws JSONException {
                JSONObject o = new JSONObject();
                o.put("callsign", mapView.getDeviceCallsign());
                GeoPoint self = selfPoint();
                if (self == null) {
                    o.put("fix", false);
                    o.put("note", "No GPS fix. Map center given instead.");
                    putPoint(o, mapView.getCenterPoint().get());
                } else {
                    o.put("fix", true);
                    putPoint(o, self);
                }
                return o;
            }
        });
    }

    private JSONObject mapView() throws Exception {
        return onUi(new Callable<JSONObject>() {
            @Override
            public JSONObject call() throws JSONException {
                JSONObject o = new JSONObject();
                GeoPoint center = mapView.getCenterPoint().get();
                o.put("center", pointJson(center));

                GeoBounds b = mapView.getBounds();
                if (b != null) {
                    JSONObject bj = new JSONObject();
                    bj.put("north", round(b.getNorth(), 6));
                    bj.put("south", round(b.getSouth(), 6));
                    bj.put("east", round(b.getEast(), 6));
                    bj.put("west", round(b.getWest(), 6));
                    o.put("bounds", bj);
                    double midLat = (b.getNorth() + b.getSouth()) / 2;
                    double midLon = (b.getEast() + b.getWest()) / 2;
                    double widthM = distanceM(new GeoPoint(midLat, b.getWest()), new GeoPoint(midLat, b.getEast()));
                    double heightM = distanceM(new GeoPoint(b.getSouth(), midLon), new GeoPoint(b.getNorth(), midLon));
                    o.put("view_width_m", Math.round(widthM));
                    o.put("view_height_m", Math.round(heightM));

                    // Terrain over a 5x5 grid across the view.
                    double min = Double.NaN, max = Double.NaN;
                    GeoPoint lowest = null, highest = null;
                    for (int i = 0; i < 5; i++) {
                        for (int j = 0; j < 5; j++) {
                            double lat = b.getSouth() + (b.getNorth() - b.getSouth()) * (i + 0.5) / 5;
                            double lon = b.getWest() + (b.getEast() - b.getWest()) * (j + 0.5) / 5;
                            double e = elev(lat, lon);
                            if (Double.isNaN(e)) continue;
                            if (Double.isNaN(min) || e < min) { min = e; lowest = new GeoPoint(lat, lon); }
                            if (Double.isNaN(max) || e > max) { max = e; highest = new GeoPoint(lat, lon); }
                        }
                    }
                    JSONObject t = new JSONObject();
                    double ce = elev(center.getLatitude(), center.getLongitude());
                    if (Double.isNaN(min) && Double.isNaN(ce)) {
                        t.put("available", false);
                        t.put("note", "No elevation data loaded for this area (add DTED/SRTM to ATAK).");
                    } else {
                        t.put("available", true);
                        if (!Double.isNaN(ce)) t.put("center_m_msl", Math.round(ce));
                        if (lowest != null) {
                            t.put("lowest_m_msl", Math.round(min));
                            t.put("lowest_at", pointJson(lowest));
                            t.put("highest_m_msl", Math.round(max));
                            t.put("highest_at", pointJson(highest));
                            t.put("relief_m", Math.round(max - min));
                        }
                    }
                    o.put("terrain", t);
                }

                o.put("meters_per_pixel", round(mapView.getMapResolution(), 2));
                o.put("rotation_deg", Math.round(mapView.getMapRotation()));
                o.put("tilt_deg", Math.round(mapView.getMapTilt()));

                List<JSONObject> rows = new ArrayList<>();
                if (b != null) {
                    for (PointMapItem p : pointItems()) {
                        if (b.contains(p.getPoint())) {
                            rows.add(describe(p, center, distanceM(center, p.getPoint())));
                        }
                    }
                }
                sortByDistance(rows);
                o.put("markers_on_screen", rows.size());
                o.put("markers", toArray(rows, 15));
                o.put("distance_bearing_reference", "map center");
                o.put("note", "Basemap imagery isn't readable. For named streets, parks, "
                        + "landmarks etc. use get_nearby_places or find_place.");
                return o;
            }
        });
    }

    /**
     * Marker search that falls back to an OpenStreetMap place search when no marker
     * matches. Small models often ask for "markers" when the user means a named place.
     */
    private JSONObject findMarkersOrPlaces(String query) throws Exception {
        JSONObject markers = find(query);
        if (markers.optInt("total_found", 0) > 0 || markers.has("error")) return markers;
        try {
            JSONObject places = findPlace(query);
            markers.put("note", "No ATAK markers matched, so this was also looked up as a "
                    + "real-world place. Answer using place_results.");
            markers.put("place_results", places.optJSONArray("results"));
        } catch (Exception e) {
            markers.put("note", "No ATAK markers matched, and the place lookup failed: "
                    + e.getMessage());
        }
        return markers;
    }

    /** Map center, view bounds, and self position, captured on the UI thread. */
    private static final class ViewSnapshot {
        GeoPoint center;
        GeoPoint self;
        GeoBounds bounds;

        GeoPoint reference() {
            return self != null ? self : center;
        }
    }

    private ViewSnapshot snapshot() throws Exception {
        return onUi(new Callable<ViewSnapshot>() {
            @Override
            public ViewSnapshot call() {
                ViewSnapshot v = new ViewSnapshot();
                v.center = mapView.getCenterPoint().get();
                v.self = selfPoint();
                v.bounds = mapView.getBounds();
                return v;
            }
        });
    }

    private static final double FAR_M = 50_000;
    private static final java.util.Set<String> GENERIC_WORDS = new java.util.HashSet<>(
            java.util.Arrays.asList("the", "and", "of", "at", "in", "on", "st", "street", "ave",
                    "avenue", "rd", "road", "nw", "ne", "sw", "se", "square", "circle", "park",
                    "place", "plaza", "station", "metro", "center", "centre", "building", "dc"));

    /**
     * Local-first place lookup:
     *  1. exact search restricted to ~25 km around you,
     *  2. similar names within 8 km (e.g. "Logan Square" finds "Logan Circle"),
     *  3. only then a worldwide search, with far results flagged.
     */
    private JSONObject findPlace(String query) throws Exception {
        query = query.trim();
        if (query.isEmpty()) return new JSONObject(error("query is empty"));
        ViewSnapshot v = snapshot();
        GeoPoint ref = v.reference();

        OfflineArea area = areaFor(ref);
        if (area != null) {
            String[] match = new String[1];
            List<OfflineArea.Hit> hits = area.search(query, ref.getLatitude(), ref.getLongitude(), 5, match);
            if (!hits.isEmpty()) {
                JSONObject out = new JSONObject();
                out.put("source", "offline map data");
                out.put("reference", v.self != null ? "self" : "map_center");
                out.put("match", "exact".equals(match[0]) ? "exact, nearby" : "similar name, nearby");
                if (!"exact".equals(match[0])) {
                    out.put("note", "No exact match for '" + query + "'. These nearby places have "
                            + "similar names; the first is most likely what the user meant. Mention "
                            + "the real name when answering.");
                }
                JSONArray arr = new JSONArray();
                for (OfflineArea.Hit h : hits) arr.put(hitJson(h, ref));
                out.put("results", arr);
                return out;
            }
            // Not in the offline area: try online, but don't fail if there's no signal.
            try {
                return findPlaceOnline(query, v, ref);
            } catch (java.io.IOException e) {
                JSONObject out = new JSONObject();
                out.put("source", "offline map data");
                out.put("match", "none");
                out.put("results", new JSONArray());
                out.put("note", "No place called '" + query + "' in the offline map, and the online "
                        + "lookup is unavailable (" + e.getMessage() + ").");
                return out;
            }
        }
        return findPlaceOnline(query, v, ref);
    }

    private JSONObject findPlaceOnline(String query, ViewSnapshot v, GeoPoint ref) throws Exception {

        double dLat = 0.22; // ~25 km
        double dLon = dLat / Math.max(0.2, Math.cos(Math.toRadians(ref.getLatitude())));
        double n = ref.getLatitude() + dLat, s = ref.getLatitude() - dLat;
        double e = ref.getLongitude() + dLon, w = ref.getLongitude() - dLon;

        JSONObject out = new JSONObject();
        out.put("reference", v.self != null ? "self" : "map_center");

        // 1. Exact, local only.
        List<JSONObject> rows = nominatimRows(osm.search(query, n, s, e, w, 5, true), ref);
        if (!rows.isEmpty()) {
            out.put("match", "exact, nearby");
            out.put("source", "OpenStreetMap (Nominatim)");
            out.put("results", toArray(rows, 5));
            return out;
        }

        // 2. Similar names nearby.
        rows = similarNamesNearby(query, ref, 8000);
        if (!rows.isEmpty()) {
            out.put("match", "similar name, nearby");
            out.put("note", "No exact match for '" + query + "' nearby. These nearby places have "
                    + "similar names; the first is most likely what the user meant. Mention the "
                    + "real name when answering.");
            out.put("source", "OpenStreetMap (Overpass)");
            out.put("results", toArray(rows, 5));
            return out;
        }

        // 3. Anywhere.
        rows = nominatimRows(osm.search(query, n, s, e, w, 5, false), ref);
        out.put("match", rows.isEmpty() ? "none" : "exact, possibly far away");
        out.put("source", "OpenStreetMap (Nominatim)");
        out.put("results", toArray(rows, 5));
        if (rows.isEmpty()) {
            out.put("note", "No match found. Try a different spelling or a broader name.");
        } else if (rows.get(0).optLong("distance_m") > FAR_M) {
            out.put("far_away", true);
            out.put("note", "Nothing called '" + query + "' was found near the user; the matches "
                    + "are far away. Tell the user and ask if they meant one of these before "
                    + "giving directions.");
        }
        return out;
    }

    private List<JSONObject> nominatimRows(JSONArray raw, GeoPoint ref) throws JSONException {
        List<JSONObject> rows = new ArrayList<>();
        for (int i = 0; i < raw.length(); i++) {
            JSONObject r = raw.getJSONObject(i);
            GeoPoint gp = new GeoPoint(r.getDouble("lat"), r.getDouble("lon"));
            JSONObject o = new JSONObject();
            o.put("name", r.optString("name", ""));
            o.put("full_name", LlmClient.truncate(r.optString("display_name", ""), 160));
            o.put("type", (r.optString("category", "") + " " + r.optString("type", "")).trim().replace('_', ' '));
            addPlaceGeometry(o, ref, gp);
            rows.add(o);
        }
        sortByDistance(rows);
        return rows;
    }

    /** Nearby features sharing the query's distinctive words, best word-overlap first, then nearest. */
    private List<JSONObject> similarNamesNearby(String query, GeoPoint ref, int radiusM) throws Exception {
        List<String> words = new ArrayList<>();
        for (String wd : query.toLowerCase(Locale.US).split("[^\\p{L}\\p{N}]+")) {
            if (wd.length() >= 3 && !GENERIC_WORDS.contains(wd)) words.add(wd);
        }
        if (words.isEmpty()) return new ArrayList<>();
        StringBuilder rx = new StringBuilder();
        for (String wd : words) {
            if (rx.length() > 0) rx.append('|');
            rx.append(wd.replaceAll("[^\\p{L}\\p{N}]", ""));
        }
        JSONArray els = osm.nameSearch(rx.toString(), ref.getLatitude(), ref.getLongitude(), radiusM);

        final java.util.Map<JSONObject, Integer> score = new java.util.HashMap<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        List<JSONObject> rows = new ArrayList<>();
        String qLower = query.toLowerCase(Locale.US);
        for (int i = 0; i < els.length(); i++) {
            JSONObject el = els.getJSONObject(i);
            JSONObject tags = el.optJSONObject("tags");
            if (tags == null) continue;
            String name = tags.optString("name", "");
            if (name.isEmpty() || !seen.add(name.toLowerCase(Locale.US))) continue;
            double lat, lon;
            if (el.has("lat")) {
                lat = el.getDouble("lat");
                lon = el.getDouble("lon");
            } else if (el.has("center")) {
                lat = el.getJSONObject("center").getDouble("lat");
                lon = el.getJSONObject("center").getDouble("lon");
            } else {
                continue;
            }
            String nLower = name.toLowerCase(Locale.US);
            int sc = 0;
            for (String wd : words) if (nLower.contains(wd)) sc += 10;
            // Bonus for sharing generic words too ("Logan Circle" vs query "Logan Square" -> 0;
            // "Logan Circle Park" vs "Logan Circle" -> bonus), and for not being a street.
            for (String wd : qLower.split("\\s+")) if (wd.length() >= 3 && nLower.contains(wd)) sc += 2;
            if (!tags.has("highway")) sc += 3;
            JSONObject o = new JSONObject();
            o.put("name", name);
            o.put("type", OsmClient.kindOf(tags).equals("feature") && tags.has("highway")
                    ? "street" : OsmClient.kindOf(tags));
            addPlaceGeometry(o, ref, new GeoPoint(lat, lon));
            score.put(o, sc);
            rows.add(o);
        }
        java.util.Collections.sort(rows, new java.util.Comparator<JSONObject>() {
            @Override
            public int compare(JSONObject a, JSONObject b) {
                int c = Integer.compare(score.get(b), score.get(a));
                return c != 0 ? c : Long.compare(a.optLong("distance_m"), b.optLong("distance_m"));
            }
        });
        return rows;
    }

    private static void addPlaceGeometry(JSONObject o, GeoPoint ref, GeoPoint gp) throws JSONException {
        putPoint(o, gp);
        double d = distanceM(ref, gp);
        o.put("distance_m", Math.round(d));
        double b = bearingDeg(ref, gp);
        o.put("bearing_deg_true", Math.round(b));
        o.put("direction", cardinal(b));
    }

    private static final int[] NEAREST_RADII = {1000, 3000, 8000, 20000};

    private JSONObject findNearest(String category) throws Exception {
        if (!OsmClient.CATEGORIES.containsKey(category)) {
            return new JSONObject(error("Unknown category '" + category + "'. Use one of: "
                    + OsmClient.CATEGORIES.keySet()));
        }
        ViewSnapshot v = snapshot();
        GeoPoint ref = v.reference();

        OfflineArea area = areaFor(ref);
        if (area != null) {
            int[] searchedOut = new int[1];
            List<OfflineArea.Hit> hits = area.nearest(category, ref.getLatitude(), ref.getLongitude(),
                    NEAREST_RADII, 3, searchedOut);
            JSONObject out = new JSONObject();
            out.put("category", category);
            out.put("source", "offline map data");
            out.put("reference", v.self != null ? "self" : "map_center");
            out.put("searched_radius_m", searchedOut[0]);
            if (hits.isEmpty()) {
                out.put("note", "Nothing of this kind within " + (searchedOut[0] / 1000) + " km.");
            } else {
                JSONArray arr = new JSONArray();
                for (OfflineArea.Hit h : hits) arr.put(hitJson(h, ref));
                out.put("nearest", arr);
                out.put("note", "Distances are to the closest edge/point of each feature.");
            }
            if ("water".equals(category)) {
                List<OfflineArea.Hit> major = area.nearestMajorWater(ref.getLatitude(), ref.getLongitude(), 2);
                if (!major.isEmpty()) {
                    JSONArray marr = new JSONArray();
                    for (OfflineArea.Hit h : major) marr.put(hitJson(h, ref));
                    out.put("nearest_major", marr);
                    out.put("note", "'nearest' includes small water (fountains, ponds); "
                            + "'nearest_major' is the closest named river/lake/reservoir. For 'body "
                            + "of water' questions lead with nearest_major and mention small ones briefly.");
                }
            }
            return out;
        }

        List<JSONObject> rows = new ArrayList<>();
        int searched = 0;
        for (int radius : NEAREST_RADII) {
            searched = radius;
            JSONArray els = osm.category(category, ref.getLatitude(), ref.getLongitude(), radius);
            rows.clear();
            java.util.Map<String, JSONObject> byName = new java.util.HashMap<>();
            for (int i = 0; i < els.length(); i++) {
                JSONObject el = els.getJSONObject(i);
                GeoPoint closest = closestPointOfElement(ref, el);
                if (closest == null) continue;
                JSONObject tags = el.optJSONObject("tags");
                String name = tags == null ? "" : tags.optString("name", "");
                String kind = OsmClient.kindOf(tags);
                String label = name.isEmpty() ? "unnamed " + kind : name;
                double d = distanceM(ref, closest);
                JSONObject prev = byName.get(label);
                if (prev != null && prev.optLong("distance_m") <= Math.round(d)) continue;
                JSONObject o = new JSONObject();
                o.put("name", label);
                o.put("kind", kind);
                addPlaceGeometry(o, ref, closest);
                byName.put(label, o);
            }
            rows.addAll(byName.values());
            if (!rows.isEmpty()) break;
        }
        sortByDistance(rows);
        // Prefer named features if a named one is nearly as close as an unnamed one.
        JSONObject out = new JSONObject();
        out.put("category", category);
        out.put("source", "OpenStreetMap (Overpass)");
        out.put("reference", v.self != null ? "self" : "map_center");
        out.put("searched_radius_m", searched);
        if (rows.isEmpty()) {
            out.put("note", "Nothing of this kind found within " + (searched / 1000) + " km.");
        } else {
            out.put("nearest", toArray(rows, 3));
            out.put("note", "Distances are to the closest edge/point of each feature.");
        }
        return out;
    }

    /** Closest point of an Overpass element (node, way geometry, or relation member geometries). */
    private static GeoPoint closestPointOfElement(GeoPoint ref, JSONObject el) throws JSONException {
        if (el.has("lat") && el.has("lon")) return new GeoPoint(el.getDouble("lat"), el.getDouble("lon"));
        GeoPoint best = null;
        double bestD = Double.MAX_VALUE;
        JSONArray geom = el.optJSONArray("geometry");
        if (geom != null && geom.length() > 0) {
            best = closestPointOnLine(ref, cleanGeom(geom));
            bestD = distanceM(ref, best);
        }
        JSONArray members = el.optJSONArray("members");
        if (members != null) {
            for (int i = 0; i < members.length(); i++) {
                JSONObject m = members.getJSONObject(i);
                JSONArray g = m.optJSONArray("geometry");
                if (g != null && g.length() > 0) {
                    JSONArray cg = cleanGeom(g);
                    if (cg.length() == 0) continue;
                    GeoPoint p = closestPointOnLine(ref, cg);
                    double d = distanceM(ref, p);
                    if (d < bestD) { bestD = d; best = p; }
                } else if (m.has("lat") && m.has("lon")) {
                    GeoPoint p = new GeoPoint(m.getDouble("lat"), m.getDouble("lon"));
                    double d = distanceM(ref, p);
                    if (d < bestD) { bestD = d; best = p; }
                }
            }
        }
        return best;
    }

    /** Drops null vertices (Overpass emits null for points clipped away by the bbox). */
    private static JSONArray cleanGeom(JSONArray g) throws JSONException {
        JSONArray out = new JSONArray();
        for (int i = 0; i < g.length(); i++) {
            JSONObject p = g.optJSONObject(i);
            if (p != null && p.has("lat") && p.has("lon")) out.put(p);
        }
        return out;
    }

    /** Keeps only the fields a small model needs, to save prompt tokens. */
    private static List<JSONObject> compact(List<JSONObject> rows) throws JSONException {
        List<JSONObject> out = new ArrayList<>();
        for (JSONObject r : rows) {
            JSONObject o = new JSONObject();
            o.put("name", r.optString("name"));
            if (r.has("kind")) o.put("kind", r.optString("kind"));
            o.put("distance_m", r.optLong("distance_m"));
            o.put("direction", r.optString("direction"));
            o.put("mgrs", r.optString("mgrs"));
            out.add(o);
        }
        return out;
    }

    private JSONObject nearbyPlaces(int radiusM) throws Exception {
        radiusM = Math.max(100, Math.min(radiusM, 1500));
        ViewSnapshot v = snapshot();
        GeoPoint ref = v.reference();
        OfflineArea area = areaFor(ref);
        if (area != null) {
            List<OfflineArea.Hit> hits = area.nearby(ref.getLatitude(), ref.getLongitude(), radiusM, 12);
            JSONObject out = new JSONObject();
            out.put("source", "offline map data");
            out.put("reference", v.self != null ? "self" : "map_center");
            out.put("radius_m", radiusM);
            JSONArray arr = new JSONArray();
            for (OfflineArea.Hit h : hits) {
                JSONObject o = new JSONObject();
                o.put("name", h.name);
                o.put("kind", h.kind);
                o.put("distance_m", Math.round(h.distM));
                o.put("direction", cardinal(bearingDeg(ref, new GeoPoint(h.lat, h.lon))));
                o.put("mgrs", mgrs(new GeoPoint(h.lat, h.lon)));
                arr.put(o);
            }
            out.put("places", arr);
            return out;
        }
        JSONArray els = osm.nearby(ref.getLatitude(), ref.getLongitude(), radiusM);

        List<JSONObject> rows = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < els.length(); i++) {
            JSONObject el = els.getJSONObject(i);
            JSONObject tags = el.optJSONObject("tags");
            if (tags == null) continue;
            String name = tags.optString("name", "");
            if (name.isEmpty()) continue;
            double lat, lon;
            if (el.has("lat")) {
                lat = el.getDouble("lat");
                lon = el.getDouble("lon");
            } else if (el.has("center")) {
                lat = el.getJSONObject("center").getDouble("lat");
                lon = el.getJSONObject("center").getDouble("lon");
            } else {
                continue;
            }
            String kind = OsmClient.kindOf(tags);
            if (!seen.add(name + "|" + kind)) continue; // same feature as node + area
            GeoPoint gp = new GeoPoint(lat, lon);
            JSONObject o = new JSONObject();
            o.put("name", name);
            o.put("kind", kind);
            putPoint(o, gp);
            double d = distanceM(ref, gp);
            o.put("distance_m", Math.round(d));
            double b = bearingDeg(ref, gp);
            o.put("bearing_deg_true", Math.round(b));
            o.put("direction", cardinal(b));
            rows.add(o);
        }
        sortByDistance(rows);
        JSONObject out = new JSONObject();
        out.put("source", "OpenStreetMap (Overpass)");
        out.put("reference", v.self != null ? "self" : "map_center");
        out.put("radius_m", radiusM);
        out.put("total_found", rows.size());
        out.put("places", toArray(compact(rows), 12));
        return out;
    }

    private JSONObject nearbyRoads(int radiusM) throws Exception {
        radiusM = Math.max(50, Math.min(radiusM, 1500));
        ViewSnapshot v = snapshot();
        GeoPoint ref = v.reference();
        OfflineArea area = areaFor(ref);
        if (area != null) {
            List<OfflineArea.Hit> hits = area.roads(ref.getLatitude(), ref.getLongitude(), radiusM, 15);
            JSONObject out = new JSONObject();
            out.put("source", "offline map data");
            out.put("reference", v.self != null ? "self" : "map_center");
            if (!hits.isEmpty() && hits.get(0).distM <= 30) out.put("on_street", hits.get(0).name);
            JSONArray arr = new JSONArray();
            for (OfflineArea.Hit h : hits) {
                JSONObject o = new JSONObject();
                o.put("name", h.name);
                o.put("type", h.kind);
                o.put("distance_m", Math.round(h.distM));
                o.put("direction", cardinal(bearingDeg(ref, new GeoPoint(h.lat, h.lon))));
                arr.put(o);
            }
            out.put("roads", arr);
            return out;
        }
        JSONArray ways = osm.roads(ref.getLatitude(), ref.getLongitude(), radiusM);

        // One row per street name, keeping its closest point.
        java.util.Map<String, JSONObject> best = new java.util.HashMap<>();
        for (int i = 0; i < ways.length(); i++) {
            JSONObject w = ways.getJSONObject(i);
            JSONObject tags = w.optJSONObject("tags");
            JSONArray geom = w.optJSONArray("geometry");
            if (tags == null || geom == null || geom.length() == 0) continue;
            String name = tags.optString("name", "");
            if (name.isEmpty()) continue;
            GeoPoint closest = closestPointOnLine(ref, geom);
            double d = distanceM(ref, closest);
            JSONObject cur = best.get(name);
            if (cur != null && cur.optLong("distance_m") <= Math.round(d)) continue;
            JSONObject o = new JSONObject();
            o.put("name", name);
            o.put("type", tags.optString("highway").replace('_', ' '));
            o.put("distance_m", Math.round(d));
            double b = bearingDeg(ref, closest);
            o.put("bearing_deg_true", Math.round(b));
            o.put("direction", cardinal(b));
            o.put("closest_point", pointJson(closest));
            best.put(name, o);
        }
        List<JSONObject> rows = new ArrayList<>(best.values());
        sortByDistance(rows);
        JSONObject out = new JSONObject();
        out.put("source", "OpenStreetMap (Overpass)");
        out.put("reference", v.self != null ? "self" : "map_center");
        if (!rows.isEmpty() && rows.get(0).optLong("distance_m") <= 30) {
            out.put("on_street", rows.get(0).optString("name"));
        }
        out.put("roads", toArray(rows, 20));
        return out;
    }

    /** Closest point to p on a polyline given as [{lat,lon},...] (flat-earth approximation, fine at street scale). */
    private static GeoPoint closestPointOnLine(GeoPoint p, JSONArray geom) throws JSONException {
        double kx = Math.cos(Math.toRadians(p.getLatitude()));
        double px = p.getLongitude() * kx, py = p.getLatitude();
        double bestD = Double.MAX_VALUE, bx = px, by = py;
        double ax = geom.getJSONObject(0).getDouble("lon") * kx;
        double ay = geom.getJSONObject(0).getDouble("lat");
        if (geom.length() == 1) return new GeoPoint(ay, ax / kx);
        for (int i = 1; i < geom.length(); i++) {
            double cx = geom.getJSONObject(i).getDouble("lon") * kx;
            double cy = geom.getJSONObject(i).getDouble("lat");
            double dx = cx - ax, dy = cy - ay;
            double len2 = dx * dx + dy * dy;
            double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / len2));
            double qx = ax + t * dx, qy = ay + t * dy;
            double d = (qx - px) * (qx - px) + (qy - py) * (qy - py);
            if (d < bestD) { bestD = d; bx = qx; by = qy; }
            ax = cx; ay = cy;
        }
        return new GeoPoint(by, bx / kx);
    }

    private JSONObject directions(JSONObject args) throws Exception {
        ViewSnapshot v = snapshot();
        GeoPoint from = v.reference();
        String mode = args.optString("mode", "foot");
        if (!"car".equals(mode) && !"bike".equals(mode)) mode = "foot";

        GeoPoint to;
        String destName;
        String dest = args.optString("destination", "").trim();
        if (args.has("to_lat") && args.has("to_lon") && !args.isNull("to_lat")) {
            to = new GeoPoint(args.getDouble("to_lat"), args.getDouble("to_lon"));
            checkPoint(to);
            destName = dest.isEmpty() ? fmt(to) : dest;
        } else if (!dest.isEmpty()) {
            JSONObject foundFirst = findPlace(dest);
            JSONArray res = foundFirst.optJSONArray("results");
            if (res == null || res.length() == 0) {
                return new JSONObject(error("Couldn't find a place called '" + dest + "'."));
            }
            JSONObject found = foundFirst;
            JSONObject r = res.getJSONObject(0); // best match (local first)
            to = new GeoPoint(r.getDouble("lat"), r.getDouble("lon"));
            destName = r.optString("name", dest);
            if (destName.isEmpty()) destName = dest;
            if (distanceM(from, to) > FAR_M && !args.optBoolean("confirm_far", false)) {
                JSONObject ask = new JSONObject();
                ask.put("ok", false);
                ask.put("needs_confirmation", true);
                ask.put("note", "No '" + dest + "' was found near the user. The closest match is "
                        + Math.round(distanceM(from, to) / 1000.0) + " km away. Do not give directions; "
                        + "tell the user and ask whether they meant this place or something nearby. "
                        + "Only if they confirm, call get_directions again with confirm_far=true.");
                ask.put("candidates", found.optJSONArray("results"));
                return ask;
            }
        } else {
            return new JSONObject(error("Give a destination name or to_lat/to_lon."));
        }

        OfflineRouter router = offline.router();
        if (router != null && router.covers(from.getLatitude(), from.getLongitude())
                && router.covers(to.getLatitude(), to.getLongitude())) {
            int m = "car".equals(mode) ? OfflineRouter.CAR : "bike".equals(mode) ? OfflineRouter.BIKE
                    : OfflineRouter.FOOT;
            OfflineRouter.Route rt = router.route(from.getLatitude(), from.getLongitude(),
                    to.getLatitude(), to.getLongitude(), m);
            if (rt != null) {
                JSONObject out = new JSONObject();
                out.put("source", "offline routing");
                out.put("from", v.self != null ? "self" : "map_center");
                out.put("to", destName);
                putPoint(out, to);
                out.put("mode", mode);
                out.put("total_distance_m", Math.round(rt.distanceM));
                out.put("total_minutes", Math.max(1, Math.round(rt.seconds / 60.0)));
                out.put("straight_line_m", Math.round(distanceM(from, to)));
                out.put("straight_line_direction", cardinal(bearingDeg(from, to)));
                if (rt.snapFromM > 150 || rt.snapToM > 150) {
                    out.put("note", "Start or end is " + Math.round(Math.max(rt.snapFromM, rt.snapToM))
                            + " m from the nearest " + mode + "-usable road.");
                }
                JSONArray steps = new JSONArray();
                for (OfflineRouter.Step st : rt.steps) {
                    if (steps.length() >= 25) break;
                    JSONObject o = new JSONObject();
                    o.put("instruction", st.text);
                    o.put("distance_m", Math.round(st.distanceM));
                    steps.put(o);
                }
                out.put("steps", steps);
                if (args.optBoolean("draw_on_map", true)) {
                    JSONArray coords = new JSONArray();
                    for (double[] p : rt.points) coords.put(new JSONArray().put(p[1]).put(p[0]));
                    drawRoute(coords, "Route to " + destName);
                    out.put("drawn_on_map", true);
                }
                return out;
            }
            // no offline route (e.g. disconnected network): fall through to online
        }

        JSONObject resp = osm.route(from.getLatitude(), from.getLongitude(),
                to.getLatitude(), to.getLongitude(), mode);
        JSONObject route = resp.getJSONArray("routes").getJSONObject(0);

        JSONArray steps = new JSONArray();
        JSONArray legs = route.optJSONArray("legs");
        JSONArray rawSteps = legs != null && legs.length() > 0
                ? legs.getJSONObject(0).optJSONArray("steps") : null;
        if (rawSteps != null) {
            for (int i = 0; i < rawSteps.length() && steps.length() < 25; i++) {
                JSONObject st = rawSteps.getJSONObject(i);
                String text = stepText(st);
                if (text.isEmpty()) continue;
                JSONObject o = new JSONObject();
                o.put("instruction", text);
                o.put("distance_m", Math.round(st.optDouble("distance", 0)));
                steps.put(o);
            }
        }

        JSONObject out = new JSONObject();
        out.put("source", "OpenStreetMap routing (routing.openstreetmap.de)");
        out.put("from", v.self != null ? "self" : "map_center");
        out.put("to", destName);
        putPoint(out, to);
        out.put("mode", mode);
        out.put("total_distance_m", Math.round(route.optDouble("distance", 0)));
        out.put("total_minutes", Math.round(route.optDouble("duration", 0) / 60.0));
        double b = bearingDeg(from, to);
        out.put("straight_line_m", Math.round(distanceM(from, to)));
        out.put("straight_line_direction", cardinal(b));
        out.put("steps", steps);

        if (args.optBoolean("draw_on_map", true)) {
            JSONObject geometry = route.optJSONObject("geometry");
            JSONArray coords = geometry == null ? null : geometry.optJSONArray("coordinates");
            if (coords != null && coords.length() > 1) {
                drawRoute(coords, "Route to " + destName);
                out.put("drawn_on_map", true);
            }
        }
        return out;
    }

    /** Builds a short instruction like "Turn left onto R Street Northwest" from an OSRM step. */
    private static String stepText(JSONObject st) {
        JSONObject m = st.optJSONObject("maneuver");
        String type = m == null ? "" : m.optString("type", "");
        String mod = m == null ? "" : m.optString("modifier", "");
        String name = st.optString("name", "");
        String onto = name.isEmpty() ? "" : " onto " + name;
        switch (type) {
            case "depart":
                return "Head " + headingWord(m) + (name.isEmpty() ? "" : " on " + name);
            case "arrive":
                return "Arrive at destination" + (mod.isEmpty() || "straight".equals(mod) ? "" : " (on the " + mod + ")");
            case "roundabout":
            case "rotary":
                int exit = m.optInt("exit", 0);
                return "At the circle, take " + (exit > 0 ? "exit " + exit : "the exit") + onto;
            case "continue":
            case "new name":
                return "Continue" + (mod.isEmpty() || "straight".equals(mod) ? "" : " " + mod) + onto;
            case "merge":
            case "on ramp":
            case "off ramp":
            case "fork":
            case "end of road":
            case "turn":
            default:
                if (mod.isEmpty()) return (type.isEmpty() ? "Go" : capitalize(type)) + onto;
                if ("straight".equals(mod)) return "Go straight" + onto;
                if (mod.startsWith("uturn")) return "Make a U-turn" + onto;
                return "Turn " + mod + onto;
        }
    }

    private static String headingWord(JSONObject m) {
        if (m == null || !m.has("bearing_after")) return "out";
        return cardinal(m.optDouble("bearing_after", 0));
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Draws (or replaces) the route line. GeoJSON coordinates are [lon, lat]. */
    private void drawRoute(final JSONArray coords, final String title) throws Exception {
        final GeoPoint[] pts = new GeoPoint[coords.length()];
        for (int i = 0; i < coords.length(); i++) {
            JSONArray c = coords.getJSONArray(i);
            pts[i] = new GeoPoint(c.getDouble(1), c.getDouble(0));
        }
        onUi(new Callable<Void>() {
            @Override
            public Void call() {
                if (routeLine != null) routeLine.removeFromGroup();
                Polyline line = new Polyline("llmassist-route");
                line.setPoints(pts);
                line.setStrokeColor(0xFF00B4FF);
                line.setStrokeWeight(5.0);
                line.setTitle(title);
                line.setMetaBoolean("llmassist.created", true);
                MapGroup group = mapView.getRootGroup().findMapGroup("Drawing Objects");
                if (group == null) group = mapView.getRootGroup();
                group.addItem(line);
                routeLine = line;
                return null;
            }
        });
    }

    private JSONObject elevationAt(double lat, double lon) throws JSONException {
        GeoPoint gp = new GeoPoint(lat, lon);
        checkPoint(gp);
        double e = elev(lat, lon);
        JSONObject o = pointJson(gp);
        if (Double.isNaN(e)) {
            o.put("available", false);
            o.put("note", "No elevation data loaded for this location (add DTED/SRTM to ATAK).");
        } else {
            o.put("available", true);
            o.put("elevation_m_msl", Math.round(e));
            o.put("elevation_ft_msl", Math.round(e * 3.28084));
        }
        return o;
    }

    /** Terrain elevation in meters above mean sea level, or NaN when no data covers the point. */
    private static double elev(double lat, double lon) {
        try {
            double hae = ElevationManager.getElevation(lat, lon, null);
            if (Double.isNaN(hae)) return Double.NaN;
            return EGM96.getMSL(lat, lon, hae); // ATAK returns height above ellipsoid
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    private static JSONObject pointJson(GeoPoint p) throws JSONException {
        JSONObject o = new JSONObject();
        putPoint(o, p);
        return o;
    }

    private JSONObject nearby(final double radiusM, final int limit) throws Exception {
        return onUi(new Callable<JSONObject>() {
            @Override
            public JSONObject call() throws JSONException {
                GeoPoint self = selfPoint();
                GeoPoint ref = self != null ? self : mapView.getCenterPoint().get();
                List<JSONObject> rows = new ArrayList<>();
                for (PointMapItem p : pointItems()) {
                    double d = distanceM(ref, p.getPoint());
                    if (d <= radiusM) rows.add(describe(p, ref, d));
                }
                sortByDistance(rows);
                JSONObject o = new JSONObject();
                o.put("reference", self != null ? "self" : "map_center");
                o.put("radius_m", Math.round(radiusM));
                o.put("total_found", rows.size());
                o.put("markers", toArray(rows, Math.max(1, Math.min(limit, MAX_RESULTS))));
                return o;
            }
        });
    }

    private JSONObject find(final String query) throws Exception {
        final String q = query.trim().toLowerCase(Locale.US);
        if (q.isEmpty()) return new JSONObject(error("query is empty"));
        return onUi(new Callable<JSONObject>() {
            @Override
            public JSONObject call() throws JSONException {
                GeoPoint self = selfPoint();
                GeoPoint ref = self != null ? self : mapView.getCenterPoint().get();
                List<JSONObject> rows = new ArrayList<>();
                for (PointMapItem p : pointItems()) {
                    if (nameOf(p).toLowerCase(Locale.US).contains(q)) {
                        rows.add(describe(p, ref, distanceM(ref, p.getPoint())));
                    }
                }
                sortByDistance(rows);
                JSONObject o = new JSONObject();
                o.put("reference", self != null ? "self" : "map_center");
                o.put("total_found", rows.size());
                o.put("markers", toArray(rows, MAX_RESULTS));
                return o;
            }
        });
    }

    private JSONObject distanceBearing(JSONObject a) throws JSONException {
        GeoPoint from = new GeoPoint(a.getDouble("from_lat"), a.getDouble("from_lon"));
        GeoPoint to = new GeoPoint(a.getDouble("to_lat"), a.getDouble("to_lon"));
        checkPoint(from);
        checkPoint(to);
        double d = distanceM(from, to);
        double b = bearingDeg(from, to);
        JSONObject o = new JSONObject();
        o.put("distance_m", Math.round(d));
        o.put("distance_km", round(d / 1000.0, 2));
        o.put("bearing_deg_true", Math.round(b));
        o.put("direction", cardinal(b));
        return o;
    }

    private JSONObject placeMarker(JSONObject a) throws Exception {
        final double lat = a.getDouble("lat");
        final double lon = a.getDouble("lon");
        final String name = a.optString("name", "LLM Point").trim();
        final String affil = a.optString("affiliation", "unknown");
        final String remarks = a.optString("remarks", "").trim();
        final GeoPoint gp = new GeoPoint(lat, lon);
        checkPoint(gp);

        if (!approve("Place " + affil + " marker \"" + name + "\" at\n"
                + fmt(gp) + (remarks.isEmpty() ? "" : "\n\nRemarks: " + remarks))) {
            return new JSONObject(error("User declined the map change."));
        }

        return onUi(new Callable<JSONObject>() {
            @Override
            public JSONObject call() throws JSONException {
                PlacePointTool.MarkerCreator mc =
                        new PlacePointTool.MarkerCreator(GeoPointMetaData.wrap(gp));
                mc.setType(cotType(affil));
                mc.setCallsign(name.isEmpty() ? "LLM Point" : name);
                mc.showCotDetails(false);
                Marker m = mc.placePoint();
                JSONObject o = new JSONObject();
                if (m == null) {
                    o.put("ok", false);
                    o.put("error", "ATAK did not create the marker");
                    return o;
                }
                if (!remarks.isEmpty()) m.setMetaString("remarks", remarks);
                m.setMetaString("llmassist.created", "true");
                o.put("ok", true);
                o.put("uid", m.getUID());
                putPoint(o, gp);
                return o;
            }
        });
    }

    private JSONObject panMap(JSONObject a) throws Exception {
        final GeoPoint gp = new GeoPoint(a.getDouble("lat"), a.getDouble("lon"));
        checkPoint(gp);
        return onUi(new Callable<JSONObject>() {
            @Override
            public JSONObject call() throws JSONException {
                CameraController.Programmatic.panTo(mapView.getRenderer3(), gp, true);
                JSONObject o = new JSONObject();
                o.put("ok", true);
                putPoint(o, gp);
                return o;
            }
        });
    }

    // ----------------------------------------------------------------- helpers

    /** Must be called on the UI thread. */
    private GeoPoint selfPoint() {
        Marker self = mapView.getSelfMarker();
        if (self == null) return null;
        GeoPoint p = self.getPoint();
        if (p == null || !p.isValid()) return null;
        if (p.getLatitude() == 0 && p.getLongitude() == 0) return null;
        return p;
    }

    /** Must be called on the UI thread. Returns user-meaningful point items only. */
    private List<PointMapItem> pointItems() {
        final List<PointMapItem> out = new ArrayList<>();
        final Marker self = mapView.getSelfMarker();
        mapView.getRootGroup().deepForEachItem(new MapGroup.MapItemsCallback() {
            @Override
            public boolean onItemFunction(MapItem item) {
                if (item instanceof PointMapItem && item != self && item.getVisible()) {
                    String t = item.getType();
                    // a-* = CoT units/entities, b-m-p-* = user map points (spot, waypoint, etc.)
                    if (t != null && (t.startsWith("a-") || t.startsWith("b-m-p"))) {
                        out.add((PointMapItem) item);
                    }
                }
                return false; // keep iterating
            }
        });
        return out;
    }

    private JSONObject describe(PointMapItem p, GeoPoint ref, double d) throws JSONException {
        GeoPoint gp = p.getPoint();
        JSONObject o = new JSONObject();
        o.put("name", nameOf(p));
        o.put("type", p.getType());
        o.put("affiliation", affiliationOf(p.getType()));
        putPoint(o, gp);
        o.put("distance_m", Math.round(d));
        double b = bearingDeg(ref, gp);
        o.put("bearing_deg_true", Math.round(b));
        o.put("direction", cardinal(b));
        String remarks = p.getMetaString("remarks", "");
        if (remarks != null && !remarks.isEmpty()) {
            o.put("remarks", LlmClient.truncate(remarks, 200));
        }
        return o;
    }

    private static String nameOf(MapItem item) {
        String cs = item.getMetaString("callsign", null);
        if (cs != null && !cs.isEmpty()) return cs;
        String title = item.getTitle();
        if (title != null && !title.isEmpty()) return title;
        return item.getUID();
    }

    private static String affiliationOf(String type) {
        if (type == null || !type.startsWith("a-") || type.length() < 3) return "n/a";
        switch (type.charAt(2)) {
            case 'f': return "friendly";
            case 'a': return "assumed friendly";
            case 'h': return "hostile";
            case 's': return "suspect";
            case 'n': return "neutral";
            case 'u': return "unknown";
            case 'p': return "pending";
            default:  return "other";
        }
    }

    private static String cotType(String affiliation) {
        switch (affiliation == null ? "" : affiliation) {
            case "friendly": return "a-f-G";
            case "hostile":  return "a-h-G";
            case "neutral":  return "a-n-G";
            default:         return "a-u-G";
        }
    }

    private static void putPoint(JSONObject o, GeoPoint p) throws JSONException {
        // "position" comes first and is already in the display format (MGRS, then lat/lon),
        // so the model copies it as-is. lat/lon stay as numbers for chaining tool calls.
        o.put("position", fmt(p));
        o.put("lat", round(p.getLatitude(), 6));
        o.put("lon", round(p.getLongitude(), 6));
        o.put("mgrs", mgrs(p));
    }

    private static String fmt(GeoPoint p) {
        return String.format(Locale.US, "%s (%.5f, %.5f)",
                mgrs(p), p.getLatitude(), p.getLongitude());
    }

    private static String mgrs(GeoPoint p) {
        try {
            return CoordinateFormatUtilities.formatToString(p, CoordinateFormat.MGRS);
        } catch (Exception e) {
            return "n/a";
        }
    }

    private static void checkPoint(GeoPoint p) {
        if (Math.abs(p.getLatitude()) > 90 || Math.abs(p.getLongitude()) > 180) {
            throw new IllegalArgumentException("Coordinate out of range: "
                    + p.getLatitude() + ", " + p.getLongitude());
        }
    }

    static double distanceM(GeoPoint a, GeoPoint b) {
        double p1 = Math.toRadians(a.getLatitude());
        double p2 = Math.toRadians(b.getLatitude());
        double dp = p2 - p1;
        double dl = Math.toRadians(b.getLongitude() - a.getLongitude());
        double h = Math.sin(dp / 2) * Math.sin(dp / 2)
                + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * EARTH_RADIUS_M * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h));
    }

    static double bearingDeg(GeoPoint a, GeoPoint b) {
        double p1 = Math.toRadians(a.getLatitude());
        double p2 = Math.toRadians(b.getLatitude());
        double dl = Math.toRadians(b.getLongitude() - a.getLongitude());
        double y = Math.sin(dl) * Math.cos(p2);
        double x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl);
        return (Math.toDegrees(Math.atan2(y, x)) + 360.0) % 360.0;
    }

    static String cardinal(double deg) {
        String[] dirs = {"N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
                "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"};
        return dirs[(int) Math.round(deg / 22.5) % 16];
    }

    private static double round(double v, int places) {
        double f = Math.pow(10, places);
        return Math.round(v * f) / f;
    }

    private static void sortByDistance(List<JSONObject> rows) {
        Collections.sort(rows, new Comparator<JSONObject>() {
            @Override
            public int compare(JSONObject x, JSONObject y) {
                return Long.compare(x.optLong("distance_m"), y.optLong("distance_m"));
            }
        });
    }

    private static JSONArray toArray(List<JSONObject> rows, int limit) {
        JSONArray arr = new JSONArray();
        for (int i = 0; i < rows.size() && i < limit; i++) arr.put(rows.get(i));
        return arr;
    }

    private static String error(String msg) {
        try {
            return new JSONObject().put("ok", false).put("error", msg).toString();
        } catch (JSONException e) {
            return "{\"ok\":false}";
        }
    }

    /** Shows an Allow/Deny dialog when confirmation is on; blocks the worker thread until answered. */
    private boolean approve(final String message) throws InterruptedException {
        if (confirmPolicy == null || !confirmPolicy.requireConfirm()) return true;
        final AtomicBoolean allowed = new AtomicBoolean(false);
        final CountDownLatch latch = new CountDownLatch(1);
        ui.post(new Runnable() {
            @Override
            public void run() {
                new AlertDialog.Builder(mapView.getContext())
                        .setTitle("TAK Assist wants to edit the map")
                        .setMessage(message)
                        .setPositiveButton("Allow", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                allowed.set(true);
                                latch.countDown();
                            }
                        })
                        .setNegativeButton("Deny", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                latch.countDown();
                            }
                        })
                        .setOnCancelListener(new DialogInterface.OnCancelListener() {
                            @Override
                            public void onCancel(DialogInterface d) {
                                latch.countDown();
                            }
                        })
                        .show();
            }
        });
        latch.await(2, TimeUnit.MINUTES);
        return allowed.get();
    }

    /** Runs a callable on the UI thread and waits for its result. */
    private <T> T onUi(final Callable<T> c) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) return c.call();
        final AtomicReference<T> out = new AtomicReference<>();
        final AtomicReference<Exception> err = new AtomicReference<>();
        final CountDownLatch latch = new CountDownLatch(1);
        ui.post(new Runnable() {
            @Override
            public void run() {
                try {
                    out.set(c.call());
                } catch (Exception e) {
                    err.set(e);
                } finally {
                    latch.countDown();
                }
            }
        });
        if (!latch.await(30, TimeUnit.SECONDS)) throw new TimeoutException("UI thread busy");
        if (err.get() != null) throw err.get();
        return out.get();
    }

    // ------------------------------------------------------- schema builders

    private static JSONObject tool(String name, String desc, JSONObject props)
            throws JSONException {
        JSONObject parameters = new JSONObject();
        parameters.put("type", "object");
        JSONArray required = (JSONArray) props.remove("__required");
        parameters.put("properties", props);
        if (required != null && required.length() > 0) parameters.put("required", required);
        JSONObject fn = new JSONObject();
        fn.put("name", name);
        fn.put("description", desc);
        fn.put("parameters", parameters);
        return new JSONObject().put("type", "function").put("function", fn);
    }

    /** Property map; required names are stashed under a private key and pulled out in tool(). */
    private static JSONObject params(String... required) throws JSONException {
        JSONObject p = new JSONObject();
        JSONArray req = new JSONArray();
        for (String r : required) req.put(r);
        p.put("__required", req);
        return p;
    }

    private static JSONObject num(String desc) throws JSONException {
        return new JSONObject().put("type", "number").put("description", desc);
    }

    private static JSONObject str(String desc) throws JSONException {
        return new JSONObject().put("type", "string").put("description", desc);
    }
}
