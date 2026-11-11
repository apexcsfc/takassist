package com.apexcsfc.atak.takassist.talk.ptt;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

/**
 * "Talk Assist PTT": a shortcut with no screen, for Samsung's side-button
 * "Double press → Open app". Brings ATAK to the front and tells Talk Assist (running
 * inside ATAK) to start listening. Runs in the plugin APK's own process, so it uses
 * only plain Android, no ATAK classes.
 */
public class PttShortcutActivity extends Activity {

    private static final String[] ATAK_PACKAGES = {"com.atakmap.app.civ", "com.atakmap.app"};
    private static final String ACTION_PTT = "com.apexcsfc.atak.takassist.talk.PTT";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String atak = null;
        for (String p : ATAK_PACKAGES) {
            Intent launch = getPackageManager().getLaunchIntentForPackage(p);
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                startActivity(launch);
                atak = p;
                break;
            }
        }
        if (atak == null) {
            Toast.makeText(this, "ATAK isn't installed.", Toast.LENGTH_LONG).show();
        } else {
            // Only ATAK gets this; Talk Assist's receiver lives inside ATAK's process.
            Intent ptt = new Intent(ACTION_PTT);
            ptt.setPackage(atak);
            sendBroadcast(ptt);
        }
        finish();
        overridePendingTransition(0, 0);
    }
}
