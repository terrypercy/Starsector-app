#!/bin/bash
# Build claude_captain.jar. Usage: C=<starsector-core dir> OUT=<output dir> tools/build.sh  (run from the repo root)
# Needs a JDK (javac, jar) and python3. The game must be closed to load a new jar.
set -e
: "${C:?set C to starsector-core}" "${OUT:?set OUT}"
OUT="$(mkdir -p "$OUT" && cd "$OUT" && pwd)"
CP="$C/starfarer.api.jar:$C/json.jar:$C/log4j-1.2.9.jar:$C/lwjgl.jar:$C/lwjgl_util.jar"
rm -rf "$OUT/classes" "$OUT/stubs" && mkdir -p "$OUT/classes" "$OUT/stubs"
# compile-time stand-ins for the game's obfuscated UI classes (not packaged; the game has the real ones)
javac -encoding UTF-8 --release 8 -nowarn -Xlint:-options -cp "$CP" -d "$OUT/stubs" $(find stubs -name '*.java')
# 1.0.9: stamp the build (git commit, "+dirty" when uncommitted) so the game's load line says which build runs
mkdir -p "$OUT/gen"
B="$(git rev-parse --short HEAD 2>/dev/null || echo nogit)$(git diff --quiet 2>/dev/null || echo +dirty)"
printf 'package claudecaptain;\npublic final class BuildInfo { private BuildInfo() {} public static final String BUILD = "%s"; }\n' "$B" > "$OUT/gen/BuildInfo.java"
javac -encoding UTF-8 --release 8 -nowarn -Xlint:-options -cp "$CP:$OUT/stubs" -d "$OUT/classes" src/*.java "$OUT/gen/BuildInfo.java" data/scripts/plugins/*.java
# the real option-data method is named "new" (not legal Java source): rename the call in the class file
python3 tools/rename_new.py "$OUT/classes/claudecaptain/DialogBridge.class"
# no reflection, no java.io file access: the game's script class loader refuses both
if grep -rl --include=*.class -e "java/lang/reflect" -e "java/io/File" "$OUT/classes" >/dev/null; then
  echo "FORBIDDEN: reflection or java.io file access in:"; grep -rl --include=*.class -e "java/lang/reflect" -e "java/io/File" "$OUT/classes"; exit 1
fi
(cd "$OUT/classes" && jar cf "$OUT/claude_captain.jar" claudecaptain data)
echo "built $OUT/claude_captain.jar"
