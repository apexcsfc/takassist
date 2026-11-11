package com.apexcsfc.atak.takassist.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.atak.plugins.impl.PluginLayoutInflater;
import com.apexcsfc.atak.takassist.llm.ChatSession;
import com.apexcsfc.atak.takassist.llm.LlmClient;
import com.apexcsfc.atak.takassist.llm.LlmServerManager;
import com.apexcsfc.atak.takassist.llm.MapToolbox;
import com.apexcsfc.atak.takassist.llm.ModelHost;
import com.apexcsfc.atak.takassist.plugin.R;
import com.atakmap.android.maps.MapView;

import java.io.File;
import java.net.URI;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Owns the chat pane's view and behavior: transcript, quick prompts,
 * input box and settings. Created by TakAssistPane as its Chat tab.
 *
 * Anonymous classes are used instead of lambdas on purpose: the SDK README
 * warns that lambdas can break in ProGuard-obfuscated release builds.
 */
public class LlmAssistPane {

    private static final String PREF_URL = "llmassist.server_url";
    private static final String PREF_CONFIRM = "llmassist.confirm_edits";
    private static final String PREF_THINKING = "llmassist.thinking";
    private static final String PREF_MODEL_PATH = "llmassist.model_path";
    private static final String PREF_AUTOSTART = "llmassist.autostart";
    private static final String PREF_ACCEL = "llmassist.accelerator";
    private static final String DEFAULT_URL = "http://127.0.0.1:8080";
    private static final int CONTEXT_SIZE = ModelHost.CONTEXT_SIZE;

    private static final int COLOR_USER = 0xFF2D4F73;
    private static final int COLOR_ASSISTANT = 0xFF2A3038;
    private static final int COLOR_TOOL = 0xFF1F2A22;
    private static final int COLOR_ERROR = 0xFF5A2328;

    private final Context pluginContext;
    private final View root;
    private final LinearLayout messages;
    private final ScrollView scroll;
    private final EditText input;
    private final Button sendButton;
    private final TextView status;
    private final View settings;
    private final EditText urlField;
    private final CheckBox confirmBox;
    private final CheckBox thinkingBox;
    private final TextView modelStatus;
    private final Button modelToggle;
    private final TextView modelCurrent;
    private final Context hostContext; // ATAK's activity context, for dialogs and intents
    private volatile String modelPath = "";
    private volatile String accelerator = LlmServerManager.CPU;
    private Button accelButton;
    private final CheckBox autostartBox;
    private final LlmServerManager server;
    private final ExecutorService serverExec = Executors.newSingleThreadExecutor();

    private final SharedPreferences prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ChatSession session;
    private volatile boolean confirmEdits;
    private boolean busy;

    public LlmAssistPane(Context pluginContext, MapView mapView) {
        this.pluginContext = pluginContext;
        root = PluginLayoutInflater.inflate(pluginContext, R.layout.llm_pane, null);

        messages = root.findViewById(R.id.llm_messages);
        scroll = root.findViewById(R.id.llm_scroll);
        input = root.findViewById(R.id.llm_input);
        sendButton = root.findViewById(R.id.llm_send);
        status = root.findViewById(R.id.llm_status);
        settings = root.findViewById(R.id.llm_settings);
        urlField = root.findViewById(R.id.llm_url);
        confirmBox = root.findViewById(R.id.llm_confirm);
        thinkingBox = root.findViewById(R.id.llm_thinking);
        modelStatus = root.findViewById(R.id.llm_model_status);
        modelToggle = root.findViewById(R.id.llm_model_toggle);
        modelCurrent = root.findViewById(R.id.llm_model_current);
        hostContext = mapView.getContext();
        autostartBox = root.findViewById(R.id.llm_autostart);

        prefs = mapView.getContext().getSharedPreferences("llmassist", Context.MODE_PRIVATE);
        urlField.setText(prefs.getString(PREF_URL, DEFAULT_URL));
        confirmEdits = prefs.getBoolean(PREF_CONFIRM, true);
        confirmBox.setChecked(confirmEdits);
        thinkingBox.setChecked(prefs.getBoolean(PREF_THINKING, false));
        modelPath = prefs.getString(PREF_MODEL_PATH, "");
        autostartBox.setChecked(prefs.getBoolean(PREF_AUTOSTART, true));

        session = new ChatSession(new MapToolbox(mapView, new MapToolbox.ConfirmPolicy() {
            @Override
            public boolean requireConfirm() {
                return confirmEdits;
            }
        }));
        // The server belongs to the plugin (ModelHost), so it may already be running
        // because Talk Assist asked for it before this pane was opened.
        server = ModelHost.get().server();
        ModelHost.get().setPaneListener(new LlmServerManager.StatusListener() {
            @Override
            public void onStatus(final LlmServerManager.State state, final String detail) {
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        showServerState(state, detail);
                    }
                });
            }
        });
        accelerator = prefs.getString(PREF_ACCEL,
                server.hasAccelerators() ? LlmServerManager.NPU : LlmServerManager.CPU);
        if (!server.hasAccelerators()) accelerator = LlmServerManager.CPU;

        session.setPreflight(new ChatSession.Preflight() {
            @Override
            public void ensureReady() throws Exception {
                if (managesLocalServer()) {
                    configureServer();
                    server.ensureRunning();
                }
            }
        });

        applyClient();
        wireUi();

        addBubble("TAK Assist", "Ask about your position or nearby markers, or "
                + "have me drop a point. Models and maps go in Download/TAKAssist.",
                COLOR_ASSISTANT);

        serverExec.execute(new Runnable() {
            @Override
            public void run() {
                server.ensureModelDirReadme();
            }
        });
        if (autostartBox.isChecked() && managesLocalServer() && server.isBundled()) {
            startServer();
        }
    }

    /** Map packs: download offline map areas and choose which ones are active. */
    private void openMapPacks() {
        final com.apexcsfc.takassist.maps.core.MapPacksView v =
                new com.apexcsfc.takassist.maps.core.MapPacksView(hostContext, hostContext, null);
        final android.app.AlertDialog d = new android.app.AlertDialog.Builder(hostContext)
                .setView(v.getView())
                .setPositiveButton("Close", null)
                .create();
        d.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface di) {
                v.shutdown();
                updateMapsLabel();
            }
        });
        d.show();
    }

    private void updateMapsLabel() {
        TextView t = root.findViewById(R.id.llm_maps_status);
        java.util.List<String> act = com.apexcsfc.takassist.maps.core.MapPacks.activeIds();
        if (act.isEmpty()) {
            t.setText(com.apexcsfc.takassist.maps.core.MapPacks.activeFile("route.bin").isFile()
                    ? "Offline maps: one area loaded" : "Offline maps: none");
        } else {
            StringBuilder sb = new StringBuilder();
            for (com.apexcsfc.takassist.maps.core.Region r : com.apexcsfc.takassist.maps.core.MapPacks.installed()) {
                if (!act.contains(r.id)) continue;
                if (sb.length() > 0) sb.append(", ");
                sb.append(r.name);
            }
            t.setText("Offline maps: " + sb);
        }
    }

    public View getView() {
        return root;
    }

    public void shutdown() {
        session.shutdown();
        serverExec.shutdownNow();
        // The plugin stops the server itself (ModelHost.shutdown) when it unloads.
        ModelHost.get().setPaneListener(null);
    }

    // ----------------------------------------------------------- model server

    /** True when the chat URL points at this phone, so the plugin should run the server itself. */
    private boolean managesLocalServer() {
        try {
            String host = URI.create(currentUrl()).getHost();
            return "127.0.0.1".equals(host) || "localhost".equals(host);
        } catch (Exception e) {
            return false;
        }
    }

    private void configureServer() {
        int port = 8080;
        try {
            int p = URI.create(currentUrl()).getPort();
            if (p > 0) port = p;
        } catch (Exception ignored) {
        }
        server.configure(modelPath, port, CONTEXT_SIZE, accelerator);
    }

    private void startServer() {
        configureServer();
        serverExec.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    server.ensureRunning();
                } catch (Exception ignored) {
                    // state and reason are already reported through the listener
                }
            }
        });
    }

    private void stopServer() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                server.stop();
            }
        }, "llmassist-stop").start();
    }

    // ------------------------------------------------------------ accelerator

    private static String accelLabel(String a) {
        if (LlmServerManager.NPU.equals(a)) return "NPU (Hexagon)";
        if (LlmServerManager.GPU.equals(a)) return "GPU (Adreno)";
        return "CPU";
    }

    private void chooseAccelerator() {
        if (!server.hasAccelerators()) {
            new AlertDialog.Builder(hostContext)
                    .setTitle("GPU/NPU not available")
                    .setMessage("This plugin build only has the CPU server. Build the Snapdragon "
                            + "server with scripts/build-llama-server-snapdragon.sh, then rebuild "
                            + "and reinstall the plugin.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }
        final String[] values = {LlmServerManager.NPU, LlmServerManager.GPU, LlmServerManager.CPU};
        final String[] labels = {"NPU (Hexagon) - fastest prompt reading",
                "GPU (Adreno)", "CPU - slowest, most compatible"};
        int checked = java.util.Arrays.asList(values).indexOf(accelerator);
        new AlertDialog.Builder(hostContext)
                .setTitle("Run the model on")
                .setSingleChoiceItems(labels, checked, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        d.dismiss();
                        if (values[which].equals(accelerator)) return;
                        accelerator = values[which];
                        prefs.edit().putString(PREF_ACCEL, accelerator).apply();
                        accelButton.setText(accelLabel(accelerator));
                        restartServer();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Stops the model and starts it again with the current settings. */
    private void restartServer() {
        setStatus("Restarting model…");
        serverExec.execute(new Runnable() {
            @Override
            public void run() {
                server.stop();
                if (managesLocalServer()) {
                    configureServer();
                    try {
                        server.ensureRunning();
                    } catch (Exception ignored) {
                        // reported through the status listener
                    }
                }
            }
        });
    }

    // ------------------------------------------------------------ model picker

    private static final String MODEL_BROWSE_URL =
            "https://huggingface.co/models?library=gguf&sort=trending&search=instruct";

    private void updateModelLabel() {
        if (!modelPath.isEmpty()) {
            modelCurrent.setText(new File(modelPath).getName());
            return;
        }
        modelCurrent.setText("No model chosen. Tap Change to pick one.");
    }

    /** Opens the phone's browser on Hugging Face's GGUF model list. */
    private void openModelBrowser() {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(MODEL_BROWSE_URL));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            hostContext.startActivity(i);
            setStatus("Download a .gguf (Q4_K_M is a good default), then tap Change");
        } catch (Exception e) {
            setStatus("Couldn't open a browser: " + e.getMessage());
        }
    }

    /** Lists .gguf files in the model folder and Downloads and lets the user pick one. */
    private void chooseModel() {
        setStatus("Looking for models…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final java.util.List<File> files = server.findModels();
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        showModelDialog(files);
                    }
                });
            }
        }, "llmassist-models").start();
    }

    private void showModelDialog(final java.util.List<File> files) {
        setStatus(busy ? "Thinking…" : "Idle");
        if (files.isEmpty()) {
            new AlertDialog.Builder(hostContext)
                    .setTitle("No models found")
                    .setMessage("Put a .gguf file in Download/TAKAssist on the phone (or anywhere in "
                            + "the Download folder).")
                    .setPositiveButton("Get models", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            openModelBrowser();
                        }
                    })
                    .setNegativeButton("Close", null)
                    .show();
            return;
        }
        String[] labels = new String[files.size()];
        int checked = -1;
        File current = modelPath.isEmpty() ? server.resolveModel() : new File(modelPath);
        for (int i = 0; i < files.size(); i++) {
            File f = files.get(i);
            boolean elsewhere = !f.getParentFile().equals(server.getModelDir());
            labels[i] = f.getName() + "  —  " + String.format(java.util.Locale.US, "%.1f GB",
                    f.length() / 1e9) + (elsewhere ? "  (" + f.getParentFile().getName() + ")" : "");
            if (current != null && f.getAbsolutePath().equals(current.getAbsolutePath())) checked = i;
        }
        new AlertDialog.Builder(hostContext)
                .setTitle("Choose a model")
                .setSingleChoiceItems(labels, checked, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        d.dismiss();
                        switchModel(files.get(which));
                    }
                })
                .setNeutralButton("Get more", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        openModelBrowser();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Moves a downloaded model into the model folder if needed, then restarts the server on it. */
    private void switchModel(final File picked) {
        if (picked.length() > 6_500_000_000L) {
            addBubble("TAK Assist", picked.getName() + " is " + String.format(java.util.Locale.US,
                    "%.1f GB", picked.length() / 1e9) + ". On a 12 GB phone with ATAK running, models "
                    + "over ~6.5 GB may not fit; it will load in slower low-memory mode or be refused "
                    + "if there isn't room.", COLOR_ASSISTANT);
        }
        setStatus("Switching model…");
        serverExec.execute(new Runnable() {
            @Override
            public void run() {
                File target = picked;
                File dir = server.getModelDir();
                // Models anywhere in Download/ are used where they are (an MDM may manage them);
                // only a file outside it would be moved in.
                if (!server.isAllowedModel(picked) && !picked.getParentFile().equals(dir)) {
                    File moved = new File(dir, picked.getName());
                    // Same storage volume, so this is an instant rename, not a copy.
                    if (dir.isDirectory() || dir.mkdirs()) {
                        if (picked.renameTo(moved)) target = moved;
                    }
                }
                modelPath = target.getAbsolutePath();
                prefs.edit().putString(PREF_MODEL_PATH, modelPath).apply();
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        updateModelLabel();
                    }
                });
                server.stop();
                if (managesLocalServer()) {
                    configureServer();
                    try {
                        server.ensureRunning();
                    } catch (Exception ignored) {
                        // reported through the status listener
                    }
                }
            }
        });
    }

    private LlmServerManager.State lastServerState = LlmServerManager.State.STOPPED;
    private boolean warming;

    private void warmUp() {
        session.warmUp(new Runnable() {
            @Override
            public void run() {
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        warming = true;
                        if (!busy) setStatus("Preparing model…");
                    }
                });
            }
        }, new Runnable() {
            @Override
            public void run() {
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        warming = false;
                        if (!busy) setStatus("Idle");
                    }
                });
            }
        });
    }

    private void showServerState(LlmServerManager.State state, String detail) {
        switch (state) {
            case STARTING:
                modelStatus.setText("Model: " + detail);
                modelToggle.setText("Stop model");
                setStatus("Loading model…");
                break;
            case READY:
                if (lastServerState != LlmServerManager.State.READY) warmUp();
                File m = server.resolveModel();
                modelStatus.setText("Model ready on " + server.runningOn()
                        + (m != null ? ": " + m.getName() : ""));
                modelToggle.setText("Stop model");
                if (!busy) setStatus("Idle");
                break;
            case FAILED:
                modelStatus.setText("Model failed: " + detail);
                modelToggle.setText("Start model");
                // The model controls live in Settings now, so surface the failure in the header too.
                setStatus("Model failed (see Settings)");
                break;
            default:
                modelStatus.setText(server.isBundled()
                        ? "Model: stopped"
                        : "Model: not bundled (using external server)");
                modelToggle.setText("Start model");
        }
        lastServerState = state;
    }

    // ------------------------------------------------------------------ wiring

    private void wireUi() {
        sendButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (busy) {
                    session.cancel();
                    pendingPartial = null;
                    liveBubble = null;   // leave what streamed so far on screen
                    setStatus("Stopped");
                } else {
                    submit(input.getText().toString());
                }
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

        root.findViewById(R.id.llm_clear).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                session.cancel();
                session.clearHistory();
                pendingPartial = null;
                liveBubble = null;
                messages.removeAllViews();
                setStatus("Cleared");
            }
        });

        root.findViewById(R.id.llm_settings_toggle).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Settings is a full page: swap it with the chat instead of stacking.
                boolean open = settings.getVisibility() != View.VISIBLE;
                settings.setVisibility(open ? View.VISIBLE : View.GONE);
                scroll.setVisibility(open ? View.GONE : View.VISIBLE);
                root.findViewById(R.id.llm_input_row).setVisibility(open ? View.GONE : View.VISIBLE);
                ((Button) v).setText(open ? "Done" : "Settings");
                if (open) updateModelLabel();
            }
        });

        root.findViewById(R.id.llm_model_choose).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chooseModel();
            }
        });
        root.findViewById(R.id.llm_model_get).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openModelBrowser();
            }
        });
        updateModelLabel();

        root.findViewById(R.id.llm_maps).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openMapPacks();
            }
        });
        updateMapsLabel();

        accelButton = root.findViewById(R.id.llm_accel);
        accelButton.setText(accelLabel(accelerator));
        accelButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chooseAccelerator();
            }
        });

        modelToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                LlmServerManager.State st = server.getState();
                if (st == LlmServerManager.State.READY || st == LlmServerManager.State.STARTING) {
                    stopServer();
                } else if (!managesLocalServer()) {
                    setStatus("Server URL is remote; nothing to start here");
                } else {
                    startServer();
                }
            }
        });

        autostartBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                prefs.edit().putBoolean(PREF_AUTOSTART, checked).apply();
            }
        });

        root.findViewById(R.id.llm_test).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                testServer();
            }
        });

        confirmBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                confirmEdits = checked;
                prefs.edit().putBoolean(PREF_CONFIRM, checked).apply();
            }
        });
        thinkingBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                prefs.edit().putBoolean(PREF_THINKING, checked).apply();
                applyClient();
            }
        });
    }

    private void testServer() {
        applyClient();
        setStatus("Testing…");
        final LlmClient probe = new LlmClient(currentUrl(), null, false);
        new Thread(new Runnable() {
            @Override
            public void run() {
                String result;
                try {
                    result = "Server OK " + shorten(probe.health());
                } catch (Exception e) {
                    result = "Server unreachable: " + e.getMessage();
                }
                final String r = result;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        setStatus(r);
                    }
                });
            }
        }, "llmassist-health").start();
    }

    // ------------------------------------------------------------------- chat

    private void submit(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty() || busy) return;
        applyClient();
        input.setText("");
        addBubble("You", text, COLOR_USER);
        pendingPartial = null;
        liveBubble = null;
        session.send(text, new ChatSession.Listener() {
            @Override
            public void onBusy(final boolean b) {
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        setBusy(b);
                    }
                });
            }

            @Override
            public void onToolActivity(final String line) {
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        discardPartial();
                        addBubble("tool", line, COLOR_TOOL);
                    }
                });
            }

            @Override
            public void onAssistantPartial(final String textSoFar) {
                showPartial(textSoFar);
            }

            @Override
            public void onAssistantMessage(final String reply) {
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        finishReply(reply);
                    }
                });
            }

            @Override
            public void onError(final String message) {
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        pendingPartial = null;
                        liveBubble = null;   // keep whatever streamed, then show the error
                        addBubble("Error", message, COLOR_ERROR);
                    }
                });
            }
        });
    }

    private void post(final String who, final String text, final int color) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                addBubble(who, text, color);
            }
        });
    }

    private void applyClient() {
        String url = currentUrl();
        prefs.edit().putString(PREF_URL, url)
                .putString(PREF_MODEL_PATH, modelPath)
                .apply();
        session.setClient(new LlmClient(url, null, thinkingBox.isChecked()));
    }

    private String currentUrl() {
        String u = urlField.getText().toString().trim();
        return u.isEmpty() ? DEFAULT_URL : u;
    }

    private void setBusy(boolean b) {
        busy = b;
        sendButton.setText(b ? "Stop" : "Send");
        setStatus(b ? "Thinking…" : "Idle");
    }

    private void setStatus(String s) {
        status.setText(s);
    }

    private TextView addBubble(String who, String text, int color) {
        TextView tv = new TextView(pluginContext);
        renderBubble(tv, who, text);
        tv.setTextColor(0xFFFFFFFF);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, "tool".equals(who) ? 12 : 15);
        tv.setTextIsSelectable(true);
        int pad = dp(8);
        tv.setPadding(pad, pad, pad, pad);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(8));
        tv.setBackground(bg);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        messages.addView(tv, lp);
        scrollToBottom();
        return tv;
    }

    private void scrollToBottom() {
        scroll.post(new Runnable() {
            @Override
            public void run() {
                scroll.fullScroll(View.FOCUS_DOWN);
            }
        });
    }

    private void renderBubble(TextView tv, String who, String text) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        sb.append(who);
        sb.setSpan(new StyleSpan(Typeface.BOLD), 0, who.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.append('\n');
        if ("LLM".equals(who)) {
            // Model replies use Markdown; show it as formatting, not as ** and - marks.
            MarkdownText.append(sb, text, pluginContext.getResources().getDisplayMetrics().density);
        } else {
            sb.append(text);
        }
        tv.setText(sb);
    }

    // ------------------------------------------------------------ streaming

    /** The bubble being filled while a reply streams in (UI thread only). */
    private TextView liveBubble;
    /** Newest partial text; the UI picks up only the latest (coalesces fast updates). */
    private volatile String pendingPartial;
    private boolean partialScheduled;

    private void showPartial(String text) {
        pendingPartial = text;
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (partialScheduled) return;
                partialScheduled = true;
                // ~10 redraws a second at most: Markdown re-rendering per token is wasted work.
                ui.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        partialScheduled = false;
                        String t = pendingPartial;
                        if (t == null) return;
                        if (liveBubble == null) {
                            liveBubble = addBubble("LLM", t, COLOR_ASSISTANT);
                        } else {
                            renderBubble(liveBubble, "LLM", t);
                            scrollToBottom();
                        }
                    }
                }, 100);
            }
        });
    }

    /** Text streamed before a tool call is the model thinking aloud: drop it. */
    private void discardPartial() {
        pendingPartial = null;
        if (liveBubble != null) {
            messages.removeView(liveBubble);
            liveBubble = null;
        }
    }

    private void finishReply(String text) {
        pendingPartial = null;
        if (liveBubble != null) {
            renderBubble(liveBubble, "LLM", text);   // final, cleaned text (notes, MGRS order)
            liveBubble = null;
            scrollToBottom();
        } else {
            addBubble("LLM", text, COLOR_ASSISTANT);
        }
    }

    private int dp(int v) {
        return Math.round(v * pluginContext.getResources().getDisplayMetrics().density);
    }

    private static String shorten(String s) {
        if (s == null) return "";
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() > 60 ? s.substring(0, 60) + "…" : s;
    }
}
