#!/usr/bin/env bash
#
# Build the User Manual PDF from Markdown.
#
# Used by .github/workflows/release.yml and runnable locally:
#   bash scripts/build-manual-pdf.sh [input.md] [output.pdf]
#
# Renders via md-to-pdf (headless Chrome / Puppeteer), which reliably handles
# the manual's emoji (checkmarks), arrows and menu glyphs, pipe tables and
# fenced code blocks. A LaTeX pipeline (pandoc/xelatex) was tried first and
# chokes on the colour emoji, which is why we render through Chrome instead.
#
# Fails loudly (non-zero exit) if the PDF is not produced, so a broken release
# is caught in CI rather than published without the manual.
set -euo pipefail

IN="${1:-docs/USER_MANUAL.md}"
OUT="${2:-dist/USER_MANUAL.pdf}"
STYLE="$(cd "$(dirname "$0")" && pwd)/manual.css"

# Pin the renderer so releases are reproducible; bump deliberately.
MD_TO_PDF_VERSION="5.2.5"

if [ ! -f "$IN" ]; then
  echo "error: input markdown not found: $IN" >&2
  exit 1
fi
mkdir -p "$(dirname "$OUT")"

# md-to-pdf has no --output flag; it writes <input-basename>.pdf next to the
# input. Generate there, then move to the requested destination.
tmp_pdf="${IN%.md}.pdf"
rm -f "$tmp_pdf"

style_arg=()
if [ -f "$STYLE" ]; then
  style_arg=(--stylesheet "$STYLE")
fi

# Give Puppeteer a fresh profile dir so concurrent/repeat runs never collide on
# the shared default ("browser is already running for ..."). On Windows/MSYS,
# hand Chrome a native path and escape the backslashes for the JSON string.
user_data_dir="$(mktemp -d 2>/dev/null || echo "${TMPDIR:-/tmp}/md2pdf.$$")"
trap 'rm -rf "$user_data_dir"' EXIT
udd="$user_data_dir"
if command -v cygpath >/dev/null 2>&1; then
  udd="$(cygpath -w "$user_data_dir")"
fi
udd_json="${udd//\\/\\\\}"

# Prefer a browser already installed on the machine. GitHub runners ship
# google-chrome with all its shared libraries, so pointing Puppeteer at it lets
# us skip its own Chromium download — the usual CI failure point (a missing/
# broken bundled Chromium or a blocked postinstall download). Locally, where no
# system Chrome is on PATH, we fall through and let Puppeteer use its bundled one.
chrome_bin="${PUPPETEER_EXECUTABLE_PATH:-}"
if [ -z "$chrome_bin" ]; then
  for c in google-chrome google-chrome-stable chromium chromium-browser; do
    if command -v "$c" >/dev/null 2>&1; then chrome_bin="$(command -v "$c")"; break; fi
  done
fi
exec_json=""
if [ -n "$chrome_bin" ]; then
  # Don't download Chromium during the npx install; we'll use the system one.
  export PUPPETEER_SKIP_DOWNLOAD=true PUPPETEER_SKIP_CHROMIUM_DOWNLOAD=true
  cb_json="${chrome_bin//\\/\\\\}"
  exec_json=",\"executablePath\":\"${cb_json}\""
fi

launch_opts="{\"args\":[\"--no-sandbox\",\"--disable-setuid-sandbox\"],\"userDataDir\":\"${udd_json}\"${exec_json}}"

echo "node:   $(node --version 2>/dev/null || echo '?')"
echo "npx:    $(npx --version 2>/dev/null || echo '?')"
echo "chrome: ${chrome_bin:-<puppeteer bundled Chromium>}"
echo "in:     $IN"
echo "out:    $OUT"

npx --yes "md-to-pdf@${MD_TO_PDF_VERSION}" "$IN" \
  "${style_arg[@]}" \
  --launch-options "$launch_opts" \
  --pdf-options '{"format":"A4","margin":"18mm","printBackground":true}'

if [ ! -s "$tmp_pdf" ]; then
  echo "error: md-to-pdf did not produce a non-empty PDF at $tmp_pdf" >&2
  exit 1
fi

mv -f "$tmp_pdf" "$OUT"
echo "Wrote $OUT ($(wc -c < "$OUT") bytes)"
