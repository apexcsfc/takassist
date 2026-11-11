#!/usr/bin/env python3
"""
Builds the offline map data that TAK Assist uses on the phone.

    python3 build_offline_map.py --bbox 38.637,-77.383,39.177,-76.691 \
        --out OUTDIR  dc.osm.pbf maryland.osm.pbf virginia.osm.pbf

Outputs:

  area.sqlite   places, POIs, street/water/park geometry, per-category points,
                indexed on a 0.01-degree grid (no SQLite extensions needed).
  route.bin     compact routable road graph (foot / bike / car) for A*.

With --pack ID, also writes ID.takmap: one file holding both, for MDM file deployment
(e.g. Knox Manage Content into Download/TAKAssist). TAK Assist installs any .takmap it
finds under Download/ and switches it on.

Needs: pip install osmium
"""

import argparse
import math
import os
import sqlite3
import struct
import sys
import time
from array import array

import osmium

CELL = 0.01          # grid cell size in degrees for area.sqlite
CHUNK = 16           # max points per stored shape chunk
ROUTE_CELL = 0.005   # grid cell size for route-node snapping

# --------------------------------------------------------------------------- tags

PLACE_KINDS = {"city", "town", "village", "hamlet", "suburb", "neighbourhood",
               "quarter", "locality", "square", "island"}
AMENITY_NAMED = {"school", "college", "university", "hospital", "clinic", "doctors",
                 "pharmacy", "police", "fire_station", "library", "townhall",
                 "courthouse", "embassy", "place_of_worship", "post_office",
                 "community_centre", "theatre", "cinema", "arts_centre", "bank",
                 "restaurant", "fast_food", "cafe", "bar", "pub", "fuel", "parking",
                 "marketplace", "shelter", "toilets", "drinking_water", "atm",
                 "bus_station", "ferry_terminal", "fountain"}


def categories(tags):
    """find_nearest categories this object belongs to (must match OsmClient.CATEGORIES)."""
    c = []
    a = tags.get("amenity")
    if a == "hospital": c.append("hospital")
    if a in ("clinic", "doctors"): c.append("clinic")
    if a == "pharmacy": c.append("pharmacy")
    if a == "police": c.append("police")
    if a == "fire_station": c.append("fire_station")
    if a in ("school", "college", "university"): c.append("school")
    if a == "fuel": c.append("fuel")
    if a == "parking": c.append("parking")
    if a == "toilets": c.append("restroom")
    if a == "drinking_water": c.append("drinking_water")
    if a == "shelter": c.append("shelter")
    if a in ("restaurant", "fast_food", "cafe"): c.append("food")
    if a in ("atm", "bank"): c.append("atm")
    if tags.get("shop") in ("supermarket", "convenience"): c.append("grocery")
    if tags.get("railway") == "station" or (tags.get("public_transport") == "station"
                                            and tags.get("subway") == "yes"):
        c.append("metro_station")
    if tags.get("highway") == "bus_stop": c.append("bus_stop")
    if tags.get("natural") in ("peak", "hill"): c.append("high_ground")
    return c


def kind_of(tags):
    if tags.get("natural") == "water" and tags.get("water"):
        return tags.get("water").replace("_", " ")   # lake, pond, reservoir, river, fountain...
    if tags.get("landuse") == "reservoir":
        return "reservoir"
    for k in ("railway", "amenity", "shop", "leisure", "tourism", "historic",
              "natural", "water", "waterway", "place", "building", "highway"):
        v = tags.get(k)
        if v:
            if k == "railway" and v == "station":
                return (tags.get("station") + " station") if tags.get("station") else "station"
            if k == "shop":
                return v.replace("_", " ") + " shop"
            if k == "building":
                return "building"
            return v.replace("_", " ")
    return "feature"


def is_water_area(tags):
    return (tags.get("natural") == "water" or tags.get("landuse") == "reservoir"
            or tags.get("natural") == "bay" or tags.get("waterway") == "riverbank")


def is_park_area(tags):
    return tags.get("leisure") in ("park", "nature_reserve", "garden")


def interesting_point(tags):
    """Named things worth finding by name / listing nearby."""
    if not tags.get("name"):
        return False
    if tags.get("place") in PLACE_KINDS: return True
    if tags.get("amenity") in AMENITY_NAMED: return True
    if tags.get("shop"): return True
    if tags.get("leisure") in ("park", "garden", "playground", "pitch", "stadium",
                               "sports_centre", "nature_reserve", "marina"): return True
    if tags.get("tourism") in ("attraction", "museum", "viewpoint", "hotel", "gallery",
                               "zoo", "monument", "artwork"): return True
    if tags.get("historic"): return True
    if tags.get("railway") == "station" or tags.get("public_transport") == "station": return True
    if tags.get("natural") in ("water", "peak", "hill", "wood", "bay", "beach", "cliff"): return True
    if tags.get("waterway") in ("river", "stream", "canal", "dam"): return True
    if tags.get("landuse") == "reservoir": return True
    if tags.get("building") and tags.get("building") != "no": return True
    if tags.get("highway") == "bus_stop": return True
    if tags.get("aeroway") in ("aerodrome", "terminal"): return True
    if tags.get("bridge") == "yes" and tags.get("man_made") == "bridge": return True
    return False

# ------------------------------------------------------------------- routing rules

HWY_CLASS = {"motorway": 1, "motorway_link": 1, "trunk": 2, "trunk_link": 2,
             "primary": 3, "primary_link": 3, "secondary": 4, "secondary_link": 4,
             "tertiary": 5, "tertiary_link": 5, "unclassified": 6, "residential": 7,
             "living_street": 8, "service": 9, "road": 6, "pedestrian": 11,
             "footway": 12, "path": 12, "steps": 13, "cycleway": 14, "track": 15,
             "bridleway": 12, "corridor": 12}
CAR_OK = {1, 2, 3, 4, 5, 6, 7, 8, 9}
NO_VALUES = {"no", "private", "discouraged"}
YES_VALUES = {"yes", "designated", "permissive", "destination", "official"}


def access(tags):
    """Returns (foot, bike, car, car_oneway, bike_oneway) for a highway way, or None."""
    hw = tags.get("highway")
    cls = HWY_CLASS.get(hw)
    if cls is None or tags.get("area") == "yes":
        return None
    gen = tags.get("access")
    blocked = gen in NO_VALUES

    def mode(key, default):
        v = tags.get(key)
        if v in YES_VALUES: return True
        if v in NO_VALUES: return False
        if blocked: return False
        return default

    foot = mode("foot", cls not in (1,))
    if hw == "trunk" and tags.get("foot") is None and tags.get("sidewalk") in (None, "no", "none"):
        foot = foot  # allowed by default in US OSM conventions
    bike = mode("bicycle", cls not in (1, 13) and not (cls == 12 and hw == "footway"))
    car_default = cls in CAR_OK and tags.get("service") not in ("parking_aisle", "drive-through")
    car = mode("motor_vehicle", car_default)
    if tags.get("motorcar") in NO_VALUES: car = False
    if tags.get("motorcar") in YES_VALUES: car = True

    ow = tags.get("oneway")
    oneway = 0
    if ow in ("yes", "1", "true"): oneway = 1
    elif ow == "-1": oneway = -1
    elif tags.get("junction") in ("roundabout", "circular") or hw in ("motorway", "motorway_link"):
        oneway = 1 if ow != "no" else 0
    bike_oneway = oneway
    if tags.get("oneway:bicycle") == "no" or (tags.get("cycleway") or "").startswith("opposite"):
        bike_oneway = 0
    if not (foot or bike or car):
        return None
    return foot, bike, car, oneway, bike_oneway, cls

# ------------------------------------------------------------------------ helpers

# Must match OfflineArea.norm() in the plugin exactly.
ABBREV = {"street": "st", "avenue": "ave", "road": "rd", "boulevard": "blvd", "drive": "dr",
          "place": "pl", "court": "ct", "terrace": "ter", "lane": "ln", "parkway": "pkwy",
          "highway": "hwy", "circle": "cir", "square": "sq", "northwest": "nw",
          "northeast": "ne", "southwest": "sw", "southeast": "se", "north": "n",
          "south": "s", "east": "e", "west": "w", "saint": "st", "mount": "mt",
          "fort": "ft", "expressway": "expy", "freeway": "fwy"}


def norm(s):
    out = []
    word = []
    for ch in (s or "").lower():
        if ch.isalnum():
            word.append(ch)
        else:
            if word:
                out.append("".join(word))
                word = []
    if word:
        out.append("".join(word))
    return " ".join(ABBREV.get(w, w) for w in out)



def e7(v):
    return int(round(v * 1e7))


def cell(lat, lon, size=CELL):
    return int(math.floor(lon / size)), int(math.floor(lat / size))


def hav(lat1, lon1, lat2, lon2):
    r = 6371008.8
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = p2 - p1, math.radians(lon2 - lon1)
    h = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(min(1.0, math.sqrt(h)))


def pack_coords(pts):
    return struct.pack("<%di" % (2 * len(pts)), *[v for la, lo in pts for v in (e7(la), e7(lo))])


class Box:
    def __init__(self, s, w, n, e):
        self.s, self.w, self.n, self.e = s, w, n, e

    def has(self, lat, lon):
        return self.s <= lat <= self.n and self.w <= lon <= self.e

# ------------------------------------------------------------------------- builder


class Builder:
    def __init__(self, box, outdir):
        self.box = box
        self.outdir = outdir
        os.makedirs(outdir, exist_ok=True)
        path = os.path.join(outdir, "area.sqlite")
        if os.path.exists(path):
            os.remove(path)
        self.db = sqlite3.connect(path)
        self.db.executescript("""
            PRAGMA journal_mode=OFF; PRAGMA synchronous=OFF;
            CREATE TABLE meta(k TEXT PRIMARY KEY, v TEXT);
            CREATE TABLE features(name TEXT, name_lc TEXT, kind TEXT, cat TEXT,
                                  lat REAL, lon REAL, cx INTEGER, cy INTEGER);
            CREATE TABLE shapes(name TEXT, name_lc TEXT, kind TEXT, cat TEXT,
                                coords BLOB, cx INTEGER, cy INTEGER);
        """)
        self.seen_feat = set()      # (osm type, id) already stored as feature
        self.seen_way = set()       # routable ways already taken (dedupe across files)
        self.seen_shape = set()
        # routing accumulation
        self.r_ways = []            # (node_ids array, foot, bike, car, ow, bow, cls, name)
        self.node_use = {}          # node id -> use count
        self.node_loc = {}          # node id -> (lat, lon)
        self.n_feat = 0
        self.n_shape = 0

    # ------------------------------------------------------------- area.sqlite rows

    def add_feature(self, key, tags, lat, lon):
        if key in self.seen_feat or not self.box.has(lat, lon):
            return
        name = tags.get("name") or ""
        cats = categories(tags)
        if not name and not cats:
            return
        self.seen_feat.add(key)
        kind = kind_of(tags)
        cx, cy = cell(lat, lon)
        rows = [(name, norm(name), kind, c, lat, lon, cx, cy) for c in (cats or [""])]
        self.db.executemany("INSERT INTO features VALUES(?,?,?,?,?,?,?,?)", rows)
        self.n_feat += len(rows)

    def add_shape(self, key, name, kind, cat, pts):
        if key in self.seen_shape or len(pts) < 2:
            return
        if not any(self.box.has(la, lo) for la, lo in pts):
            return
        self.seen_shape.add(key)
        rows = []
        i = 0
        while i < len(pts) - 1:
            chunk = pts[i:i + CHUNK]
            if len(chunk) < 2:
                break
            if any(self.box.has(la, lo) for la, lo in chunk):
                cx, cy = cell(chunk[0][0], chunk[0][1])
                rows.append((name, norm(name), kind, cat, pack_coords(chunk), cx, cy))
            i += CHUNK - 1  # overlap by one point so chunks connect
        self.db.executemany("INSERT INTO shapes VALUES(?,?,?,?,?,?,?)", rows)
        self.n_shape += len(rows)

    # ------------------------------------------------------------------ pbf passes

    def process(self, path):
        t0 = time.time()
        print("reading", path, flush=True)
        keys = ("name", "highway", "amenity", "shop", "leisure", "tourism", "historic",
                "railway", "public_transport", "natural", "waterway", "landuse", "place",
                "building", "aeroway", "man_made")
        fp = (osmium.FileProcessor(path)
              .with_locations()
              .with_areas()
              .with_filter(osmium.filter.KeyFilter(*keys)))
        n = 0
        for obj in fp:
            n += 1
            if n % 500000 == 0:
                print("  ", n, "objects", flush=True)
            if obj.is_node():
                loc = obj.location
                if loc.valid():
                    self.add_feature(("n", obj.id), obj.tags, loc.lat, loc.lon)
            elif obj.is_way():
                self.way(obj)
            elif obj.is_area():
                self.area(obj)
        print("  done in %.0fs: %d features, %d shape chunks, %d routable ways"
              % (time.time() - t0, self.n_feat, self.n_shape, len(self.r_ways)), flush=True)

    def way(self, w):
        tags = w.tags
        try:
            pts = [(n.location.lat, n.location.lon) for n in w.nodes]
        except osmium.InvalidLocationError:
            return
        if len(pts) < 2 or not any(self.box.has(la, lo) for la, lo in pts):
            return
        name = tags.get("name") or ""

        # Streets: lookup geometry + routing graph.
        acc = access(tags) if tags.get("highway") else None
        if acc and w.id not in self.seen_way:
            self.seen_way.add(w.id)
            ids = array("q", (n.ref for n in w.nodes))
            for i, ref in enumerate(ids):
                self.node_use[ref] = self.node_use.get(ref, 0) + (2 if i in (0, len(ids) - 1) else 1)
                if ref not in self.node_loc:
                    self.node_loc[ref] = pts[i]
            ref_name = name or tags.get("ref") or ""
            sub = tags.get("footway") or ""
            if tags.get("highway") == "footway" and sub == "" and tags.get("crossing"):
                sub = "crossing"
            self.r_ways.append((ids, *acc, ref_name, sub))
            if name:
                self.add_shape(("w", w.id), name, tags.get("highway").replace("_", " "), "road", pts)

        # Rivers/streams/canals as lines.
        if tags.get("waterway") in ("river", "stream", "canal"):
            self.add_shape(("ww", w.id), name, tags.get("waterway"), "water", pts)

        # Named ways that are POIs but not areas (closed ways become areas).
        if not w.is_closed() and interesting_point(tags) and not tags.get("highway"):
            la, lo = pts[len(pts) // 2]
            self.add_feature(("w", w.id), tags, la, lo)

    def area(self, a):
        tags = a.tags
        water, park = is_water_area(tags), is_park_area(tags)
        poi = interesting_point(tags) or categories(tags)
        if not (water or park or poi):
            return
        rings = []
        try:
            for outer in a.outer_rings():
                rings.append([(n.location.lat, n.location.lon) for n in outer])
        except osmium.InvalidLocationError:
            return
        if not rings:
            return
        pts_all = [p for r in rings for p in r]
        if not any(self.box.has(la, lo) for la, lo in pts_all):
            return
        key = ("a", a.id)
        name = tags.get("name") or ""
        if water or park:
            for i, r in enumerate(rings):
                self.add_shape(("a", a.id, i), name, kind_of(tags), "water" if water else "park", r)
        if poi:
            # centroid of the largest ring (good enough for a label point)
            r = max(rings, key=len)
            la = sum(p[0] for p in r) / len(r)
            lo = sum(p[1] for p in r) / len(r)
            if not self.box.has(la, lo):
                la, lo = min(r, key=lambda p: abs(p[0] - (self.box.s + self.box.n) / 2))
            self.add_feature(key, tags, la, lo)

    # ------------------------------------------------------------------- finishing

    def finish_sqlite(self):
        print("indexing area.sqlite", flush=True)
        b = self.box
        self.db.executemany("INSERT INTO meta VALUES(?,?)", [
            ("south", str(b.s)), ("west", str(b.w)), ("north", str(b.n)), ("east", str(b.e)),
            ("cell", str(CELL)), ("chunk", str(CHUNK)),
            ("built", time.strftime("%Y-%m-%d")), ("source", "OpenStreetMap contributors (ODbL)"),
        ])
        self.db.executescript("""
            CREATE INDEX fx_cell ON features(cx, cy);
            CREATE INDEX fx_cat ON features(cat, cx, cy);
            CREATE INDEX fx_name ON features(name_lc);
            CREATE INDEX sh_cat ON shapes(cat, cx, cy);
            CREATE INDEX sh_name ON shapes(name_lc);
            ANALYZE;
        """)
        self.db.commit()
        self.db.execute("VACUUM")
        self.db.close()

    def name_sidewalks(self):
        """Give unnamed sidewalks/paths the name of the street they run beside, so walking
        directions say "11th St NW" instead of "the path". Crossings become "the crosswalk"."""
        G = 0.0005  # ~50 m grid
        grid = {}
        for ids, foot, bike, car, ow, bow, cls, name, sub in self.r_ways:
            if not name or cls > 9:
                continue
            pts = [self.node_loc[r] for r in ids]
            for k in range(len(pts) - 1):
                (a1, o1), (a2, o2) = pts[k], pts[k + 1]
                for gy in range(int(math.floor(min(a1, a2) / G)) - 1, int(math.floor(max(a1, a2) / G)) + 2):
                    for gx in range(int(math.floor(min(o1, o2) / G)) - 1, int(math.floor(max(o1, o2) / G)) + 2):
                        grid.setdefault((gx, gy), []).append((a1, o1, a2, o2, name))

        def seg_dist(lat, lon, a1, o1, a2, o2):
            kx = math.cos(math.radians(lat))
            px, py = lon * kx, lat
            ax, ay, bx, by = o1 * kx, a1, o2 * kx, a2
            dx, dy = bx - ax, by - ay
            l2 = dx * dx + dy * dy
            t = 0 if l2 == 0 else max(0, min(1, ((px - ax) * dx + (py - ay) * dy) / l2))
            qx, qy = ax + t * dx, ay + t * dy
            return math.hypot(qx - px, qy - py) * 111_320

        named = 0
        for i, w in enumerate(self.r_ways):
            ids, foot, bike, car, ow, bow, cls, name, sub = w
            if name or cls < 11:
                continue
            if sub == "crossing":
                self.r_ways[i] = (ids, foot, bike, car, ow, bow, cls, "the crosswalk", sub)
                continue
            pts = [self.node_loc[r] for r in ids]
            votes = {}
            for lat, lon in pts[:: max(1, len(pts) // 4)] + [pts[-1]]:
                best, bd = None, 25.0
                for a1, o1, a2, o2, nm in grid.get((int(math.floor(lon / G)), int(math.floor(lat / G))), ()):
                    d = seg_dist(lat, lon, a1, o1, a2, o2)
                    if d < bd:
                        best, bd = nm, d
                if best:
                    votes[best] = votes.get(best, 0) + 1
            if votes:
                nm = max(votes, key=votes.get)
                self.r_ways[i] = (ids, foot, bike, car, ow, bow, cls, nm, sub)
                named += 1
        print("  named %d sidewalks/paths after adjacent streets" % named, flush=True)

    def finish_route(self):
        print("building route graph from %d ways" % len(self.r_ways), flush=True)
        # Graph nodes = junctions + way ends. Everything in between becomes shape points.
        node_index = {}
        lat_arr, lon_arr = array("i"), array("i")

        def nid(ref):
            i = node_index.get(ref)
            if i is None:
                i = len(lat_arr)
                node_index[ref] = i
                la, lo = self.node_loc[ref]
                lat_arr.append(e7(la))
                lon_arr.append(e7(lo))
            return i

        self.name_sidewalks()
        names = {"": 0}
        name_list = [""]
        shape_lat, shape_lon = array("i"), array("i")
        edges = []  # (src, dst, len_dm, name_id, shape_start, shape_count, flags, cls)

        for ids, foot, bike, car, ow, bow, cls, name, _sub in self.r_ways:
            nm = names.get(name)
            if nm is None:
                nm = len(name_list)
                names[name] = nm
                name_list.append(name)
            seg_start = 0
            for i in range(1, len(ids)):
                ref = ids[i]
                if i == len(ids) - 1 or self.node_use.get(ref, 0) > 1:
                    a_ref, b_ref = ids[seg_start], ref
                    inner = ids[seg_start + 1:i]
                    pts = [self.node_loc[r] for r in ids[seg_start:i + 1]]
                    length = sum(hav(*pts[k], *pts[k + 1]) for k in range(len(pts) - 1))
                    s0 = len(shape_lat)
                    for r in inner:
                        la, lo = self.node_loc[r]
                        shape_lat.append(e7(la))
                        shape_lon.append(e7(lo))
                    a, b = nid(a_ref), nid(b_ref)
                    if a != b:
                        dm = max(1, int(round(length * 10)))
                        # flags: bit0 foot, bit1 bike, bit2 car, bit3 shape reversed
                        fwd = (1 if foot else 0) | (2 if bike and bow >= 0 else 0) | (4 if car and ow >= 0 else 0)
                        rev = (1 if foot else 0) | (2 if bike and bow <= 0 else 0) | (4 if car and ow <= 0 else 0)
                        if fwd:
                            edges.append((a, b, dm, nm, s0, len(inner), fwd, cls))
                        if rev:
                            edges.append((b, a, dm, nm, s0, len(inner), rev | 8, cls))
                    seg_start = i

        n = len(lat_arr)
        edges.sort(key=lambda e: e[0])
        first = array("i", [0] * (n + 1))
        for e in edges:
            first[e[0] + 1] += 1
        for i in range(n):
            first[i + 1] += first[i]

        # snapping grid over the graph nodes
        min_lat = min(lat_arr) if n else 0
        min_lon = min(lon_arr) if n else 0
        cs = int(ROUTE_CELL * 1e7)
        gw = (max(lon_arr) - min_lon) // cs + 1 if n else 1
        gh = (max(lat_arr) - min_lat) // cs + 1 if n else 1
        buckets = [[] for _ in range(gw * gh)]
        for i in range(n):
            buckets[((lat_arr[i] - min_lat) // cs) * gw + (lon_arr[i] - min_lon) // cs].append(i)
        cell_start = array("i", [0])
        cell_nodes = array("i")
        for bkt in buckets:
            cell_nodes.extend(bkt)
            cell_start.append(len(cell_nodes))

        name_bytes = [s.encode("utf-8") for s in name_list]
        name_off = array("i", [0])
        for nb in name_bytes:
            name_off.append(name_off[-1] + len(nb))

        path = os.path.join(self.outdir, "route.bin")
        with open(path, "wb") as f:
            # header: 64 bytes
            f.write(struct.pack("<4siiiiiiiiiii", b"LLMR", 1, n, len(edges), len(shape_lat),
                                len(name_list), min_lat, min_lon, cs, gw, gh, len(cell_nodes)))
            f.write(b"\0" * (64 - 48))
            f.write(lat_arr.tobytes()); f.write(lon_arr.tobytes())
            f.write(first.tobytes())
            rec = struct.Struct("<iiiiHBB")
            for e in edges:
                f.write(rec.pack(e[1], e[2], e[3], e[4], min(e[5], 65535), e[6], e[7]))
            f.write(shape_lat.tobytes()); f.write(shape_lon.tobytes())
            f.write(name_off.tobytes())
            f.write(b"".join(name_bytes))
            pad = (-f.tell()) % 4
            f.write(b"\0" * pad)
            f.write(cell_start.tobytes()); f.write(cell_nodes.tobytes())
        print("route.bin: %d nodes, %d edges, %d shape pts, %d names, %.1f MB"
              % (n, len(edges), len(shape_lat), len(name_list), os.path.getsize(path) / 1e6), flush=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--bbox", required=True, help="south,west,north,east")
    ap.add_argument("--out", required=True)
    ap.add_argument("--pack", metavar="ID", help="also write ID.takmap (e.g. dc-md-va)")
    ap.add_argument("--name", help="area name shown on the phone (with --pack)")
    ap.add_argument("pbf", nargs="+")
    a = ap.parse_args()
    s, w, n, e = (float(x) for x in a.bbox.split(","))
    if sys.byteorder != "little":
        sys.exit("needs a little-endian machine")
    b = Builder(Box(s, w, n, e), a.out)
    for p in a.pbf:
        b.process(p)
    b.finish_route()
    b.finish_sqlite()
    print("area.sqlite: %.1f MB" % (os.path.getsize(os.path.join(a.out, "area.sqlite")) / 1e6))
    if a.pack:
        import json, zipfile
        pid = a.pack.lower()
        region = {"id": pid, "name": a.name or pid, "built": time.strftime("%Y-%m-%d"), "bbox": [s, w, n, e]}
        path = os.path.join(a.out, pid + ".takmap")
        with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED, allowZip64=True) as z:
            z.writestr("region.json", json.dumps(region))
            z.write(os.path.join(a.out, "area.sqlite"), "area.sqlite")
            z.write(os.path.join(a.out, "route.bin"), "route.bin")
        print("%s: %.1f MB" % (path, os.path.getsize(path) / 1e6))


if __name__ == "__main__":
    main()
