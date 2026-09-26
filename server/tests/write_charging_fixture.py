"""v0.18.0: writes server/tests/fixtures/charging_v3.json - THE charging rule, as cases.

    cd server && python -m tests.write_charging_fixture

Two phones and the Brain each compute what a checkpoint costs. If any of the three
drifts, a signed checkpoint one side issues is "wrong" on another and the session cannot
settle - and no green suite on either side would notice, because each is consistent with
itself. So Python writes the expected cost for a spread of cases and BOTH suites read
the same file: test_evidence.ChargingFixtureTest here, ChargingFixtureTest.kt on the
phone. Regenerate only when the rule itself is meant to change, and say so in the report.
"""
import json
import os

from brain import evidence

MB = evidence.MB
CASES = [
    # (label, version, rate centimes/MB, budget centimes, ceiling bytes, up, down)
    ("v2 plain 3 MB", 2, 300, 5_000, 0, 1 * MB, 2 * MB),
    ("v2 up only still charged", 2, 300, 5_000, 0, 2 * MB, 0),
    ("v3 up only is zero", 3, 300, 5_000, 0, 2 * MB, 0),
    ("v3 one byte down charges everything", 3, 300, 5_000, 0, 2 * MB, 1),
    ("v3 plain 3 MB", 3, 300, 5_000, 0, 1 * MB, 2 * MB),
    ("v3 rounds half up", 3, 300, 5_000, 0, 0, MB // 2),
    ("v3 below half rounds down", 3, 300, 5_000, 0, 0, MB // 2 - 1),
    ("v3 clamps to budget", 3, 300, 5_000, 0, 10 * MB, 10 * MB),
    ("v3 clamps to ceiling then budget", 3, 300, 5_000, 4 * MB, 3 * MB, 3 * MB),
    ("v3 free session", 3, 0, 0, 0, 5 * MB, 5 * MB),
    ("v3 nothing at all", 3, 300, 5_000, 0, 0, 0),
    ("v3 large exact", 3, 1_234, 100_000_000, 0, 123_456_789, 987_654_321),
]


def contract(version, rate, budget, ceiling):
    c = evidence.Contract.__new__(evidence.Contract)
    c.version = version
    c.rate_centimes_per_mb = rate
    c.buyer_budget_centimes = budget
    c.max_billable_bytes = ceiling
    return c


def cases():
    out = []
    for label, version, rate, budget, ceiling, up, down in CASES:
        c = contract(version, rate, budget, ceiling)
        out.append({"label": label, "version": version, "rate": rate, "budget": budget, "ceiling": ceiling,
                    "up": up, "down": down, "cost": c.cost_for_usage(up, down)})
    return out


def main():
    path = os.path.join(os.path.dirname(__file__), "fixtures", "charging_v3.json")
    data = {"note": "written by server/tests/write_charging_fixture.py; read by test_evidence.py and ChargingFixtureTest.kt",
            "mb": MB, "cases": cases()}
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(data, f, indent=1, sort_keys=True)
        f.write("\n")
    print("wrote %d cases to %s" % (len(data["cases"]), path))


if __name__ == "__main__":
    main()
