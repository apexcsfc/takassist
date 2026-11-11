package com.apexcsfc.atak.takassist.talk.ui;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.apexcsfc.atak.takassist.talk.llm.IntentClient;
import com.apexcsfc.atak.takassist.talk.llm.IntentPrompt;
import com.apexcsfc.atak.takassist.plugin.R;
import com.apexcsfc.atak.takassist.talk.offline.OfflineArea;
import com.apexcsfc.atak.takassist.talk.offline.OfflineRouter;
import com.apexcsfc.atak.takassist.talk.resolve.RouteShape;
import com.apexcsfc.atak.takassist.talk.llm.DetailPrompt;
import com.apexcsfc.atak.takassist.talk.resolve.Keywords;
import com.apexcsfc.atak.takassist.talk.resolve.MapNames;
import com.apexcsfc.atak.takassist.talk.resolve.Medevac;
import com.apexcsfc.atak.takassist.talk.resolve.Salute;
import com.apexcsfc.atak.takassist.talk.resolve.MarkerCounter;
import com.apexcsfc.atak.takassist.talk.resolve.MarkerIntent;
import com.apexcsfc.atak.takassist.talk.resolve.Place;
import com.apexcsfc.atak.takassist.talk.resolve.Transcript;
import com.apexcsfc.atak.takassist.talk.resolve.Resolver;
import com.apexcsfc.atak.takassist.talk.resolve.TargetFinder;
import com.apexcsfc.atak.takassist.talk.speech.SpeechInput;
import com.atak.plugins.impl.PluginLayoutInflater;
import com.atakmap.android.bloodhound.BloodHoundTool;
import com.atakmap.android.cot.CotMapComponent;
import com.atakmap.android.emergency.tool.EmergencyManager;
import com.atakmap.android.emergency.tool.EmergencyType;
import com.atakmap.android.importexport.CotEventFactory;
import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.maps.MapGroup;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.Marker;
import com.atakmap.android.maps.PointMapItem;
import com.atakmap.android.routes.Route;
import com.atakmap.android.routes.RouteMapReceiver;
import com.atakmap.android.toolbar.Tool;
import com.atakmap.android.toolbar.ToolManagerBroadcastReceiver;
import com.atakmap.android.user.PlacePointTool;
import com.atakmap.android.util.ATAKUtilities;
import com.atakmap.comms.CotDispatcher;
import com.atakmap.coremap.conversions.CoordinateFormat;
import com.atakmap.coremap.conversions.CoordinateFormatUtilities;
import com.atakmap.coremap.cot.event.CotEvent;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.coremap.maps.coords.GeoPointMetaData;
import com.atakmap.map.CameraController;

import java.io.File;
import java.net.URI;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Report in (speech or text) → model fills fields → resolver computes the point →
 * confirm card → marker on this map, and optionally out to the team.
 * Nothing touches the map or the network until the user taps Place or Place + send.
 */
public class TalkAssistPane {

    private static final String PREFS = "talkassist";
    private static final String PREF_NORTH = "talkassist.default_north";
    /** LLM Assist's prefs: reuse its server URL so both plugins hit the same model. */
    private static final String LLM_PREFS = "llmassist";
    private static final String LLM_PREF_URL = "llmassist.server_url";
    private static final String LLM_PREF_AUTOSTART = "llmassist.autostart";
    /** LLM Assist's ModelHost listens for this and starts the model with its own settings. */
    private static final String ACTION_START = "com.apexcsfc.atak.takassist.START_MODEL";
    /** LLM Assist announces model state changes with this (extras "state", "detail"). */
    private static final String ACTION_STATE = "com.apexcsfc.atak.takassist.MODEL_STATE";
    private static final long MODEL_LOAD_TIMEOUT_MS = 10 * 60 * 1000L;
    /** LLM Assist answers a start request at once; silence this long means it isn't there. */
    private static final long ACK_TIMEOUT_MS = 8_000L;

    private static final int GRAY = 0xFFC9D1D9;
    private static final int AMBER = 0xFFD29922;
    private static final int GREEN = 0xFF3FB950;
    private static final int RED = 0xFFF85149;

    /** What the model line shows. Only changed on the UI thread. */
    private enum ModelUi { CHECKING, OFF, REQUESTED, LOADING, READY, FAILED, NO_ANSWER, REMOTE_DOWN }

    private final MapView mapView;
    private final View root;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final SharedPreferences prefs;
    private final SharedPreferences llmPrefs;
    private final SpeechInput speech;

    private final TextView status;
    private final Button northBtn;
    private final LinearLayout card;
    private final View actions;
    /** Preview marker shown while the confirm card is up: local, never saved, never sent. */
    private Marker draft;
    private final TextView cardTitle;
    private final TextView cardBody;
    private final TextView cardWarn;
    private final TextView log;
    private final EditText input;
    private final Button goBtn;
    private final Button talkBtn;

    private volatile IntentClient client;
    private Resolver.Result pending;
    private boolean busy;

    private final TextView modelText;
    private final Button modelBtn;
    private volatile ModelUi modelUi = ModelUi.CHECKING;
    private volatile String modelDetail = "";
    private long modelSince; // when REQUESTED/LOADING began, for the timer
    /** Health probes for the model line, kept off the report worker. */
    private final ExecutorService probe = Executors.newSingleThreadExecutor();

    /** LLM Assist's state announcements (STOPPED, STARTING, READY, FAILED, REMOTE). */
    private final BroadcastReceiver modelStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String st = intent.getStringExtra("state");
            final String d = intent.getStringExtra("detail");
            if ("STARTING".equals(st)) setModel(ModelUi.LOADING, d);
            else if ("READY".equals(st)) setModel(ModelUi.READY, d);
            else if ("FAILED".equals(st)) setModel(ModelUi.FAILED, d);
            else if ("REMOTE".equals(st)) setModel(ModelUi.REMOTE_DOWN, d);
            else if ("STOPPED".equals(st) && modelUi != ModelUi.REQUESTED) setModel(ModelUi.OFF, "");
        }
    };

    /** Once a second while loading: refresh the timer, notice silence, catch a missed READY. */
    private final Runnable modelTicker = new Runnable() {
        private int n;

        @Override
        public void run() {
            if (modelUi != ModelUi.REQUESTED && modelUi != ModelUi.LOADING) return;
            if (modelUi == ModelUi.REQUESTED
                    && System.currentTimeMillis() - modelSince > ACK_TIMEOUT_MS) {
                setModel(ModelUi.NO_ANSWER, "");
                return;
            }
            renderModel();
            if (++n % 5 == 0) checkModel(false);
            ui.postDelayed(this, 1000);
        }
    };

    @SuppressLint("ClickableViewAccessibility")
    public TalkAssistPane(Context pluginContext, MapView mapView) {
        this.mapView = mapView;
        root = PluginLayoutInflater.inflate(pluginContext, R.layout.talk_pane, null);

        final Context atak = mapView.getContext();
        prefs = atak.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        llmPrefs = atak.getSharedPreferences(LLM_PREFS, Context.MODE_PRIVATE);
        speech = new SpeechInput(atak);

        status = root.findViewById(R.id.ta_status);
        northBtn = root.findViewById(R.id.ta_north);
        card = root.findViewById(R.id.ta_card);
        actions = root.findViewById(R.id.ta_actions);
        cardTitle = root.findViewById(R.id.ta_card_title);
        cardBody = root.findViewById(R.id.ta_card_body);
        cardWarn = root.findViewById(R.id.ta_card_warn);
        log = root.findViewById(R.id.ta_log);
        input = root.findViewById(R.id.ta_input);
        goBtn = root.findViewById(R.id.ta_go);
        talkBtn = root.findViewById(R.id.ta_talk);
        modelText = root.findViewById(R.id.ta_model);
        modelBtn = root.findViewById(R.id.ta_model_btn);
        modelBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestModel();
            }
        });

        updateNorthLabel();
        northBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.edit().putString(PREF_NORTH,
                        defaultNorth() == Resolver.DefaultNorth.MAGNETIC ? "true" : "magnetic").apply();
                updateNorthLabel();
            }
        });

        goBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                submit(input.getText().toString());
            }
        });
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    submit(input.getText().toString());
                    return true;
                }
                return false;
            }
        });

        talkBtn.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startTalking();
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (speech.isListening()) {
                            speech.stop();
                            talkBtn.setText("Working…");
                        }
                        return true;
                    default:
                        return false;
                }
            }
        });

        root.findViewById(R.id.ta_place).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                commit(false);
            }
        });
        root.findViewById(R.id.ta_send).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                commit(true);
            }
        });
        root.findViewById(R.id.ta_cancel).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pending = null;
                clearDraft();
                showCard(false);
                setStatus("Cancelled. Nothing was placed. Say or type a report.");
            }
        });

        AtakBroadcast.getInstance().registerReceiver(modelStateReceiver,
                new AtakBroadcast.DocumentedIntentFilter(ACTION_STATE, "TAK Assist model state"));
        checkModel(true);
    }

    // ------------------------------------------------------------ model line

    /**
     * Asks the server whether the model is up. On first open, starts it if LLM Assist's
     * "start the model automatically" setting is on, so it's usually ready by the time
     * a report has been said.
     */
    private void checkModel(final boolean firstOpen) {
        final String url = serverUrl();
        final IntentClient c = new IntentClient(url);
        probe.execute(new Runnable() {
            @Override
            public void run() {
                final boolean ready = c.isReady();
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (ready) {
                            if (modelUi != ModelUi.READY) setModel(ModelUi.READY, "");
                        } else if (firstOpen) {
                            if (!isLocal(url)) setModel(ModelUi.REMOTE_DOWN, url);
                            else if (llmPrefs.getBoolean(LLM_PREF_AUTOSTART, true)) requestModel();
                            else setModel(ModelUi.OFF, "");
                        }
                    }
                });
            }
        });
    }

    /** Start button / Retry / a report arriving while the model is off. UI thread. */
    private void requestModel() {
        final String url = serverUrl();
        if (!isLocal(url)) {
            setModel(ModelUi.CHECKING, "");
            checkModel(true);
            return;
        }
        if (modelUi == ModelUi.REQUESTED || modelUi == ModelUi.LOADING || modelUi == ModelUi.READY) return;
        setModel(ModelUi.REQUESTED, "");
        AtakBroadcast.getInstance().sendBroadcast(new Intent(ACTION_START));
    }

    /** UI thread. */
    private void setModel(ModelUi s, String detail) {
        final boolean wasLoading = modelUi == ModelUi.REQUESTED || modelUi == ModelUi.LOADING;
        final boolean loading = s == ModelUi.REQUESTED || s == ModelUi.LOADING;
        modelUi = s;
        modelDetail = detail == null ? "" : detail.trim();
        ui.removeCallbacks(modelTicker);
        if (loading) {
            if (!wasLoading) modelSince = System.currentTimeMillis();
            ui.postDelayed(modelTicker, 1000);
        }
        renderModel();
    }

    /** Writes the model line in plain words. UI thread. */
    private void renderModel() {
        final String elapsed = elapsed();
        String text;
        int color = GRAY;
        String btn = null;
        switch (modelUi) {
            case OFF:
                text = "Model: off. Tap Start, or just send a report and it will start.";
                btn = "Start";
                break;
            case REQUESTED:
                text = "Model: starting it… " + elapsed;
                color = AMBER;
                break;
            case LOADING:
                text = "Model: loading into memory… " + elapsed
                        + (modelDetail.isEmpty() ? "" : "\n" + modelDetail)
                        + "\nThis usually takes under a minute. Reports wait until it's ready.";
                color = AMBER;
                break;
            case READY:
                text = "Model: ready.";
                color = GREEN;
                break;
            case FAILED:
                text = "Model: couldn't start."
                        + (modelDetail.isEmpty() ? "" : "\n" + modelDetail)
                        + "\nOpen the Chat tab → Settings to check the model file.";
                color = RED;
                btn = "Retry";
                break;
            case NO_ANSWER:
                text = "Model: the model host didn't answer.\nClose and reopen TAK Assist, "
                        + "or reload it under ATAK → Plugins.";
                color = RED;
                btn = "Retry";
                break;
            case REMOTE_DOWN:
                text = "Model: the server at " + (modelDetail.isEmpty() ? serverUrl() : modelDetail)
                        + " isn't answering.\nCheck that server, or the server URL in Chat → Settings.";
                color = RED;
                btn = "Retry";
                break;
            case CHECKING:
            default:
                text = "Model: checking…";
                break;
        }
        modelText.setText(text);
        modelText.setTextColor(color);
        if (btn == null) {
            modelBtn.setVisibility(View.GONE);
        } else {
            modelBtn.setText(btn);
            modelBtn.setVisibility(View.VISIBLE);
        }
    }

    private String elapsed() {
        if (modelSince == 0) return "";
        long s = (System.currentTimeMillis() - modelSince) / 1000;
        return String.format(Locale.US, "(%d:%02d)", s / 60, s % 60);
    }

    /**
     * Worker thread: waits for the model so a report said during loading is read as
     * soon as it's ready. Returns null when ready, otherwise why the report wasn't read.
     */
    private String waitForModel(IntentClient c) throws InterruptedException {
        if (c.isReady()) return null;
        ui.post(new Runnable() {
            @Override
            public void run() {
                setStatus("Your report will be read as soon as the model is ready.");
                if (modelUi != ModelUi.REQUESTED && modelUi != ModelUi.LOADING) {
                    if (modelUi == ModelUi.READY) setModel(ModelUi.OFF, ""); // it stopped
                    requestModel();
                }
            }
        });
        final long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < MODEL_LOAD_TIMEOUT_MS) {
            Thread.sleep(1000);
            if (c.isReady()) return null;
            switch (modelUi) {
                case FAILED:
                case NO_ANSWER:
                case REMOTE_DOWN:
                    return "Your report wasn't read because the model isn't running. See the model line above.";
                default:
                    break;
            }
        }
        return "Your report wasn't read: the model took more than 10 minutes to load.";
    }

    private String serverUrl() {
        return llmPrefs.getString(LLM_PREF_URL, IntentClient.DEFAULT_URL);
    }

    private static boolean isLocal(String url) {
        try {
            String host = URI.create(url == null || url.trim().isEmpty()
                    ? IntentClient.DEFAULT_URL : url.trim()).getHost();
            return "127.0.0.1".equals(host) || "localhost".equals(host);
        } catch (Exception e) {
            return false;
        }
    }

    public View getView() {
        return root;
    }

    public void shutdown() {
        AtakBroadcast.getInstance().unregisterReceiver(modelStateReceiver);
        ui.removeCallbacks(modelTicker);
        probe.shutdownNow();
        clearDraft();
        synchronized (this) {
            if (area != null) {
                try {
                    area.close();
                } catch (Exception ignored) {
                }
                area = null;
            }
        }
        speech.destroy();
        IntentClient c = client;
        if (c != null) c.abort();
        worker.shutdownNow();
    }

    // ------------------------------------------------------------------ input

    private void startTalking() {
        startListening(false);
    }

    /**
     * Side-button shortcut: listen without a button to hold. Speech ends by itself when the
     * user stops talking, then the report is processed as usual. UI thread.
     */
    public void startHandsFree() {
        if (speech.isListening()) return;
        startListening(true);
    }

    private void startListening(boolean handsFree) {
        if (busy) {
            setStatus("Still working on the last report…");
            return;
        }
        final String why = speech.unavailableReason();
        if (why != null) {
            setStatus(why);
            return;
        }
        input.setText("");
        if (handsFree) {
            beep();
            setStatus("Listening… speak your report. It stops when you stop talking.");
        }
        talkBtn.setText(handsFree ? "Listening… (stops when you stop talking)" : "Listening… release when done");
        speech.start(new SpeechInput.Listener() {
            @Override
            public void onPartial(String text) {
                input.setText(text);
            }

            @Override
            public void onFinal(String text) {
                talkBtn.setText("Hold to talk");
                input.setText(text);
                submit(text);
            }

            @Override
            public void onError(String message) {
                talkBtn.setText("Hold to talk");
                setStatus(message);
            }

            @Override
            public void onNotice(String message) {
                setStatus(message);
            }
        });
    }

    private void submit(final String raw) {
        final String text = raw == null ? "" : raw.trim();
        if (text.isEmpty() || busy) return;
        busy = true;
        goBtn.setEnabled(false);
        // A new command starts with a clean pane.
        showCard(false);
        clearDraft();
        pending = null;
        log.setText("");
        setStatus("Reading report…");

        final String url = serverUrl();
        final IntentClient c = new IntentClient(url);
        client = c;
        // Where to search for place names from: the user, else the map centre.
        final AtakEnvironment here = new AtakEnvironment(null, null);
        final double[] searchFrom = here.self() != null ? here.self() : here.mapCenter();
        worker.execute(new Runnable() {
            @Override
            public void run() {
                MarkerIntent intent = null;
                String error = null;
                PlaceLookup place = null;
                String placeError = null;
                Medevac medevac = null;
                Salute salute = null;
                List<String> mapNames = null;
                try {
                    error = waitForModel(c);
                    if (error == null) {
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                setStatus("Reading report…");
                            }
                        });
                        final String cleaned = Transcript.clean(text);
                        intent = IntentPrompt.parse(c.extract(cleaned));
                        // Unmistakable command words win over the model's label.
                        final String kw = Keywords.action(text);
                        if (kw != null && !kw.equals(intent.action)) intent.action = kw;
                        if ("emergency".equals(intent.action) && intent.emergencyType == null) {
                            intent.emergencyType = Keywords.emergencyType(text);
                        }
                        if ("medevac".equals(intent.action)) {
                            postStatus("Filling in the 9-line…");
                            medevac = DetailPrompt.parseMedevac(c.extract(DetailPrompt.MEDEVAC_SYSTEM,
                                    "medevac", DetailPrompt.medevacSchema(), cleaned, 400));
                        } else if ("salute".equals(intent.action)) {
                            postStatus("Filling in the SALUTE report…");
                            salute = DetailPrompt.parseSalute(c.extract(DetailPrompt.SALUTE_SYSTEM,
                                    "salute", DetailPrompt.saluteSchema(), cleaned, 200));
                        }
                        if ("set_map".equals(intent.action)) {
                            mapNames = installedMapNames();
                        } else if ("directions".equals(intent.action) && intent.place == null && intent.label != null) {
                            // "directions to Union Station" may arrive as a name, not a place: look it up too.
                            try {
                                place = findPlace(intent.label, searchFrom);
                            } catch (IllegalStateException ignored) {
                                // no map data: markers can still match
                            }
                        } else if (intent.place != null) {
                            try {
                                place = findPlace(intent.place, searchFrom);
                            } catch (IllegalStateException e) {
                                placeError = e.getMessage();
                            }
                        }
                    }
                } catch (Exception e) {
                    error = "Couldn't read that report: " + e.getMessage();
                }
                final MarkerIntent fIntent = intent;
                final String fError = error;
                final PlaceLookup fPlace = place;
                final Medevac fMedevac = medevac;
                final Salute fSalute = salute;
                final List<String> fMapNames = mapNames;
                final String fPlaceError = placeError;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        busy = false;
                        goBtn.setEnabled(true);
                        if (fError != null) {
                            setStatus(fError);
                            return;
                        }
                        // Backstop: a "how many…" question the model didn't label.
                        if (fIntent.action == null || "unclear".equals(fIntent.action)) {
                            String side = MarkerCounter.countQuestionAffiliation(text);
                            if (side != null) {
                                fIntent.action = "count_markers";
                                fIntent.affiliation = side;
                            }
                        }
                        final Place firstPlace = fPlace == null || fPlace.options.isEmpty() ? null : fPlace.options.get(0);
                        if ("locate".equals(fIntent.action)) {
                            answerLocate(text, fIntent);
                        } else if ("directions".equals(fIntent.action)) {
                            startDirections(text, fIntent, firstPlace, fPlaceError);
                        } else if ("stop_navigation".equals(fIntent.action)) {
                            stopNavigation();
                        } else if ("stop_bloodhound".equals(fIntent.action)) {
                            stopBloodhound();
                        } else if ("close_tool".equals(fIntent.action)) {
                            closeActiveTool();
                        } else if ("emergency".equals(fIntent.action)) {
                            confirmEmergency(fIntent.emergencyType);
                        } else if ("medevac".equals(fIntent.action)) {
                            createMedevac(text, fIntent, firstPlace, fPlaceError, fMedevac);
                        } else if ("salute".equals(fIntent.action)) {
                            showSalute(text, fIntent, firstPlace, fPlaceError, fSalute);
                        } else if ("bloodhound".equals(fIntent.action)) {
                            startBloodhound(text, fIntent,
                                    fPlace == null || fPlace.options.isEmpty() ? null : fPlace.options.get(0),
                                    fPlaceError);
                        } else if ("set_map".equals(fIntent.action)) {
                            switchMap(fIntent.mapName, fMapNames);
                        } else if ("count_markers".equals(fIntent.action)) {
                            answerCount(text, fIntent);
                        } else if (fPlace != null && fPlace.ask) {
                            askWhichPlace(text, fIntent, fPlace, searchFrom);
                        } else {
                            finishReport(text, fIntent,
                                    fPlace == null || fPlace.options.isEmpty() ? null : fPlace.options.get(0),
                                    fPlaceError);
                        }
                    }
                });
            }
        });
    }

    // ---------------------------------------------------------------- confirm

    private void showResult(String said, Resolver.Result r, MarkerIntent in) {
        if (!r.ok) {
            setStatus("Say again: " + r.sayAgain);
            appendLog("“" + said + "” → " + r.sayAgain);
            return;
        }
        pending = r;
        final GeoPoint gp = new GeoPoint(r.lat, r.lon);

        cardTitle.setText(describeType(r.cotType) + " · " + r.callsign);
        StringBuilder b = new StringBuilder();
        b.append(CoordinateFormatUtilities.formatToString(gp, CoordinateFormat.MGRS));
        for (String d : r.details) b.append('\n').append(d);
        if (!r.remarks.isEmpty()) b.append("\nRemarks: ").append(r.remarks);
        b.append("\nHeard: “").append(said).append('”');
        cardBody.setText(b.toString());

        if (r.warnings.isEmpty()) {
            cardWarn.setVisibility(View.GONE);
        } else {
            StringBuilder w = new StringBuilder();
            for (String s : r.warnings) w.append(w.length() == 0 ? "" : "\n").append("⚠ ").append(s);
            cardWarn.setText(w.toString());
            cardWarn.setVisibility(View.VISIBLE);
        }
        showCard(true);
        showDraft(r, gp);
        setStatus("Draft is on the map. Check it, then Place or Place + send.");
        CameraController.Programmatic.panTo(mapView.getRenderer3(), gp, true);
    }

    private void commit(boolean sendToTeam) {
        final Resolver.Result r = pending;
        if (r == null) return;
        pending = null;
        showCard(false);
        clearDraft();

        final GeoPoint gp = new GeoPoint(r.lat, r.lon);
        PlacePointTool.MarkerCreator mc = new PlacePointTool.MarkerCreator(GeoPointMetaData.wrap(gp));
        mc.setType(r.cotType);
        mc.setCallsign(r.callsign);
        mc.showCotDetails(false);
        final Marker m = mc.placePoint();
        if (m == null) {
            setStatus("ATAK didn't create the marker.");
            return;
        }
        if (!r.remarks.isEmpty()) m.setRemarks(r.remarks);
        m.setMetaString("talkassist.created", "true");

        String where = "your map";
        if (sendToTeam) {
            try {
                CotEvent ev = CotEventFactory.createCotEvent(m);
                CotDispatcher out = CotMapComponent.getExternalDispatcher();
                if (ev == null || out == null) throw new IllegalStateException("no dispatcher");
                out.dispatch(ev);
                where = "your map and the team";
            } catch (Exception e) {
                where = "your map only (sending failed: " + e.getMessage() + ")";
            }
        }
        setStatus("Placed " + r.callsign + " on " + where + ".");
        appendLog(time() + "  " + r.callsign + " → " + where);
        input.setText("");
    }

    /** Short tone so the user knows it's listening without looking. */
    private static void beep() {
        try {
            final android.media.ToneGenerator tg =
                    new android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 80);
            tg.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 150);
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    tg.release();
                }
            }, 400);
        } catch (RuntimeException ignored) {
            // no tone available: listening still works
        }
    }

    // ------------------------------------------------------------------ draft

    /** Drops a DRAFT marker where the report resolved. UI thread. */
    private void showDraft(Resolver.Result r, GeoPoint gp) {
        clearDraft();
        PlacePointTool.MarkerCreator mc = new PlacePointTool.MarkerCreator(GeoPointMetaData.wrap(gp));
        mc.setType(r.cotType);
        mc.setCallsign("DRAFT · " + r.callsign);
        mc.setNeverPersist(true);
        mc.showCotDetails(false);
        draft = mc.placePoint();
        if (draft != null) draft.setMetaBoolean("talkassist.draft", true);
    }

    /** Removes the DRAFT marker, if any. UI thread. */
    private void clearDraft() {
        if (draft != null) {
            draft.removeFromGroup();
            draft = null;
        }
    }

    private void showCard(boolean show) {
        card.setVisibility(show ? View.VISIBLE : View.GONE);
        actions.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Reads ATAK state; only called on the UI thread. The place lookup was already done
     * on the worker thread, so it's handed in rather than queried here.
     */
    private class AtakEnvironment implements Resolver.Environment {
        private final Place place;
        private final String placeError;

        AtakEnvironment(Place place, String placeError) {
            this.place = place;
            this.placeError = placeError;
        }

        @Override
        public Place findPlace(String spoken) {
            if (placeError != null) throw new IllegalStateException(placeError);
            return place;
        }

        @Override
        public double[] self() {
            Marker s = mapView.getSelfMarker();
            if (s == null) return null;
            GeoPoint p = s.getPoint();
            if (p == null || !p.isValid() || (p.getLatitude() == 0 && p.getLongitude() == 0)) return null;
            return new double[]{p.getLatitude(), p.getLongitude()};
        }

        @Override
        public double[] mapCenter() {
            GeoPointMetaData c = mapView.getCenterPoint();
            GeoPoint p = c == null ? null : c.get();
            return p == null || !p.isValid() ? null : new double[]{p.getLatitude(), p.getLongitude()};
        }

        @Override
        public String selfMgrs() {
            double[] s = self();
            return s == null ? null
                    : CoordinateFormatUtilities.formatToString(new GeoPoint(s[0], s[1]), CoordinateFormat.MGRS);
        }

        @Override
        public double[] mgrsToLatLon(String mgrs) {
            GeoPoint p;
            try {
                p = CoordinateFormatUtilities.convert(
                        com.apexcsfc.atak.takassist.talk.resolve.MgrsText.pretty(mgrs), CoordinateFormat.MGRS);
            } catch (IllegalArgumentException e) {
                p = CoordinateFormatUtilities.convert(mgrs, CoordinateFormat.MGRS);
            }
            if (p == null || !p.isValid()) throw new IllegalArgumentException("invalid grid");
            return new double[]{p.getLatitude(), p.getLongitude()};
        }

        @Override
        public double magneticToTrue(double lat, double lon, double magneticDeg) {
            return ATAKUtilities.convertFromMagneticToTrue(new GeoPoint(lat, lon), magneticDeg);
        }
    }

    // ---------------------------------------------------------------- places

    private OfflineArea area;
    private long areaStamp = -1;

    /** The offline map file LLM Assist uses; reopened if it's replaced. Worker thread. */
    private synchronized OfflineArea area() {
        File f = com.apexcsfc.takassist.maps.core.MapPacks.activeFile("area.sqlite");
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
                } catch (Exception ignored) {
                    // reported as "no map data" below
                }
            }
        }
        return area;
    }

    /** Result of a place lookup: the candidates, and whether the user must pick one. */
    private static final class PlaceLookup {
        final List<Place> options;
        final boolean ask;

        PlaceLookup(List<Place> options, boolean ask) {
            this.options = options;
            this.ask = ask;
        }
    }

    private static final int MAX_PLACE_OPTIONS = 4;

    /**
     * Worker thread. A name that matches exactly is used as-is. Anything less certain
     * (a near-spelling, a name that only shares a word, or a partial name with several
     * different matches) comes back with ask = true so the user picks from a list.
     * Throws IllegalStateException when there's no map data.
     */
    private PlaceLookup findPlace(String spoken, double[] from) {
        OfflineArea a = area();
        if (a == null) {
            throw new IllegalStateException("Place names need an offline map (Download/TAKAssist). "
                    + "Give a grid, or a direction and distance, instead.");
        }
        if (from == null) {
            throw new IllegalStateException("I need your GPS fix or the map to look up \"" + spoken
                    + "\". Give a grid instead?");
        }
        final String q = OfflineArea.norm(spoken);
        String[] match = {"none"};
        List<OfflineArea.Hit> hits = a.search(spoken, from[0], from[1], 12, match);

        if ("exact".equals(match[0]) && !hits.isEmpty()) {
            OfflineArea.Hit top = hits.get(0);
            if (OfflineArea.norm(top.name).equals(q)) {
                // Said the full name. Several different places can share it (two "Publix"
                // stores, three "Lake Eve"s): then the user picks one by distance and direction.
                List<Place> same = sameNamePlaces(hits, OfflineArea.norm(top.name));
                return new PlaceLookup(same, same.size() > 1);
            }
            // Part of a name ("Columbia"): ask only if it fits several different places.
            List<Place> opts = distinctPlaces(hits);
            return new PlaceLookup(opts, opts.size() > 1);
        }

        // Not an exact name: near-spellings first, then names sharing a word.
        List<OfflineArea.Hit> all = new ArrayList<>(a.searchFuzzy(spoken, from[0], from[1], 12));
        all.addAll(hits);
        List<Place> opts = distinctPlaces(all);
        return new PlaceLookup(opts, !opts.isEmpty());
    }

    /** Places called exactly that name, one per location (200 m apart or more), in rank order. */
    private static List<Place> sameNamePlaces(List<OfflineArea.Hit> hits, String normName) {
        List<Place> out = new ArrayList<>();
        for (OfflineArea.Hit h : hits) {
            if (out.size() >= MAX_PLACE_OPTIONS) break;
            if (!OfflineArea.norm(h.name).equals(normName)) continue;
            boolean dup = false;
            for (Place p : out) {
                if (com.apexcsfc.atak.takassist.talk.resolve.Geodesy.inverse(p.lat, p.lon, h.lat, h.lon)[0] < 200) {
                    dup = true;
                    break;
                }
            }
            if (!dup) out.add(toPlace(h, true));
        }
        return out;
    }

    /** One option per place name (the best-ranked of each), in rank order. */
    private static List<Place> distinctPlaces(List<OfflineArea.Hit> hits) {
        List<Place> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (OfflineArea.Hit h : hits) {
            if (out.size() >= MAX_PLACE_OPTIONS) break;
            if (seen.add(OfflineArea.norm(h.name))) out.add(toPlace(h, false));
        }
        return out;
    }

    /** "Did you mean…?" list. Picking one finishes the report; None of these asks again. UI thread. */
    private void askWhichPlace(final String said, final MarkerIntent in, final PlaceLookup lookup,
                               final double[] from) {
        final List<Place> opts = lookup.options;
        final String[] labels = new String[opts.size()];
        for (int i = 0; i < opts.size(); i++) {
            Place p = opts.get(i);
            String where = "";
            if (from != null) {
                double[] db = com.apexcsfc.atak.takassist.talk.resolve.Geodesy.inverse(from[0], from[1], p.lat, p.lon);
                where = " · " + (db[0] < 1000 ? Math.round(db[0]) + " m " : String.format(Locale.US, "%.1f km ", db[0] / 1000))
                        + compass8(db[1]) + " of you";
            }
            labels[i] = p.name + "\n" + (p.kind.isEmpty() ? "place" : p.kind) + where;
        }
        setStatus("Which place did you mean by \"" + in.place + "\"?");
        new AlertDialog.Builder(mapView.getContext())
                .setTitle("Which place did you mean?")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        Place p = opts.get(which);
                        // The user chose it, so it no longer needs a "check the match" warning.
                        finishReport(said, in, new Place(p.name, p.kind, p.lat, p.lon, true), null);
                    }
                })
                .setNegativeButton("None of these", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        setStatus("Say it again with a different name, or give a grid or a direction and distance.");
                    }
                })
                .setOnCancelListener(new DialogInterface.OnCancelListener() {
                    @Override
                    public void onCancel(DialogInterface d) {
                        setStatus("Cancelled. Nothing was placed. Say or type a report.");
                    }
                })
                .show();
    }

    private static String compass8(double deg) {
        final String[] p = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
        return p[(int) Math.round(com.apexcsfc.atak.takassist.talk.resolve.Geodesy.normalizeDeg(deg) / 45.0) % 8];
    }

    // ------------------------------------------------------------ directions

    private static final String ACTION_START_NAV = "com.atakmap.android.maps.START_NAV";
    private static final String ACTION_END_NAV = "com.atakmap.android.maps.END_NAV";
    private static final String ROUTE_UID_PREFIX = "talkassist-route-";
    private static final String ROUTE_REMARKS = "Directions from TAK Assist";
    /** Most recent Talk Assist route; replaced by the next one so routes don't pile up. */
    private Route lastRoute;

    /** "Driving directions to the tank": find where, route by road offline, start ATAK navigation. UI thread. */
    private void startDirections(String said, MarkerIntent in, Place place, String placeError) {
        clearDraft();
        pending = null;
        showCard(false);
        final AtakEnvironment env = new AtakEnvironment(place, placeError);
        final double[] self = env.self();
        if (self == null) {
            setStatus("Directions need your GPS fix, and I don't have one yet.");
            return;
        }
        final String mode = in.travelMode == null ? "car" : in.travelMode;

        // Where they said it is (if they did), worked out like a report.
        double[] hint = null;
        String hintError = null;
        final boolean hasLocation = in.locationMode != null && !in.locationMode.trim().isEmpty();
        if (hasLocation || in.place != null) {
            MarkerIntent where = locationOnly(in);
            if (!hasLocation) where.locationMode = "place";
            Resolver.Result r = new Resolver(env, defaultNorth()).resolve(where);
            if (r.ok) hint = new double[]{r.lat, r.lon};
            else hintError = r.sayAgain;
        }

        // A named marker comes first: "the tank", "the rally point".
        final String label = in.label == null ? "" : in.label.trim();
        if (!label.isEmpty()) {
            final TargetFinder.Found f = TargetFinder.find(mapItems(), label, in.affiliation, hint, self);
            if (!f.options.isEmpty() && !f.ask) {
                TargetFinder.Item t = f.options.get(0);
                routeTo(t.name, t.lat, t.lon, mode, self);
                return;
            }
            if (f.ask) {
                final String[] labels = new String[f.options.size()];
                for (int i = 0; i < labels.length; i++) labels[i] = targetLabel(f.options.get(i), self);
                setStatus("Directions to which " + label + "?");
                new AlertDialog.Builder(mapView.getContext())
                        .setTitle("Directions to which marker?")
                        .setItems(labels, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                TargetFinder.Item t = f.options.get(which);
                                routeTo(t.name, t.lat, t.lon, mode, self);
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
                return;
            }
            if (hint == null && place != null) {
                routeTo(place.name, place.lat, place.lon, mode, self); // a place by that name
                return;
            }
            if (hint == null) {
                setStatus("I can't find a marker or place called \"" + label + "\". Give a grid instead?");
                return;
            }
        }
        if (hint != null) {
            routeTo(place != null && in.locationMode == null ? place.name
                    : CoordinateFormatUtilities.formatToString(new GeoPoint(hint[0], hint[1]), CoordinateFormat.MGRS),
                    hint[0], hint[1], mode, self);
            return;
        }
        setStatus(hintError != null ? "Directions to where? " + hintError
                : "Directions to where? Name a marker or place, or give a grid.");
    }

    /** Routes by road on the worker thread, then builds the ATAK route and starts navigation. */
    private void routeTo(final String destName, final double toLat, final double toLon, final String mode,
                         final double[] from) {
        if (com.apexcsfc.atak.takassist.talk.resolve.Geodesy.inverse(from[0], from[1], toLat, toLon)[0] < 50) {
            setStatus("That's where you are now, so there's nothing to route. Name a marker or place.");
            return;
        }
        setStatus("Working out the route to " + destName + "…");
        probe.execute(new Runnable() {
            @Override
            public void run() {
                String err = null;
                OfflineRouter.Route rt = null;
                final OfflineRouter router = router();
                final int m = "foot".equals(mode) ? OfflineRouter.FOOT : "bike".equals(mode) ? OfflineRouter.BIKE
                        : OfflineRouter.CAR;
                if (router == null) {
                    err = "Road directions need an offline map (Download/TAKAssist). "
                            + "Use Bloodhound for a straight-line track instead.";
                } else if (!router.covers(from[0], from[1]) || !router.covers(toLat, toLon)) {
                    err = "Your offline road data doesn't cover " + (router.covers(from[0], from[1])
                            ? destName : "where you are") + ". Use Bloodhound for a straight-line track instead.";
                } else {
                    try {
                        rt = router.route(from[0], from[1], toLat, toLon, m);
                    } catch (Exception e) {
                        err = "Routing failed: " + e.getMessage();
                    }
                    if (rt == null && err == null) {
                        err = "No " + modeWord(mode) + " route found to " + destName + ". It may be off the "
                                + "roads in your offline map data (e.g. an airfield, or outside the area it was "
                                + "built for). Bloodhound gives a straight-line track instead.";
                    }
                }
                final String fErr = err;
                final OfflineRouter.Route fRt = rt;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (fErr != null) setStatus(fErr);
                        else showRoute(destName, toLat, toLon, mode, fRt);
                    }
                });
            }
        });
    }

    /** Builds a real ATAK route (so ATAK's navigation, cues and route tools work) and starts navigating. */
    private void showRoute(String destName, double toLat, double toLon, String mode, OfflineRouter.Route rt) {
        // Directions replace a straight-line track: turn off any Bloodhound that's still running.
        sendBloodhoundOff();
        if (lastRoute != null) {
            AtakBroadcast.getInstance().sendBroadcast(new Intent(ACTION_END_NAV));
            lastRoute.removeFromGroup();
            lastRoute = null;
        }
        final List<double[]> pts = RouteShape.simplify(rt.points, 8, 250);
        final String title = "To " + destName;
        // The UID prefix and remarks are saved with the route, so it's recognisable after ATAK restarts.
        final Route route = new Route(mapView, title, 0xFF00B4FF, "CP",
                ROUTE_UID_PREFIX + java.util.UUID.randomUUID().toString());
        route.setRemarks(ROUTE_REMARKS);
        final List<PointMapItem> marks = new ArrayList<>();
        for (int i = 0; i < pts.size(); i++) {
            final GeoPoint gp = new GeoPoint(pts.get(i)[0], pts.get(i)[1]);
            if (i == 0 || i == pts.size() - 1) {
                Marker wp = Route.createWayPoint(GeoPointMetaData.wrap(gp), java.util.UUID.randomUUID().toString());
                if (i == pts.size() - 1) {
                    wp.setMetaString("callsign", destName);
                    wp.setTitle(destName);
                }
                marks.add(wp);
            } else {
                marks.add(Route.createControlPoint(gp));
            }
        }
        route.addMarkers(0, marks);
        route.setRouteMethod("foot".equals(mode) ? "Walking" : "Driving");
        route.setMetaBoolean("talkassist.created", true);
        RouteMapReceiver.getInstance().getRouteGroup().addItem(route);
        route.persist(mapView.getMapEventDispatcher(), null, getClass());
        lastRoute = route;

        // Same message ATAK's own "Navigate" button sends: start at the route's first waypoint.
        Intent nav = new Intent(ACTION_START_NAV);
        nav.putExtra("uid", marks.get(0).getUID());
        AtakBroadcast.getInstance().sendBroadcast(nav);

        final long min = Math.max(1, Math.round(rt.seconds / 60.0));
        cardTitle.setText(capitalize(modeWord(mode)) + " route to " + destName);
        StringBuilder b = new StringBuilder()
                .append(String.format(Locale.US, "%.1f km · about %d min", rt.distanceM / 1000.0, min));
        int n = 0;
        for (OfflineRouter.Step st : rt.steps) {
            if (n++ >= 6) {
                b.append("\n…and ").append(rt.steps.size() - 6).append(" more steps");
                break;
            }
            b.append("\n• ").append(st.text)
                    .append(st.distanceM >= 1 ? String.format(Locale.US, " (%s)", distText(st.distanceM)) : "");
        }
        b.append("\nATAK navigation started: follow its cues. Say \"stop navigation\" to end it.");
        cardBody.setText(b.toString());
        if (rt.snapFromM > 150 || rt.snapToM > 150) {
            cardWarn.setText("⚠ The start or end is " + Math.round(Math.max(rt.snapFromM, rt.snapToM))
                    + " m from the nearest " + modeWord(mode) + " road.");
            cardWarn.setVisibility(View.VISIBLE);
        } else {
            cardWarn.setVisibility(View.GONE);
        }
        card.setVisibility(View.VISIBLE);
        actions.setVisibility(View.GONE);
        setStatus("Route ready and navigation started.");
        appendLog(time() + "  route → " + destName);
        input.setText("");
    }

    /** Ends ATAK navigation and removes Talk Assist's route(s) from the map. UI thread. */
    private void stopNavigation() {
        clearDraft();
        showCard(false);
        AtakBroadcast.getInstance().sendBroadcast(new Intent(ACTION_END_NAV));
        input.setText("");

        // Talk Assist's routes, recognised by UID prefix or remarks (both survive a restart).
        final List<Route> ours = new ArrayList<>();
        // Older routes made before routes were labelled: named "To …"; removed only if confirmed.
        final List<Route> maybe = new ArrayList<>();
        for (Route r : allRoutes()) {
            final String uid = r.getUID() == null ? "" : r.getUID();
            final String rem = r.getRemarks() == null ? "" : r.getRemarks();
            final String title = r.getTitle() == null ? "" : r.getTitle();
            if (uid.startsWith(ROUTE_UID_PREFIX) || rem.contains(ROUTE_REMARKS)
                    || r.getMetaBoolean("talkassist.created", false) || r == lastRoute) {
                ours.add(r);
            } else if (title.startsWith("To ")) {
                maybe.add(r);
            }
        }
        for (Route r : ours) r.removeFromGroup();
        lastRoute = null;

        if (maybe.isEmpty()) {
            setStatus(ours.isEmpty() ? "Navigation stopped. There was no TAK Assist route to remove."
                    : "Directions cleared.");
            appendLog(time() + "  directions cleared");
            return;
        }
        final String[] names = new String[maybe.size()];
        for (int i = 0; i < names.length; i++) names[i] = maybe.get(i).getTitle();
        setStatus("Found older routes that may be TAK Assist's.");
        new AlertDialog.Builder(mapView.getContext())
                .setTitle("Remove these routes too?")
                .setMessage(android.text.TextUtils.join("\n", names))
                .setPositiveButton("Remove", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        for (Route r : maybe) r.removeFromGroup();
                        setStatus("Directions cleared.");
                        appendLog(time() + "  directions cleared");
                    }
                })
                .setNegativeButton("Keep them", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        setStatus("Navigation stopped. Those routes were kept.");
                    }
                })
                .show();
    }

    /** Every route on the map. UI thread. */
    private List<Route> allRoutes() {
        final List<Route> out = new ArrayList<>();
        final RouteMapReceiver rmr = RouteMapReceiver.getInstance();
        final MapGroup g = rmr == null ? mapView.getRootGroup() : rmr.getRouteGroup();
        (g == null ? mapView.getRootGroup() : g).deepForEachItem(new MapGroup.MapItemsCallback() {
            @Override
            public boolean onItemFunction(MapItem item) {
                if (item instanceof Route) out.add((Route) item);
                return false;
            }
        });
        return out;
    }

    private OfflineRouter router;
    private long routerStamp = -1;

    /** The offline road network LLM Assist uses; reopened if replaced. Worker thread. */
    private synchronized OfflineRouter router() {
        File f = com.apexcsfc.takassist.maps.core.MapPacks.activeFile("route.bin");
        long stamp = f.isFile() ? f.lastModified() ^ f.length() : 0;
        if (stamp != routerStamp) {
            router = null;
            routerStamp = stamp;
            if (stamp != 0) {
                try {
                    router = OfflineRouter.open(f);
                } catch (Exception ignored) {
                    // reported as missing road data
                }
            }
        }
        return router;
    }

    /** All point markers on the map except you and Talk Assist drafts. UI thread. */
    private List<TargetFinder.Item> mapItems() {
        final Marker me = mapView.getSelfMarker();
        final List<TargetFinder.Item> items = new ArrayList<>();
        mapView.getRootGroup().deepForEachItem(new MapGroup.MapItemsCallback() {
            @Override
            public boolean onItemFunction(MapItem item) {
                if (item instanceof PointMapItem && item != me
                        && !item.getMetaBoolean("talkassist.draft", false)
                        && !Route.WAYPOINT_TYPE.equals(item.getType())
                        && !Route.CONTROLPOINT_TYPE.equals(item.getType())) {
                    GeoPoint p = ((PointMapItem) item).getPoint();
                    if (p != null && p.isValid()) {
                        items.add(new TargetFinder.Item(item.getUID(), item.getTitle(), item.getType(),
                                p.getLatitude(), p.getLongitude()));
                    }
                }
                return false;
            }
        });
        return items;
    }

    private static String modeWord(String mode) {
        return "foot".equals(mode) ? "walking" : "bike".equals(mode) ? "bike" : "driving";
    }

    private static String distText(double m) {
        return m < 1000 ? Math.round(m) + " m" : String.format(Locale.US, "%.1f km", m / 1000.0);
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ------------------------------------------------------- MEDEVAC / SALUTE

    /** ATAK's MEDEVAC screen; opens for the CASEVAC marker with this uid. */
    private static final String ACTION_MED_LINE = "com.atakmap.android.MED_LINE";

    /**
     * Builds ATAK's own CASEVAC marker with every 9-line field that was heard, then opens
     * ATAK's MEDEVAC screen on it. Sending stays in ATAK, after the user has checked it.
     */
    private void createMedevac(String said, MarkerIntent in, Place place, String placeError, Medevac m) {
        clearDraft();
        pending = null;
        showCard(false);
        if (m == null) m = new Medevac();

        // Line 1: the pickup site. No location said means here.
        final MarkerIntent where = locationOnly(in);
        if (where.locationMode == null || where.locationMode.trim().isEmpty()) where.locationMode = "self";
        final Resolver.Result r = new Resolver(new AtakEnvironment(place, placeError), defaultNorth()).resolve(where);
        if (!r.ok) {
            setStatus("Where's the pickup site? " + r.sayAgain);
            return;
        }

        final String title = "MEDEVAC " + new SimpleDateFormat("HHmm", Locale.US).format(new Date());
        final GeoPoint gp = new GeoPoint(r.lat, r.lon);
        PlacePointTool.MarkerCreator mc = new PlacePointTool.MarkerCreator(GeoPointMetaData.wrap(gp));
        mc.setType(Medevac.COT_TYPE);
        mc.setCallsign(title);
        mc.showCotDetails(false);
        final Marker mk = mc.placePoint();
        if (mk == null) {
            setStatus("ATAK didn't create the MEDEVAC marker.");
            return;
        }
        for (java.util.Map.Entry<String, Object> e : m.toMeta(title).entrySet()) {
            if (e.getValue() instanceof Boolean) mk.setMetaBoolean(e.getKey(), (Boolean) e.getValue());
            else mk.setMetaString(e.getKey(), String.valueOf(e.getValue()));
        }
        if (m.callsign != null) {
            // ATAK's Line 2 call sign is the sender's own; keep a spoken one in the remarks.
            String rem = mk.getMetaString("medline_remarks", "");
            mk.setMetaString("medline_remarks", ("Call sign: " + m.callsign + ". " + rem).trim());
        }
        mk.setMetaString("talkassist.created", "true");

        Intent open = new Intent(ACTION_MED_LINE);
        open.putExtra("targetUID", mk.getUID());
        AtakBroadcast.getInstance().sendBroadcast(open);

        final int n = m.patients();
        cardTitle.setText("MEDEVAC" + (n > 0 ? " · " + n + (n == 1 ? " patient" : " patients") : ""));
        StringBuilder b = new StringBuilder("Pickup: ")
                .append(CoordinateFormatUtilities.formatToString(gp, CoordinateFormat.MGRS));
        for (String d : r.details) b.append('\n').append(d);
        b.append("\nATAK's MEDEVAC screen is open with what you said filled in. "
                + "Check every line, then send it from there.");
        b.append("\nHeard: “").append(said).append('”');
        cardBody.setText(b.toString());
        final List<String> missing = m.missingLines();
        if (missing.isEmpty()) {
            cardWarn.setVisibility(View.GONE);
        } else {
            StringBuilder w = new StringBuilder("Not heard, fill in on ATAK's screen:");
            for (String s : missing) w.append("\n• ").append(s);
            cardWarn.setText(w.toString());
            cardWarn.setVisibility(View.VISIBLE);
        }
        card.setVisibility(View.VISIBLE);
        actions.setVisibility(View.GONE);
        setStatus("MEDEVAC started. It hasn't been sent: review and send it in ATAK's screen.");
        appendLog(time() + "  " + title + " (not sent)");
        input.setText("");
    }

    /** SALUTE: a marker at the enemy's location with the report as remarks, through the usual confirm card. */
    private void showSalute(String said, MarkerIntent in, Place place, String placeError, Salute s) {
        if (s == null) s = new Salute();
        final MarkerIntent where = locationOnly(in);
        where.affiliation = in.affiliation == null || "unknown".equals(in.affiliation) || "any".equals(in.affiliation)
                ? "hostile" : in.affiliation; // a SALUTE is about the enemy unless said otherwise
        where.dimension = in.dimension;
        where.label = in.label != null ? in.label : "SALUTE";
        final Resolver.Result r = new Resolver(new AtakEnvironment(place, placeError), defaultNorth()).resolve(where);
        if (!r.ok) {
            setStatus("Say again: where are they? " + r.sayAgain);
            return;
        }
        final String mgrs = CoordinateFormatUtilities.formatToString(new GeoPoint(r.lat, r.lon), CoordinateFormat.MGRS);
        final String dtg = new SimpleDateFormat("ddHHmm'L' MMM yy", Locale.US).format(new Date()).toUpperCase(Locale.US);
        final List<String> notes = new ArrayList<>();
        if (!s.missing().isEmpty()) {
            notes.add("SALUTE lines not reported: " + android.text.TextUtils.join(", ", s.missing()) + ".");
        }
        showResult(said, r.withRemarks(s.remarks(mgrs, dtg), notes), in);
    }

    /** Just the location part of an intent, as a place_marker request. */
    private static MarkerIntent locationOnly(MarkerIntent in) {
        MarkerIntent w = new MarkerIntent();
        w.action = "place_marker";
        w.affiliation = "friendly";
        w.locationMode = in.locationMode;
        w.refPoint = in.refPoint;
        w.place = in.place;
        w.bearingDeg = in.bearingDeg;
        w.cardinal = in.cardinal;
        w.bearingRef = in.bearingRef;
        w.distance = in.distance;
        w.distanceUnit = in.distanceUnit;
        w.grid = in.grid;
        return w;
    }

    // -------------------------------------------------------------- emergency

    /** 911 / In Contact / Ring the Bell go to the whole team at once, so always confirm first. */
    private void confirmEmergency(String type) {
        clearDraft();
        pending = null;
        showCard(false);
        final EmergencyManager em = EmergencyManager.getInstance();
        if (em == null) {
            setStatus("ATAK's emergency alerts aren't available right now.");
            return;
        }
        if ("cancel".equals(type)) {
            if (!Boolean.TRUE.equals(em.isEmergencyOn())) {
                setStatus("No alert is active, so there's nothing to cancel.");
                return;
            }
            final EmergencyType active = em.getEmergencyType();
            new AlertDialog.Builder(mapView.getContext())
                    .setTitle("Cancel the " + (active == null ? "active" : active.getDescription()) + " alert?")
                    .setMessage("Your team will be told the alert is cancelled.")
                    .setPositiveButton("Cancel alert", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            em.cancelRepeat();
                            setStatus("Alert cancelled.");
                            appendLog(time() + "  alert cancelled");
                        }
                    })
                    .setNegativeButton("Keep it on", null)
                    .show();
            return;
        }
        final EmergencyType et;
        if ("in_contact".equals(type)) et = EmergencyType.TroopsInContact;
        else if ("ring_the_bell".equals(type)) et = EmergencyType.RingTheBell;
        else if ("nine_one_one".equals(type)) et = EmergencyType.NineOneOne;
        else {
            setStatus("Which alert? Say 911, in contact, or ring the bell.");
            return;
        }
        new AlertDialog.Builder(mapView.getContext())
                .setTitle("Send " + et.getDescription().toUpperCase(Locale.US) + " alert?")
                .setMessage("This goes to your whole team right away, with your position, and keeps "
                        + "repeating until it's cancelled.")
                .setPositiveButton("Send alert", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        em.initiateRepeat(et, false);
                        setStatus(et.getDescription() + " alert sent. Say \"cancel alert\" to stop it.");
                        appendLog(time() + "  " + et.getDescription() + " alert sent");
                    }
                })
                .setNegativeButton("Don't send", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        setStatus("Alert not sent.");
                    }
                })
                .show();
    }

    /** setStatus from the worker thread. */
    private void postStatus(final String s) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                setStatus(s);
            }
        });
    }

    // ------------------------------------------------------------ bloodhound

    /** ATAK's Bloodhound tool: starts tracking from you to the marker with this uid. */
    private static final String ACTION_BLOOD_HOUND = "com.atakmap.android.toolbars.BLOOD_HOUND";

    /** "Bloodhound to the truck 2 km south of me": find that marker, then start ATAK's tool. UI thread. */
    private void startBloodhound(String said, MarkerIntent in, Place place, String placeError) {
        clearDraft();
        pending = null;
        showCard(false);

        final AtakEnvironment env = new AtakEnvironment(place, placeError);
        final double[] self = env.self();
        if (self == null) {
            setStatus("Bloodhound needs your GPS fix, and I don't have one yet.");
            return;
        }

        // Where they said the target is, worked out the same way as a report.
        double[] hint = null;
        if (in.locationMode != null && !in.locationMode.trim().isEmpty()) {
            MarkerIntent where = new MarkerIntent();
            where.action = "place_marker";
            where.locationMode = in.locationMode;
            where.refPoint = in.refPoint;
            where.place = in.place;
            where.bearingDeg = in.bearingDeg;
            where.cardinal = in.cardinal;
            where.bearingRef = in.bearingRef;
            where.distance = in.distance;
            where.distanceUnit = in.distanceUnit;
            where.grid = in.grid;
            Resolver.Result r = new Resolver(env, defaultNorth()).resolve(where);
            if (r.ok) hint = new double[]{r.lat, r.lon};
        }

        final Marker me = mapView.getSelfMarker();
        final List<TargetFinder.Item> items = new ArrayList<>();
        mapView.getRootGroup().deepForEachItem(new MapGroup.MapItemsCallback() {
            @Override
            public boolean onItemFunction(MapItem item) {
                if (item instanceof PointMapItem && item != me
                        && !item.getMetaBoolean("talkassist.draft", false)) {
                    GeoPoint p = ((PointMapItem) item).getPoint();
                    if (p != null && p.isValid()) {
                        items.add(new TargetFinder.Item(item.getUID(), item.getTitle(), item.getType(),
                                p.getLatitude(), p.getLongitude()));
                    }
                }
                return false;
            }
        });

        final TargetFinder.Found f = TargetFinder.find(items, in.label, in.affiliation, hint, self);
        final String what = in.label == null || in.label.trim().isEmpty() ? "that marker" : "\"" + in.label.trim() + "\"";
        if (f.options.isEmpty()) {
            setStatus("I can't find " + what + (hint != null ? " near there" : "") + " on the map. "
                    + "Bloodhound only works on a marker that's already placed.");
            return;
        }
        if (!f.ask) {
            houndTo(f.options.get(0), self);
            return;
        }
        final String[] labels = new String[f.options.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = targetLabel(f.options.get(i), self);
        setStatus("Which " + what + " should Bloodhound go to?");
        new AlertDialog.Builder(mapView.getContext())
                .setTitle("Bloodhound to which marker?")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        houndTo(f.options.get(which), self);
                    }
                })
                .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        setStatus("Bloodhound not started.");
                    }
                })
                .show();
    }

    /** Stops ATAK's Bloodhound and removes its line, readout and widgets. UI thread. */
    private void stopBloodhound() {
        clearDraft();
        showCard(false);
        setStatus(sendBloodhoundOff() ? "Bloodhound stopped." : "Asked ATAK to stop Bloodhound. If the "
                + "green line is still there, tap ATAK's Bloodhound button to turn it off.");
        appendLog(time() + "  bloodhound stopped");
        input.setText("");
    }

    /**
     * Calls stop() on ATAK's own Bloodhound tool, the same thing its button does when it's on;
     * stop() always removes the line, HUD and widgets. The tool is found as the active tool, or
     * in ATAK's tool list by its type (not by field name, which a release build may rename).
     * Returns false if the tool couldn't be reached and a fallback message was sent instead.
     */
    private static boolean sendBloodhoundOff() {
        final BloodHoundTool bh = findBloodhoundTool();
        if (bh != null) {
            try {
                bh.stop();
                bh.setActive(false);
                return true;
            } catch (RuntimeException ignored) {
                // fall through to the broadcast
            }
        }
        Intent end = new Intent("com.atakmap.android.maps.toolbar.END_TOOL");
        end.putExtra("tool", BloodHoundTool.TOOL_IDENTIFIER);
        AtakBroadcast.getInstance().sendBroadcast(end);
        return false;
    }

    /** Closes whatever ATAK tool is active (a toolbar or panel left open) and says which it was. */
    private void closeActiveTool() {
        clearDraft();
        showCard(false);
        final ToolManagerBroadcastReceiver tm = ToolManagerBroadcastReceiver.getInstance();
        final Tool t = tm == null ? null : tm.getActiveTool();
        if (t == null) {
            setStatus("No ATAK tool is open. If something is still showing, tap it and send a screenshot "
                    + "so it can be identified.");
            return;
        }
        final String id = t.getIdentifier();
        if (t instanceof BloodHoundTool) {
            ((BloodHoundTool) t).stop(); // removes the line, not just the toolbar
        }
        tm.endCurrentTool();
        setStatus("Closed ATAK tool: " + (id == null ? "(unnamed)" : id.substring(id.lastIndexOf('.') + 1)) + ".");
        appendLog(time() + "  closed " + id);
        input.setText("");
    }

    private static BloodHoundTool findBloodhoundTool() {
        final ToolManagerBroadcastReceiver tm = ToolManagerBroadcastReceiver.getInstance();
        if (tm == null) return null;
        final Tool active = tm.getActiveTool();
        if (active instanceof BloodHoundTool) return (BloodHoundTool) active;
        try {
            for (java.lang.reflect.Field f : ToolManagerBroadcastReceiver.class.getDeclaredFields()) {
                if (!java.util.Map.class.isAssignableFrom(f.getType())) continue;
                f.setAccessible(true);
                Object m = f.get(tm);
                if (!(m instanceof java.util.Map)) continue;
                for (Object v : ((java.util.Map<?, ?>) m).values()) {
                    if (v instanceof BloodHoundTool) return (BloodHoundTool) v;
                }
            }
        } catch (Exception ignored) {
            // not reachable on this ATAK build
        }
        return null;
    }

    private void houndTo(TargetFinder.Item t, double[] self) {
        // A straight-line track replaces route navigation, so the two lines never overlap.
        AtakBroadcast.getInstance().sendBroadcast(new Intent(ACTION_END_NAV));
        Intent i = new Intent(ACTION_BLOOD_HOUND);
        i.putExtra("uid", t.uid);
        AtakBroadcast.getInstance().sendBroadcast(i);
        setStatus("Bloodhound to " + targetLabel(t, self).replace("\n", ", ")
                + ". ATAK shows the bearing, range and ETA on the map; tap its X to stop.");
        appendLog(time() + "  bloodhound → " + t.name);
        input.setText("");
    }

    /** "truck\nhostile · 2.0 km S of you". */
    private static String targetLabel(TargetFinder.Item t, double[] self) {
        String side;
        switch (t.cotType.length() > 2 && t.cotType.startsWith("a-") ? t.cotType.charAt(2) : '?') {
            case 'h': side = "hostile"; break;
            case 'f': side = "friendly"; break;
            case 'n': side = "neutral"; break;
            case 'u': side = "unknown"; break;
            default:  side = "marker"; break;
        }
        String where = "";
        if (self != null) {
            double[] db = com.apexcsfc.atak.takassist.talk.resolve.Geodesy.inverse(self[0], self[1], t.lat, t.lon);
            where = " · " + (db[0] < 1000 ? Math.round(db[0]) + " m " : String.format(Locale.US, "%.1f km ", db[0] / 1000))
                    + compass8(db[1]) + " of you";
        }
        return t.name + "\n" + side + where;
    }

    // ------------------------------------------------------------------ maps

    /** ATAK's own "select this map" broadcast (LayersManagerBroadcastReceiver). */
    private static final String ACTION_SELECT_LAYER = "com.atakmap.android.maps.SELECT_LAYER";

    /** Names of the map sources in ATAK's map-source folder. Worker thread. */
    private static List<String> installedMapNames() {
        final List<String> names = new ArrayList<>();
        final java.util.Set<String> seen = new java.util.HashSet<>();
        final File root = new File(android.os.Environment.getExternalStorageDirectory(), "atak/imagery/mobile");
        for (File dir : new File[]{new File(root, "mapsources"), root}) {
            File[] files = dir.listFiles();
            if (files == null) continue;
            for (File f : files) {
                if (!f.isFile() || !f.getName().toLowerCase(Locale.US).endsWith(".xml") || f.length() > 256_000) continue;
                try {
                    String n = MapNames.nameFromXml(new String(java.nio.file.Files.readAllBytes(f.toPath()),
                            java.nio.charset.StandardCharsets.UTF_8));
                    if (n != null && seen.add(n.toLowerCase(Locale.US))) names.add(n);
                } catch (Exception ignored) {
                    // unreadable file: skip it
                }
            }
        }
        Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    /** "Switch to NRL DRG": match the name, ask if unsure, then tell ATAK. UI thread. */
    private void switchMap(String spoken, final List<String> names) {
        clearDraft();
        pending = null;
        showCard(false);
        final String asked = spoken == null ? "" : spoken.trim();
        if (asked.isEmpty()) {
            setStatus("Which map? Say its name, for example \"NRL DRG\".");
            return;
        }
        final List<String> all = mergeMapNames(names, atakMapNames());
        if (all.isEmpty()) {
            // Can't see the list; let ATAK try the name as said.
            selectMap(asked, true);
            return;
        }
        switchMapAmong(asked, all);
    }

    /** Matches the spoken name against every map name; asks whenever more than one could fit. UI thread. */
    private void switchMapAmong(final String asked, final List<String> names) {
        final MapNames.Match m = MapNames.match(asked, names);
        if (m.options.isEmpty()) {
            // Nothing matched: let the user pick from every map ATAK has instead of guessing.
            askWhichMap("I couldn't find \"" + asked + "\". Which map?", names, asked);
            return;
        }
        if (!m.ask) {
            selectMap(m.options.get(0), false);
            return;
        }
        askWhichMap("Which map did you mean by \"" + asked + "\"?", m.options, asked);
    }

    /** Map picker. Picking one switches; None of these leaves the map alone. UI thread. */
    private void askWhichMap(String question, List<String> options, final String asked) {
        final String[] labels = options.toArray(new String[0]);
        setStatus(question);
        new AlertDialog.Builder(mapView.getContext())
                .setTitle(options.size() > 5 ? "Pick a map" : "Which map did you mean?")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        selectMap(labels[which], false);
                    }
                })
                .setNegativeButton("None of these", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        setStatus("Map not changed. Say the name again, as shown in Maps & Favorites.");
                    }
                })
                .show();
    }

    /**
     * Every map ATAK can show, as named in Maps & Favorites: the selections offered by the
     * raster layers in the map stack (built-in sources such as the FAA charts included). UI thread.
     */
    private List<String> atakMapNames() {
        final List<String> out = new ArrayList<>();
        try {
            for (com.atakmap.map.layer.Layer l : mapView.getLayers(MapView.RenderStack.MAP_LAYERS)) {
                collectMapNames(l, out, 0);
            }
        } catch (Throwable ignored) {
            // older/newer ATAK without this API: fall back to the map-source files
        }
        return out;
    }

    private static void collectMapNames(com.atakmap.map.layer.Layer l, List<String> out, int depth) {
        if (l == null || depth > 6) return;
        if (l instanceof com.atakmap.map.layer.raster.RasterLayer2) {
            try {
                java.util.Collection<String> opts = ((com.atakmap.map.layer.raster.RasterLayer2) l).getSelectionOptions();
                if (opts != null) out.addAll(opts);
            } catch (Throwable ignored) {
            }
        }
        if (l instanceof com.atakmap.android.maps.CardLayer) {
            for (com.atakmap.map.layer.Layer c : ((com.atakmap.android.maps.CardLayer) l).getLayers()) {
                collectMapNames(c, out, depth + 1);
            }
        } else if (l instanceof com.atakmap.map.layer.MultiLayer) {
            for (com.atakmap.map.layer.Layer c : ((com.atakmap.map.layer.MultiLayer) l).getLayers()) {
                collectMapNames(c, out, depth + 1);
            }
        } else if (l instanceof com.atakmap.map.layer.ProxyLayer) {
            collectMapNames(((com.atakmap.map.layer.ProxyLayer) l).get(), out, depth + 1);
        }
    }

    /** Both lists, without case-insensitive duplicates, sorted. */
    private static List<String> mergeMapNames(List<String> a, List<String> b) {
        final List<String> out = new ArrayList<>();
        final java.util.Set<String> seen = new java.util.HashSet<>();
        for (List<String> l : java.util.Arrays.asList(a, b)) {
            if (l == null) continue;
            for (String n : l) {
                if (n != null && !n.trim().isEmpty() && seen.add(n.trim().toLowerCase(Locale.US))) out.add(n.trim());
            }
        }
        Collections.sort(out, String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    private void selectMap(String name, boolean unverified) {
        Intent i = new Intent(ACTION_SELECT_LAYER);
        i.putExtra("selection", name);
        // Keep this map instead of letting ATAK auto-switch as you pan, like picking it by hand.
        i.putExtra("isLocked", true);
        AtakBroadcast.getInstance().sendBroadcast(i);
        // ATAK doesn't answer this broadcast, so don't claim more than we know.
        setStatus(unverified
                ? "Asked ATAK to switch to \"" + name + "\". I couldn't find that name in your map "
                        + "sources, so if the map doesn't change, check the name in Maps & Favorites."
                : "Switching the map to " + name + ".");
        appendLog(time() + "  map → " + name);
        input.setText("");
    }

    // ---------------------------------------------------------------- locate

    /** "Where is the nearest enemy?", "Where's the truck?", "Where am I?". From the map itself. UI thread. */
    private void answerLocate(String said, MarkerIntent in) {
        clearDraft();
        pending = null;
        final double[] self = new AtakEnvironment(null, null).self();
        final String lower = said.toLowerCase(Locale.US);
        if (Keywords.isSelfRequest(said) || ("self".equals(in.locationMode) && in.label == null
                && (in.affiliation == null || "any".equals(in.affiliation) || "friendly".equals(in.affiliation)))) {
            if (self == null) {
                setStatus("I don't have a GPS fix for you yet.");
                return;
            }
            final GeoPoint me = new GeoPoint(self[0], self[1]);
            cardTitle.setText("You are here");
            cardBody.setText(CoordinateFormatUtilities.formatToString(me, CoordinateFormat.MGRS)
                    + String.format(Locale.US, "\n%.5f, %.5f", self[0], self[1]));
            cardWarn.setVisibility(View.GONE);
            card.setVisibility(View.VISIBLE);
            actions.setVisibility(View.GONE);
            zoomTo(me);
            setStatus("Your position.");
            input.setText("");
            return;
        }

        final String side = in.affiliation == null ? "any" : in.affiliation;
        final List<TargetFinder.Item> found = TargetFinder.nearest(mapItems(), in.label, side, self, 4);
        final String what = in.label != null && !in.label.trim().isEmpty() ? "\"" + in.label.trim() + "\""
                : "any".equals(side) ? "markers" : side + " markers";
        if (found.isEmpty()) {
            setStatus("I can't find " + what + " on the map.");
            showCard(false);
            return;
        }
        final boolean named = in.label != null && !in.label.trim().isEmpty();
        final boolean wantsNearest = lower.matches(".*\\b(nearest|closest)\\b.*");
        if (named && found.size() > 1 && !wantsNearest) {
            final String[] labels = new String[found.size()];
            for (int i = 0; i < labels.length; i++) labels[i] = targetLabel(found.get(i), self);
            setStatus("Which " + in.label.trim() + " do you mean?");
            new AlertDialog.Builder(mapView.getContext())
                    .setTitle("Which " + in.label.trim() + "?")
                    .setItems(labels, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int which) {
                            List<TargetFinder.Item> one = new ArrayList<>();
                            one.add(found.get(which));
                            showLocated(one, self, in);
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }
        showLocated(found, self, in);
    }

    /** Shows the first marker in found (and the next-closest ones) on the card and pans to it. UI thread. */
    private void showLocated(List<TargetFinder.Item> found, double[] self, MarkerIntent in) {
        final TargetFinder.Item t = found.get(0);
        final GeoPoint gp = new GeoPoint(t.lat, t.lon);
        final StringBuilder b = new StringBuilder();
        if (self != null) {
            final double[] db = com.apexcsfc.atak.takassist.talk.resolve.Geodesy.inverse(self[0], self[1], t.lat, t.lon);
            final double mag = com.apexcsfc.atak.takassist.talk.resolve.Geodesy.normalizeDeg(
                    ATAKUtilities.convertFromTrueToMagnetic(new GeoPoint(self[0], self[1]), db[1]));
            b.append(distText(db[0])).append(' ').append(compass8(db[1])).append(" of you")
                    .append(String.format(Locale.US, " (%03.0f° true / %03.0f° magnetic)", db[1], mag));
        }
        b.append(b.length() == 0 ? "" : "\n").append(CoordinateFormatUtilities.formatToString(gp, CoordinateFormat.MGRS));
        if (found.size() > 1 && self != null) {
            b.append("\nNext closest:");
            for (int i = 1; i < found.size(); i++) {
                TargetFinder.Item o = found.get(i);
                double[] d = com.apexcsfc.atak.takassist.talk.resolve.Geodesy.inverse(self[0], self[1], o.lat, o.lon);
                b.append("\n• ").append(o.name).append(": ").append(distText(d[0])).append(' ').append(compass8(d[1]));
            }
        }
        b.append("\nSay \"Bloodhound to the ").append(t.name).append("\" or \"directions to the ")
                .append(t.name).append("\" to go there.");
        final String sideWord = sideOf(t.cotType);
        cardTitle.setText((in.label == null || in.label.trim().isEmpty() ? "Nearest " + (sideWord.equals("marker") ? "marker" : sideWord) + ": " : "")
                + t.name);
        cardBody.setText(b.toString());
        cardWarn.setVisibility(View.GONE);
        card.setVisibility(View.VISIBLE);
        actions.setVisibility(View.GONE);
        zoomTo(gp);
        setStatus("Found on your map.");
        appendLog(time() + "  located " + t.name);
        input.setText("");
    }

    /** How much map to show around a located point: 2,000 ft from the centre to the nearest screen edge. */
    private static final double SHOW_RADIUS_M = 2000d * 0.3048d;
    /** Fallback map scale if the screen size can't be read. */
    private static final double SHOW_SCALE = 1d / 25000d;

    /** Centres the map on gp and sets the view to about 2,000 ft around it. UI thread. */
    private void zoomTo(GeoPoint gp) {
        final com.atakmap.map.MapRenderer3 r = mapView.getRenderer3();
        double scale = SHOW_SCALE;
        try {
            final int px = Math.min(mapView.getWidth(), mapView.getHeight());
            if (px > 0) {
                final double s = mapView.mapResolutionAsMapScale(2d * SHOW_RADIUS_M / px);
                if (s > 0 && !Double.isNaN(s) && !Double.isInfinite(s)) scale = s;
            }
        } catch (Throwable ignored) {
            // keep the fallback scale
        }
        CameraController.Programmatic.zoomTo(r, scale, false);
        CameraController.Programmatic.panTo(r, gp, true);
    }

    private static String sideOf(String cot) {
        if (cot == null || cot.length() < 3 || !cot.startsWith("a-")) return "marker";
        switch (cot.charAt(2)) {
            case 'h': return "hostile";
            case 'f': return "friendly";
            case 'n': return "neutral";
            case 'u': return "unknown";
            default:  return "marker";
        }
    }

    // ---------------------------------------------------------------- count

    /** "How many hostiles near me?" Counted from the map itself. UI thread. */
    private void answerCount(String said, MarkerIntent in) {
        clearDraft();
        pending = null;
        final Marker self = mapView.getSelfMarker();
        final List<MarkerCounter.Item> items = new ArrayList<>();
        mapView.getRootGroup().deepForEachItem(new MapGroup.MapItemsCallback() {
            @Override
            public boolean onItemFunction(MapItem item) {
                if (item instanceof PointMapItem && item != self
                        && !item.getMetaBoolean("talkassist.draft", false)) {
                    GeoPoint p = ((PointMapItem) item).getPoint();
                    if (p != null && p.isValid()) {
                        items.add(new MarkerCounter.Item(item.getTitle(), item.getType(),
                                p.getLatitude(), p.getLongitude()));
                    }
                }
                return false; // keep going
            }
        });
        Double radius = null;
        if (in.distance != null) radius = Resolver.toMetres(in.distance, in.distanceUnit);
        final String affil = in.affiliation == null ? "any" : in.affiliation;
        final MarkerCounter.Answer a = MarkerCounter.count(items, affil,
                new AtakEnvironment(null, null).self(), radius);

        cardTitle.setText(a.title);
        StringBuilder b = new StringBuilder();
        for (String line : a.lines) b.append(b.length() == 0 ? "" : "\n").append(line);
        b.append(b.length() == 0 ? "" : "\n").append("Asked: “").append(said).append('”');
        cardBody.setText(b.toString());
        cardWarn.setVisibility(View.GONE);
        card.setVisibility(View.VISIBLE);
        actions.setVisibility(View.GONE); // an answer, nothing to place
        setStatus("Counted from the markers on your map.");
        appendLog(time() + "  " + a.title);
        input.setText("");
    }

    /** Resolves the report against the map and shows the confirm card. UI thread. */
    private void finishReport(String said, MarkerIntent in, Place place, String placeError) {
        showResult(said, new Resolver(new AtakEnvironment(place, placeError), defaultNorth()).resolve(in), in);
    }

    private static Place toPlace(OfflineArea.Hit h, boolean exact) {
        return new Place(h.name, h.kind, h.lat, h.lon, exact);
    }

    private Resolver.DefaultNorth defaultNorth() {
        return "true".equals(prefs.getString(PREF_NORTH, "magnetic"))
                ? Resolver.DefaultNorth.TRUE : Resolver.DefaultNorth.MAGNETIC;
    }

    private void updateNorthLabel() {
        northBtn.setText(defaultNorth() == Resolver.DefaultNorth.MAGNETIC ? "Bearings: MAG" : "Bearings: TRUE");
    }

    private static String describeType(String cot) {
        String a;
        switch (cot.charAt(2)) {
            case 'h': a = "HOSTILE"; break;
            case 'f': a = "FRIENDLY"; break;
            case 'n': a = "NEUTRAL"; break;
            default:  a = "UNKNOWN"; break;
        }
        String d;
        switch (cot.charAt(4)) {
            case 'A': d = "air"; break;
            case 'S': d = "sea"; break;
            default:  d = "ground"; break;
        }
        return a + " " + d;
    }

    private void setStatus(String s) {
        status.setText(s);
    }

    /** Shows only the latest result; earlier ones are cleared so the pane stays uncluttered. */
    private void appendLog(String line) {
        log.setText(line);
    }

    private static String time() {
        return new SimpleDateFormat("HH:mm", Locale.US).format(new Date());
    }
}
