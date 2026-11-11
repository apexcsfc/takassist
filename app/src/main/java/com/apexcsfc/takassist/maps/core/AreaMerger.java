package com.apexcsfc.takassist.maps.core;

import android.database.sqlite.SQLiteDatabase;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Stitches several area.sqlite files (places, streets, water, parks) into one.
 * Rows from overlapping regions are kept once.
 */
public final class AreaMerger {

    public static void merge(List<File> inputs, List<double[]> boxes, File out) throws IOException {
        File part = new File(out.getPath() + ".part");
        part.delete();
        new File(part.getPath() + "-journal").delete();
        SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(part, null);
        try {
            db.rawQuery("PRAGMA journal_mode=OFF", null).close();
            db.execSQL("PRAGMA synchronous=OFF");
            db.execSQL("CREATE TABLE meta(k TEXT PRIMARY KEY, v TEXT)");
            db.execSQL("CREATE TABLE features(name TEXT, name_lc TEXT, kind TEXT, cat TEXT, lat REAL, lon REAL, cx INTEGER, cy INTEGER)");
            db.execSQL("CREATE TABLE shapes(name TEXT, name_lc TEXT, kind TEXT, cat TEXT, coords BLOB, cx INTEGER, cy INTEGER)");
            for (int i = 0; i < inputs.size(); i++) {
                db.execSQL("ATTACH DATABASE ? AS r", new Object[]{inputs.get(i).getPath()});
                db.beginTransaction();
                try {
                    db.execSQL("INSERT INTO features SELECT name, name_lc, kind, cat, lat, lon, cx, cy FROM r.features");
                    db.execSQL("INSERT INTO shapes SELECT name, name_lc, kind, cat, coords, cx, cy FROM r.shapes");
                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                }
                db.execSQL("DETACH DATABASE r");
            }
            if (inputs.size() > 1) {
                db.execSQL("DELETE FROM features WHERE rowid NOT IN (SELECT MIN(rowid) FROM features GROUP BY name, cat, lat, lon)");
                db.execSQL("DELETE FROM shapes WHERE rowid NOT IN (SELECT MIN(rowid) FROM shapes GROUP BY cat, name, coords)");
            }
            double s = 90, w = 180, n = -90, e = -180;
            for (double[] b : boxes) {
                s = Math.min(s, b[0]); w = Math.min(w, b[1]); n = Math.max(n, b[2]); e = Math.max(e, b[3]);
            }
            String[][] meta = {{"south", "" + s}, {"west", "" + w}, {"north", "" + n}, {"east", "" + e},
                    {"cell", "0.01"}, {"chunk", "16"}, {"built", "stitched"},
                    {"source", "OpenStreetMap contributors (ODbL)"}};
            for (String[] kv : meta) db.execSQL("INSERT INTO meta VALUES(?,?)", kv);
            db.execSQL("CREATE INDEX fx_cell ON features(cx, cy)");
            db.execSQL("CREATE INDEX fx_cat ON features(cat, cx, cy)");
            db.execSQL("CREATE INDEX fx_name ON features(name_lc)");
            db.execSQL("CREATE INDEX sh_cat ON shapes(cat, cx, cy)");
            db.execSQL("CREATE INDEX sh_name ON shapes(name_lc)");
            db.execSQL("ANALYZE");
        } finally {
            db.close();
        }
        new File(part.getPath() + "-journal").delete();
        if (out.exists() && !out.delete()) throw new IOException("Can't replace " + out);
        if (!part.renameTo(out)) throw new IOException("Couldn't move the stitched places file into place");
    }
}
