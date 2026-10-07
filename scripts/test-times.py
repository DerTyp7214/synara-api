#!/usr/bin/env python3
import argparse
import json
import re
import statistics
import sys
import xml.etree.ElementTree as ET
from collections import Counter, defaultdict
from pathlib import Path

DATABASE_SUFFIX = re.compile(r"^(?:(?P<test>.+) )?\[\d+\] (?P<database>POSTGRES|SQLITE)$", re.DOTALL)
DATABASE_TYPES = ("POSTGRES", "SQLITE", "other")
CHANGE_THRESHOLD_SECONDS = 1.0

USAGE = """Reads the JUnit XML reports under <root>/**/build/test-results/**/TEST-*.xml and prints
totals per module, times per database type, the slowest classes and test cases.
Cases whose name ends in "[n] POSTGRES" or "[n] SQLITE" are database parameterized, with
the test name in front ("test name [n] POSTGRES") or without it ("[n] POSTGRES", reports
written before the test name was part of the display name). A POSTGRES case is paired
with the SQLITE case of the same test for the excess, by occurrence order within the
class where the name carries no test.
--json writes all data, --compare reads such a file and prints before / after / saved
for the classes both sides have a report for."""


def parse_arguments():
    default_root = Path(__file__).resolve().parent.parent
    parser = argparse.ArgumentParser(description=USAGE, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--root", type=Path, default=default_root, help="repository root (default: derived from the script location)")
    parser.add_argument("--top", type=int, default=25, help="rows in the slowest tables (default 25)")
    parser.add_argument("--json", type=Path, dest="json_output", help="write the full data to this file")
    parser.add_argument("--compare", type=Path, help="baseline JSON written by --json")
    return parser.parse_args()


def module_of(report, root):
    relative = report.relative_to(root).parts
    return "/".join(relative[:relative.index("build")])


def database_type(name):
    match = DATABASE_SUFFIX.match(name)
    return match.group("database") if match else "other"


def test_of(name):
    match = DATABASE_SUFFIX.match(name)
    return match.group("test") if match else None


def read_reports(root):
    classes = {}
    for report in sorted(root.glob("**/build/test-results/**/TEST-*.xml")):
        suite = ET.parse(report).getroot()
        class_name = suite.get("name")
        module = module_of(report, root)
        cases = []
        occurrences = Counter()
        for case in suite.iter("testcase"):
            name = case.get("name")
            occurrences[name] += 1
            outcome = "passed"
            if case.find("skipped") is not None:
                outcome = "skipped"
            elif case.find("failure") is not None:
                outcome = "failure"
            elif case.find("error") is not None:
                outcome = "error"
            cases.append({
                "name": name,
                "occurrence": occurrences[name],
                "database": database_type(name),
                "test": test_of(name),
                "time": float(case.get("time", 0)),
                "outcome": outcome,
            })
        classes[f"{module}::{class_name}"] = {
            "module": module,
            "class": class_name,
            "timestamp": suite.get("timestamp"),
            "time": float(suite.get("time", 0)),
            "cases": cases,
        }
    return classes


def summarize_class(entry):
    summary = {database: {"count": 0, "time": 0.0} for database in DATABASE_TYPES}
    for case in entry["cases"]:
        database = database_type(case["name"])
        summary[database]["count"] += 1
        summary[database]["time"] += case["time"]
    return summary


def paired_excess(entry):
    seen = Counter()
    times = {"POSTGRES": {}, "SQLITE": {}}
    for case in entry["cases"]:
        database = database_type(case["name"])
        if database == "other":
            continue
        test = test_of(case["name"])
        seen[(database, test)] += 1
        times[database][(test, seen[(database, test)])] = case["time"]
    return sum(time - times["SQLITE"][key] for key, time in times["POSTGRES"].items() if key in times["SQLITE"])


def totals(classes):
    result = {"classes": 0, "tests": 0, "skipped": 0, "failures": 0, "errors": 0, "time": 0.0}
    for entry in classes:
        result["classes"] += 1
        result["tests"] += len(entry["cases"])
        result["time"] += entry["time"]
        for case in entry["cases"]:
            if case["outcome"] == "skipped":
                result["skipped"] += 1
            elif case["outcome"] == "failure":
                result["failures"] += 1
            elif case["outcome"] == "error":
                result["errors"] += 1
    return result


def database_stats(classes):
    times = defaultdict(list)
    excess = 0.0
    for entry in classes:
        for case in entry["cases"]:
            times[database_type(case["name"])].append(case["time"])
        excess += paired_excess(entry)
    stats = {}
    for database in DATABASE_TYPES:
        values = times[database]
        stats[database] = {
            "count": len(values),
            "time": sum(values),
            "median": statistics.median(values) if values else 0.0,
        }
    stats["excess_postgres_over_sqlite"] = excess
    return stats


def build_data(classes):
    entries = list(classes.values())
    by_module = defaultdict(list)
    for entry in entries:
        by_module[entry["module"]].append(entry)
    timestamps = sorted(entry["timestamp"] for entry in entries if entry["timestamp"])
    return {
        "totals": totals(entries),
        "modules": {module: totals(items) for module, items in sorted(by_module.items())},
        "database_types": {module: database_stats(items) for module, items in sorted(by_module.items())},
        "timestamp_first": timestamps[0] if timestamps else None,
        "timestamp_last": timestamps[-1] if timestamps else None,
        "classes": classes,
    }


def table(headers, rows, align_left=1):
    widths = [max(len(str(row[i])) for row in [headers] + rows) for i in range(len(headers))]
    lines = []
    for row in [headers, ["-" * w for w in widths]] + rows:
        cells = []
        for index, value in enumerate(row):
            text = str(value)
            cells.append(text.ljust(widths[index]) if index < align_left else text.rjust(widths[index]))
        lines.append("  ".join(cells))
    return "\n".join(lines)


def seconds(value):
    return f"{value:.1f}"


def signed(value):
    return f"{value:+.1f}"


def print_totals(data):
    print(f"Reports: {data['totals']['classes']} classes, {data['timestamp_first']} .. {data['timestamp_last']}")
    print()
    print("Totals per module")
    rows = [[module, t["classes"], t["tests"], t["skipped"], t["failures"], t["errors"], seconds(t["time"])]
            for module, t in data["modules"].items()]
    t = data["totals"]
    rows.append(["ALL", t["classes"], t["tests"], t["skipped"], t["failures"], t["errors"], seconds(t["time"])])
    print(table(["module", "classes", "tests", "skipped", "failures", "errors", "time s"], rows))
    print()


def print_database_types(data, module):
    stats = data["database_types"].get(module)
    if stats is None:
        return
    print(f"Database types in {module}")
    rows = [[database, stats[database]["count"], seconds(stats[database]["time"]), f"{stats[database]['median']:.3f}"]
            for database in DATABASE_TYPES]
    print(table(["type", "cases", "time s", "median s"], rows))
    print(f"excess of POSTGRES over its SQLITE sibling: {seconds(stats['excess_postgres_over_sqlite'])} s")
    print()


def print_slowest_classes(classes, top):
    print(f"Slowest {top} classes")
    rows = []
    for entry in sorted(classes.values(), key=lambda e: -e["time"])[:top]:
        summary = summarize_class(entry)
        rows.append([
            entry["class"], seconds(entry["time"]), len(entry["cases"]),
            f"{summary['POSTGRES']['count']} / {seconds(summary['POSTGRES']['time'])}",
            f"{summary['SQLITE']['count']} / {seconds(summary['SQLITE']['time'])}",
            f"{summary['other']['count']} / {seconds(summary['other']['time'])}",
        ])
    print(table(["class", "time s", "tests", "PG n / s", "SQLite n / s", "other n / s"], rows))
    print()


def print_slowest_cases(classes, top):
    print(f"Slowest {top} test cases")
    flat = [(case["time"], entry["class"], case["name"], case["occurrence"])
            for entry in classes.values() for case in entry["cases"]]
    rows = [[cls, name, occurrence, f"{time:.3f}"] for time, cls, name, occurrence in sorted(flat, key=lambda f: -f[0])[:top]]
    print(table(["class", "name", "#", "time s"], rows, align_left=2))
    print()


def case_counts(classes):
    counts = Counter()
    for entry in classes.values():
        for case in entry["cases"]:
            counts[(entry["module"], entry["class"], case["name"])] += 1
    return counts


def names_carry_test(classes):
    return any(test_of(case["name"]) for entry in classes.values() for case in entry["cases"])


def has_database_cases(classes):
    return any(database_type(case["name"]) != "other" for entry in classes.values() for case in entry["cases"])


def print_comparison(before, after):
    common = sorted(set(before["classes"]) & set(after["classes"]))
    only_before = len(before["classes"]) - len(common)
    only_after = len(after["classes"]) - len(common)
    before_classes = {key: before["classes"][key] for key in common}
    after_classes = {key: after["classes"][key] for key in common}
    before, after = build_data(before_classes), build_data(after_classes)

    print("Comparison before / after / saved")
    print(f"{len(common)} classes with a report on both sides, {only_before} only in the baseline and "
          f"{only_after} only in this run are left out")
    rows = []
    t_before, t_after = before["totals"], after["totals"]
    rows.append(["total time s", seconds(t_before["time"]), seconds(t_after["time"]), signed(t_before["time"] - t_after["time"])])
    rows.append(["total tests", t_before["tests"], t_after["tests"], t_before["tests"] - t_after["tests"]])
    for module in sorted(set(before["modules"]) | set(after["modules"])):
        b = before["modules"].get(module, {"time": 0.0})["time"]
        a = after["modules"].get(module, {"time": 0.0})["time"]
        rows.append([f"module {module} s", seconds(b), seconds(a), signed(b - a)])
    for module in sorted(set(before["database_types"]) & set(after["database_types"])):
        b_stats = before["database_types"][module]
        a_stats = after["database_types"][module]
        for database in DATABASE_TYPES:
            if not b_stats[database]["count"] and not a_stats[database]["count"]:
                continue
            b = b_stats[database]["time"]
            a = a_stats[database]["time"]
            rows.append([f"{module} {database} s", seconds(b), seconds(a), signed(b - a)])
            rows.append([f"{module} {database} median s", f"{b_stats[database]['median']:.3f}",
                         f"{a_stats[database]['median']:.3f}",
                         f"{b_stats[database]['median'] - a_stats[database]['median']:+.3f}"])
            rows.append([f"{module} {database} cases", b_stats[database]["count"], a_stats[database]["count"],
                         b_stats[database]["count"] - a_stats[database]["count"]])
    print(table(["metric", "before", "after", "saved"], rows))
    print()

    print(f"Classes with a time change above {CHANGE_THRESHOLD_SECONDS:.0f} s, sorted by saving (before / after per database type)")
    changes = []
    for key in common:
        b_entry, a_entry = before_classes[key], after_classes[key]
        if abs(b_entry["time"] - a_entry["time"]) > CHANGE_THRESHOLD_SECONDS:
            changes.append((b_entry["time"] - a_entry["time"], key, b_entry, a_entry))
    rows = []
    for saved, key, b_entry, a_entry in sorted(changes, key=lambda change: -change[0]):
        b_summary, a_summary = summarize_class(b_entry), summarize_class(a_entry)
        rows.append([key, seconds(b_entry["time"]), seconds(a_entry["time"]), signed(saved)] + [
            f"{seconds(b_summary[database]['time'])} / {seconds(a_summary[database]['time'])}" for database in DATABASE_TYPES
        ])
    headers = ["class", "before", "after", "saved", "PG s", "SQLite s", "other s"]
    print(table(headers, rows) if rows else "none")
    print()

    if has_database_cases(before_classes) and names_carry_test(before_classes) != names_carry_test(after_classes):
        print("Test case names are not compared: one side names database cases with the test name, the other without.")
        return
    before_counts, after_counts = case_counts(before_classes), case_counts(after_classes)
    added = after_counts - before_counts
    removed = before_counts - after_counts
    print(f"Added test cases (class, name, count): {sum(added.values())}")
    for (module, cls, name), count in sorted(added.items()):
        print(f"  + {cls} :: {name} x{count}")
    print(f"Removed test cases (class, name, count): {sum(removed.values())}")
    for (module, cls, name), count in sorted(removed.items()):
        print(f"  - {cls} :: {name} x{count}")


def main():
    arguments = parse_arguments()
    root = arguments.root.resolve()
    classes = read_reports(root)
    if not classes:
        print(f"no reports found under {root}", file=sys.stderr)
        return 1
    data = build_data(classes)
    print_totals(data)
    for module in data["database_types"]:
        if module == "server" or len(data["database_types"]) == 1:
            print_database_types(data, module)
    print_slowest_classes(classes, arguments.top)
    print_slowest_cases(classes, arguments.top)
    if arguments.compare:
        baseline = json.loads(arguments.compare.read_text())
        print_comparison(baseline, data)
    if arguments.json_output:
        arguments.json_output.write_text(json.dumps(data, indent=1))
    return 0


if __name__ == "__main__":
    sys.exit(main())
