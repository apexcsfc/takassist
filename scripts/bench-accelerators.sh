#!/usr/bin/env bash
# Benchmarks the bundled Snapdragon llama-server on the phone's NPU, GPU and CPU with the
# same model and the same work, so the numbers are directly comparable.
#
#   ./scripts/bench-accelerators.sh                    # all three
#   DEVICES="npu gpu" ./scripts/bench-accelerators.sh  # a subset
#
# Closes ATAK first (frees memory and stops the plugin's own server). Results go to
# ~/Downloads/bench-results.txt; each run's server log is pulled to ~/Downloads/bench-<dev>.log.

set -uo pipefail

ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
MODEL="${MODEL:-/sdcard/atak/llm/Qwen_Qwen3.5-9B-Q4_K_M.gguf}"
DEVICES="${DEVICES:-npu gpu cpu}"
RUNS="${RUNS:-3}"
PORT=8090
OUT="$HOME/Downloads/bench-results.txt"

PKG=$("$ADB" shell pm list packages | grep -i llmassist | sed 's/package://' | tr -d '\r')
[ -n "$PKG" ] || { echo "LLM Assist plugin isn't installed on the phone." >&2; exit 1; }
DIR=$("$ADB" shell pm path "$PKG" | sed 's#package:##; s#/base.apk##' | tr -d '\r')/lib/arm64
ENV="LD_LIBRARY_PATH=$DIR:/system/lib64:/vendor/lib64 ADSP_LIBRARY_PATH='$DIR;/vendor/dsp/cdsp;/vendor/lib/rfsa/adsp;/system/lib/rfsa/adsp;/vendor/dsp'"
SERVER_ARGS="-m $MODEL --host 127.0.0.1 --port $PORT -c 6144 -t 6 -np 1 --jinja --no-webui --cache-ram 0 --ctx-checkpoints 4"

# The same prompt for every device: about 1,000 tokens of map-assistant style text.
CHUNK="The patrol moved north along 11th Street NW past U Street, checked the Metro entrance, then turned east toward Howard University and reported no change near the reservoir. "
PROMPT=""
for i in $(seq 1 30); do PROMPT="$PROMPT$CHUNK"; done
PROMPT="${PROMPT}Summarize the patrol route in three short bullet points."
BODY=$(printf '{"prompt":"%s","n_predict":128,"temperature":0,"ignore_eos":true,"cache_prompt":false}' "$PROMPT")

num() { sed -n "s/.*\"$1\":\([0-9.]*\).*/\1/p" | head -1; }

stop_server() {
  "$ADB" shell "pkill -f libllamaserver_snap" >/dev/null 2>&1
  sleep 3
}

echo "== Closing ATAK and any running server"
"$ADB" shell am force-stop com.atakmap.app.civ
stop_server
"$ADB" forward tcp:$PORT tcp:$PORT >/dev/null

{
  echo "LLM Assist accelerator benchmark  $(date)"
  echo "model: $MODEL   runs per device: $RUNS   prompt: ~1000 tokens   output: 128 tokens"
  printf '%-5s %10s %22s %22s\n' device load_s "prompt_tok_per_s" "gen_tok_per_s"
} > "$OUT"

for dev in $DEVICES; do
  case "$dev" in
    npu) EXTRA="--device HTP0,HTP1,HTP2 -ngl 99"; DEV_ENV="GGML_HEXAGON_DEVICES=3" ;;
    gpu) EXTRA="--device GPUOpenCL -ngl 99";       DEV_ENV="" ;;
    cpu) EXTRA="--device none -ngl 0";             DEV_ENV="" ;;
    *) echo "unknown device $dev"; continue ;;
  esac
  echo "== $dev: starting server"
  # Only the server is backgrounded, with all of its stdio redirected, so 'adb shell' returns
  # immediately instead of waiting on it.
  "$ADB" shell "cd $DIR; $ENV $DEV_ENV nohup ./libllamaserver_snap.so $SERVER_ARGS $EXTRA \
      > /data/local/tmp/bench-$dev.log 2>&1 < /dev/null &" < /dev/null
  t0=$(date +%s); ok=0
  for _ in $(seq 1 240); do
    if curl -s -m 2 "http://127.0.0.1:$PORT/health" | grep -q '"ok"'; then ok=1; break; fi
    sleep 1
  done
  load=$(( $(date +%s) - t0 ))
  if [ $ok -ne 1 ]; then
    echo "   $dev: server didn't come up (see ~/Downloads/bench-$dev.log)"
    printf '%-5s %10s %22s %22s\n' "$dev" "fail" "-" "-" >> "$OUT"
    "$ADB" pull /data/local/tmp/bench-$dev.log "$HOME/Downloads/bench-$dev.log" >/dev/null 2>&1
    stop_server; continue
  fi
  echo "   loaded in ${load}s; warm-up run"
  curl -s -m 600 -H 'Content-Type: application/json' -d "$BODY" "http://127.0.0.1:$PORT/completion" >/dev/null
  pp_all=""; tg_all=""
  for r in $(seq 1 "$RUNS"); do
    resp=$(curl -s -m 600 -H 'Content-Type: application/json' -d "$BODY" "http://127.0.0.1:$PORT/completion")
    pp=$(echo "$resp" | num prompt_per_second); tg=$(echo "$resp" | num predicted_per_second)
    echo "   run $r: prompt ${pp:-?} tok/s, generation ${tg:-?} tok/s"
    pp_all="$pp_all ${pp:-0}"; tg_all="$tg_all ${tg:-0}"
  done
  avg() { echo "$@" | awk '{s=0; for(i=1;i<=NF;i++) s+=$i; printf "%.1f", s/NF}'; }
  printf '%-5s %10s %22s %22s\n' "$dev" "$load" "$(avg $pp_all)" "$(avg $tg_all)" >> "$OUT"
  "$ADB" pull /data/local/tmp/bench-$dev.log "$HOME/Downloads/bench-$dev.log" >/dev/null 2>&1
  stop_server
done

"$ADB" forward --remove tcp:$PORT >/dev/null 2>&1
echo
cat "$OUT"
echo
echo "Saved to $OUT. Reopen ATAK when done."
