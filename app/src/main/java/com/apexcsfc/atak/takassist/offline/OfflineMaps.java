package com.apexcsfc.atak.takassist.offline;

import android.util.Log;

import java.io.File;

/**
 * Lazily opens area.sqlite and route.bin from the model folder and reopens them
 * if the files are replaced (for example after pushing a newer build).
 * Either file may be missing; callers then fall back to online lookups.
 */
public class OfflineMaps {

    private static final String TAG = "LlmAssistOffline";

    private final File dir;
    private OfflineArea area;
    private OfflineRouter router;
    private long areaStamp = -1, routeStamp = -1;

    public OfflineMaps(File dir) {
        this.dir = dir;
    }

    /** Maps live in Download/TAKAssist (older installs: atak/llm); checked on every call. */
    private File dir() {
        return com.apexcsfc.takassist.maps.core.MapPacks.activeDir();
    }

    public synchronized OfflineArea area() {
        File f = new File(dir(), "area.sqlite");
        long stamp = f.isFile() ? f.lastModified() ^ f.length() : 0;
        if (stamp != areaStamp) {
            if (area != null) {
                try {
                    area.close();
                } catch (Exception ignored) {
                }
            }
            area = null;
            areaStamp = stamp;
            if (stamp != 0) {
                try {
                    area = OfflineArea.open(f);
                } catch (Exception e) {
                    Log.e(TAG, "cannot open " + f, e);
                }
            }
        }
        return area;
    }

    public synchronized OfflineRouter router() {
        File f = new File(dir(), "route.bin");
        long stamp = f.isFile() ? f.lastModified() ^ f.length() : 0;
        if (stamp != routeStamp) {
            router = null;
            routeStamp = stamp;
            if (stamp != 0) {
                try {
                    router = OfflineRouter.open(f);
                } catch (Exception e) {
                    Log.e(TAG, "cannot open " + f, e);
                }
            }
        }
        return router;
    }

    /** One line for the UI/status, e.g. "Offline maps: area + routing". */
    public String status() {
        OfflineArea a = area();
        OfflineRouter r = router();
        if (a == null && r == null) return "Offline maps: none (online lookups)";
        return "Offline maps: " + (a != null ? "places" : "") + (a != null && r != null ? " + " : "")
                + (r != null ? "routing" : "");
    }
}
