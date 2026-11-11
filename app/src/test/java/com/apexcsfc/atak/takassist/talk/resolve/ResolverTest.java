package com.apexcsfc.atak.takassist.talk.resolve;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

/** Off-device checks for everything that decides where a marker lands. */
public class ResolverTest {

    /** Fake device: user at a fixed point in DC, 10.5° west declination, simple grid lookup. */
    private static class FakeEnv implements Resolver.Environment {
        double[] self = {38.8895, -77.0353};
        double[] center = {38.9000, -77.0000};
        String lastGrid;

        @Override public double[] self() { return self; }
        @Override public double[] mapCenter() { return center; }
        @Override public String selfMgrs() { return self == null ? null : "18S UJ 23392 06527"; }

        @Override
        public double[] mgrsToLatLon(String mgrs) {
            lastGrid = mgrs;
            if (!mgrs.startsWith("18SUJ")) throw new IllegalArgumentException("bad");
            return new double[]{38.90, -77.03};
        }

        @Override
        public double magneticToTrue(double lat, double lon, double magneticDeg) {
            return magneticDeg - 10.5;
        }

        Place place = new Place("Columbia Heights", "neighbourhood", 38.9282, -77.0326, true);
        String placeError;

        @Override
        public Place findPlace(String spoken) {
            if (placeError != null) throw new IllegalStateException(placeError);
            return place;
        }
    }

    private static MarkerIntent atPlace(String name) {
        MarkerIntent in = relative(null, null, null, null, null);
        in.locationMode = "place";
        in.place = name;
        return in;
    }

    private static MarkerIntent relative(Double bearing, String cardinal, String ref, Double dist, String unit) {
        MarkerIntent in = new MarkerIntent();
        in.action = "place_marker";
        in.affiliation = "hostile";
        in.dimension = "ground";
        in.label = "truck";
        in.locationMode = "relative";
        in.refPoint = "self";
        in.bearingDeg = bearing;
        in.cardinal = cardinal;
        in.bearingRef = ref;
        in.distance = dist;
        in.distanceUnit = unit;
        return in;
    }

    private static double[] distBearingFromSelf(FakeEnv env, Resolver.Result r) {
        return Geodesy.inverse(env.self[0], env.self[1], r.lat, r.lon);
    }

    // ---------------------------------------------------------------- geodesy

    @Test
    public void vincentyMatchesPublishedExample() {
        // Flinders Peak → Buninyong, the standard Vincenty test line.
        double[] p = Geodesy.destination(-37.95103342, 144.42486789, 306.86815920, 54972.271);
        assertEquals(-37.65282114, p[0], 1e-7);
        assertEquals(143.92649554, p[1], 1e-7);
        double[] back = Geodesy.inverse(-37.95103342, 144.42486789, p[0], p[1]);
        assertEquals(54972.271, back[0], 0.001);
        assertEquals(306.86815920, back[1], 1e-6);
    }

    // --------------------------------------------------------------- relative

    @Test
    public void compassWordIsTrueNorthEvenWhenDefaultIsMagnetic() {
        FakeEnv env = new FakeEnv();
        Resolver.Result r = new Resolver(env, Resolver.DefaultNorth.MAGNETIC)
                .resolve(relative(null, "N", "unspecified", 200.0, "m"));
        assertTrue(r.ok);
        double[] db = distBearingFromSelf(env, r);
        assertEquals(200.0, db[0], 0.01);
        assertEquals(0.0, Math.min(db[1], 360 - db[1]), 0.001);
        assertEquals("a-h-G", r.cotType);
        assertEquals("truck", r.callsign);
        assertTrue(r.warnings.isEmpty());
    }

    @Test
    public void unspecifiedNumberUsesMagneticDefaultAndWarns() {
        FakeEnv env = new FakeEnv();
        Resolver.Result r = new Resolver(env, Resolver.DefaultNorth.MAGNETIC)
                .resolve(relative(45.0, null, "unspecified", 1.5, "km"));
        assertTrue(r.ok);
        double[] db = distBearingFromSelf(env, r);
        assertEquals(1500.0, db[0], 0.01);
        assertEquals(34.5, db[1], 0.001);
        assertFalse(r.warnings.isEmpty());
        assertTrue(r.details.get(0).contains("045° magnetic (035° true)"));
    }

    @Test
    public void spokenTrueIsNotConverted() {
        FakeEnv env = new FakeEnv();
        Resolver.Result r = new Resolver(env, Resolver.DefaultNorth.MAGNETIC)
                .resolve(relative(90.0, null, "true", 300.0, "yd"));
        assertTrue(r.ok);
        double[] db = distBearingFromSelf(env, r);
        assertEquals(300 * 0.9144, db[0], 0.01);
        assertEquals(90.0, db[1], 0.001);
        assertTrue(r.warnings.isEmpty());
    }

    @Test
    public void trueDefaultLeavesUnspecifiedNumberAlone() {
        FakeEnv env = new FakeEnv();
        Resolver.Result r = new Resolver(env, Resolver.DefaultNorth.TRUE)
                .resolve(relative(270.0, null, "unspecified", 100.0, "m"));
        assertEquals(270.0, distBearingFromSelf(env, r)[1], 0.001);
    }

    @Test
    public void missingOrSillyInputBecomesSayAgain() {
        FakeEnv env = new FakeEnv();
        Resolver res = new Resolver(env, Resolver.DefaultNorth.MAGNETIC);
        assertFalse(res.resolve(relative(10.0, null, "true", null, "m")).ok);       // no distance
        assertFalse(res.resolve(relative(null, null, "true", 200.0, "m")).ok);      // no direction
        assertFalse(res.resolve(relative(400.0, null, "true", 200.0, "m")).ok);     // bad bearing
        assertFalse(res.resolve(relative(10.0, null, "true", 60.0, "km")).ok);      // too far
        assertFalse(res.resolve(relative(10.0, null, "true", 200.0, "parsecs")).ok); // bad unit
        env.self = null;
        assertFalse(res.resolve(relative(10.0, null, "true", 200.0, "m")).ok);      // no GPS fix
    }

    @Test
    public void mapCentreReference() {
        FakeEnv env = new FakeEnv();
        MarkerIntent in = relative(null, "E", "unspecified", 1.0, "km");
        in.refPoint = "map_center";
        Resolver.Result r = new Resolver(env, Resolver.DefaultNorth.MAGNETIC).resolve(in);
        double[] db = Geodesy.inverse(env.center[0], env.center[1], r.lat, r.lon);
        assertEquals(1000.0, db[0], 0.01);
        assertEquals(90.0, db[1], 0.01);
    }

    @Test
    public void unclearPassesTheQuestionThrough() {
        MarkerIntent in = new MarkerIntent();
        in.action = "unclear";
        in.clarify = "Where are they?";
        Resolver.Result r = new Resolver(new FakeEnv(), Resolver.DefaultNorth.MAGNETIC).resolve(in);
        assertFalse(r.ok);
        assertEquals("Where are they?", r.sayAgain);
    }

    // ------------------------------------------------------------------- grid

    @Test
    public void gridWithoutZoneTakesItFromSelf() {
        FakeEnv env = new FakeEnv();
        MarkerIntent in = relative(null, null, null, null, null);
        in.locationMode = "grid";
        in.grid = "2348 0647";
        Resolver.Result r = new Resolver(env, Resolver.DefaultNorth.MAGNETIC).resolve(in);
        assertTrue(r.ok);
        assertEquals("18SUJ23480647", env.lastGrid);
        assertTrue(r.details.get(0).contains("18S UJ 2348 0647"));
    }

    @Test
    public void mgrsCompletion() {
        String self = "18S UJ 23392 06527";
        assertEquals("18SUJ23480647", MgrsText.complete("2348 0647", self));
        assertEquals("18SUJ2306", MgrsText.complete("uj 23 06", self));
        assertEquals("17RLM12345678", MgrsText.complete("17R LM 1234 5678", self));
        try {
            MgrsText.complete("234 0647", self);
            fail("odd digit count must be rejected");
        } catch (IllegalArgumentException expected) { }
        try {
            MgrsText.complete("2348 0647", null);
            fail("no zone available must be rejected");
        } catch (IllegalArgumentException expected) { }
    }

    // ----------------------------------------------------------------- places

    @Test
    public void atANeighbourhoodLandsOnItsCentreWithAWarning() {
        FakeEnv env = new FakeEnv();
        Resolver.Result r = new Resolver(env, Resolver.DefaultNorth.MAGNETIC).resolve(atPlace("Columbia Heights"));
        assertTrue(r.ok);
        assertEquals(38.9282, r.lat, 1e-9);
        assertEquals(-77.0326, r.lon, 1e-9);
        assertTrue(r.details.get(0).contains("Columbia Heights (neighbourhood)"));
        assertTrue(r.warnings.get(0).contains("centre of Columbia Heights"));
    }

    @Test
    public void spellingMatchIsFlagged() {
        FakeEnv env = new FakeEnv();
        env.place = new Place("Columbia Heights", "neighbourhood", 38.9282, -77.0326, false);
        Resolver.Result r = new Resolver(env, Resolver.DefaultNorth.MAGNETIC).resolve(atPlace("Colombia Heights"));
        assertTrue(r.ok);
        boolean flagged = false;
        for (String w : r.warnings) flagged |= w.contains("matched Columbia Heights");
        assertTrue(flagged);
    }

    @Test
    public void pointPlaceHasNoCentreWarning() {
        FakeEnv env = new FakeEnv();
        env.place = new Place("Howard University Hospital", "hospital", 38.9169, -77.0204, true);
        Resolver.Result r = new Resolver(env, Resolver.DefaultNorth.MAGNETIC).resolve(atPlace("Howard University Hospital"));
        assertTrue(r.ok);
        assertTrue(r.warnings.isEmpty());
    }

    @Test
    public void bearingAndDistanceFromAPlace() {
        FakeEnv env = new FakeEnv();
        env.place = new Place("Howard University Hospital", "hospital", 38.9169, -77.0204, true);
        MarkerIntent in = relative(null, "E", "unspecified", 300.0, "m");
        in.refPoint = "place";
        in.place = "Howard University Hospital";
        Resolver.Result r = new Resolver(env, Resolver.DefaultNorth.MAGNETIC).resolve(in);
        assertTrue(r.ok);
        double[] db = Geodesy.inverse(38.9169, -77.0204, r.lat, r.lon);
        assertEquals(300.0, db[0], 0.01);
        assertEquals(90.0, db[1], 0.01);
        assertTrue(r.details.get(0).contains("from Howard University Hospital (hospital)"));
    }

    @Test
    public void unknownPlaceOrNoMapDataBecomesSayAgain() {
        FakeEnv env = new FakeEnv();
        env.place = null;
        Resolver.Result r = new Resolver(env, Resolver.DefaultNorth.MAGNETIC).resolve(atPlace("Narnia"));
        assertFalse(r.ok);
        assertTrue(r.sayAgain.contains("Narnia"));

        env.placeError = "Place names need the offline map file";
        r = new Resolver(env, Resolver.DefaultNorth.MAGNETIC).resolve(atPlace("Columbia Heights"));
        assertFalse(r.ok);
        assertTrue(r.sayAgain.startsWith("Place names need"));

        assertFalse(new Resolver(new FakeEnv(), Resolver.DefaultNorth.MAGNETIC).resolve(atPlace(null)).ok);
    }

    // ------------------------------------------------------ messy input

    @Test
    public void transcriptCleanup() {
        assertEquals("Friendly Squad 1.5 kilometers Northeast", Transcript.clean("Friendly Squad 1.5 clicks Northeast"));
        assertEquals("enemy two kilometers south", Transcript.clean("enemy two klicks south"));
        assertEquals("drone 3 kilometers east", Transcript.clean("drone 3 kliks east"));
        assertEquals("truck 2 kilometers north", Transcript.clean("truck 2k north"));
        assertEquals("truck 300 meters north", Transcript.clean("truck 300m north"));
        // Left alone: no number in front, or not a unit.
        assertEquals("he clicks the button", Transcript.clean("he clicks the button"));
        assertEquals("grid 18S UJ 2348 0647", Transcript.clean("grid 18S UJ 2348 0647"));
        assertEquals("k9 unit at the corner", Transcript.clean("k9 unit at the corner"));
    }

    @Test
    public void unclearWithAUsableLocationIsStillPlaced() {
        MarkerIntent in = relative(null, "NE", "unspecified", 1.5, "km");
        in.action = "unclear";
        in.affiliation = "friendly";
        in.clarify = "Where exactly is Squad 1?";
        Resolver.Result r = new Resolver(new FakeEnv(), Resolver.DefaultNorth.MAGNETIC).resolve(in);
        assertTrue(r.ok);
        assertEquals("a-f-G", r.cotType);
    }

    @Test
    public void unclearWithoutALocationStillAsks() {
        MarkerIntent in = relative(null, "NE", "unspecified", null, null); // no distance
        in.action = "unclear";
        in.clarify = "How far?";
        Resolver.Result r = new Resolver(new FakeEnv(), Resolver.DefaultNorth.MAGNETIC).resolve(in);
        assertFalse(r.ok);
        assertEquals("How far?", r.sayAgain);
    }

    // ----------------------------------------------------------------- count

    private static java.util.List<MarkerCounter.Item> sampleMap(double[] self) {
        java.util.List<MarkerCounter.Item> m = new java.util.ArrayList<>();
        double[] p;
        p = Geodesy.destination(self[0], self[1], 0, 300);    m.add(new MarkerCounter.Item("truck", "a-h-G", p[0], p[1]));
        p = Geodesy.destination(self[0], self[1], 90, 2500);  m.add(new MarkerCounter.Item("BMP", "a-h-G-E-V", p[0], p[1]));
        p = Geodesy.destination(self[0], self[1], 180, 8000); m.add(new MarkerCounter.Item("drone", "a-h-A", p[0], p[1]));
        p = Geodesy.destination(self[0], self[1], 45, 600);   m.add(new MarkerCounter.Item("squad", "a-f-G", p[0], p[1]));
        p = Geodesy.destination(self[0], self[1], 270, 400);  m.add(new MarkerCounter.Item("van", "a-s-G", p[0], p[1]));
        p = Geodesy.destination(self[0], self[1], 10, 100);   m.add(new MarkerCounter.Item("rally point", "b-m-p-w", p[0], p[1]));
        return m;
    }

    @Test
    public void countsHostilesWithBandsNearestAndSuspectNote() {
        double[] self = {38.9153, -77.0219};
        MarkerCounter.Answer a = MarkerCounter.count(sampleMap(self), "hostile", self, null);
        assertEquals(3, a.count);
        assertEquals("3 hostile markers on the map", a.title);
        assertEquals("1 within 1 km · 2 within 5 km", a.lines.get(0));
        assertTrue(a.lines.get(2).startsWith("• truck: 300 m N"));
        assertTrue(a.lines.get(a.lines.size() - 1).contains("1 suspect marker"));
    }

    @Test
    public void countsWithinASpokenRadius() {
        double[] self = {38.9153, -77.0219};
        MarkerCounter.Answer a = MarkerCounter.count(sampleMap(self), "hostile", self, 3000.0);
        assertEquals(2, a.count);
        assertEquals("2 hostile markers within 3.0 km of you", a.title);
    }

    @Test
    public void countsAnyAndIgnoresNonUnitPoints() {
        double[] self = {38.9153, -77.0219};
        MarkerCounter.Answer a = MarkerCounter.count(sampleMap(self), "any", self, null);
        assertEquals(5, a.count); // rally point (b-m-p-w) isn't a unit
        assertEquals(0, MarkerCounter.count(new java.util.ArrayList<MarkerCounter.Item>(), "hostile", self, null).count);
    }

    @Test
    public void recognisesCountQuestions() {
        assertEquals("hostile", MarkerCounter.countQuestionAffiliation("How many hostile targets are near me give me a number"));
        assertEquals("hostile", MarkerCounter.countQuestionAffiliation("how many enemies are there"));
        assertEquals("friendly", MarkerCounter.countQuestionAffiliation("count the friendlies"));
        assertEquals("any", MarkerCounter.countQuestionAffiliation("how many markers on the map"));
        assertEquals(null, MarkerCounter.countQuestionAffiliation("enemy truck 200 meters north"));
    }

    // ------------------------------------------------------------------ maps

    private static final java.util.List<String> MAPS = java.util.Arrays.asList(
            "Esri World Topo", "NRL DRG", "NRL DRG Mosaic", "NRL FAA Sectionals",
            "USGS Imagery Topo", "USGS Topo", "OpenTopoMap", "Esri World Imagery");

    @Test
    public void mapNameExactIgnoringCase() {
        MapNames.Match m = MapNames.match("NRL drg", MAPS);
        assertFalse(m.ask);
        assertEquals("NRL DRG", m.options.get(0));
        assertEquals("NRL DRG", MapNames.match("nrl-drg map", MAPS).options.get(0));
    }

    @Test
    public void mapNamePartialAsksWhenSeveralFit() {
        MapNames.Match m = MapNames.match("drg", MAPS);
        assertTrue(m.ask);
        assertEquals("NRL DRG", m.options.get(0)); // shortest first
        assertEquals("NRL DRG Mosaic", m.options.get(1));

        m = MapNames.match("sectionals", MAPS);   // only one fits: no question
        assertFalse(m.ask);
        assertEquals("NRL FAA Sectionals", m.options.get(0));
    }

    @Test
    public void mapNameSynonymsAndSpelling() {
        MapNames.Match m = MapNames.match("satellite", MAPS);
        assertTrue(m.options.contains("Esri World Imagery"));
        assertTrue(m.options.contains("USGS Imagery Topo"));
        m = MapNames.match("esri world topoo", MAPS);
        assertTrue(m.ask);                        // misspelt: confirm
        assertEquals("Esri World Topo", m.options.get(0));
        assertTrue(MapNames.match("google earth", MAPS).options.isEmpty());
    }

    @Test
    public void mapNameFromXml() {
        assertEquals("NRL DRG", MapNames.nameFromXml(
                "<customMapSource>\n  <name>NRL DRG</name>\n  <minZoom>0</minZoom>\n</customMapSource>"));
        assertEquals(null, MapNames.nameFromXml("<customMapSource></customMapSource>"));
    }

    // ------------------------------------------------------------ bloodhound

    /** Like the screenshot: a truck just north of the user, another ~2 km south, an aircraft. */
    private static java.util.List<TargetFinder.Item> trucks(double[] self) {
        java.util.List<TargetFinder.Item> m = new java.util.ArrayList<>();
        double[] p;
        p = Geodesy.destination(self[0], self[1], 350, 400);  m.add(new TargetFinder.Item("t1", "truck", "a-h-G", p[0], p[1]));
        p = Geodesy.destination(self[0], self[1], 185, 2100); m.add(new TargetFinder.Item("t2", "truck", "a-h-G", p[0], p[1]));
        p = Geodesy.destination(self[0], self[1], 200, 6000); m.add(new TargetFinder.Item("a1", "enemy aircraft", "a-h-A", p[0], p[1]));
        p = Geodesy.destination(self[0], self[1], 60, 300);   m.add(new TargetFinder.Item("f1", "them", "a-f-G", p[0], p[1]));
        return m;
    }

    @Test
    public void hintPicksTheTruckToTheSouth() {
        double[] self = {38.9153, -77.0219};
        double[] hint = Geodesy.destination(self[0], self[1], 180, 2000); // "two klicks south"
        TargetFinder.Found f = TargetFinder.find(trucks(self), "truck", "any", hint, self);
        assertFalse(f.ask);
        assertEquals("t2", f.options.get(0).uid);
    }

    @Test
    public void sameNameWithoutHintAsks() {
        double[] self = {38.9153, -77.0219};
        TargetFinder.Found f = TargetFinder.find(trucks(self), "truck", "any", null, self);
        assertTrue(f.ask);
        assertEquals("t1", f.options.get(0).uid); // nearest first
        assertEquals(2, f.options.size());
    }

    @Test
    public void nameSideAndSpellingMatch() {
        double[] self = {38.9153, -77.0219};
        TargetFinder.Found f = TargetFinder.find(trucks(self), "the enemy aircraft", "hostile", null, self);
        assertFalse(f.ask);
        assertEquals("a1", f.options.get(0).uid);
        assertEquals("t2", TargetFinder.find(trucks(self), "trucks", "hostile",
                Geodesy.destination(self[0], self[1], 180, 2000), self).options.get(0).uid);
        assertTrue(TargetFinder.find(trucks(self), "truck", "friendly", null, self).options.isEmpty());
        assertTrue(TargetFinder.find(trucks(self), "tank", "any", null, self).options.isEmpty());
    }

    @Test
    public void nearestBySideOrName() {
        double[] self = {38.9153, -77.0219};
        java.util.List<TargetFinder.Item> n = TargetFinder.nearest(trucks(self), null, "hostile", self, 4);
        assertEquals("t1", n.get(0).uid);                 // nearest hostile: the truck 400 m N
        assertEquals(3, n.size());                        // the friendly "them" is excluded
        assertEquals("a1", TargetFinder.nearest(trucks(self), "aircraft", "any", self, 4).get(0).uid);
        assertEquals("f1", TargetFinder.nearest(trucks(self), null, "friendly", self, 4).get(0).uid);
        // Bloodhound / directions "to the nearest enemy": no name, a side, no question asked.
        TargetFinder.Found f = TargetFinder.find(trucks(self), null, "hostile", null, self);
        assertFalse(f.ask);
        assertEquals("t1", f.options.get(0).uid);
    }

    @Test
    public void locateKeywords() {
        assertEquals("locate", Keywords.action("Where is the nearest enemy to my position"));
        assertEquals("locate", Keywords.action("where's the truck"));
        assertEquals("locate", Keywords.action("where am I"));
        assertEquals("directions", Keywords.action("directions to the nearest enemy"));
        assertEquals(null, Keywords.action("how many enemies are nearest me"));
    }

    @Test
    public void hintFarFromAnyMatchFindsNothing() {
        double[] self = {38.9153, -77.0219};
        double[] hint = Geodesy.destination(self[0], self[1], 90, 5000); // 5 km east: no truck there
        assertTrue(TargetFinder.find(trucks(self), "truck", "any", hint, self).options.isEmpty());
    }

    // ------------------------------------------------------- MEDEVAC / SALUTE

    @Test
    public void medevacMapsOntoAtakFields() {
        Medevac m = new Medevac();
        m.frequency = "34.5";
        m.urgent = 2;
        m.priority = 1;
        m.hoist = true;
        m.equipmentOther = "oxygen";
        m.litter = 1;
        m.ambulatory = 2;
        m.security = "E";
        m.marking = "smoke";
        m.usMilitary = 3;
        java.util.Map<String, Object> meta = m.toMeta("MEDEVAC 2030");
        assertEquals(Boolean.TRUE, meta.get("casevac"));
        assertEquals("MEDEVAC 2030", meta.get("title"));
        assertEquals("34.5", meta.get("freq"));
        assertEquals("2", meta.get("urgent"));
        assertEquals("1", meta.get("priority"));
        assertEquals(null, meta.get("routine"));          // not said: left for ATAK's screen
        assertEquals(Boolean.TRUE, meta.get("hoist"));
        assertEquals(Boolean.TRUE, meta.get("equipment_other"));
        assertEquals("oxygen", meta.get("equipment_detail"));
        assertEquals("2", meta.get("security"));          // N=0 P=1 E=2 X=3
        assertEquals("2", meta.get("hlz_marking"));       // panels=0 pyro=1 smoke=2 none=3 other=4
        assertEquals("3", meta.get("us_military"));
        assertEquals(3, m.patients());
        assertTrue(m.missingLines().isEmpty() || !m.missingLines().contains("Line 3 (patients by precedence)"));
    }

    @Test
    public void medevacListsWhatWasntHeard() {
        Medevac m = new Medevac();
        m.urgent = 1;
        java.util.List<String> miss = m.missingLines();
        assertEquals(6, miss.size());                     // lines 2, 4, 5, 6, 7, 8
        assertFalse(miss.contains("Line 3 (patients by precedence)"));
        m.security = "bogus";
        assertEquals(null, m.toMeta("t").get("security")); // never guess an index
    }

    @Test
    public void saluteRemarks() {
        Salute s = new Salute();
        s.size = "4 personnel";
        s.activity = "setting up a mortar";
        s.equipment = "mortar, pickup truck";
        String r = s.remarks("18S UJ 24500 09500", "212045L OCT 26");
        assertTrue(r.startsWith("SALUTE\nS: 4 personnel\nA: setting up a mortar\nL: 18S UJ 24500 09500"));
        assertTrue(r.contains("\nU: not reported"));
        assertTrue(r.contains("\nT: 212045L OCT 26 (time of report)"));
        assertEquals(java.util.Arrays.asList("Unit"), s.missing());
    }

    @Test
    public void commandKeywords() {
        assertEquals("medevac", Keywords.action("I need a medevac at my location"));
        assertEquals("medevac", Keywords.action("send a nine line"));
        assertEquals("salute", Keywords.action("Salute report four enemy east"));
        assertEquals("emergency", Keywords.action("troops in contact"));
        assertEquals("in_contact", Keywords.emergencyType("TIC TIC"));
        assertEquals("nine_one_one", Keywords.emergencyType("send a 911"));
        assertEquals("ring_the_bell", Keywords.emergencyType("ring the bell"));
        assertEquals("cancel", Keywords.emergencyType("cancel the alert"));
        assertEquals("bloodhound", Keywords.action("blood hound to the truck"));
        assertEquals(null, Keywords.action("enemy truck 200 meters north"));
    }

    // ------------------------------------------------------------ directions

    @Test
    public void routeShapeKeepsTheRoadShape() {
        java.util.List<double[]> pts = new java.util.ArrayList<>();
        // 1 km east along a straight road, then 1 km north: 201 points, one real corner.
        double[] start = {38.9, -77.0};
        for (int i = 0; i <= 100; i++) pts.add(Geodesy.destination(start[0], start[1], 90, i * 10));
        double[] corner = pts.get(pts.size() - 1);
        for (int i = 1; i <= 100; i++) pts.add(Geodesy.destination(corner[0], corner[1], 0, i * 10));
        java.util.List<double[]> s = RouteShape.simplify(pts, 8, 250);
        assertEquals(3, s.size());                        // start, corner, end
        assertEquals(corner[0], s.get(1)[0], 1e-9);
        assertTrue(RouteShape.simplify(pts, 0.001, 20).size() <= 20); // cap respected
    }

    @Test
    public void directionsKeywords() {
        assertEquals("directions", Keywords.action("give me driving directions to the tank"));
        assertEquals("directions", Keywords.action("navigate to union station"));
        assertEquals("bloodhound", Keywords.action("bloodhound to the tank"));
        assertEquals("stop_bloodhound", Keywords.action("Clear the Bloodhound feature from the map"));
        assertEquals("stop_bloodhound", Keywords.action("turn off blood hound"));
        assertEquals("stop_bloodhound", Keywords.action("stop bloodhound"));
        assertEquals("close_tool", Keywords.action("remove this checkerboard too please"));
        assertEquals("close_tool", Keywords.action("close that tool"));
        assertEquals("close_tool", Keywords.action("hide the toolbar"));
        assertEquals("stop_navigation", Keywords.action("stop navigation"));
        assertEquals("stop_navigation", Keywords.action("cancel the route"));
        assertEquals("stop_navigation", Keywords.action("clear driving directions"));
        assertEquals("stop_navigation", Keywords.action("remove the route"));
        assertEquals("stop_navigation", Keywords.action("turn off turn by turn navigation"));
        assertEquals("directions", Keywords.action("driving directions to the truck"));
    }

    @Test
    public void cotTypes() {
        assertEquals("a-f-G", Resolver.cotType("friendly", "ground"));
        assertEquals("a-h-A", Resolver.cotType("hostile", "air"));
        assertEquals("a-n-S", Resolver.cotType("neutral", "sea"));
        assertEquals("a-u-G", Resolver.cotType("whatever", null));
    }

    @Test
    public void mapNameSharedBySeveralMapsAsks() {
        java.util.List<String> maps = java.util.Arrays.asList(
                "FAA Sectional", "FAA IFR Low", "FAA IFR High", "FAA Terminal Area", "NRL DRG");
        MapNames.Match m = MapNames.match("FAA", maps);
        assertTrue(m.ask);
        assertEquals(4, m.options.size());
        m = MapNames.match("the FAA sectional map", maps);
        assertEquals("FAA Sectional", m.options.get(0));
    }

    @Test
    public void zoomAndCenterOnMeAreLocateSelf() {
        for (String said : new String[]{"Zoom in on me", "center on my location", "zoom to me",
                "show me where I am", "where am I", "take me to my position"}) {
            assertEquals(said, "locate", Keywords.action(said));
            assertTrue(said, Keywords.isSelfRequest(said));
        }
        assertEquals("locate", Keywords.action("zoom in on the truck"));
        assertFalse(Keywords.isSelfRequest("zoom in on the truck"));
        assertFalse(Keywords.isSelfRequest("enemy truck 200 meters north of me"));
        assertFalse(Keywords.isSelfRequest("find me the nearest hospital"));
    }
}
