package com.apexcsfc.atak.takassist;

import android.content.Context;
import android.content.SharedPreferences;

import com.atakmap.android.maps.MapView;

/**
 * "Offline only": when on (the default), TAK Assist never sends map searches, routing
 * requests or speech to online services, so the user's area and queries stay on the phone.
 */
public final class Privacy {
    public static final String PREF_OFFLINE_ONLY = "takassist.offline_only";

    private Privacy() {
    }

    public static boolean offlineOnly(Context c) {
        try {
            // Same store as the Chat tab's settings: ATAK's own "llmassist" preferences.
            MapView mv = MapView.getMapView();
            if (mv != null) c = mv.getContext();
            if (c == null) return true;
            SharedPreferences p = c.getSharedPreferences("llmassist", Context.MODE_PRIVATE);
            return p.getBoolean(PREF_OFFLINE_ONLY, true);
        } catch (RuntimeException e) {
            return true; // fail closed
        }
    }

    public static void setOfflineOnly(boolean on) {
        MapView mv = MapView.getMapView();
        if (mv == null) return;
        mv.getContext().getSharedPreferences("llmassist", Context.MODE_PRIVATE)
                .edit().putBoolean(PREF_OFFLINE_ONLY, on).apply();
    }

    /**
     * Offline mode: the model server must be on this phone. Called before every request to it,
     * so a server address pointing off the phone can't carry the conversation away.
     */
    public static void checkModelServer(String baseUrl) throws java.io.IOException {
        if (!offlineOnly()) return;
        String host;
        try {
            host = new java.net.URL(baseUrl).getHost();
        } catch (java.net.MalformedURLException e) {
            throw new java.io.IOException("Bad model server address: " + baseUrl);
        }
        if (!isLoopback(host)) {
            throw new java.io.IOException("Offline mode is on, so the model server must be on this phone "
                    + "(127.0.0.1). Switch to online mode to use " + host + ".");
        }
    }

    public static boolean isLoopback(String host) {
        if (host == null) return false;
        String h = host.toLowerCase(java.util.Locale.US);
        if (h.startsWith("[") && h.endsWith("]")) h = h.substring(1, h.length() - 1);
        // Loopback literals, plus "localhost" (Android pins it to 127.0.0.1 in /system/etc/hosts).
        return h.equals("localhost") || h.equals("::1") || h.matches("127(\\.\\d{1,3}){3}");
    }

    public static boolean offlineOnly() {
        return offlineOnly(null);
    }
}
