"""Formatting helpers for scenarios.sh.

Kept as a file rather than inlined with python3 -c, because the shell quoting needed to embed
Python containing both kinds of quote is a reliable source of breakage.

Usage: _format.py <list|outcome|describe> [scenario-id]   with JSON on stdin.
"""
import json
import sys


def micros(value):
    """Microseconds in whichever unit reads naturally, so nothing rounds away to zero."""
    if value < 1000:
        return "{}us".format(value)
    if value < 100000:
        return "{:.1f}ms".format(value / 1000.0)
    return "{:.2f}s".format(value / 1000000.0)


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else "list"
    raw = sys.stdin.read().strip()
    if not raw:
        print("    (no response: the application did not answer. It may still be saturated by")
        print("     the previous scenario; see 'scenarios.sh ready'.)")
        return
    try:
        payload = json.loads(raw)
    except ValueError:
        print("    (unexpected response, not JSON):")
        print("    " + raw[:300])
        return

    if mode == "list":
        category = None
        for row in payload:
            if row["category"] != category:
                category = row["category"]
                print("\n  " + category)
            tag = "" if row["transport"] == "ANY" else "  [{} only]".format(row["transport"].lower())
            print("    {:<26} {}{}".format(row["id"], row["name"], tag))
        print()

    elif mode == "ids":
        # api-no-timeout holds request threads until the upstream gives up, so anything after
        # it would be measuring a saturated server rather than itself. It goes last.
        last = {"api-no-timeout"}
        runnable = [row["id"] for row in payload if row["transport"] != "REACTIVE"]
        for scenario_id in [i for i in runnable if i not in last]:
            print(scenario_id)
        for scenario_id in [i for i in runnable if i in last]:
            print(scenario_id)

    elif mode == "describe":
        wanted = sys.argv[2]
        for row in payload:
            if row["id"] == wanted:
                print()
                print("  " + row["name"])
                print("  situation : " + row["situation"])
                print("  graph     : " + row["expectedGraph"])
                print("  sound     : " + row["expectedSound"])

    elif mode == "outcome":
        print("    {} ok, {} failed, {:.1f}/s".format(
            payload["completed"], payload["failed"], payload["throughputPerSecond"]))
        print("    p50 {}  p95 {}  p99 {}  max {}".format(
            micros(payload["p50Micros"]), micros(payload["p95Micros"]),
            micros(payload["p99Micros"]), micros(payload["maxMicros"])))
        print("    " + payload["note"])


if __name__ == "__main__":
    main()
