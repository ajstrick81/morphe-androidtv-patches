#!/usr/bin/env sh
# Regenerate killads-317-trace.js = prelude.js + the current shipped killads.js (its own
# leading 'use strict' line is dropped; the prelude carries it). Re-run after killads.js changes.
set -e
D=$(cd "$(dirname "$0")" && pwd)
SRC="$D/../../patches/src/main/resources/netflix/native/killads.js"
head -n 1 "$SRC" | grep -q "^'use strict';" || { echo "killads.js no longer starts with 'use strict'; check build.sh" >&2; exit 1; }
{ cat "$D/prelude.js"; tail -n +2 "$SRC"; } > "$D/killads-317-trace.js"
echo "wrote $D/killads-317-trace.js"
