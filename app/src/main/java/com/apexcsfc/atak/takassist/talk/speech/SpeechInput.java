package com.apexcsfc.atak.takassist.talk.speech;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Push-to-talk speech input that never leaves the phone: TAK Assist records the microphone
 * itself and transcribes with whisper.cpp (bundled as libwhispercli.so) and a whisper model
 * file in Download/TAKAssist (ggml-base.en-q5_1.bin by default). No Google speech service,
 * no language-pack download, no network, no server port.
 *
 * Runs with ATAK's context, so ATAK needs the microphone permission. Callbacks arrive on
 * the UI thread. The public API matches the earlier Android-recognizer version.
 */
public class SpeechInput {

    public interface Listener {
        void onPartial(String text);

        void onFinal(String text);

        void onError(String message);

        /** Informational; listening continues. */
        void onNotice(String message);
    }

    /** Words whisper should expect (passed as its initial prompt). */
    private static final List<String> BIAS = Arrays.asList(
            "hostile", "friendly", "friendlies", "neutral", "enemy", "grid", "bearing", "azimuth",
            "magnetic", "meters", "klicks", "dismounts", "vehicle", "truck", "drone", "MGRS",
            "SALUTE", "MEDEVAC", "nine-line", "north", "south", "east", "west", "northeast",
            "northwest", "southeast", "southwest");

    public static final String DEFAULT_MODEL = "ggml-base.en-q5_1.bin";
    private static final String PLUGIN_PACKAGE = "com.apexcsfc.atak.takassist";
    private static final String BINARY = "libwhispercli.so";

    private static final int RATE = 16000;
    /** Speech ends after this much quiet (same as the old recognizer's setting). */
    private static final long END_SILENCE_MS = 2500;
    /** Give up if nobody speaks within this long. */
    private static final long NO_SPEECH_MS = 8000;
    private static final long MAX_MS = 45000;

    private final Context context;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Listener listener;
    private volatile boolean listening;
    private volatile boolean working;
    private volatile boolean stopRequested;
    private volatile boolean cancelled;
    private Thread worker;

    public SpeechInput(Context atakContext) {
        this.context = atakContext;
    }

    /** Null when speech can be used, otherwise why not. */
    public String unavailableReason() {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            return "ATAK doesn't have microphone permission. Allow it in Android Settings → Apps → ATAK → Permissions, or type the report.";
        }
        if (binary() == null) {
            return "This TAK Assist build has no speech engine. Type the report instead.";
        }
        if (findModel() == null) {
            return "Speech model missing: put " + DEFAULT_MODEL + " in Download/TAKAssist (or deploy it with Knox Manage), or type the report.";
        }
        if (working) {
            return "Still transcribing the last report…";
        }
        return null;
    }

    public boolean isListening() {
        return listening;
    }

    /** Starts recording; speech ends by itself after a pause, or on stop(). UI thread. */
    public void start(Listener l) {
        if (listening || working) return;
        this.listener = l;
        stopRequested = false;
        cancelled = false;
        listening = true;
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                runOnce();
            }
        }, "takassist-speech");
        worker.start();
    }

    /** Button released: stop recording and transcribe what was said. */
    public void stop() {
        stopRequested = true;
    }

    public void cancel() {
        cancelled = true;
        stopRequested = true;
    }

    public void destroy() {
        cancel();
        listener = null;
    }

    // ------------------------------------------------------------------ recording

    private void runOnce() {
        byte[] pcm;
        try {
            pcm = record();
        } catch (SecurityException e) {
            finishError("ATAK doesn't have microphone permission. Allow it in Android Settings → Apps → ATAK → Permissions.");
            return;
        } catch (Exception e) {
            finishError("Couldn't use the microphone: " + e.getMessage());
            return;
        } finally {
            listening = false;
        }
        if (cancelled) return;
        if (pcm == null) {
            finishError("Didn't hear anything. Hold the button and speak, or type the report.");
            return;
        }
        working = true;
        post(new Runnable() {
            @Override
            public void run() {
                if (listener != null) listener.onNotice("Transcribing on the phone…");
            }
        });
        try {
            final String text = transcribe(pcm);
            post(new Runnable() {
                @Override
                public void run() {
                    working = false;
                    if (listener == null || cancelled) return;
                    if (text.isEmpty()) {
                        listener.onError("Couldn't make out any words. Try again closer to the microphone, or type the report.");
                    } else {
                        listener.onFinal(text);
                    }
                }
            });
        } catch (Exception e) {
            working = false;
            finishError("Speech engine failed: " + e.getMessage());
        }
    }

    /** Records until a pause after speech, stop(), or the time limit. Null if no speech. */
    @SuppressWarnings("MissingPermission")
    private byte[] record() {
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int chunk = RATE / 10; // 100 ms of samples
        AudioRecord rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, chunk * 2 * 4));
        if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
            rec.release();
            throw new IllegalStateException("microphone is busy");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(RATE * 2 * 10);
        short[] buf = new short[chunk];
        byte[] bytes = new byte[chunk * 2];
        long start = System.currentTimeMillis(), lastVoice = 0;
        boolean heard = false;
        double noise = -1; // running estimate of the room's background level
        try {
            rec.startRecording();
            while (!stopRequested) {
                int n = rec.read(buf, 0, buf.length);
                if (n <= 0) continue;
                double sum = 0;
                for (int i = 0; i < n; i++) {
                    sum += (double) buf[i] * buf[i];
                    bytes[2 * i] = (byte) (buf[i] & 0xff);
                    bytes[2 * i + 1] = (byte) ((buf[i] >> 8) & 0xff);
                }
                out.write(bytes, 0, n * 2);
                double rms = Math.sqrt(sum / n);
                long now = System.currentTimeMillis();
                if (noise < 0) noise = rms;
                boolean voice = rms > Math.max(500, noise * 3);
                if (!voice) noise = noise * 0.95 + rms * 0.05;
                if (voice) {
                    lastVoice = now;
                    if (!heard) {
                        heard = true;
                        post(new Runnable() {
                            @Override
                            public void run() {
                                if (listener != null) listener.onPartial("…");
                            }
                        });
                    }
                }
                if (heard && now - lastVoice > END_SILENCE_MS) break;
                if (!heard && now - start > NO_SPEECH_MS) break;
                if (now - start > MAX_MS) break;
            }
        } finally {
            try {
                rec.stop();
            } catch (IllegalStateException ignored) {
            }
            rec.release();
        }
        // A button press counts as speech even if it was quiet; an empty hands-free wait doesn't.
        return heard || (stopRequested && out.size() > RATE) ? out.toByteArray() : null;
    }

    // ------------------------------------------------------------------ whisper

    private String transcribe(byte[] pcm) throws IOException, InterruptedException {
        File bin = binary(), model = findModel();
        if (bin == null || model == null) throw new IOException("speech engine or model missing");
        File dir = new File(context.getCacheDir(), "takassist-speech");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("can't create " + dir);
        File wav = new File(dir, "utterance.wav");
        writeWav(wav, pcm);
        try {
            int threads = Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() / 2));
            List<String> cmd = new ArrayList<>(Arrays.asList(
                    bin.getAbsolutePath(), "-m", model.getAbsolutePath(), "-f", wav.getAbsolutePath(),
                    "-t", String.valueOf(threads), "-l", "en", "-nt", "-np", "-bs", "1", "-bo", "1",
                    "--prompt", join(BIAS)));
            ProcessBuilder pb = new ProcessBuilder(cmd); // started directly: no shell
            pb.directory(dir);
            pb.redirectErrorStream(false);
            Process p = pb.start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line.trim()).append(' ');
            }
            if (!p.waitFor(90, TimeUnit.SECONDS)) {
                p.destroy();
                throw new IOException("timed out");
            }
            return clean(sb.toString());
        } finally {
            //noinspection ResultOfMethodCallIgnored
            wav.delete(); // the recording never stays on the phone
        }
    }

    /** Drops whisper's non-speech tags like [BLANK_AUDIO] or (wind). */
    static String clean(String s) {
        String t = s.replaceAll("\\[[^\\]]*\\]", " ").replaceAll("\\([^)]*\\)", " ");
        return t.replaceAll("\\s+", " ").trim();
    }

    private File binary() {
        try {
            String dir = context.getPackageManager().getApplicationInfo(PLUGIN_PACKAGE, 0).nativeLibraryDir;
            File f = new File(dir, BINARY);
            return f.isFile() ? f : null;
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    /** Download/TAKAssist/ggml-base.en-q5_1.bin, else any whisper ggml-*.bin there. */
    static File findModel() {
        File root = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "TAKAssist");
        File def = new File(root, DEFAULT_MODEL);
        if (def.isFile()) return def;
        File[] kids = root.listFiles();
        if (kids == null) return null;
        for (File k : kids) {
            String n = k.getName().toLowerCase(Locale.US);
            if (k.isFile() && n.startsWith("ggml-") && n.endsWith(".bin")) return k;
        }
        return null;
    }

    private static void writeWav(File f, byte[] pcm) throws IOException {
        try (OutputStream o = new FileOutputStream(f)) {
            int dataLen = pcm.length, byteRate = RATE * 2;
            byte[] h = new byte[44];
            put(h, 0, "RIFF");
            le32(h, 4, 36 + dataLen);
            put(h, 8, "WAVEfmt ");
            le32(h, 16, 16);
            le16(h, 20, 1);
            le16(h, 22, 1);
            le32(h, 24, RATE);
            le32(h, 28, byteRate);
            le16(h, 32, 2);
            le16(h, 34, 16);
            put(h, 36, "data");
            le32(h, 40, dataLen);
            o.write(h);
            o.write(pcm);
        }
    }

    private static void put(byte[] b, int at, String s) {
        byte[] x = s.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(x, 0, b, at, x.length);
    }

    private static void le32(byte[] b, int at, int v) {
        b[at] = (byte) v;
        b[at + 1] = (byte) (v >> 8);
        b[at + 2] = (byte) (v >> 16);
        b[at + 3] = (byte) (v >> 24);
    }

    private static void le16(byte[] b, int at, int v) {
        b[at] = (byte) v;
        b[at + 1] = (byte) (v >> 8);
    }

    private static String join(List<String> words) {
        StringBuilder sb = new StringBuilder();
        for (String w : words) sb.append(sb.length() == 0 ? "" : ", ").append(w);
        return sb.append('.').toString();
    }

    private void finishError(final String msg) {
        post(new Runnable() {
            @Override
            public void run() {
                if (listener != null && !cancelled) listener.onError(msg);
            }
        });
    }

    private void post(Runnable r) {
        ui.post(r);
    }
}
