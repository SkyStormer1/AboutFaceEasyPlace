#!/usr/bin/env bash
# Runs the placement search against stand-in blocks, without Minecraft.
#
# The mod itself needs a full Loom toolchain and a Minecraft jar to build. The search does not: it
# is ordinary Kotlin over a handful of Minecraft types, so those types are stubbed here and the
# search is exercised directly.
#
# Needs kotlinc (https://kotlinlang.org/docs/command-line.html) and a JDK on PATH.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(dirname "$here")"
out="$here/build"
main=com.skystormer.aboutfaceeasyplace.verification.SearchVerification

command -v kotlinc >/dev/null || { echo "kotlinc is not on PATH"; exit 1; }

rm -rf "$out"
mkdir -p "$out/stubs" "$out/classes"

# Stubs first: the Kotlin below is compiled against them, standing in for Minecraft and Fabric.
javac -nowarn -d "$out/stubs" $(find "$here/stubs" -name '*.java')

kotlinc -nowarn -classpath "$out/stubs" -d "$out/classes" \
    "$root/src/main/kotlin" \
    "$root/src/main/java/com/skystormer/aboutfaceeasyplace/BlockStates.java" \
    "$here/SearchVerification.kt"

javac -nowarn -d "$out/classes" -classpath "$out/stubs:$out/classes" \
    $(find "$root/src/main/java" -name '*.java')

if command -v kotlin >/dev/null; then
    kotlin -classpath "$out/classes:$out/stubs" "$main"
else
    # No `kotlin` launcher, so the stdlib has to be found next to the compiler by hand.
    stdlib="$(dirname "$(readlink -f "$(command -v kotlinc)")")/../lib/kotlin-stdlib.jar"
    java -classpath "$out/classes:$out/stubs:$stdlib" "$main"
fi
