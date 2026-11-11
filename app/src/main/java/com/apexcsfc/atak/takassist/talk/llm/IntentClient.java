package com.apexcsfc.atak.takassist.talk.llm;

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
 * Talks to the llama-server that LLM Assist runs (127.0.0.1:8080 by default).
 * One short, schema-constrained request per report. Blocking; call off the UI thread.
 */
public class IntentClient {

    public static final String DEFAULT_URL = "http://127.0.0.1:8080";

    private static final int CONNECT_TIMEOUT_MS = 3_000;
    private static final int READ_TIMEOUT_MS = 60_000;

    private final String baseUrl;
    private volatile HttpURLConnection active;

    public IntentClient(String baseUrl) {
        String u = baseUrl == null || baseUrl.trim().isEmpty() ? DEFAULT_URL : baseUrl.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        if (u.endsWith("/v1")) u = u.substring(0, u.length() - 3);
        this.baseUrl = u;
    }

    /** True when the server answers /health with 200 (model loaded). */
    public boolean isReady() {
        try {
            request("GET", "/health", null, CONNECT_TIMEOUT_MS);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Sends one report and returns the model's raw JSON text. */
    public String extract(String report) throws IOException, JSONException {
        return extract(IntentPrompt.SYSTEM, "marker_report", IntentPrompt.schema(), report, 300);
    }

    /** One schema-constrained request with any prompt (used for MEDEVAC and SALUTE details). */
    public String extract(String system, String schemaName, JSONObject schema, String report, int maxTokens)
            throws IOException, JSONException {
        JSONArray messages = new JSONArray()
                .put(new JSONObject().put("role", "system").put("content", system))
                .put(new JSONObject().put("role", "user").put("content", report));

        JSONObject body = new JSONObject();
        body.put("model", "local");
        body.put("messages", messages);
        body.put("temperature", 0);
        body.put("max_tokens", maxTokens);
        body.put("stream", false);
        // llama-server turns this schema into a grammar, so the reply always parses.
        body.put("response_format", new JSONObject()
                .put("type", "json_schema")
                .put("json_schema", new JSONObject()
                        .put("name", schemaName)
                        .put("schema", schema)));
        // Qwen 3.x templates: skip the thinking block; this is a copy-into-fields task.
        body.put("chat_template_kwargs", new JSONObject().put("enable_thinking", false));

        String text = request("POST", "/v1/chat/completions", body.toString(), READ_TIMEOUT_MS);
        JSONArray choices = new JSONObject(text).optJSONArray("choices");
        if (choices == null || choices.length() == 0) throw new IOException("The model returned nothing.");
        return choices.getJSONObject(0).getJSONObject("message").optString("content", "");
    }

    /** Drops an in-flight request. */
    public void abort() {
        HttpURLConnection c = active;
        if (c != null) c.disconnect();
    }

    private String request(String method, String path, String body, int readTimeoutMs)
            throws IOException {
        com.apexcsfc.atak.takassist.Privacy.checkModelServer(baseUrl);
        HttpURLConnection c = (HttpURLConnection) new URL(baseUrl + path).openConnection();
        com.apexcsfc.atak.takassist.llm.ServerKey.apply(c, baseUrl);
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
                throw new IOException("HTTP " + code + ": "
                        + (text.length() > 300 ? text.substring(0, 300) + "…" : text));
            }
            return text;
        } finally {
            active = null;
            c.disconnect();
        }
    }

    private static String readAll(InputStream is) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) != -1) sb.append(buf, 0, n);
        }
        return sb.toString();
    }
}
