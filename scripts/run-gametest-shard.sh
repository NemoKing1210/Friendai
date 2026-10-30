#!/bin/sh
# Runs every GameTest class of one shard, one class per server boot, and reports the classes that failed.
#
#   scripts/run-gametest-shard.sh plan-task
#   scripts/run-gametest-shard.sh --class clear_tests
#   scripts/run-gametest-shard.sh --list
#
# A shard is a list of class names in .github/gametest-shards.json. The GameTest filter selects one class at a time
# (`friendai-gametest:<class>_*`), because classes share one server world and a batch with them: a class that only
# passes with a particular neighbour is a test that cannot be trusted. A failure never stops the loop; the report names
# every failing class and the exit status is non-zero when any class failed or ran no tests, so a class that silently
# stops running turns the shard red.
set -eu

root=$(cd -- "$(dirname -- "$0")/.." && pwd)
cd "$root"

shards_file=".github/gametest-shards.json"
python=$(command -v python3 || command -v python || true)
[ -n "$python" ] || {
    echo "python is required to read $shards_file" >&2
    exit 2
}

if [ "${1:-}" = "--list" ]; then
    "$python" -c 'import json,sys; print("\n".join(s["name"] for s in json.load(open(sys.argv[1]))["shards"]))' "$shards_file"
    exit 0
fi
if [ "${1:-}" = "--class" ]; then
    [ $# -eq 2 ] || {
        sed -n '2,7p' "$0" | sed 's/^# \{0,1\}//' >&2
        exit 2
    }
    name="class-$2"
    classes=$2
else
[ $# -eq 1 ] || {
    sed -n '2,7p' "$0" | sed 's/^# \{0,1\}//' >&2
    exit 2
}

name=$1
classes=$("$python" - "$shards_file" "$name" <<'PY'
import json
import re
import sys

path, shard = sys.argv[1], sys.argv[2]
shards = json.load(open(path))["shards"]
for entry in shards:
    if entry["name"] == shard:
        break
else:
    sys.exit("unknown shard %r; known: %s" % (shard, ", ".join(s["name"] for s in shards)))
print("\n".join(re.sub(r"([a-z0-9])([A-Z])", r"\1_\2", name).lower() for name in entry["classes"]))
PY
) || exit 2
fi

[ -n "$classes" ] || {
    echo "no classes selected for shard '$name'" >&2
    exit 2
}

log_dir="build/gametest-shards"
mkdir -p "$log_dir"
failed=""
empty=""
total=0
failed_tests=0

for class_id in $classes; do
    log="$log_dir/$class_id.log"
    echo "::group::$class_id"
    ./gradlew runGameTest -Dfabric-api.gametest.filter="friendai-gametest:${class_id}_*" \
        --console=plain --stacktrace >"$log" 2>&1 </dev/null || true
    complete=$(grep -aoE '[0-9]+ GAME TESTS COMPLETE' "$log" | head -1 | grep -oE '^[0-9]+' || true)
    count_failed=$(grep -aoE '[0-9]+ required tests failed' "$log" | head -1 | grep -oE '^[0-9]+' || true)
    complete=${complete:-0}
    count_failed=${count_failed:-0}
    total=$((total + complete))
    failed_tests=$((failed_tests + count_failed))
    if [ "$complete" -eq 0 ]; then
        empty="$empty $class_id"
        echo "$class_id: no tests ran; see $log"
    elif [ "$count_failed" -ne 0 ]; then
        failed="$failed $class_id"
        echo "$class_id: $count_failed of $complete failed"
        grep -aE '\(Minecraft\) friendai-gametest:.* failed at' "$log" | sort -u |
            sed -E 's/^.*friendai-gametest:/  /; s/ failed at [-0-9, ]+! / | /' | cut -c1-200 || true
    else
        echo "$class_id: $complete passed"
    fi
    echo "::endgroup::"
done

echo "shard $name: $total tests, $failed_tests failed"
if [ -n "$empty" ]; then
    echo "classes that ran no tests:$empty" >&2
fi
if [ -n "$failed" ]; then
    echo "classes that failed:$failed" >&2
fi
[ -z "$empty" ] && [ -z "$failed" ]
