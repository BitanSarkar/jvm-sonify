"""Formatting helpers for scenarios.sh.

Kept as a file rather than inlined with python3 -c, because the shell quoting needed to embed
Python containing both kinds of quote is a reliable source of breakage.

Usage: _format.py <list|outcome|describe> [scenario-id]   with JSON on stdin.
"""
import json
import sys


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else "list"
    payload = json.load(sys.stdin)

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
        for row in payload:
            if row["transport"] != "REACTIVE":
                print(row["id"])

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
        print("    p50 {}ms  p95 {}ms  p99 {}ms  max {}ms".format(
            payload["p50Ms"], payload["p95Ms"], payload["p99Ms"], payload["maxMs"]))
        print("    " + payload["note"])


if __name__ == "__main__":
    main()
