package com.apexcsfc.atak.takassist.talk.resolve;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Turns a {@link MarkerIntent} into an exact position and CoT type. Plain Java with
 * no ATAK or Android dependencies, so every rule here is unit-tested off-device.
 * ATAK-specific work (MGRS parsing, magnetic variation) comes in through
 * {@link Environment}.
 *
 * Anything missing or out of range becomes a "say again" question rather than a guess.
 */
public final class Resolver {

    /** Relative callouts beyond this are almost certainly a mishearing. */
    public static final double MAX_RELATIVE_M = 50_000;
    /** Grids further than this from the user are flagged in the confirm card. */
    public static final double FAR_WARNING_M = 100_000;

    /** What the resolver needs from the device. */
    public interface Environment {
        /** {lat, lon} of the user, or null without a fix. */
        double[] self();

        /** {lat, lon} of the map centre, or null. */
        double[] mapCenter();

        /** The user's own MGRS (any spacing), or null; supplies a missing zone/square. */
        String selfMgrs();

        /** Converts a full MGRS string to {lat, lon}; throws IllegalArgumentException if invalid. */
        double[] mgrsToLatLon(String mgrs);

        /** True bearing for a magnetic bearing taken at (lat, lon). */
        double magneticToTrue(double lat, double lon, double magneticDeg);

        /**
         * Best match for a spoken place name, or null if nothing matches.
         * Throws IllegalStateException (message shown to the user) when place
         * lookup isn't possible at all, e.g. no offline map data.
         */
        Place findPlace(String spoken);
    }

    /** Places further than this from the user are flagged: probably the wrong match. */
    public static final double PLACE_FAR_WARNING_M = 25_000;

    /** Result of resolving one intent. */
    public static final class Result {
        public final boolean ok;
        /** When !ok: the question to put to the user. */
        public final String sayAgain;
        public final double lat;
        public final double lon;
        public final String cotType;
        public final String callsign;
        public final String remarks;
        /** Human-readable lines for the confirm card. */
        public final List<String> details;
        /** Things worth a second look before sending. */
        public final List<String> warnings;

        private Result(boolean ok, String sayAgain, double lat, double lon, String cotType,
                       String callsign, String remarks, List<String> details,
                       List<String> warnings) {
            this.ok = ok;
            this.sayAgain = sayAgain;
            this.lat = lat;
            this.lon = lon;
            this.cotType = cotType;
            this.callsign = callsign;
            this.remarks = remarks;
            this.details = details;
            this.warnings = warnings;
        }

        static Result sayAgain(String q) {
            return new Result(false, q, Double.NaN, Double.NaN, null, null, null,
                    new ArrayList<String>(), new ArrayList<String>());
        }

        /** Same result with different remarks (e.g. a SALUTE report) and extra warnings. */
        public Result withRemarks(String newRemarks, List<String> moreWarnings) {
            List<String> w = new ArrayList<>(warnings);
            if (moreWarnings != null) w.addAll(moreWarnings);
            return new Result(ok, sayAgain, lat, lon, cotType, callsign, newRemarks, details, w);
        }
    }

    /** How to read a numeric bearing when the speaker didn't say true or magnetic. */
    public enum DefaultNorth { TRUE, MAGNETIC }

    private final Environment env;
    private final DefaultNorth defaultNorth;

    public Resolver(Environment env, DefaultNorth defaultNorth) {
        this.env = env;
        this.defaultNorth = defaultNorth == null ? DefaultNorth.MAGNETIC : defaultNorth;
    }

    public Result resolve(MarkerIntent in) {
        if (in == null) return Result.sayAgain("I didn't catch that. Say again?");
        if (!"place_marker".equals(in.action)) {
            String q = trim(in.clarify);
            final Result sayAgain = Result.sayAgain(q.isEmpty() ? "I didn't catch a marker in that. Say again?" : q);
            // Safety net: a small model sometimes calls a report "unclear" yet still fills
            // in a usable location. Try it; if it doesn't resolve cleanly, ask as it wanted.
            if (trim(in.locationMode).isEmpty()) return sayAgain;
            final MarkerIntent retry = copy(in);
            retry.action = "place_marker";
            final Result r = resolve(retry);
            return r.ok ? r : sayAgain;
        }

        final String affil = oneOf(in.affiliation, "unknown", "hostile", "friendly", "neutral", "unknown");
        final String dim = oneOf(in.dimension, "ground", "ground", "air", "sea");
        final String mode = trim(in.locationMode);

        final List<String> details = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
        double[] point;

        switch (mode) {
            case "self": {
                point = env.self();
                if (point == null) return Result.sayAgain("I don't have a GPS fix for you yet. Give me a grid instead?");
                details.add("At your position");
                break;
            }
            case "grid": {
                final String grid;
                try {
                    grid = MgrsText.complete(in.grid, env.selfMgrs());
                } catch (IllegalArgumentException e) {
                    return Result.sayAgain(e.getMessage());
                }
                try {
                    point = env.mgrsToLatLon(grid);
                } catch (RuntimeException e) {
                    return Result.sayAgain("Grid " + MgrsText.pretty(grid) + " isn't valid. Say again the grid?");
                }
                if (point == null) {
                    return Result.sayAgain("Grid " + MgrsText.pretty(grid) + " isn't valid. Say again the grid?");
                }
                details.add("Grid " + MgrsText.pretty(grid));
                final double[] self = env.self();
                if (self != null) {
                    final double[] db = Geodesy.inverse(self[0], self[1], point[0], point[1]);
                    details.add(String.format(Locale.US, "%s at %03.0f° true from you",
                            distanceText(db[0]), db[1]));
                    if (db[0] > FAR_WARNING_M) {
                        warnings.add("That grid is " + distanceText(db[0]) + " away. Check the zone and square.");
                    }
                }
                break;
            }
            case "place": {
                final Place p;
                try {
                    p = lookup(in.place);
                } catch (IllegalArgumentException e) {
                    return Result.sayAgain(e.getMessage());
                }
                point = new double[]{p.lat, p.lon};
                details.add("At " + p.label());
                describePlace(in.place, p, details, warnings);
                if (p.isArea()) {
                    warnings.add("Placed at the centre of " + p.name + ". For an exact spot, give a grid "
                            + "or a direction and distance from a landmark.");
                }
                addFromSelf(point, details, warnings);
                break;
            }
            case "relative": {
                final boolean fromCenter = "map_center".equals(in.refPoint);
                final boolean fromPlace = "place".equals(in.refPoint);
                Place refPlace = null;
                final double[] origin;
                if (fromPlace) {
                    try {
                        refPlace = lookup(in.place);
                    } catch (IllegalArgumentException e) {
                        return Result.sayAgain(e.getMessage());
                    }
                    origin = new double[]{refPlace.lat, refPlace.lon};
                } else {
                    origin = fromCenter ? env.mapCenter() : env.self();
                }
                if (origin == null) {
                    return Result.sayAgain(fromCenter
                            ? "I can't read the map centre right now. Say again from your position?"
                            : "I don't have a GPS fix for you yet. Give me a grid instead?");
                }

                Double raw = in.bearingDeg;
                boolean fromWord = false;
                if (raw == null && in.cardinal != null && !in.cardinal.trim().isEmpty()) {
                    raw = cardinalToDeg(in.cardinal);
                    fromWord = true;
                    if (raw == null) return Result.sayAgain("Which direction? Say a compass direction or a bearing.");
                }
                if (raw == null) return Result.sayAgain("Which direction? Say a compass direction or a bearing.");
                if (raw < 0 || raw > 360) {
                    return Result.sayAgain(String.format(Locale.US, "A bearing of %.0f isn't valid. Say again the bearing?", raw));
                }

                final String ref = trim(in.bearingRef);
                // Compass words are read as true (map) directions; numbers follow what was
                // said, then the user's default.
                final boolean magnetic = !fromWord && ("magnetic".equals(ref)
                        || (!"true".equals(ref) && defaultNorth == DefaultNorth.MAGNETIC));
                final double trueBearing = Geodesy.normalizeDeg(magnetic
                        ? env.magneticToTrue(origin[0], origin[1], raw)
                        : raw);

                if (in.distance == null) return Result.sayAgain("How far? Say again the distance.");
                final Double metres = toMetres(in.distance, in.distanceUnit);
                if (metres == null) return Result.sayAgain("What unit was that distance in?");
                if (metres <= 0) return Result.sayAgain("Say again the distance?");
                if (metres > MAX_RELATIVE_M) {
                    return Result.sayAgain("That's " + distanceText(metres)
                            + ", which is further than I'll plot from a bearing. Say again, or give a grid?");
                }

                point = Geodesy.destination(origin[0], origin[1], trueBearing, metres);
                final String dir = fromWord
                        ? in.cardinal.trim().toUpperCase(Locale.US) + String.format(Locale.US, " (%03.0f° true)", trueBearing)
                        : magnetic
                        ? String.format(Locale.US, "%03.0f° magnetic (%03.0f° true)", raw, trueBearing)
                        : String.format(Locale.US, "%03.0f° true", trueBearing);
                details.add(distanceText(metres) + " at " + dir + " from "
                        + (fromPlace ? refPlace.label() : fromCenter ? "the map centre" : "you"));
                if (fromPlace) {
                    describePlace(in.place, refPlace, details, warnings);
                    if (refPlace.isArea()) {
                        warnings.add("Measured from the centre of " + refPlace.name + ".");
                    }
                    addFromSelf(point, details, warnings);
                }
                if (!fromWord && !"true".equals(ref) && !"magnetic".equals(ref)) {
                    warnings.add("Bearing read as " + (magnetic ? "magnetic" : "true")
                            + " (not spoken). Change the default in the pane if that's wrong.");
                }
                break;
            }
            default:
                return Result.sayAgain("Where is it? Give a grid, or a direction and distance.");
        }

        final String label = trim(in.label);
        final String callsign = label.isEmpty() ? capitalize(affil) + " " + dim : label;
        final String remarks = trim(in.remarks);

        return new Result(true, null, point[0], point[1], cotType(affil, dim), callsign,
                remarks, details, warnings);
    }

    private static MarkerIntent copy(MarkerIntent in) {
        MarkerIntent c = new MarkerIntent();
        c.action = in.action;
        c.affiliation = in.affiliation;
        c.dimension = in.dimension;
        c.label = in.label;
        c.locationMode = in.locationMode;
        c.refPoint = in.refPoint;
        c.place = in.place;
        c.bearingDeg = in.bearingDeg;
        c.cardinal = in.cardinal;
        c.bearingRef = in.bearingRef;
        c.distance = in.distance;
        c.distanceUnit = in.distanceUnit;
        c.grid = in.grid;
        c.remarks = in.remarks;
        c.mapName = in.mapName;
        c.emergencyType = in.emergencyType;
        c.travelMode = in.travelMode;
        c.clarify = in.clarify;
        return c;
    }

    // ----------------------------------------------------------------- places

    private Place lookup(String spoken) {
        final String name = trim(spoken);
        if (name.isEmpty()) throw new IllegalArgumentException("Which place? Say the name again.");
        final Place p;
        try {
            p = env.findPlace(name);
        } catch (IllegalStateException e) {
            throw new IllegalArgumentException(e.getMessage());
        }
        if (p == null) {
            throw new IllegalArgumentException("I can't find \"" + name + "\" on the offline map. "
                    + "Say it another way, or give a grid or a direction and distance.");
        }
        return p;
    }

    private static void describePlace(String spoken, Place p, List<String> details, List<String> warnings) {
        if (!p.exactMatch) {
            warnings.add("Heard \"" + trim(spoken) + "\", matched " + p.name + ". Check it's the right place.");
        }
    }

    /** Adds "N m at B° from you" and a far-away warning when the user has a fix. */
    private void addFromSelf(double[] point, List<String> details, List<String> warnings) {
        final double[] self = env.self();
        if (self == null) return;
        final double[] db = Geodesy.inverse(self[0], self[1], point[0], point[1]);
        details.add(String.format(Locale.US, "%s at %03.0f° true from you", distanceText(db[0]), db[1]));
        if (db[0] > PLACE_FAR_WARNING_M) {
            warnings.add("That's " + distanceText(db[0]) + " from you. Make sure it's the right place.");
        }
    }

    // ------------------------------------------------------------------ rules

    /** 2525-style CoT atom type for affiliation and battle dimension. */
    public static String cotType(String affiliation, String dimension) {
        final char a;
        switch (affiliation == null ? "" : affiliation) {
            case "friendly": a = 'f'; break;
            case "hostile":  a = 'h'; break;
            case "neutral":  a = 'n'; break;
            default:         a = 'u'; break;
        }
        final char d;
        switch (dimension == null ? "" : dimension) {
            case "air": d = 'A'; break;
            case "sea": d = 'S'; break;
            default:    d = 'G'; break;
        }
        return "a-" + a + "-" + d;
    }

    /** Metres for a spoken distance, or null for an unknown unit. */
    public static Double toMetres(double value, String unit) {
        switch (unit == null ? "m" : unit.trim().toLowerCase(Locale.US)) {
            case "":
            case "m":   return value;
            case "km":  return value * 1000.0;
            case "mi":  return value * 1609.344;
            case "yd":  return value * 0.9144;
            case "ft":  return value * 0.3048;
            case "nmi": return value * 1852.0;
            default:    return null;
        }
    }

    private static final String[] POINTS = {
            "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
            "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"};

    /** Degrees for a 16-point compass word, or null. */
    public static Double cardinalToDeg(String word) {
        final String w = word.trim().toUpperCase(Locale.US);
        for (int i = 0; i < POINTS.length; i++) {
            if (POINTS[i].equals(w)) return i * 22.5;
        }
        return null;
    }

    static String distanceText(double m) {
        if (m < 1000) return String.format(Locale.US, "%.0f m", m);
        return String.format(Locale.US, "%.1f km", m / 1000.0);
    }

    private static String oneOf(String v, String def, String... allowed) {
        final String t = trim(v).toLowerCase(Locale.US);
        for (String a : allowed) if (a.equals(t)) return t;
        return def;
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
