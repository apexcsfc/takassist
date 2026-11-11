package com.apexcsfc.atak.takassist.llm;

import android.content.Context;
import android.os.Environment;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Runs the llama.cpp server that ships inside this plugin's APK.
 *
 * The binary is packaged as jniLibs/arm64-v8a/libllamaserver.so so Android
 * extracts it to the plugin's nativeLibraryDir, the one app location that is
 * allowed to hold executables. It runs as a child process of ATAK (same UID,
 * so it can read the model from /sdcard), not inside ATAK's JVM, so a model
 * crash or out-of-memory kill doesn't take ATAK down.
 *
 * Models are looked up in Download/TAKAssist/ (first .gguf alphabetically,
 * ignoring mmproj files) unless a specific path is configured.
 *
 * All blocking methods must be called off the UI thread.
 */
public class LlmServerManager {

    public enum State { STOPPED, STARTING, READY, FAILED }

    public interface StatusListener {
        void onStatus(State state, String detail);
    }

    public static final String BINARY_NAME = "libllamaserver.so";        // static, CPU only
    public static final String SNAP_BINARY = "libllamaserver_snap.so";   // Snapdragon GPU/NPU build
    public static final String CPU = "cpu", GPU = "gpu", NPU = "npu";
    /** Each Hexagon NPU session can map ~3.2 GB; keep each slice of weights below that. */
    private static final long NPU_BYTES_PER_SESSION = 2_600_000_000L;
    private static final long LOAD_TIMEOUT_MS = 10 * 60 * 1000L;

    private final File binary;
    private final File binarySnap;
    private final String libDir;
    private final File workDir;
    private final File logFile;
    private final File pidFile;

    private volatile Process process;
    private volatile State state = State.STOPPED;
    private volatile String detail = "";
    private volatile StatusListener listener;

    // Launch settings (set before start)
    private volatile String modelPath = "";
    private volatile int port = 8080;
    private volatile int contextSize = 8192;
    private volatile String accelerator = CPU;
    private volatile String runningOn = CPU;   // what the current process actually uses

    public LlmServerManager(Context pluginContext) {
        libDir = pluginContext.getApplicationInfo().nativeLibraryDir;
        binary = new File(libDir, BINARY_NAME);
        binarySnap = new File(libDir, SNAP_BINARY);
        // Models and maps share Download/TAKAssist, so MDM file deployment (Knox Manage
        // Content can only write to public folders) can put both on the phone.
        workDir = com.apexcsfc.takassist.maps.core.MapPacks.root();
        logFile = new File(workDir, "server.log");
        pidFile = new File(workDir, "server.pid");
        migrateModels();
    }

    /** Moves models from the old atak/llm folder to Download/TAKAssist (same volume: instant). */
    private void migrateModels() {
        File old = com.apexcsfc.takassist.maps.core.MapPacks.legacyRoot();
        File[] files = old.listFiles();
        if (files == null) return;
        workDir.mkdirs();
        for (File f : files) {
            String n = f.getName().toLowerCase(Locale.US);
            if (f.isFile() && n.endsWith(".gguf")) {
                File to = new File(workDir, f.getName());
                if (!to.exists()) f.renameTo(to);
            }
        }
    }

    /** Every .gguf under Download/ (TAKAssist first, then e.g. KnoxManage/Content), sorted. */
    public java.util.List<File> findModels() {
        java.util.List<File> out = new java.util.ArrayList<>();
        addModels(workDir, out, 0, 0);
        addModels(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), out, 0, 4);
        return out;
    }

    private void addModels(File dir, java.util.List<File> out, int depth, int maxDepth) {
        File[] list = dir == null ? null : dir.listFiles();
        if (list == null) return;
        Arrays.sort(list);
        for (File f : list) {
            String n = f.getName().toLowerCase(Locale.US);
            if (f.isFile() && n.endsWith(".gguf") && !n.contains("mmproj")) {
                if (!out.contains(f)) out.add(f);
            } else if (f.isDirectory() && depth < maxDepth && !n.startsWith(".") && !f.equals(workDir)) {
                addModels(f, out, depth + 1, maxDepth);
            }
        }
    }

    // ----------------------------------------------------------- settings

    public void setListener(StatusListener l) {
        listener = l;
        if (l != null) l.onStatus(state, detail);
    }

    public void configure(String modelPath, int port, int contextSize, String accelerator) {
        this.accelerator = accelerator == null ? CPU : accelerator;
        configure(modelPath, port, contextSize);
    }

    /** True when the Snapdragon GPU/NPU build of the server is bundled. */
    public boolean hasAccelerators() {
        return binarySnap.isFile();
    }

    /** "CPU", "GPU" or "NPU": what the running server is using. */
    public String runningOn() {
        return runningOn.toUpperCase(Locale.US);
    }

    public void configure(String modelPath, int port, int contextSize) {
        this.modelPath = modelPath == null ? "" : modelPath.trim();
        this.port = port;
        this.contextSize = contextSize;
    }

    public File getModelDir() {
        return workDir;
    }

    public State getState() {
        return state;
    }

    public boolean isBundled() {
        return binary.isFile();
    }

    /** The model that start() would load, or null if none is found. */
    public File resolveModel() {
        if (!modelPath.isEmpty()) {
            File f = new File(modelPath);
            // A path saved before the move to Download/TAKAssist: same file, new folder.
            if (!f.isFile()) f = new File(workDir, f.getName());
            return f.isFile() && isAllowedModel(f) ? f : null;
        }
        // Never pick a model on the user's behalf: any app can put a .gguf in Download.
        return null;
    }

    /**
     * Only .gguf files inside the phone's Download folder (Download/TAKAssist and below) are
     * loaded, after resolving symlinks and "..", so a saved setting can't point the server at
     * anything else.
     */
    public boolean isAllowedModel(File f) {
        try {
            File c = f.getCanonicalFile();
            String n = c.getName().toLowerCase(Locale.US);
            if (!n.endsWith(".gguf") || n.contains("mmproj")) return false;
            File parent = c.getParentFile();
            if (parent == null) return false;
            File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).getCanonicalFile();
            // Anywhere inside Download/ (Download/TAKAssist, Download/KnoxManage/Content, ...).
            for (File p = parent; p != null; p = p.getParentFile()) {
                if (p.equals(downloads)) return true;
            }
            return false;
        } catch (IOException e) {
            return false;
        }
    }

    // ---------------------------------------------------------- lifecycle

    /**
     * Makes sure a server is answering on the port, starting the bundled one if
     * needed, and blocks until the model is loaded. Safe to call repeatedly.
     */
    public synchronized void ensureRunning() throws IOException, InterruptedException {
        LlmClient probe = new LlmClient("http://127.0.0.1:" + port, null, false);

        if (healthy(probe)) {
            if (owned(probe)) {
                deleteKeyFile();
                set(State.READY, process == null ? "Server already running" : "Model loaded");
                return;
            }
            // Answering, but not with this session's password: a server left over from an
            // earlier ATAK session. Replace it so Chat and Talk can use the new password.
            killOrphan();
            Thread.sleep(1500);
            if (healthy(probe)) {
                String why = "Another program is already using port " + port + ". Stop it and try again.";
                set(State.FAILED, why);
                throw new IOException(why);
            }
        }
        boolean fellBack = false;
        if (process == null || !isAlive(process)) {
            launch(false);
        }

        long deadline = System.currentTimeMillis() + LOAD_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            Process p = process;
            if (p == null) {
                throw new IOException("Server stopped while loading");
            }
            if (!isAlive(p)) {
                process = null;
                String why = "Server exited (code " + exitCode(p) + "): " + lastLogLine();
                if (!CPU.equals(runningOn) && !fellBack) {
                    // GPU/NPU didn't come up (driver, permissions, unsupported op...): use the CPU.
                    fellBack = true;
                    set(State.STARTING, runningOn() + " failed (" + lastLogLine() + "). Using CPU…");
                    // Keep the GPU/NPU attempt's log; the CPU run overwrites server.log.
                    copyQuietly(logFile, new File(workDir, "server-accel.log"));
                    launch(true);
                    continue;
                }
                set(State.FAILED, why);
                throw new IOException(why);
            }
            if (healthy(probe)) {
                deleteKeyFile();
                set(State.READY, "Model loaded");
                return;
            }
            Thread.sleep(1000);
        }
        stop();
        set(State.FAILED, "Timed out loading the model");
        throw new IOException("Timed out loading the model");
    }

    /** Not synchronized, so it can interrupt a load that's in progress in ensureRunning(). */
    public void stop() {
        Process p = process;
        process = null;
        if (p != null) {
            p.destroy();
            try {
                for (int i = 0; i < 30 && isAlive(p); i++) Thread.sleep(100);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            if (isAlive(p)) killPid(readPid());
        }
        //noinspection ResultOfMethodCallIgnored
        pidFile.delete();
        set(State.STOPPED, "");
    }

    private void launch(boolean forceCpu) throws IOException {
        boolean accel = !forceCpu && !CPU.equals(accelerator) && binarySnap.isFile();
        runningOn = accel ? accelerator : CPU;
        File exe = accel ? binarySnap : binary;
        if (!exe.isFile()) {
            set(State.FAILED, "llama-server is not bundled in this plugin build");
            throw new IOException(detail);
        }
        File model = resolveModel();
        if (model == null) {
            String where = modelPath.isEmpty() ? "No model chosen. Tap Change to pick one."
                    : "Model not found: " + new File(modelPath).getName() + ". Tap Change to pick another.";
            set(State.FAILED, where);
            throw new IOException(where);
        }
        if (!workDir.isDirectory() && !workDir.mkdirs()) {
            throw new IOException("Cannot create " + workDir);
        }

        killOrphan();

        // Memory guard. Repacked weights (llama.cpp's default on ARM) live in anonymous RAM
        // that Android can only swap, so a model that barely fits makes the low-memory killer
        // take out ATAK. Without repacking, weights stay as file-backed mmap pages that the
        // kernel can drop and re-read instead.
        long modelBytes = model.length();
        long availBytes = memAvailableBytes();
        boolean lowMemory = false;
        if (availBytes > 0) {
            if (modelBytes > availBytes) {
                String why = String.format(Locale.US,
                        "Not enough memory for %s (%.1f GB model, %.1f GB free). Choose a smaller model.",
                        model.getName(), modelBytes / 1e9, availBytes / 1e9);
                set(State.FAILED, why);
                throw new IOException(why);
            }
            lowMemory = modelBytes + 1_500_000_000L > availBytes;
        }

        int threads = Math.max(2, Math.min(6, Runtime.getRuntime().availableProcessors() - 2));
        File kf = keyFile();
        ServerKey.rotate(kf);
        String keyPath = kf.getAbsolutePath();
        List<String> args = new ArrayList<>();
        args.add(exe.getAbsolutePath());
        args.addAll(Arrays.asList(
                "-m", model.getAbsolutePath(),
                "--host", "127.0.0.1",
                "--port", String.valueOf(port),
                "-c", String.valueOf(contextSize),
                "-t", String.valueOf(threads),
                "-np", "1",
                "--jinja",
                "--no-webui",
                // A new password every start (read from ATAK's private storage, so it never
                // appears on the command line), and no /slots endpoint listing conversations.
                "--api-key-file", keyPath,
                "--no-slots",
                // Phone memory: the default 8 GB RAM prompt cache and 32 context checkpoints
                // per slot can push model + ATAK past 12 GB and get both killed. The slot's own
                // KV cache still reuses the shared prompt prefix between questions.
                "--cache-ram", "0",
                "--ctx-checkpoints", "4"));
        int npuSessions = 0;
        if (GPU.equals(runningOn)) {
            args.addAll(Arrays.asList("--device", "GPUOpenCL", "-ngl", "99"));
        } else if (NPU.equals(runningOn)) {
            npuSessions = (int) Math.max(1, Math.min(4,
                    (modelBytes + NPU_BYTES_PER_SESSION - 1) / NPU_BYTES_PER_SESSION));
            StringBuilder dev = new StringBuilder();
            for (int i = 0; i < npuSessions; i++) dev.append(i == 0 ? "" : ",").append("HTP").append(i);
            args.addAll(Arrays.asList("--device", dev.toString(), "-ngl", "99"));
        } else if (lowMemory) {
            args.add("--no-repack");
        }

        // The server is started directly (no shell), with an argument list built only from
        // fixed flags, numbers and the checked model path above.
        ProcessBuilder pb = new ProcessBuilder(args);
        pb.directory(workDir);
        pb.redirectErrorStream(true);
        boolean redirected = false;
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            pb.redirectOutput(logFile);
            redirected = true;
        }
        if (accel) {
            // The Snapdragon build is dynamically linked: its own libraries live next to it,
            // the Adreno OpenCL driver and FastRPC (for the NPU) live in /vendor.
            java.util.Map<String, String> env = pb.environment();
            // /system/lib64 must come before /vendor/lib64: the vendor partition has its own,
            // older libc++.so, and system libraries (liblog) fail to link against it.
            // /vendor/lib64 is still searched for the Adreno driver (libOpenCL.so).
            env.put("LD_LIBRARY_PATH", libDir + ":/system/lib64:/vendor/lib64");
            env.put("ADSP_LIBRARY_PATH", libDir + ";/vendor/dsp/cdsp;/vendor/lib/rfsa/adsp;"
                    + "/system/lib/rfsa/adsp;/vendor/dsp");
            if (npuSessions > 0) env.put("GGML_HEXAGON_DEVICES", String.valueOf(npuSessions));
        }

        String where = accel ? " on " + runningOn() : (lowMemory ? " (low-memory mode)" : "");
        set(State.STARTING, "Loading " + model.getName() + where + "…");
        process = pb.start();
        if (!redirected) pumpToLog(process);
        recordPid(exe.getAbsolutePath());
    }

    /** Before Android 8 ProcessBuilder can't write to a file itself: copy the output over. */
    private void pumpToLog(final Process p) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try (java.io.InputStream in = p.getInputStream();
                     java.io.OutputStream out = new java.io.FileOutputStream(logFile)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        out.flush();
                    }
                } catch (IOException ignored) {
                    // server exited
                }
            }
        }, "llmassist-serverlog").start();
    }

    private static void copyQuietly(File from, File to) {
        try (java.io.InputStream in = new java.io.FileInputStream(from);
             java.io.OutputStream out = new java.io.FileOutputStream(to)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } catch (IOException ignored) {
            // the copy is only a convenience for troubleshooting
        }
    }

    /**
     * Writes the server's PID to server.pid so a later session can clean up a server
     * left behind if ATAK was killed. Found by matching /proc/<pid>/cmdline against
     * the binary we just started (Process.pid() isn't available before Android 13).
     */
    private void recordPid(String exePath) {
        for (int attempt = 0; attempt < 20; attempt++) {
            int pid = findPid(exePath);
            if (pid > 0) {
                try (java.io.FileWriter w = new java.io.FileWriter(pidFile)) {
                    w.write(String.valueOf(pid));
                } catch (IOException ignored) {
                    // cleanup on next launch just won't find it
                }
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** Newest process whose command line starts with exePath, or -1. */
    private static int findPid(String exePath) {
        File[] procs = new File("/proc").listFiles();
        if (procs == null) return -1;
        int best = -1;
        for (File p : procs) {
            String name = p.getName();
            if (name.isEmpty() || !Character.isDigit(name.charAt(0))) continue;
            try (BufferedReader r = new BufferedReader(new FileReader(new File(p, "cmdline")))) {
                String line = r.readLine();
                if (line == null) continue;
                int nul = line.indexOf('\0');
                String argv0 = nul >= 0 ? line.substring(0, nul) : line;
                if (argv0.equals(exePath)) best = Math.max(best, Integer.parseInt(name));
            } catch (IOException | NumberFormatException ignored) {
                // not readable or gone
            }
        }
        return best;
    }

    // ------------------------------------------------------------ helpers

    /** MemAvailable from /proc/meminfo in bytes, or -1 if unreadable. */
    static long memAvailableBytes() {
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/meminfo"))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("MemAvailable:")) {
                    String[] parts = line.trim().split("\\s+");
                    return Long.parseLong(parts[1]) * 1024L;
                }
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    /** True if the server on the port accepts this session's password. */
    private static boolean owned(LlmClient probe) {
        if (!ServerKey.isSet()) return false;
        try {
            probe.checkAccess();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** ATAK's private storage: only ATAK (and the server it starts) can read it. */
    private static File keyFile() {
        com.atakmap.android.maps.MapView mv = com.atakmap.android.maps.MapView.getMapView();
        return new File(new File(mv.getContext().getFilesDir(), "takassist"), "server.key");
    }

    /** The server has read the password by the time it answers; don't leave it on disk. */
    private static void deleteKeyFile() {
        try {
            //noinspection ResultOfMethodCallIgnored
            keyFile().delete();
        } catch (RuntimeException ignored) {
        }
    }

    private static boolean healthy(LlmClient probe) {
        try {
            probe.health();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean isAlive(Process p) {
        try {
            p.exitValue();
            return false;
        } catch (IllegalThreadStateException e) {
            return true;
        }
    }

    private static int exitCode(Process p) {
        try {
            return p.exitValue();
        } catch (IllegalThreadStateException e) {
            return -1;
        }
    }

    /** Kills a server left over from a previous ATAK session, if the PID still belongs to it. */
    private void killOrphan() {
        killPid(readPid());
        //noinspection ResultOfMethodCallIgnored
        pidFile.delete();
    }

    private int readPid() {
        try (BufferedReader r = new BufferedReader(new FileReader(pidFile))) {
            return Integer.parseInt(r.readLine().trim());
        } catch (Exception e) {
            return -1;
        }
    }

    private static void killPid(int pid) {
        if (pid <= 0) return;
        // Only kill it if the PID still points at our binary (PIDs get reused).
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/" + pid + "/cmdline"))) {
            String cmdline = r.readLine();
            // Matches both the CPU (libllamaserver.so) and Snapdragon (libllamaserver_snap.so) builds.
            if (cmdline != null && cmdline.contains("libllamaserver")) {
                android.os.Process.killProcess(pid);
            }
        } catch (IOException ignored) {
            // process is gone
        }
    }

    /** Last non-empty line of the server log, for error messages. */
    public String lastLogLine() {
        try (RandomAccessFile f = new RandomAccessFile(logFile, "r")) {
            long len = f.length();
            int n = (int) Math.min(len, 4096);
            byte[] buf = new byte[n];
            f.seek(len - n);
            f.readFully(buf);
            String[] lines = new String(buf, StandardCharsets.UTF_8).split("\n");
            for (int i = lines.length - 1; i >= 0; i--) {
                String s = lines[i].trim();
                if (!s.isEmpty()) return LlmClient.truncate(s, 200);
            }
        } catch (IOException ignored) {
        }
        return "see " + logFile;
    }

    private void set(State s, String d) {
        state = s;
        detail = d == null ? "" : d;
        StatusListener l = listener;
        if (l != null) l.onStatus(state, detail);
    }


    /** Writes a short README into the model folder so the user knows where to put files. */
    public void ensureModelDirReadme() {
        try {
            if (!workDir.isDirectory() && !workDir.mkdirs()) return;
            File readme = new File(workDir, "README.txt");
            if (readme.exists()) return;
            try (FileWriter w = new FileWriter(readme)) {
                w.write("Put a .gguf model file in this folder (for example a Q4_K_M quant).\n"
                        + "TAK Assist loads the first .gguf it finds, alphabetically, or the one picked in Chat > Settings.\n"
                        + "server.log in this folder has the model server's output.\n");
            }
        } catch (IOException ignored) {
        }
    }
}
