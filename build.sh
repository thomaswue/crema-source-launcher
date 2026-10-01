#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
GRAALVM_HOME=${GRAALVM_HOME:-${JAVA_HOME:-}}
if [[ -z "$GRAALVM_HOME" || ! -x "$GRAALVM_HOME/bin/native-image" ]]; then
    echo 'Set GRAALVM_HOME (or JAVA_HOME) to a Crema-enabled GraalVM JDK.' >&2
    exit 1
fi
GRAALVM_HOME=$(cd -- "$GRAALVM_HOME" && pwd -P)
mkdir -p "$ROOT/build"
mkdir -p "$ROOT/build/classes"
cd "$ROOT/build"
"$GRAALVM_HOME/bin/native-image" --version | tee graalvm-version.txt
"$GRAALVM_HOME/bin/javac" \
    --add-exports=jdk.compiler/com.sun.tools.javac.launcher=ALL-UNNAMED \
    -d "$ROOT/build/classes" "$ROOT/src/CremaSourceLauncher.java"

# SourceLauncher and its reachable javac implementation are AOT compiled.
# Crema loads the bytecode produced by javac at runtime. Preserve the whole
# java.base module so new source classes can link against its APIs and internals.
"$GRAALVM_HOME/bin/native-image" \
    --add-exports=jdk.compiler/com.sun.tools.javac.launcher=ALL-UNNAMED \
    -cp "$ROOT/build/classes" \
    -H:+UnlockExperimentalVMOptions \
    -H:+RuntimeClassLoading \
    -H:Preserve=module=java.base \
    -H:-InterpreterTraceSupport \
    -H:+AllowJRTFileSystem \
    "-H:ConfigurationFileDirectories=$ROOT/config" \
    --initialize-at-run-time=com.sun.tools.javac.file.Locations \
    '--initialize-at-build-time=com.sun.tools.doclint,com.sun.tools.javac.parser.Tokens$TokenKind,com.sun.tools.javac.parser.Tokens$Token$Tag' \
    --emit build-report \
    "$@" \
    -o "$ROOT/build/crema-source-launcher" \
    CremaSourceLauncher \
    2>&1 | tee build.log

# Record the JDK used to build this image; its lib/modules is required at
# runtime for javac to resolve platform classes through the jrt filesystem.
printf '%s\n' "$GRAALVM_HOME" > graalvm-home.txt
echo "Built $ROOT/build/crema-source-launcher"
