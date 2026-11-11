# TAK Assist

An ATAK-CIV plugin with a language model running on the phone itself, fully offline.
One toolbar button opens a pane with two tabs:

- **Chat:** ask questions in plain words and the model answers from the map: your
  position, what is nearby, the nearest water or hospital, and turn-by-turn directions
  drawn on the map.
- **Talk:** say or type a report ("enemy truck 200 meters north of me") and it becomes a
  map marker after the user confirms it.

TAK Assist replaces two earlier ApexCSFC plugins, LLM Assist and Talk Assist.

- **Plugin version:** 1.0.0
- **ATAK version:** ATAK-CIV 5.8.0
- **Package:** `com.apexcsfc.atak.takassist`
- **Developer:** ApexCSFC
- **Point of contact:** msheehan@apexcsfc.com

## What it does

**Chat.** The pane sends the user's question to a model server on `127.0.0.1`
(OpenAI-compatible chat API). The model can call these plugin tools, and every distance,
bearing, coordinate and route comes from plugin code, not from the model:

| Tool | Purpose |
| --- | --- |
| get_self_location, get_map_view | User position (MGRS and lat/lon), map center and scale |
| find_place, find_nearest, get_nearby_places, get_nearby_roads | Place and street lookups |
| get_directions | Walk, bike or drive routes, optionally drawn as a polyline |
| get_elevation | Ground elevation (MSL) |
| get_nearby_markers, find_markers, distance_bearing | Read existing map items |
| place_marker | Add a point; asks the user to confirm by default |
| pan_map | Center the map |

**Talk.** Speech is turned into text by Android's speech recognizer (offline when the
device has the language pack). One schema-constrained request to the same local model
copies the report into fixed fields (what, affiliation, distance, bearing, grid). Plain
Java code then works out the exact point (WGS-84 geodesy, MGRS, magnetic to true), and
the user confirms a card before anything is placed or sent. Missing or out-of-range
values produce a "say again" prompt, never a guess.

**TAK Assist PTT** is a launcher shortcut with no screen, for a hardware button such as
Samsung's side-key double press: it brings ATAK forward and starts listening on the Talk
tab (an explicit broadcast to ATAK's own package).

## Requirements

- ATAK-CIV 5.8 on a 64-bit ARM Android device; 8 GB RAM minimum, 12 GB recommended.
- A GGUF model file in `Download/TAKAssist/` (for example Llama 3.2 3B Q4_K_M, about
  2 GB). Models are not bundled.
- Optional offline map data (places, streets, road network), built from OpenStreetMap
  extracts with `scripts/build_offline_map.py`, as single-file `.takmap` packs.

## Installation and use

1. Install the APK and load **TAK Assist** from ATAK → Settings → Plugins.
2. Put a `.gguf` model, and optionally `.takmap` map packs, in `Download/TAKAssist/` on the
   device (by MDM file deployment, USB file transfer, or the device's browser).
3. Tap the TAK Assist toolbar button. The model loads in 15–40 seconds.

Map packs found anywhere in `Download/` are unpacked into `Download/TAKAssist/regions/`
when the plugin starts. Several areas are joined into one map (Chat → Settings → Map packs).

## Permissions

The plugin's manifest requests no permissions. It runs inside ATAK and uses ATAK's
existing storage, microphone and network access. The manifest declares a `<queries>`
entry for the ATAK packages so the PTT shortcut can bring ATAK forward.

## Files

- Reads models and map data from `Download/TAKAssist/` and below, and models anywhere in
  `Download/`. Writes there only: unpacked map areas, the active joined map, `server.log`.
- On first start, moves models and maps from the earlier `atak/llm/` folder to
  `Download/TAKAssist/`.

## Network use

- **Model server:** HTTP to `127.0.0.1` only by default. The user can change the server
  URL in Settings to use their own server on a local network.
- **OpenStreetMap services (optional):** used only when the offline map data doesn't
  cover the area and a connection is available: `nominatim.openstreetmap.org` (place
  search), `overpass-api.de` (nearby features) and `routing.openstreetmap.de` (routing).
  The hosts are fixed in code and all parameters are URL-encoded.
- No analytics, telemetry or accounts.

## Native components

`app/src/main/jniLibs/arm64-v8a/` contains builds of llama.cpp's `llama-server`
(MIT license, release b11748), built from unmodified source with the scripts in
`scripts/`:

- `libllamaserver.so`: static ARM64 CPU build (`scripts/build-llama-server.sh`).
- `libllamaserver_snap.so` and its libraries: Snapdragon build with Adreno OpenCL and
  Hexagon NPU backends (`scripts/build-llama-server-snapdragon.sh`, Qualcomm's toolchain).
  The `libggml-htp-v*.so` files run on the Hexagon DSP.

The plugin starts the server as a separate child process (no shell), with an argument
list built from fixed flags and a model path restricted to the device's `Download/`
folder. The server listens on `127.0.0.1` only and stops when the plugin unloads.

## Security notes

- The model's output is shown as text only and is never executed.
- Map edits from the model (placing a marker) need user confirmation unless the user
  turns that off. Talk reports always need confirmation.
- Map packs are zip files; only `area.sqlite`, `route.bin` and `region.json` entries are
  extracted, by fixed name, into the plugin's own folder.

## Build

Standard ATAK plugin template (civ flavor):

```
./gradlew assembleCivRelease
```

Unit tests (geodesy, MGRS, report parsing): `./gradlew testCivDebugUnitTest`.

## Third-party notices

See `THIRD_PARTY_NOTICES.md` (llama.cpp / ggml, nlohmann/json, OpenStreetMap data).
