package com.apexcsfc.atak.takassist.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.apexcsfc.atak.takassist.Privacy;
import com.apexcsfc.atak.takassist.plugin.R;
import com.apexcsfc.atak.takassist.talk.ui.TalkAssistPane;
import com.atak.plugins.impl.PluginLayoutInflater;
import com.atakmap.android.maps.MapView;

/**
 * The one TAK Assist pane: a Chat tab (the map-aware assistant, model settings and
 * start/stop) and a Talk tab (spoken or typed reports into map markers). Both tabs use
 * the same on-device model, which {@link com.apexcsfc.atak.takassist.llm.ModelHost} owns.
 *
 * Each tab's view is built the first time it's shown and then kept, so switching tabs
 * never loses a conversation or a half-confirmed marker.
 */
public class TakAssistPane {

    public static final int TAB_CHAT = 0;
    public static final int TAB_TALK = 1;

    private static final String PREFS = "takassist";
    private static final String PREF_TAB = "takassist.tab";

    private static final int SELECTED = Color.WHITE;
    private static final int UNSELECTED = Color.parseColor("#9AA5B1");
    private static final int SELECTED_BG = Color.parseColor("#2A313A");

    private final Context pluginContext;
    private final MapView mapView;
    private final View root;
    private final FrameLayout content;
    private final TextView chatTab;
    private final TextView talkTab;
    private final TextView modeToggle;
    private final SharedPreferences prefs;

    private LlmAssistPane chat;
    private TalkAssistPane talk;
    private int current = -1;

    public TakAssistPane(Context pluginContext, MapView mapView) {
        this.pluginContext = pluginContext;
        this.mapView = mapView;
        root = PluginLayoutInflater.inflate(pluginContext, R.layout.tak_pane, null);
        content = root.findViewById(R.id.tak_content);
        chatTab = root.findViewById(R.id.tak_tab_chat);
        talkTab = root.findViewById(R.id.tak_tab_talk);
        modeToggle = root.findViewById(R.id.tak_mode);
        modeToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleMode();
            }
        });
        styleMode();
        prefs = mapView.getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        chatTab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showTab(TAB_CHAT);
            }
        });
        talkTab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showTab(TAB_TALK);
            }
        });

        showTab(prefs.getInt(PREF_TAB, TAB_CHAT) == TAB_TALK ? TAB_TALK : TAB_CHAT);
    }

    public View getView() {
        return root;
    }

    /** Offline → online asks first (it lets searches and speech leave the phone); online → offline doesn't. */
    private void toggleMode() {
        if (Privacy.offlineOnly()) {
            new AlertDialog.Builder(mapView.getContext())
                    .setTitle("Switch to online mode?")
                    .setMessage("When the offline map has no answer, searches and routes will go to "
                            + "public OpenStreetMap services, and speech may use Google's online "
                            + "recognizer. Your approximate area and what you ask leave the phone.")
                    .setPositiveButton("Go online", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            Privacy.setOfflineOnly(false);
                            styleMode();
                        }
                    })
                    .setNegativeButton("Stay offline", null)
                    .show();
        } else {
            Privacy.setOfflineOnly(true);
            styleMode();
        }
    }

    private void styleMode() {
        boolean offline = Privacy.offlineOnly();
        modeToggle.setText(offline ? "\u25CF  OFFLINE" : "\u25CF  ONLINE");
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(16 * mapView.getContext().getResources().getDisplayMetrics().density);
        bg.setColor(Color.parseColor(offline ? "#1F4D45" : "#5A4415"));
        bg.setStroke(2, Color.parseColor(offline ? "#4FB9A5" : "#E0A030"));
        modeToggle.setBackground(bg);
        modeToggle.setTextColor(Color.parseColor(offline ? "#7FD8C6" : "#F5C060"));
        modeToggle.setContentDescription(offline
                ? "Offline mode. Tap to go online." : "Online mode. Tap to go offline.");
    }

    /** Switches to a tab, building its view the first time. UI thread. */
    public void showTab(int tab) {
        if (tab == current) return;
        if (tab == TAB_TALK && talk == null) {
            talk = new TalkAssistPane(pluginContext, mapView);
            add(talk.getView());
        } else if (tab == TAB_CHAT && chat == null) {
            chat = new LlmAssistPane(pluginContext, mapView);
            add(chat.getView());
        }
        if (chat != null) chat.getView().setVisibility(tab == TAB_CHAT ? View.VISIBLE : View.GONE);
        if (talk != null) talk.getView().setVisibility(tab == TAB_TALK ? View.VISIBLE : View.GONE);
        style(chatTab, tab == TAB_CHAT);
        style(talkTab, tab == TAB_TALK);
        current = tab;
        prefs.edit().putInt(PREF_TAB, tab).apply();
    }

    /** Side-button shortcut: open the Talk tab and start listening hands-free. UI thread. */
    public void startHandsFree() {
        showTab(TAB_TALK);
        talk.startHandsFree();
    }

    public void shutdown() {
        if (chat != null) {
            chat.shutdown();
            chat = null;
        }
        if (talk != null) {
            talk.shutdown();
            talk = null;
        }
    }

    private void add(View v) {
        if (v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
        content.addView(v, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private static void style(TextView t, boolean selected) {
        t.setTextColor(selected ? SELECTED : UNSELECTED);
        t.setBackgroundColor(selected ? SELECTED_BG : Color.TRANSPARENT);
    }
}
