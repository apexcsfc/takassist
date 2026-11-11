package com.apexcsfc.takassist.maps.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** One map region: from the server's catalog, or installed on the phone (region.json). */
public final class Region {

    public static final class FileRef {
        public final String name, url, sha256;
        public final long size;

        FileRef(String name, String url, long size, String sha256) {
            this.name = name;
            this.url = url;
            this.size = size;
            this.sha256 = sha256;
        }
    }

    public final String id, name, built;
    /** south, west, north, east */
    public final double[] bbox;
    public final List<FileRef> files = new ArrayList<>();

    public Region(String id, String name, String built, double[] bbox) {
        this.id = id;
        this.name = name;
        this.built = built == null ? "" : built;
        this.bbox = bbox;
    }

    /** Download size (what goes over the network). */
    public long downloadBytes() {
        long s = 0;
        for (FileRef f : files) s += f.size;
        return s;
    }

    public boolean contains(double lat, double lon) {
        return bbox != null && lat >= bbox[0] && lat <= bbox[2] && lon >= bbox[1] && lon <= bbox[3];
    }

    public String bboxText() {
        return bbox == null ? "" : String.format(Locale.US, "%.4f,%.4f,%.4f,%.4f", bbox[0], bbox[1], bbox[2], bbox[3]);
    }

    public static Region fromJson(JSONObject o) throws JSONException {
        String id = o.getString("id");
        if (!id.matches("[a-z0-9][a-z0-9_~-]{0,127}")) throw new JSONException("Bad region id: " + id);
        JSONArray b = o.optJSONArray("bbox");
        double[] bbox = b == null ? null : new double[]{b.getDouble(0), b.getDouble(1), b.getDouble(2), b.getDouble(3)};
        Region r = new Region(id, o.optString("name", id), o.optString("built", ""), bbox);
        JSONArray fs = o.optJSONArray("files");
        if (fs != null) {
            for (int i = 0; i < fs.length(); i++) {
                JSONObject f = fs.getJSONObject(i);
                String n = f.getString("name");
                if (!n.matches("(area\\.sqlite|route\\.bin)(\\.gz)?")) throw new JSONException("Unexpected file in " + id + ": " + n);
                r.files.add(new FileRef(n, f.getString("url"), f.optLong("size", 0), f.optString("sha256", "")));
            }
        }
        return r;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject().put("id", id).put("name", name).put("built", built);
        if (bbox != null) o.put("bbox", new JSONArray().put(bbox[0]).put(bbox[1]).put(bbox[2]).put(bbox[3]));
        return o;
    }

    public static List<Region> parseCatalog(String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        JSONArray a = root.getJSONArray("regions");
        List<Region> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) out.add(fromJson(a.getJSONObject(i)));
        return out;
    }
}
