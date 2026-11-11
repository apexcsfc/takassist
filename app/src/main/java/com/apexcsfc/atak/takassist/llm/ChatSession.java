package com.apexcsfc.atak.takassist.llm;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Runs the conversation on a single worker thread.
 *
 * Each user turn: fresh system prompt (with live map situation) + recent history
 * + the new message, then a tool-calling loop until the model answers in text.
 * Only the final user/assistant text is kept in history, so tool chatter does
 * not eat the context window on a phone-sized model.
 *
 * Listener callbacks arrive on the worker thread; the UI must post to main.
 */
public class ChatSession {

    public interface Listener {
        void onBusy(boolean busy);
        void onToolActivity(String line);
        void onAssistantMessage(String text);

        /**
         * The reply so far, while it is being generated (cleaned for display). Called
         * often; the final text still arrives through onAssistantMessage.
         */
        void onAssistantPartial(String textSoFar);
        void onError(String message);
    }

    /** Runs before each turn; used to start the bundled model server on demand. */
    public interface Preflight {
        void ensureReady() throws Exception;
    }

    private static final int MAX_TOOL_ROUNDS = 6;
    private static final int MAX_HISTORY_MESSAGES = 16;

    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final List<JSONObject> history = new ArrayList<>();
    private final MapToolbox toolbox;
    private volatile LlmClient client;
    private volatile boolean cancelled;
    private volatile Preflight preflight;

    public ChatSession(MapToolbox toolbox) {
        this.toolbox = toolbox;
    }

    public void setClient(LlmClient client) {
        this.client = client;
    }

    public void setPreflight(Preflight p) {
        this.preflight = p;
    }

    public void clearHistory() {
        synchronized (history) {
            history.clear();
        }
    }

    public void cancel() {
        cancelled = true;
        LlmClient c = client;
        if (c != null) c.abort();
    }

    public void shutdown() {
        cancel();
        exec.shutdownNow();
    }

    /**
     * Pre-processes the system prompt and tool list right after a model loads, so the
     * server's KV cache already holds them when the first real question arrives (on a
     * phone CPU that prefix can take a minute or more to process). Runs on the chat
     * thread so it never overlaps a real question.
     */
    public void warmUp(final Runnable onStart, final Runnable onDone) {
        exec.execute(new Runnable() {
            @Override
            public void run() {
                LlmClient c = client;
                if (c == null) return;
                if (onStart != null) onStart.run();
                try {
                    JSONArray messages = new JSONArray();
                    messages.put(msg("system", systemPrompt()));
                    messages.put(msg("user", "Reply with OK."));
                    c.chat(messages, MapToolbox.toolSchemas(), 1);
                } catch (Exception ignored) {
                    // best effort; the first real question just takes longer
                } finally {
                    if (onDone != null) onDone.run();
                }
            }
        });
    }

    public void send(final String userText, final Listener listener) {
        cancelled = false;
        exec.execute(new Runnable() {
            @Override
            public void run() {
                ChatSession.this.run(userText, listener);
            }
        });
    }

    private void run(String userText, Listener l) {
        l.onBusy(true);
        try {
            LlmClient c = client;
            if (c == null) throw new IllegalStateException("No LLM server configured");
            Preflight pf = preflight;
            if (pf != null) pf.ensureReady();
            if (cancelled) return;

            JSONArray messages = new JSONArray();
            messages.put(msg("system", systemPrompt()));
            synchronized (history) {
                for (JSONObject h : history) messages.put(h);
            }
            // The live situation rides on the newest user message, not the system prompt.
            // That keeps system prompt + tool schemas + history byte-identical between
            // turns, so llama-server reuses its cached prompt instead of re-reading
            // ~3k tokens on every question.
            JSONObject userMsg = msg("user", userText);
            messages.put(msg("user", situationBlock() + "\n\n" + userText));

            java.util.Map<String, String> toolCache = new java.util.HashMap<>();
            boolean fromMapCenter = false; // any tool result measured from the map center
            JSONArray tools = MapToolbox.toolSchemas();
            for (int round = 0; round <= MAX_TOOL_ROUNDS; round++) {
                if (cancelled) return;
                // On the last round withhold tools so the model must answer in text.
                final StringBuilder soFar = new StringBuilder();
                JSONObject reply = c.chatStream(messages, round < MAX_TOOL_ROUNDS ? tools : null, 1024,
                        new LlmClient.DeltaListener() {
                            @Override
                            public void onText(String delta) {
                                if (cancelled) return;
                                soFar.append(delta);
                                String shown = partialClean(soFar.toString());
                                if (!shown.isEmpty()) l.onAssistantPartial(shown);
                            }
                        });
                if (cancelled) return;

                JSONArray calls = reply.optJSONArray("tool_calls");
                if (calls != null && calls.length() > 0) {
                    messages.put(reply);
                    for (int i = 0; i < calls.length(); i++) {
                        if (cancelled) return;
                        JSONObject call = calls.getJSONObject(i);
                        JSONObject fn = call.optJSONObject("function");
                        String name = fn == null ? "" : fn.optString("name", "");
                        String id = call.optString("id", "call_" + round + "_" + i);
                        JSONObject args = parseArgs(fn == null ? null : fn.opt("arguments"));

                        String key = name + " " + args.toString();
                        String result = toolCache.get(key);
                        if (result == null) {
                            l.onToolActivity(key);
                            result = toolbox.execute(name, args);
                            // Keep tool output small: every token costs prompt time on a phone CPU.
                            if (result.length() > 5000) {
                                result = result.substring(0, 5000) + "...(truncated)";
                            }
                            toolCache.put(key, result);
                            if (result.contains("\"map_center\"") || result.contains("\"fix\":false")) {
                                fromMapCenter = true;
                            }
                        } else {
                            result = "{\"note\":\"You already called this with the same arguments; "
                                    + "the result is unchanged. Answer the user now.\",\"result\":"
                                    + result + "}";
                        }

                        JSONObject toolMsg = new JSONObject();
                        toolMsg.put("role", "tool");
                        toolMsg.put("tool_call_id", id);
                        toolMsg.put("name", name);
                        toolMsg.put("content", result);
                        messages.put(toolMsg);
                    }
                    continue;
                }

                String text = cleanup(reply.isNull("content") ? "" : reply.optString("content", ""));
                if (text.isEmpty()) text = "(empty response)";
                if (fromMapCenter) {
                    // Added by the plugin, not the model, so it can't be forgotten.
                    text += "\n\n*(Measured from the map center: no GPS fix.)*";
                }
                remember(userMsg, msg("assistant", text));
                l.onAssistantMessage(text);
                return;
            }
        } catch (Exception e) {
            if (!cancelled) {
                l.onError(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
        } finally {
            l.onBusy(false);
        }
    }

    private String systemPrompt() {
        return "You are TAK Assist inside ATAK on a phone. Rules:\n"
                + "- Be brief; the user reads on a small screen.\n"
                + "- Never estimate positions, distances or bearings; use tools.\n"
                + "- Positions: copy the tool's 'position' field as written (MGRS, then lat/lon "
                + "in parentheses). Bearings in degrees true. "
                + "Distances in m under 1 km, else km.\n"
                + "- You read the map through tools; never say you can't see it. Streets, places, "
                + "water, parks, stations: find_place, find_nearest, get_nearby_roads, "
                + "get_nearby_places. Routes: get_directions.\n"
                + "- 'Markers' are only ATAK points; named places on the basemap are not markers.\n"
                + "- Only place markers or move the map when asked. Report tool errors plainly.\n"
                + "- Each user message starts with a [Situation] block of live data; use it directly "
                + "when it answers the question.";
    }

    private String situationBlock() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        String local = toolbox.offlineContext();
        return "[Situation]\nTime: " + f.format(new Date()) + "\n" + toolbox.situationSummary()
                + (local.isEmpty() ? "" : "\n" + local);
    }

    private void remember(JSONObject user, JSONObject assistant) {
        synchronized (history) {
            history.add(user);
            history.add(assistant);
            while (history.size() > MAX_HISTORY_MESSAGES) {
                history.remove(0);
                history.remove(0);
            }
        }
    }

    private static JSONObject parseArgs(Object raw) {
        if (raw instanceof JSONObject) return (JSONObject) raw;
        String s = raw == null ? "" : raw.toString().trim();
        if (s.isEmpty() || "null".equals(s)) return new JSONObject();
        try {
            return new JSONObject(s);
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    /** Strips Qwen-style reasoning blocks if the server leaves them in the content. */
    private static String cleanup(String s) {
        return mgrsFirst(s.replaceAll("(?s)<think>.*?</think>", "").trim());
    }

    /** Display version of a reply that is still arriving: hides an unfinished think block. */
    static String partialClean(String s) {
        String t = s.replaceAll("(?s)<think>.*?</think>", "");
        int open = t.indexOf("<think>");
        if (open >= 0) t = t.substring(0, open);
        return mgrsFirst(t.trim());
    }

    /** One MGRS grid reference, e.g. "18S UJ 25063 10220" or "18SUJ2506310220". */
    private static final String MGRS_RX =
            "\\d{1,2}[C-HJ-NP-X]\\s?[A-HJ-NP-Z]{2}\\s?\\d{1,5}\\s?\\d{1,5}";
    private static final java.util.regex.Pattern LATLON_THEN_MGRS = java.util.regex.Pattern.compile(
            "(?:(?i:lat(?:itude)?\\s*[/,]\\s*lon(?:g|gitude)?)\\s*:?\\s*)?(-?\\d{1,2}\\.\\d{3,})\\s*°?\\s*,\\s*(-?\\d{1,3}\\.\\d{3,})\\s*°?\\s*"
                    + "\\(\\s*(?:MGRS[:\\s]*)?(" + MGRS_RX + ")\\s*\\)");

    /**
     * Enforces "MGRS first, then lat/lon" in replies: rewrites "38.92348, -77.01798
     * (MGRS 18S UJ 25063 10220)" as "18S UJ 25063 10220 (38.92348, -77.01798)".
     */
    static String mgrsFirst(String s) {
        java.util.regex.Matcher m = LATLON_THEN_MGRS.matcher(s);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String rep = m.group(3) + " (" + m.group(1) + ", " + m.group(2) + ")";
            m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(rep));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static JSONObject msg(String role, String content) throws JSONException {
        return new JSONObject().put("role", role).put("content", content);
    }
}
