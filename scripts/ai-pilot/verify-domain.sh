#!/usr/bin/env bash
set -euo pipefail
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source_root="$(realpath "${1:?domain source root required}")"
tools="${PILOT_KOTLIN_CACHE:?verified Kotlin cache required}"
output="$(mktemp -d "$source_root/.verify-XXXXXX")"
trap 'rm -rf -- "$output"' EXIT

classpath=""
while read -r hash artifact; do
  jar="$tools/${artifact##*/}"
  printf '%s  %s\n' "$hash" "$jar" | sha256sum --check --status
  classpath="${classpath:+$classpath:}$jar"
done < "$script_dir/kotlin.lock"

mapfile -t sources < <(find "$source_root/domain/src" -name '*.kt' -type f | sort)
java -Xmx1024m -cp "$classpath" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect -jvm-target 17 -classpath "$classpath" \
  "-Xplugin=$tools/kotlin-serialization-compiler-plugin-embeddable-2.0.21.jar" \
  -d "$output" "${sources[@]}"
mapfile -t tests < <(find "$output" -name '*Test.class' -printf '%P\n' | sed 's/\.class$//;s@/@.@g' | sort)
test "${#tests[@]}" -gt 0
java -Xmx1024m -cp "$output:$classpath" org.junit.runner.JUnitCore "${tests[@]}"
