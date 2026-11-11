package com.apexcsfc.atak.takassist.plugin;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import com.apexcsfc.atak.takassist.llm.ModelHost;
import com.apexcsfc.atak.takassist.ui.TakAssistPane;
import com.atak.plugins.impl.PluginContextProvider;
import com.atakmap.android.maps.MapView;

import gov.tak.api.plugin.IPlugin;
import gov.tak.api.plugin.IServiceController;
import gov.tak.api.ui.IHostUIService;
import gov.tak.api.ui.Pane;
import gov.tak.api.ui.PaneBuilder;
import gov.tak.api.ui.ToolbarItem;
import gov.tak.api.ui.ToolbarItemAdapter;
import gov.tak.platform.marshal.MarshalManager;

/**
 * TAK Assist plugin entry point (assets/plugin.xml). One toolbar button opens one pane
 * with Chat and Talk tabs; both use the on-device model that {@link ModelHost} owns.
 * Follows the ATAK 5.8 SDK plugintemplate pattern: IPlugin + toolbar item + Pane.
 */
public class TakAssistPlugin implements IPlugin {

    /** Sent by the "TAK Assist PTT" shortcut (side-button double press). */
    public static final String ACTION_PTT = "com.apexcsfc.atak.takassist.talk.PTT";

    private final Context pluginContext;
    private final IHostUIService uiService;
    private final ToolbarItem toolbarItem;
    private TakAssistPane controller;
    private Pane pane;

    private final BroadcastReceiver pttReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Give ATAK a moment to come to the front: Android only lets a visible app use the mic.
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    showPane();
                    if (controller != null) controller.startHandsFree();
                }
            }, 600);
        }
    };
    private boolean pttRegistered;

    public TakAssistPlugin(IServiceController serviceController) {
        final PluginContextProvider ctxProvider =
                serviceController.getService(PluginContextProvider.class);
        pluginContext = ctxProvider != null ? ctxProvider.getPluginContext() : null;
        if (pluginContext != null) {
            pluginContext.setTheme(R.style.ATAKPluginTheme);
        }

        uiService = serviceController.getService(IHostUIService.class);
        ModelHost.init(pluginContext);

        toolbarItem = new ToolbarItem.Builder(
                pluginContext.getString(R.string.app_name),
                MarshalManager.marshal(
                        pluginContext.getResources().getDrawable(R.drawable.ic_toolbar),
                        android.graphics.drawable.Drawable.class,
                        gov.tak.api.commons.graphics.Bitmap.class))
                .setListener(new ToolbarItemAdapter() {
                    @Override
                    public void onClick(ToolbarItem item) {
                        showPane();
                    }
                })
                // A stable identifier lets ATAK remember where the user put the button.
                .setIdentifier(pluginContext.getPackageName())
                .build();
    }

    @Override
    public void onStart() {
        if (uiService != null) uiService.addToolbarItem(toolbarItem);
        // Lets the Talk tab start the model before the Chat tab has been opened.
        ModelHost.get().register();

        // A real (not ATAK-local) receiver, because the PTT shortcut runs outside ATAK.
        final Context atak = MapView.getMapView().getContext();
        final IntentFilter f = new IntentFilter(ACTION_PTT);
        if (Build.VERSION.SDK_INT >= 33) {
            atak.registerReceiver(pttReceiver, f, Context.RECEIVER_EXPORTED);
        } else {
            atak.registerReceiver(pttReceiver, f);
        }
        pttRegistered = true;

        // Map packs dropped into Download/ (e.g. by Knox Manage) are installed and switched on.
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    com.apexcsfc.takassist.maps.core.PackFiles.importAll(null);
                } catch (Exception ignored) {
                }
            }
        }, "takassist-mappacks").start();

        // If the TAK Assist Maps app is installed (an administrator deployed it), ask it to
        // check its managed settings and fetch any map areas the administrator assigned.
        try {
            Intent sync = new Intent(MAPS_SYNC);
            sync.setClassName(MAPS_PACKAGE, MAPS_PACKAGE + ".SyncReceiver");
            sync.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            atak.sendBroadcast(sync);
        } catch (Exception ignored) {
            // not installed
        }
    }

    private static final String MAPS_PACKAGE = "com.apexcsfc.takassist.maps";
    private static final String MAPS_SYNC = "com.apexcsfc.takassist.maps.SYNC";

    @Override
    public void onStop() {
        if (pttRegistered) {
            try {
                MapView.getMapView().getContext().unregisterReceiver(pttReceiver);
            } catch (IllegalArgumentException ignored) {
                // already gone
            }
            pttRegistered = false;
        }
        if (uiService != null) {
            if (pane != null && uiService.isPaneVisible(pane)) uiService.closePane(pane);
            uiService.removeToolbarItem(toolbarItem);
        }
        if (controller != null) {
            controller.shutdown();
            controller = null;
            pane = null;
        }
        ModelHost.get().shutdown();
    }

    private void showPane() {
        if (uiService == null) return;
        if (pane == null) {
            controller = new TakAssistPane(pluginContext, MapView.getMapView());
            pane = new PaneBuilder(controller.getView())
                    .setMetaValue(Pane.RELATIVE_LOCATION, Pane.Location.Default)
                    .setMetaValue(Pane.PREFERRED_WIDTH_RATIO, 0.5D)
                    .setMetaValue(Pane.PREFERRED_HEIGHT_RATIO, 0.5D)
                    .build();
        }
        if (!uiService.isPaneVisible(pane)) uiService.showPane(pane, null);
    }
}
