#!/usr/bin/env bash
# Runs the placement search against stand-in blocks, without Minecraft.
#
# The mod itself needs a full Loom toolchain and a Minecraft jar to build. The search does not:
# it is ordinary Kotlin over a handful of Minecraft types, so the types are stubbed here and the
# search is exercised directly. What this proves is that the search finds a rotation and a click
# producing a wanted block state; what it cannot prove is that Minecraft places blocks the way the
# stand-ins in SearchVerification.kt say it does.
#
# Needs kotlinc (https://kotlinlang.org/docs/command-line.html) and a JDK on PATH.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(dirname "$here")"
out="$here/build"

command -v kotlinc >/dev/null || { echo "kotlinc is not on PATH"; exit 1; }

rm -rf "$out"
mkdir -p "$out/stubs" "$out/classes"

javac -nowarn -d "$out/stubs" $(find "$here/stubs" -name '*.java')
kotlinc -nowarn -classpath "$out/stubs" -d "$out/classes" \
    "$root/src/main/kotlin" \
    "$root/src/main/java/com/skystormer/aboutfaceeasyplace/BlockStates.java" \
    "$here/SearchVerification.kt"
javac -nowarn -d "$out/classes" -classpath "$out/stubs:$out/classes" \
    $(find "$root/src/main/java" -name '*.java')

java -classpath "$out/classes:$out/stubs:$(kotlinc -version 2>&1 >/dev/null; dirname "$(command -v kotlinc)")/../lib/kotlin-stdlib.jar" \
    com.skystormer.aboutfaceeasyplace.verification.SearchVerification
