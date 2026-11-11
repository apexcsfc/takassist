package com.apexcsfc.takassist.maps.core;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.zip.GZIPInputStream;

/**
 * Fetches the catalog and region files. Supports https://, http:// and file:// addresses
 * (file:// lets an administrator point at packs copied to the phone or an SD card).
 * Partly downloaded files resume; every file is checked against its SHA-256.
 */
public final class Downloader {

    public interface Progress {
        /** done/total bytes of the current file; total may be 0 when unknown. */
        void onBytes(String what, long done, long total);
    }

    private static final int CONNECT_MS = 15_000, READ_MS = 60_000;
    private volatile boolean cancelled;

    public void cancel() {
        cancelled = true;
    }

    public static String resolve(String base, String rel) {
        try {
            return URI.create(base).resolve(rel.replace(" ", "%20")).toString();
        } catch (Exception e) {
            return rel;
        }
    }

    public String fetchText(String url) throws IOException {
        URLConnection c = open(url, 0);
        try (InputStream in = new BufferedInputStream(c.getInputStream())) {
            byte[] buf = new byte[8192];
            StringBuilder sb = new StringBuilder();
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            int n;
            while ((n = in.read(buf)) > 0) {
                bo.write(buf, 0, n);
                if (bo.size() > 4 << 20) throw new IOException("The region list is too large");
            }
            return new String(bo.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            if (c instanceof HttpURLConnection) ((HttpURLConnection) c).disconnect();
        }
    }

    /**
     * Downloads url to dest. A ".gz" source is unpacked on the fly. The SHA-256 (of the
     * unpacked file) must match when given. Resumes a previous partial download.
     */
    public void download(String url, boolean gz, File dest, long size, String sha256, Progress p) throws IOException {
        File part = new File(dest.getPath() + (gz ? ".gz.part" : ".part"));
        long have = part.isFile() ? part.length() : 0;
        if (size > 0 && have > size) {
            part.delete();
            have = 0;
        }
        if (size <= 0 || have < size) {
            URLConnection c = open(url, have);
            boolean append = have > 0 && c instanceof HttpURLConnection
                    && ((HttpURLConnection) c).getResponseCode() == 206;
            if (!append) have = 0;
            long total = size > 0 ? size : (c.getContentLengthLong() > 0 ? c.getContentLengthLong() + have : 0);
            try (InputStream in = new BufferedInputStream(c.getInputStream(), 1 << 16);
                 OutputStream out = new FileOutputStream(part, append)) {
                byte[] buf = new byte[1 << 16];
                int n;
                long done = have, lastTick = 0;
                while ((n = in.read(buf)) > 0) {
                    if (cancelled) throw new IOException("Cancelled");
                    out.write(buf, 0, n);
                    done += n;
                    if (p != null && done - lastTick > (1 << 20)) {
                        lastTick = done;
                        p.onBytes("Downloading " + dest.getName(), done, total);
                    }
                }
            } finally {
                if (c instanceof HttpURLConnection) ((HttpURLConnection) c).disconnect();
            }
        }

        if (!gz && (sha256 == null || sha256.isEmpty())) {
            if (dest.exists() && !dest.delete()) throw new IOException("Can't replace " + dest);
            if (!part.renameTo(dest)) throw new IOException("Can't save " + dest);
            return;
        }
        // Unpack (or not) into dest.tmp while hashing, then move into place.
        File tmp = new File(dest.getPath() + ".tmp");
        MessageDigest md = sha();
        try (InputStream raw = new BufferedInputStream(new FileInputStream(part), 1 << 16);
             InputStream in = gz ? new GZIPInputStream(raw, 1 << 16) : raw;
             OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[1 << 16];
            int n;
            long done = 0, lastTick = 0;
            while ((n = in.read(buf)) > 0) {
                if (cancelled) throw new IOException("Cancelled");
                out.write(buf, 0, n);
                md.update(buf, 0, n);
                done += n;
                if (p != null && done - lastTick > (8 << 20)) {
                    lastTick = done;
                    p.onBytes("Checking " + dest.getName(), done, 0);
                }
            }
        }
        String got = hex(md.digest());
        if (sha256 != null && !sha256.isEmpty() && !sha256.equalsIgnoreCase(got)) {
            tmp.delete();
            part.delete();
            throw new IOException(dest.getName() + " didn't download correctly (checksum mismatch). Try again.");
        }
        if (dest.exists() && !dest.delete()) throw new IOException("Can't replace " + dest);
        if (!tmp.renameTo(dest)) throw new IOException("Can't save " + dest);
        part.delete();
    }

    /** Size of the file at url (HTTP HEAD), or 0 when the server doesn't say. */
    public long headSize(String url) throws IOException {
        URLConnection c = new URL(url).openConnection();
        c.setConnectTimeout(CONNECT_MS);
        c.setReadTimeout(READ_MS);
        try {
            if (c instanceof HttpURLConnection) {
                HttpURLConnection h = (HttpURLConnection) c;
                h.setRequestMethod("HEAD");
                h.setInstanceFollowRedirects(true);
                int code = h.getResponseCode();
                if (code == 404) throw new IOException("Not found on the map download site: " + url);
                if (code >= 400) throw new IOException("The map download site answered " + code);
            }
            return Math.max(0, c.getContentLengthLong());
        } finally {
            if (c instanceof HttpURLConnection) ((HttpURLConnection) c).disconnect();
        }
    }

    private URLConnection open(String url, long from) throws IOException {
        URLConnection c = new URL(url).openConnection();
        c.setConnectTimeout(CONNECT_MS);
        c.setReadTimeout(READ_MS);
        c.setRequestProperty("User-Agent", "TAKAssistMaps/1.0 (Android)");
        if (c instanceof HttpURLConnection) {
            HttpURLConnection h = (HttpURLConnection) c;
            h.setInstanceFollowRedirects(true);
            if (from > 0) h.setRequestProperty("Range", "bytes=" + from + "-");
            int code = h.getResponseCode();
            if (code >= 400) throw new IOException("The server answered " + code + " for " + url);
        }
        return c;
    }

    static MessageDigest sha() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String hex(byte[] d) {
        StringBuilder sb = new StringBuilder();
        for (byte x : d) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
