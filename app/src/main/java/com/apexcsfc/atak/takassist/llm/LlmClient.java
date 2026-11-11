package com.apexcsfc.atak.takassist.llm;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Minimal client for an OpenAI-compatible /v1/chat/completions endpoint.
 * Targets llama.cpp's llama-server (run with --jinja so tool calling works),
 * but any OpenAI-compatible server (Ollama, LM Studio, vLLM) will also work.
 *
 * No third-party dependencies: HttpURLConnection + org.json (both in Android).
 * All calls block, so call them off the UI thread.
 */
public class LlmClient {

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    /** On-phone 9B inference can be slow on long prompts, so give it room. */
    private static final int CHAT_READ_TIMEOUT_MS = 300_000;

    private final String baseUrl;
    private final String model;
    private final boolean enableThinking;
    private volatile HttpURLConnection active;

    public LlmClient(String baseUrl, String model, boolean enableThinking) {
        String u = baseUrl == null ? "" : baseUrl.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        if (u.endsWith("/v1")) u = u.substring(0, u.length() - 3);
        this.baseUrl = u;
        this.model = model;
        this.enableThinking = enableThinking;
    }

    /**
     * Sends one chat completion request.
     *
     * @param messages OpenAI-format message array
     * @param tools    OpenAI-format tool schemas, or null to forbid tool use
     * @return the assistant "message" object (may contain content and/or tool_calls)
     */
    public JSONObject chat(JSONArray messages, JSONArray tools)
            throws IOException, JSONException {
        return chat(messages, tools, 1024);
    }

    public JSONObject chat(JSONArray messages, JSONArray tools, int maxTokens)
            throws IOException, JSONException {
        JSONObject body = new JSONObject();
        body.put("model", model == null || model.isEmpty() ? "local" : model);
        body.put("messages", messages);
        body.put("temperature", 0.3);
        body.put("max_tokens", maxTokens);
        body.put("stream", false);
        if (tools != null && tools.length() > 0) {
            body.put("tools", tools);
            body.put("tool_choice", "auto");
        }
        // Qwen 3.x chat templates read this flag; other templates ignore it.
        JSONObject kwargs = new JSONObject();
        kwargs.put("enable_thinking", enableThinking);
        body.put("chat_template_kwargs", kwargs);

        String text = request("POST", "/v1/chat/completions",
                body.toString(), CHAT_READ_TIMEOUT_MS);
        JSONObject json = new JSONObject(text);
        JSONArray choices = json.optJSONArray("choices");
        if (choices == null || choices.length() == 0) {
            throw new IOException("Server returned no choices: " + truncate(text, 200));
        }
        return choices.getJSONObject(0).getJSONObject("message");
    }

    /** Receives reply text as it is generated. */
    public interface DeltaListener {
        void onText(String delta);
    }

    /**
     * Like {@link #chat}, but streams the reply (Server-Sent Events) and calls
     * {@code listener} with each piece of text as it arrives. Tool calls arrive in
     * fragments and are reassembled. Returns the same "message" object as chat():
     * {"role":"assistant","content":...,"tool_calls":[...]}.
     */
    public JSONObject chatStream(JSONArray messages, JSONArray tools, int maxTokens,
                                 DeltaListener listener) throws IOException, JSONException {
        JSONObject body = new JSONObject();
        body.put("model", model == null || model.isEmpty() ? "local" : model);
        body.put("messages", messages);
        body.put("temperature", 0.3);
        body.put("max_tokens", maxTokens);
        body.put("stream", true);
        if (tools != null && tools.length() > 0) {
            body.put("tools", tools);
            body.put("tool_choice", "auto");
        }
        JSONObject kwargs = new JSONObject();
        kwargs.put("enable_thinking", enableThinking);
        body.put("chat_template_kwargs", kwargs);

        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        com.apexcsfc.atak.takassist.Privacy.checkModelServer(baseUrl);
        HttpURLConnection c = (HttpURLConnection) new URL(baseUrl + "/v1/chat/completions").openConnection();
        ServerKey.apply(c, baseUrl);
        active = c;
        StringBuilder content = new StringBuilder();
        // tool calls by index: id, name, arguments (accumulated)
        java.util.TreeMap<Integer, String[]> calls = new java.util.TreeMap<>();
        try {
            c.setRequestMethod("POST");
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(CHAT_READ_TIMEOUT_MS);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.setRequestProperty("Accept", "text/event-stream");
            c.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream os = c.getOutputStream()) {
                os.write(bytes);
            }
            int code = c.getResponseCode();
            if (code >= 400) {
                InputStream es = c.getErrorStream();
                throw new IOException("HTTP " + code + ": " + truncate(es == null ? "" : readAll(es), 300));
            }
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (!line.startsWith("data:")) continue;
                    String data = line.substring(5).trim();
                    if (data.isEmpty()) continue;
                    if ("[DONE]".equals(data)) break;
                    JSONObject ev = new JSONObject(data);
                    if (ev.has("error")) {
                        throw new IOException("Server error: " + truncate(ev.opt("error").toString(), 300));
                    }
                    JSONArray choices = ev.optJSONArray("choices");
                    if (choices == null || choices.length() == 0) continue;
                    JSONObject delta = choices.getJSONObject(0).optJSONObject("delta");
                    if (delta == null) continue;
                    if (delta.has("content") && !delta.isNull("content")) {
                        String piece = delta.optString("content", "");
                        if (!piece.isEmpty()) {
                            content.append(piece);
                            if (listener != null) listener.onText(piece);
                        }
                    }
                    JSONArray tc = delta.optJSONArray("tool_calls");
                    if (tc != null) {
                        for (int i = 0; i < tc.length(); i++) {
                            JSONObject t = tc.getJSONObject(i);
                            int idx = t.optInt("index", i);
                            String[] acc = calls.get(idx);
                            if (acc == null) {
                                acc = new String[]{"", "", ""};
                                calls.put(idx, acc);
                            }
                            if (t.has("id") && !t.isNull("id")) acc[0] = t.optString("id", acc[0]);
                            JSONObject fn = t.optJSONObject("function");
                            if (fn != null) {
                                if (fn.has("name") && !fn.isNull("name")) acc[1] += fn.optString("name", "");
                                if (fn.has("arguments") && !fn.isNull("arguments")) {
                                    acc[2] += fn.optString("arguments", "");
                                }
                            }
                        }
                    }
                }
            }
        } finally {
            active = null;
            c.disconnect();
        }

        JSONObject message = new JSONObject();
        message.put("role", "assistant");
        message.put("content", content.toString());
        if (!calls.isEmpty()) {
            JSONArray arr = new JSONArray();
            for (java.util.Map.Entry<Integer, String[]> e : calls.entrySet()) {
                String[] acc = e.getValue();
                JSONObject fn = new JSONObject();
                fn.put("name", acc[1]);
                fn.put("arguments", acc[2].isEmpty() ? "{}" : acc[2]);
                JSONObject call = new JSONObject();
                call.put("id", acc[0].isEmpty() ? "call_" + e.getKey() : acc[0]);
                call.put("type", "function");
                call.put("function", fn);
                arr.put(call);
            }
            message.put("tool_calls", arr);
        }
        return message;
    }

    /** Returns the body of GET /health; throws if the server is down or still loading. */
    public String health() throws IOException {
        return request("GET", "/health", null, CONNECT_TIMEOUT_MS);
    }

    /** Throws unless the server accepts this session's password (/props needs it; /health doesn't). */
    public void checkAccess() throws IOException {
        request("GET", "/props", null, CONNECT_TIMEOUT_MS);
    }

    /** Drops any in-flight request (used by the Stop button). */
    public void abort() {
        HttpURLConnection c = active;
        if (c != null) c.disconnect();
    }

    private String request(String method, String path, String body, int readTimeoutMs)
            throws IOException {
        com.apexcsfc.atak.takassist.Privacy.checkModelServer(baseUrl);
        HttpURLConnection c = (HttpURLConnection) new URL(baseUrl + path).openConnection();
        ServerKey.apply(c, baseUrl);
        active = c;
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(readTimeoutMs);
            c.setRequestProperty("Accept", "application/json");
            if (body != null) {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                c.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream os = c.getOutputStream()) {
                    os.write(bytes);
                }
            }
            int code = c.getResponseCode();
            InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = is == null ? "" : readAll(is);
            if (code >= 400) {
                throw new IOException("HTTP " + code + ": " + truncate(text, 300));
            }
            return text;
        } finally {
            active = null;
            c.disconnect();
        }
    }

    private static String readAll(InputStream is) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(is, StandardCharsets.UTF_8))) {
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) != -1) sb.append(buf, 0, n);
        }
        return sb.toString();
    }

    static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
