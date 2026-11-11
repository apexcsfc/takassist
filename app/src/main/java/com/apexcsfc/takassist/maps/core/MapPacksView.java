package com.apexcsfc.takassist.maps.core;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The map areas screen, built in code so the TAK Assist plugin and the TAK Assist Maps app
 * share it. A tick box per area and one button, Apply.
 *
 * Without {@link Hooks} (in the plugin) it lists areas already on the phone and Apply only
 * switches which are in use. With hooks (in the Maps app) it lists every state; Apply
 * downloads and builds what's missing, through the app's background service.
 */
public final class MapPacksView {

    /** Supplied by the Maps app. */
    public interface Hooks {
        /** Start installing (if needed) and activating these areas. */
        void apply(List<String> ids);

        void cancel();

        /** {running?, text, fraction} of the background job, polled while the screen is open. */
        Object[] status();

        /** Non-null when an administrator controls the areas; shown instead of Apply. */
        String lockedNote();
    }

    private static final int BG = Color.parseColor("#1B1F24");
    private static final int FG = Color.WHITE;
    private static final int DIM = Color.parseColor("#9AA5B1");
    private static final int OK = Color.parseColor("#7BD88F");
    private static final int BAD = Color.parseColor("#FF8A80");

    private final Context ctx, dialogCtx;
    private final Hooks hooks;
    private final LinearLayout root, list;
    private final TextView status;
    private final ProgressBar bar;
    private final Button apply;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Map<String, CheckBox> boxes = new LinkedHashMap<>();
    private volatile boolean busy;
    private boolean wasRunning;
    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            pollStatus();
            ui.postDelayed(this, 700);
        }
    };

    public MapPacksView(Context ctx, Context dialogCtx, Hooks hooks) {
        this.ctx = ctx;
        this.dialogCtx = dialogCtx;
        this.hooks = hooks;

        root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        int pad = dp(12);
        root.setPadding(pad, pad, pad, pad);

        TextView title = text(hooks != null ? "Offline map areas" : "Map areas", 20, FG);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);
        String helpText = hooks != null
                ? "Tick the states you need and tap Install. Each one is downloaded from OpenStreetMap "
                + "(Geofabrik) and prepared on this phone; use Wi-Fi and keep the phone plugged in."
                : "Tick the areas to use and tap Install. Areas are added with the TAK Assist Maps app.";
        TextView help = text(helpText, 14, DIM);
        help.setPadding(0, dp(4), 0, dp(8));
        root.addView(help);

        ScrollView sv = new ScrollView(ctx);
        list = new LinearLayout(ctx);
        list.setOrientation(LinearLayout.VERTICAL);
        sv.addView(list);
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        bar = new ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setVisibility(View.GONE);
        root.addView(bar);
        status = text("", 14, DIM);
        status.setPadding(0, dp(4), 0, dp(4));
        root.addView(status);

        apply = button("Install");
        apply.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (hooks != null && Boolean.TRUE.equals(hooks.status()[0])) {
                    hooks.cancel();
                } else if (!busy) {
                    applySelection();
                }
            }
        });
        root.addView(apply, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));

        String locked = hooks != null ? hooks.lockedNote() : null;
        if (locked != null) {
            apply.setVisibility(View.GONE);
            say(locked, DIM);
        }
        showList();
        if (hooks != null) ui.post(poll);
    }

    public View getView() {
        return root;
    }

    public void shutdown() {
        ui.removeCallbacks(poll);
        worker.shutdownNow();
    }

    // ------------------------------------------------------------------ list

    private void showList() {
        List<String> active = MapPacks.activeIds();
        Map<String, Region> all = new LinkedHashMap<>();
        // Areas on the phone first, then (Maps app) every other state.
        for (Region r : MapPacks.installed()) all.put(r.id, r);
        if (hooks != null) for (Region r : States.all()) if (!all.containsKey(r.id)) all.put(r.id, r);
        list.removeAllViews();
        boxes.clear();
        if (all.isEmpty()) {
            list.addView(text("No map areas on this phone yet. Install the TAK Assist Maps app, or ask "
                    + "your administrator.", 14, DIM));
            apply.setEnabled(false);
            return;
        }
        boolean locked = hooks != null && hooks.lockedNote() != null;
        for (final Region r : all.values()) {
            boolean inst = MapPacks.isInstalled(r.id);
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, dp(4), 0, dp(4));
            CheckBox cb = new CheckBox(ctx);
            cb.setText(r.name);
            cb.setTextColor(FG);
            cb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
            cb.setChecked(active.contains(r.id));
            cb.setEnabled(!locked);
            row.addView(cb);
            if (inst) {
                String s = (active.contains(r.id) ? "In use · " : "On phone · ") + MapPackManager.mb(MapPacks.installedBytes(r.id));
                if (!r.built.isEmpty()) s += " · map data from " + r.built;
                TextView sub = text(s, 13, active.contains(r.id) ? OK : DIM);
                sub.setPadding(dp(32), 0, 0, 0);
                row.addView(sub);
                if (!active.contains(r.id) && !locked) {
                    row.setOnLongClickListener(new View.OnLongClickListener() {
                        @Override
                        public boolean onLongClick(View v) {
                            askDelete(r);
                            return true;
                        }
                    });
                }
            }
            boxes.put(r.id, cb);
            list.addView(row);
        }
    }

    // ------------------------------------------------------------------ apply

    private void applySelection() {
        final List<String> want = new ArrayList<>();
        for (Map.Entry<String, CheckBox> e : boxes.entrySet()) if (e.getValue().isChecked()) want.add(e.getKey());
        if (want.isEmpty()) {
            say("Tick at least one area.", BAD);
            return;
        }
        int missing = 0;
        for (String id : want) if (!MapPacks.isInstalled(id)) missing++;
        if (missing > 0 && hooks != null) {
            confirm("Download " + missing + (missing == 1 ? " state?" : " states?"),
                    "Each state is downloaded from OpenStreetMap and prepared on this phone. This takes "
                            + "a few minutes per state (longer for big states). Use Wi-Fi and keep the phone plugged in. "
                            + "You can leave this screen; it carries on in the background.",
                    new Runnable() {
                        @Override
                        public void run() {
                            hooks.apply(want);
                            pollStatus();
                        }
                    });
            return;
        }
        if (hooks != null) {
            hooks.apply(want);
            pollStatus();
            return;
        }
        // Plugin: switch between areas already on the phone.
        busy = true;
        bar.setVisibility(View.VISIBLE);
        bar.setIndeterminate(true);
        apply.setEnabled(false);
        worker.execute(new Runnable() {
            @Override
            public void run() {
                String err = null;
                try {
                    new MapPackManager(new MapPackManager.Listener() {
                        @Override
                        public void onStatus(final String text, double f) {
                            ui.post(new Runnable() {
                                @Override
                                public void run() {
                                    say(text, DIM);
                                }
                            });
                        }
                    }).activate(want);
                } catch (Exception e) {
                    err = e.getMessage() == null ? e.toString() : e.getMessage();
                }
                final String fe = err;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        busy = false;
                        bar.setVisibility(View.GONE);
                        apply.setEnabled(true);
                        showList();
                        if (fe == null) say(want.size() == 1 ? "Done. Using this area." : "Done. Using these areas, joined together.", OK);
                        else say(fe, BAD);
                    }
                });
            }
        });
    }

    private void pollStatus() {
        Object[] st = hooks.status();
        boolean running = Boolean.TRUE.equals(st[0]);
        String text = (String) st[1];
        double f = st[2] == null ? -1 : (Double) st[2];
        if (running) {
            bar.setVisibility(View.VISIBLE);
            bar.setIndeterminate(f < 0);
            if (f >= 0) bar.setProgress((int) (f * 1000));
            say(text, DIM);
            apply.setText("Cancel");
        } else {
            bar.setVisibility(View.GONE);
            apply.setText("Install");
            if (wasRunning) {
                showList();
                if (text != null && !text.isEmpty()) say(text, text.startsWith("Done") ? OK : BAD);
            }
        }
        wasRunning = running;
    }

    // ---------------------------------------------------------------- dialogs

    private void askDelete(final Region r) {
        confirm("Delete " + r.name + "?", "Frees " + MapPackManager.mb(MapPacks.installedBytes(r.id))
                + ". It can be downloaded again later.", new Runnable() {
            @Override
            public void run() {
                try {
                    new MapPackManager(null).remove(r.id);
                    say(r.name + " deleted.", DIM);
                } catch (Exception e) {
                    say(e.getMessage(), BAD);
                }
                showList();
            }
        });
    }

    private void confirm(String title, String msg, final Runnable yes) {
        new AlertDialog.Builder(dialogCtx)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        yes.run();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---------------------------------------------------------------- helpers

    private void say(String s, int color) {
        status.setTextColor(color);
        status.setText(s);
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    private Button button(String s) {
        Button b = new Button(ctx);
        b.setText(s);
        b.setAllCaps(false);
        return b;
    }

    private int dp(int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }
}
