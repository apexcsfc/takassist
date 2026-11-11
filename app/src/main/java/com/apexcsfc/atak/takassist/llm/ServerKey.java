package com.apexcsfc.atak.takassist.llm;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/**
 * The password for the bundled model server. A new random one is made every time the server
 * starts and is only ever held in ATAK's memory and, briefly, in a file in ATAK's private
 * storage that the server reads at start-up. Chat and Talk attach it to every request, so
 * other apps and web pages on the phone can't use the model.
 */
public final class ServerKey {
    private static volatile String key;

    private ServerKey() {
    }

    /** Makes a new key, writes it to {@code file} (owner-only) and returns it. */
    static String rotate(File file) throws IOException {
        byte[] b = new byte[32];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder(64);
        for (byte x : b) sb.append(String.format(java.util.Locale.US, "%02x", x));
        String k = sb.toString();
        File dir = file.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
        try (FileOutputStream o = new FileOutputStream(file)) {
            o.write(k.getBytes(StandardCharsets.US_ASCII));
        }
        //noinspection ResultOfMethodCallIgnored
        file.setReadable(false, false);
        //noinspection ResultOfMethodCallIgnored
        file.setReadable(true, true);
        key = k;
        return k;
    }

    static boolean isSet() {
        return key != null;
    }

    /** Adds the key to a request, but only one going to this phone (never to a remote server). */
    public static void apply(HttpURLConnection c, String baseUrl) {
        String k = key;
        if (k == null) return;
        try {
            if (com.apexcsfc.atak.takassist.Privacy.isLoopback(new URL(baseUrl).getHost())) {
                c.setRequestProperty("Authorization", "Bearer " + k);
            }
        } catch (java.net.MalformedURLException ignored) {
            // no key for an address we can't read
        }
    }
}
