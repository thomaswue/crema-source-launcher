#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
if [[ ! -x "$ROOT/build/crema" || ! -f "$ROOT/build/graalvm-home.txt" ]]; then
    echo 'Build the launcher first with ./build.sh.' >&2
    exit 1
fi
BUILT_JDK=$(< "$ROOT/build/graalvm-home.txt")
RUNTIME_JDK=${GRAALVM_HOME:-$BUILT_JDK}
if [[ ! -f "$RUNTIME_JDK/lib/modules" ]]; then
    echo "JDK module image missing: $RUNTIME_JDK/lib/modules" >&2
    exit 1
fi
exec "$ROOT/build/crema" "-Djava.home=$RUNTIME_JDK" "$@"
