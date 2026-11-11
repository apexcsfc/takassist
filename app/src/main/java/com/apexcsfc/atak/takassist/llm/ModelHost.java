package com.apexcsfc.atak.takassist.llm;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.maps.MapView;

import java.net.URI;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Owns the bundled model server for the whole plugin, not just the pane, so the
 * model can be started before the LLM Assist pane has ever been opened and keeps
 * running while the user moves between panes.
 *
 * Other plugins in the same ATAK (Talk Assist) can't share Java classes with this
 * one, so they talk to it with ATAK-local broadcasts:
 * <ul>
 *   <li>{@link #ACTION_START}: start the model with LLM Assist's saved settings.</li>
 *   <li>{@link #ACTION_STATE}: sent on every state change and in answer to every start
 *       request, with {@link #EXTRA_STATE} (STOPPED, STARTING, READY, FAILED, or REMOTE
 *       when the server URL points at another machine) and {@link #EXTRA_DETAIL}.</li>
 * </ul>
 */
public final class ModelHost {

    public static final String ACTION_START = "com.apexcsfc.atak.takassist.START_MODEL";
    /** Unload the model (sent by TAK Vision before it loads its own; a phone can't hold both). */
    public static final String ACTION_STOP = "com.apexcsfc.atak.takassist.STOP_MODEL";
    public static final String ACTION_STATE = "com.apexcsfc.atak.takassist.MODEL_STATE";
    public static final String EXTRA_STATE = "state";
    public static final String EXTRA_DETAIL = "detail";

    public static final int CONTEXT_SIZE = 6144;

    // Same keys and defaults the pane uses.
    private static final String PREFS = "llmassist";
    private static final String PREF_URL = "llmassist.server_url";
    private static final String PREF_MODEL_PATH = "llmassist.model_path";
    private static final String PREF_ACCEL = "llmassist.accelerator";
    private static final String DEFAULT_URL = "http://127.0.0.1:8080";

    private static ModelHost instance;

    private final LlmServerManager server;
    private final ExecutorService exec = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "llmassist-modelhost");
            t.setDaemon(true);
            return t;
        }
    });
    private volatile LlmServerManager.StatusListener paneListener;
    private BroadcastReceiver receiver;

    private ModelHost(Context pluginContext) {
        server = new LlmServerManager(pluginContext);
        server.setListener(new LlmServerManager.StatusListener() {
            @Override
            public void onStatus(LlmServerManager.State state, String detail) {
                LlmServerManager.StatusListener l = paneListener;
                if (l != null) l.onStatus(state, detail);
                lastDetail = detail == null ? "" : detail;
                announce(state.name(), detail);
            }
        });
    }

    private volatile String lastDetail = "";

    private static void announce(String state, String detail) {
        Intent i = new Intent(ACTION_STATE);
        i.putExtra(EXTRA_STATE, state);
        i.putExtra(EXTRA_DETAIL, detail == null ? "" : detail);
        AtakBroadcast.getInstance().sendBroadcast(i);
    }

    public static synchronized ModelHost init(Context pluginContext) {
        if (instance == null) instance = new ModelHost(pluginContext);
        return instance;
    }

    public static synchronized ModelHost get() {
        if (instance == null) throw new IllegalStateException("ModelHost.init() not called");
        return instance;
    }

    public LlmServerManager server() {
        return server;
    }

    /** The pane's status callback; null when the pane goes away. */
    public void setPaneListener(LlmServerManager.StatusListener l) {
        paneListener = l;
        if (l != null) l.onStatus(server.getState(), "");
    }

    /** Called from the plugin's onStart: listen for start requests from other plugins. */
    public synchronized void register() {
        if (receiver != null) return;
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (ACTION_START.equals(intent.getAction())) {
                    requestStart();
                } else if (ACTION_STOP.equals(intent.getAction())) {
                    requestStop();
                }
            }
        };
        AtakBroadcast.DocumentedIntentFilter filter = new AtakBroadcast.DocumentedIntentFilter(ACTION_START,
                "Start TAK Assist's on-device model with its saved settings");
        filter.addAction(ACTION_STOP, "Unload TAK Assist's on-device model to free memory");
        AtakBroadcast.getInstance().registerReceiver(receiver, filter);
    }

    /**
     * Starts the model with the settings saved in the LLM Assist pane. Does nothing
     * when the server URL points at another machine, or when it's already up or loading.
     */
    public void requestStart() {
        // Every path answers, so the caller never waits on silence.
        final MapView mv = MapView.getMapView();
        if (mv == null) {
            announce("FAILED", "ATAK's map isn't ready yet. Try again in a moment.");
            return;
        }
        final SharedPreferences prefs = mv.getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        final String url = prefs.getString(PREF_URL, DEFAULT_URL).trim();
        int port = 8080;
        try {
            URI u = URI.create(url.isEmpty() ? DEFAULT_URL : url);
            String host = u.getHost();
            if (!"127.0.0.1".equals(host) && !"localhost".equals(host)) {
                announce("REMOTE", url);
                return;
            }
            if (u.getPort() > 0) port = u.getPort();
        } catch (Exception e) {
            announce("FAILED", "The model server URL isn't valid: " + url);
            return;
        }
        if (!server.isBundled()) {
            announce("FAILED", "This TAK Assist build doesn't include the model server.");
            return;
        }
        LlmServerManager.State st = server.getState();
        if (st == LlmServerManager.State.READY || st == LlmServerManager.State.STARTING) {
            // Already up or loading: re-announce so a late listener learns the state.
            announce(st.name(), st == LlmServerManager.State.STARTING ? lastDetail : "");
            return;
        }

        String accel = prefs.getString(PREF_ACCEL,
                server.hasAccelerators() ? LlmServerManager.NPU : LlmServerManager.CPU);
        if (!server.hasAccelerators()) accel = LlmServerManager.CPU;
        server.configure(prefs.getString(PREF_MODEL_PATH, ""), port, CONTEXT_SIZE, accel);

        exec.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    server.ensureRunning();
                } catch (Exception ignored) {
                    // State and reason already went out through the listener.
                }
            }
        });
    }

    /**
     * Unloads the model (another plugin needs the memory). Chat and Talk start it again by
     * themselves the next time they're used. Announces STOPPED through the usual listener.
     */
    public void requestStop() {
        if (server.getState() == LlmServerManager.State.STOPPED) {
            announce("STOPPED", "");
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                server.stop();
            }
        }, "llmassist-stop-request").start();
    }

    /** Called from the plugin's onStop: stop listening and stop the model. */
    public synchronized void shutdown() {
        if (receiver != null) {
            AtakBroadcast.getInstance().unregisterReceiver(receiver);
            receiver = null;
        }
        paneListener = null;
        new Thread(new Runnable() {
            @Override
            public void run() {
                server.stop();
            }
        }, "llmassist-stop").start();
    }
}
