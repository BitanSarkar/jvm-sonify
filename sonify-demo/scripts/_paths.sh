# Locates build output, wherever it is.
#
# Build output normally lives in each module's target directory. When the working copy sits
# inside a synced folder, .mvn/maven.config moves it outside to keep the sync service from
# writing conflict duplicates into it. These helpers check both, so the scripts work either way
# and nobody has to remember which layout is in use.

SONIFY_EXTERNAL_BUILD="${SONIFY_BUILD_HOME:-$HOME/.sonify-build}"

# sonify_out <module-name> -> the directory holding that module's build output
sonify_out() {
  local module="$1" root="${2:-$SONIFY_ROOT}"
  for candidate in "$root/$module/target" "$SONIFY_EXTERNAL_BUILD/$module"; do
    if [ -d "$candidate" ]; then
      echo "$candidate"
      return 0
    fi
  done
  return 1
}

# sonify_jar <module-name> -> the repackaged jar, or nothing if it has not been built
sonify_jar() {
  local module="$1" out
  out="$(sonify_out "$module")" || return 1
  local jar
  jar="$(ls "$out/$module"-*.jar 2>/dev/null | grep -v '\.original$' | head -1)"
  [ -n "$jar" ] && echo "$jar"
}

# sonify_classes <module-name> -> the compiled classes directory
sonify_classes() {
  local out
  out="$(sonify_out "$1")" || return 1
  echo "$out/classes"
}

# Prints the commands to start everything, with paths that actually exist on this machine.
sonify_start_hints() {
  local scenarios demo reactive
  scenarios="$(sonify_classes sonify-scenarios 2>/dev/null || echo 'sonify-scenarios/target/classes')"
  demo="$(sonify_jar sonify-demo 2>/dev/null || echo 'sonify-demo/target/sonify-demo-0.1.0-SNAPSHOT.jar')"
  reactive="$(sonify_jar sonify-demo-reactive 2>/dev/null || echo 'sonify-demo-reactive/target/sonify-demo-reactive-0.1.0-SNAPSHOT.jar')"
  echo "  upstream: java -cp $scenarios net.bitsar.sonify.scenarios.upstream.UpstreamServer 8090"
  echo "  demo    : java -Xmx512m -jar $demo"
  echo "  reactive: java -Xmx512m -jar $reactive"
}
