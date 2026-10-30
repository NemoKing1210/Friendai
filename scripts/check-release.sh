#!/bin/sh
# Checks one release candidate before it is published, and prints its SHA-256.
#
#   scripts/check-release.sh [tag] [jar]
#
# tag defaults to $GITHUB_REF_NAME (in Actions) or v<mod_version>; jar defaults to
# build/libs/friendai-<mod_version>.jar. The checks: the version is MAJOR.MINOR.PATCH, the tag is v<version>, the jar
# exists and is named after the version, the fabric.mod.json inside it carries the same version, and a sidecar
# .sha256 file, when present, matches the jar. Everything this script needs is in the checkout, so a maintainer can run
# it locally before tagging and the release workflow runs the same file.
set -eu

root=$(cd -- "$(dirname -- "$0")/.." && pwd)
cd "$root"

fail() {
    echo "check-release: $*" >&2
    exit 1
}

version=$(sed -n 's/^mod_version=//p' gradle.properties)
[ -n "$version" ] || fail "gradle.properties has no mod_version"

pattern='(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-[0-9A-Za-z.-]+)?'
printf '%s\n' "$version" | grep -Eq "^${pattern}\$" || fail "mod_version '$version' is not MAJOR.MINOR.PATCH"

tag=${1:-${GITHUB_REF_NAME:-v$version}}
jar=${2:-build/libs/friendai-$version.jar}

printf '%s\n' "$tag" | grep -Eq "^v${pattern}\$" || fail "tag '$tag' is not vMAJOR.MINOR.PATCH"
[ "v$version" = "$tag" ] || fail "tag $tag does not match mod_version=$version"

[ -f "$jar" ] || fail "missing jar $jar (run ./gradlew assemble first)"
[ -s "$jar" ] || fail "empty jar $jar"
[ "$(basename "$jar")" = "friendai-$version.jar" ] || fail "jar $(basename "$jar") is not friendai-$version.jar"

command -v unzip >/dev/null 2>&1 || fail "unzip is required to read fabric.mod.json"
mod_json=$(unzip -p "$jar" fabric.mod.json 2>/dev/null) || fail "$jar has no fabric.mod.json"
jar_version=$(printf '%s\n' "$mod_json" | sed -n 's/.*"version"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -n 1)
[ -n "$jar_version" ] || fail "$jar has a fabric.mod.json without a version"
[ "$jar_version" = "$version" ] || fail "fabric.mod.json version '$jar_version' does not match mod_version=$version"

if command -v sha256sum >/dev/null 2>&1; then
    checksum=$(sha256sum "$jar" | cut -d' ' -f1)
else
    checksum=$(shasum -a 256 "$jar" | cut -d' ' -f1)
fi
[ -n "$checksum" ] || fail "could not compute the SHA-256 of $jar"

if [ -f "$jar.sha256" ]; then
    recorded=$(cut -d' ' -f1 <"$jar.sha256")
    [ "$recorded" = "$checksum" ] || fail "$jar.sha256 does not match $jar"
fi

echo "check-release: $tag -> $(basename "$jar") (fabric.mod.json $jar_version)"
echo "sha256=$checksum"
