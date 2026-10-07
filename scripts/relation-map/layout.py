import json
import math
import shutil
import subprocess
import sys
from collections import defaultdict

CHAR = 6.75
SUB_CHAR = 5.6
ROW_CHAR = 5.75
ROW_HEIGHT = 13
ROW_TOP = 24
PAD = 20
BLOCK_MARGIN = 16
BLOCK_TITLE = 20
BLOCK_GAP = 34
REQUIRED_TOOLS = ("dot", "unflatten")


def check_tools():
    missing = [t for t in REQUIRED_TOOLS if shutil.which(t) is None]
    if missing:
        sys.exit("Graphviz is required, not found on PATH: %s" % ", ".join(missing))


def quote(s):
    return '"' + str(s).replace('"', '\\"') + '"'


def row_text(row):
    return row[0] + ((" > " + row[1]) if row[1] else "") + (("  " + row[2]) if row[2] else "")


def node_size(n):
    w = len(n["label"]) * CHAR + PAD
    h = 26
    if n.get("sub"):
        w = max(w, len(n["sub"]) * SUB_CHAR + PAD)
        h = 38
    rows = n.get("rows")
    if rows:
        for row in rows:
            w = max(w, len(row_text(row)) * ROW_CHAR + PAD)
        h = ROW_TOP + len(rows) * ROW_HEIGHT + 5
    return round(w, 1), h


def run_dot(nodes, edges, direction="LR", attrs="", cluster_labels=None, labels=False, unflatten=None, extra=()):
    lines = ["digraph G {", "rankdir=%s; %s" % (direction, attrs), 'node [shape=box fixedsize=true fontname="monospace" fontsize=11];', 'edge [arrowsize=0.6 fontsize=9 fontname="monospace"];']
    grouped = defaultdict(list)
    for n in nodes:
        grouped[n["group"] if cluster_labels is not None else None].append(n)
    order = list(cluster_labels) if cluster_labels is not None else [None]
    for g in order:
        members = grouped.get(g, [])
        if not members:
            continue
        if g is not None:
            lines.append("subgraph %s { label=%s; margin=14;" % (quote("cluster_" + g), quote(cluster_labels[g])))
        for n in members:
            w, h = node_size(n)
            lines.append('%s [width=%.3f height=%.3f label=""];' % (quote(n["id"]), w / 72, h / 72))
        if g is not None:
            lines.append("}")
    for i, e in enumerate(edges):
        parts = ["id=%s" % quote("e%d" % i)]
        if labels and e.get("label"):
            parts.append("label=%s" % quote(e["label"]))
        if e.get("weak"):
            parts.append("constraint=false")
        lines.append("%s -> %s [%s];" % (quote(e["from"]), quote(e["to"]), " ".join(parts)))
    dot = "\n".join(lines)
    if unflatten:
        dot = subprocess.run(["unflatten"] + unflatten, input=(dot + "\n}").encode(), capture_output=True, check=True).stdout.decode()
        dot = dot[:dot.rindex("}")]
    for e in extra:
        dot += "\n%s -> %s [style=invis];" % (quote(e["from"]), quote(e["to"]))
    dot += "\n}"
    result = subprocess.run(["dot", "-Tjson"], input=dot.encode(), capture_output=True)
    if result.returncode != 0:
        sys.exit("dot failed: %s" % result.stderr.decode()[:400])
    graph = json.loads(result.stdout)
    bb = [float(x) for x in graph["bb"].split(",")]
    height = bb[3]
    positions = {}
    clusters = []
    for o in graph.get("objects", []):
        name = o["name"]
        if name.startswith("cluster_"):
            b = [float(x) for x in o["bb"].split(",")]
            clusters.append({"group": name[8:], "x": b[0], "y": height - b[3], "w": b[2] - b[0], "h": b[3] - b[1]})
        elif "pos" in o:
            x, y = [float(v) for v in o["pos"].split(",")]
            positions[name] = (x, height - y)
    routes = {}
    for e in graph.get("edges", []):
        if "id" not in e:
            continue
        end = None
        points = []
        for p in e["pos"].split(" "):
            if p.startswith("e,"):
                end = p[2:]
            elif not p.startswith("s,"):
                points.append(p)

        def xy(s):
            x, y = s.split(",")
            return (float(x), height - float(y))

        points = [xy(p) for p in points]
        label_position = None
        if "lp" in e:
            label_position = xy(e["lp"])
        routes[e["id"]] = {"pts": points, "end": xy(end) if end else None, "lp": label_position or points[len(points) // 2]}
    return {"w": bb[2], "h": bb[3], "pos": positions, "clusters": clusters, "routes": routes}


def path_d(route, dx=0.0, dy=0.0):
    points = route["pts"]

    def fmt(p):
        return "%.1f %.1f" % (p[0] + dx, p[1] + dy)

    d = "M" + fmt(points[0]) + ("C" if (len(points) - 1) % 3 == 0 else "L") + " ".join(fmt(p) for p in points[1:])
    if route["end"]:
        d += "L" + fmt(route["end"])
    return d


def place(nodes, result, dx=0.0, dy=0.0):
    placed = {}
    for n in nodes:
        x, y = result["pos"][n["id"]]
        w, h = node_size(n)
        m = dict(n)
        m.update({"x": round(x + dx, 1), "y": round(y + dy, 1), "w": w, "h": h})
        placed[n["id"]] = m
    return placed


def clean_edge(e):
    return {k: v for k, v in e.items() if k not in ("weak", "row")}


def routed_edges(edges, result, dx=0.0, dy=0.0):
    out = []
    for i, e in enumerate(edges):
        route = result["routes"]["e%d" % i]
        m = clean_edge(e)
        m["d"] = path_d(route, dx, dy)
        m["lp"] = [round(route["lp"][0] + dx, 1), round(route["lp"][1] + dy, 1)]
        out.append(m)
    return out


def cluster_boxes(result, labels, dx=0.0, dy=0.0):
    return [{"group": c["group"], "label": labels[c["group"]], "x": round(c["x"] + dx, 1), "y": round(c["y"] + dy, 1), "w": round(c["w"], 1), "h": round(c["h"], 1)} for c in result["clusters"]]


FLOW_SEPARATION = {"TB": "nodesep=0.22; ranksep=0.75;", "LR": "nodesep=0.16; ranksep=0.9;"}
WRAP_LIMIT = 9


def wrapped_dot(nodes, edges, direction, cluster_labels, labels):
    unflatten = ["-l", "4", "-f", "-c", "4"]
    first = run_dot(nodes, edges, direction=direction, attrs=FLOW_SEPARATION[direction], cluster_labels=cluster_labels, labels=labels, unflatten=unflatten)
    axis, cross = (0, 1) if direction == "LR" else (1, 0)
    stacks = defaultdict(list)
    for n in nodes:
        position = first["pos"][n["id"]]
        stacks[(n["group"], round(position[axis]))].append((position[cross], n["id"]))
    chains = []
    for key in sorted(stacks):
        stack = sorted(stacks[key])
        if len(stack) <= WRAP_LIMIT:
            continue
        cols = math.ceil(len(stack) / WRAP_LIMIT)
        for k in range(len(stack) - 1):
            if k % cols != cols - 1:
                chains.append({"from": stack[k][1], "to": stack[k + 1][1], "hidden": 1})
    if not chains:
        return first
    return run_dot(nodes, edges, direction=direction, attrs=FLOW_SEPARATION[direction], cluster_labels=cluster_labels, labels=labels, unflatten=unflatten, extra=chains)


def flow_layout(nodes, edges, groups, direction, labels):
    cluster_labels = {g["id"]: g["label"] for g in groups}
    result = wrapped_dot(nodes, edges, direction, cluster_labels, labels)
    if not 0.8 <= result["w"] / result["h"] <= 2.6:
        second = wrapped_dot(nodes, edges, "TB" if direction == "LR" else "LR", cluster_labels, labels)
        if abs(math.log(second["w"] / second["h"] / 1.6)) < abs(math.log(result["w"] / result["h"] / 1.6)):
            result = second
    placed = place(nodes, result)
    return {"w": round(result["w"], 1), "h": round(result["h"], 1), "nodes": [placed[n["id"]] for n in nodes], "edges": routed_edges(edges, result), "clusters": cluster_boxes(result, cluster_labels)}


def spread(count, height):
    if count <= 1:
        return [0.0] * count
    span = min(height - 10, (count - 1) * 5.0)
    return [-span / 2 + span * i / (count - 1) for i in range(count)]


def rounded(points, radius=5.0):
    points = [p for i, p in enumerate(points) if i == 0 or p != points[i - 1]]
    d = "M%.1f %.1f" % points[0]
    for i in range(1, len(points) - 1):
        p0, p1, p2 = points[i - 1], points[i], points[i + 1]
        r1 = min(radius, math.dist(p0, p1) / 2)
        r2 = min(radius, math.dist(p1, p2) / 2)
        if r1 <= 0 or r2 <= 0:
            d += "L%.1f %.1f" % p1
            continue
        a = (p1[0] + (p0[0] - p1[0]) * r1 / math.dist(p0, p1), p1[1] + (p0[1] - p1[1]) * r1 / math.dist(p0, p1))
        b = (p1[0] + (p2[0] - p1[0]) * r2 / math.dist(p1, p2), p1[1] + (p2[1] - p1[1]) * r2 / math.dist(p1, p2))
        d += "L%.1f %.1fQ%.1f %.1f %.1f %.1f" % (a[0], a[1], p1[0], p1[1], b[0], b[1])
    d += "L%.1f %.1f" % points[-1]
    return d, points


def row_port(center, height, row):
    return center - height / 2 + ROW_TOP + (row + 0.5) * ROW_HEIGHT - 3


def gutter_route(nodes, edges, lane, y, size, group_of, lanes_total, bundle=False, pad=0.0, gutter_min=54.0, outer_min=24.0, clear=17.0):
    lane_step = 4.5
    lane_nodes = defaultdict(list)
    for n in nodes:
        lane_nodes[lane[n["id"]]].append(n["id"])
    rank = {}
    for ids in lane_nodes.values():
        for position, i in enumerate(sorted(ids, key=lambda i: y[i])):
            rank[i] = position
    pairs = {(e["from"], e["to"]) for e in edges}
    sides = defaultdict(list)
    plans = []
    bundles = {}
    for index, e in enumerate(edges):
        a, b = e["from"], e["to"]
        i, j = lane[a], lane[b]
        row = e.get("row")
        if row is None and i == j and group_of[a] == group_of[b] and abs(rank[a] - rank[b]) == 1:
            plans.append({"straight": 1})
            continue
        if i < j:
            plan = {"sa": 1, "sb": -1, "gs": i + 1, "gt": j, "between": range(i + 1, j)}
        elif i > j:
            plan = {"sa": -1, "sb": 1, "gs": i, "gt": j + 1, "between": range(j + 1, i)}
        else:
            plan = {"sa": 1, "sb": 1, "gs": i + 1, "gt": i + 1, "between": range(0)}
        plan["key"] = (b, plan["sb"], e.get("kind") or "") if bundle else ("", index, "")
        plans.append(plan)
        if row is None:
            sides[(a, plan["sa"])].append((y[b], lane[b], index, "s"))
        else:
            plan["ys"] = row_port(y[a], size[a][1], row)
        if bundle:
            bundles.setdefault(plan["key"], []).append(index)
        else:
            sides[(b, plan["sb"])].append((y[a], lane[a], index, "t"))
    heads = {}
    for key, indices in bundles.items():
        heads[indices[0]] = indices
        sides[(key[0], key[1])].append((sum(y[edges[i]["from"]] for i in indices) / len(indices), 0, indices[0], "t"))
    for (node, side), items in sorted(sides.items()):
        items.sort()
        for (_, _, index, end), delta in zip(items, spread(len(items), size[node][1])):
            if end == "t" and index in heads:
                for member in heads[index]:
                    plans[member]["yt"] = y[node] + delta
            else:
                plans[index]["y" + end] = y[node] + delta

    channels = []

    def channel(plan):
        blocked = sorted((y[i] - size[i][1] / 2 - 5, y[i] + size[i][1] / 2 + 5) for k in plan["between"] for i in lane_nodes[k])
        merged = []
        for low, high in blocked:
            if merged and low <= merged[-1][1]:
                merged[-1][1] = max(merged[-1][1], high)
            else:
                merged.append([low, high])

        def free(value):
            return not any(low < value < high for low, high in merged)

        middle = (plan["ys"] + plan["yt"]) / 2
        base = next((c for c in (plan["yt"], plan["ys"], middle) if free(c)), None)
        if base is None:
            low, high = next((low, high) for low, high in merged if low < middle < high)
            base = low - 1 if middle - low <= high - middle else high + 1
        span = (min(plan["gs"], plan["gt"]), max(plan["gs"], plan["gt"]))

        def taken(value):
            return any(key != plan["key"] and abs(value - cy) < 4 and lo <= span[1] and span[0] <= hi for cy, lo, hi, key in channels)

        chosen = base
        for step in range(60):
            found = [c for c in ((base + step * lane_step, base - step * lane_step) if step else (base,)) if free(c) and not taken(c)]
            if found:
                chosen = found[0]
                break
        channels.append((chosen, span[0], span[1], plan["key"]))
        return chosen

    segments = defaultdict(dict)

    def add(gutter, key, low, high, index, field):
        entry = segments[gutter].setdefault(key, [low, high, []])
        entry[0] = min(entry[0], low)
        entry[1] = max(entry[1], high)
        entry[2].append((index, field))

    for index, plan in enumerate(plans):
        if plan.get("straight"):
            continue
        if plan["gs"] == plan["gt"]:
            plan["yc"] = plan["ys"]
            if plan["ys"] != plan["yt"]:
                add(plan["gs"], ("t",) + plan["key"], min(plan["ys"], plan["yt"]), max(plan["ys"], plan["yt"]), index, "ls")
        else:
            plan["yc"] = channel(plan)
            if plan["ys"] != plan["yc"]:
                add(plan["gs"], ("s", index), min(plan["ys"], plan["yc"]), max(plan["ys"], plan["yc"]), index, "ls")
            if plan["yc"] != plan["yt"]:
                add(plan["gt"], ("t",) + plan["key"], min(plan["yc"], plan["yt"]), max(plan["yc"], plan["yt"]), index, "lt")
    gutter_lanes = {}
    for gutter in range(lanes_total + 1):
        ends = []
        for low, high, items in sorted(segments[gutter].values(), key=lambda s: (s[0], s[1], s[2][0][0])):
            for k, end in enumerate(ends):
                if end + 7 < low:
                    ends[k] = high
                    slot = k
                    break
            else:
                ends.append(high)
                slot = len(ends) - 1
            for index, field in items:
                plans[index][field] = slot
        gutter_lanes[gutter] = len(ends)

    lane_width = {k: max(size[i][0] for i in ids) for k, ids in lane_nodes.items()}
    gutter_x = {}
    lane_x = {}
    x = 0.0
    for k in range(lanes_total + 1):
        outer = k == 0 or k == lanes_total
        width = max(outer_min if outer and not gutter_lanes[k] else gutter_min, gutter_lanes[k] * lane_step + 2 * clear) + 2 * pad
        gutter_x[k] = (x, width)
        x += width
        if k < lanes_total:
            lane_x[k] = x + lane_width.get(k, 0) / 2
            x += lane_width.get(k, 0)
    total_width = x

    def track(gutter, slot):
        left, width = gutter_x[gutter]
        return left + width / 2 + (slot - (gutter_lanes[gutter] - 1) / 2) * lane_step

    placed = {}
    for n in nodes:
        m = dict(n)
        m.update({"x": round(lane_x[lane[n["id"]]], 1), "y": round(y[n["id"]], 1), "w": size[n["id"]][0], "h": size[n["id"]][1]})
        placed[n["id"]] = m
    routes = []
    top = min(y[n["id"]] - size[n["id"]][1] / 2 for n in nodes)
    bottom = max(y[n["id"]] + size[n["id"]][1] / 2 for n in nodes)
    for e, plan in zip(edges, plans):
        a, b = placed[e["from"]], placed[e["to"]]
        if plan.get("straight"):
            down = 1 if b["y"] > a["y"] else -1
            x = a["x"] + (down * 7 if (e["to"], e["from"]) in pairs else 0)
            points = [(x, a["y"] + down * a["h"] / 2), (x, b["y"] - down * b["h"] / 2)]
            routes.append((points, [x + 6 + len(e.get("label") or "") * 2.8, (a["y"] + b["y"]) / 2 - 3 + (a["h"] - b["h"]) / 4 * down]))
            continue
        points = [(a["x"] + plan["sa"] * a["w"] / 2, plan["ys"])]
        if plan["gs"] == plan["gt"]:
            if plan["ys"] != plan["yt"]:
                tx = track(plan["gs"], plan["ls"])
                points += [(tx, plan["ys"]), (tx, plan["yt"])]
        else:
            if plan["ys"] != plan["yc"]:
                tx = track(plan["gs"], plan["ls"])
                points += [(tx, plan["ys"]), (tx, plan["yc"])]
            if plan["yc"] != plan["yt"]:
                tx = track(plan["gt"], plan["lt"])
                points += [(tx, plan["yc"]), (tx, plan["yt"])]
        points.append((b["x"] + plan["sb"] * b["w"] / 2, plan["yt"]))
        top = min(top, plan["yc"] - 8)
        bottom = max(bottom, plan["yc"] + 8)
        routes.append((points, None))
    return {"placed": placed, "routes": routes, "width": total_width, "top": top, "bottom": bottom, "gutters": gutter_x}


def emit(e, points, lp=None, dx=0.0, dy=0.0):
    d, points = rounded([(px + dx, py + dy) for px, py in points])
    if lp is None:
        longest = max(range(len(points) - 1), key=lambda k: (abs(points[k + 1][0] - points[k][0]), -k))
        lp = [(points[longest][0] + points[longest + 1][0]) / 2, points[longest][1] - 5]
    else:
        lp = [lp[0] + dx, lp[1] + dy]
    m = clean_edge(e)
    m.update({"d": d, "lp": [round(lp[0], 1), round(lp[1], 1)]})
    return m


def columns_layout(nodes, edges, groups, labels=False, ordered=False, bundle=False):
    row_gap, group_gap, group_pad, group_title = (26.0 if ordered else 16.0), 30.0, 12.0, 20.0
    by_column = defaultdict(list)
    for g in groups:
        by_column[g["column"]].append(g)
    columns = sorted(by_column)
    base = {}
    offset = 0
    for column in columns:
        base[column] = offset
        offset += max(max(1, g.get("cols", 1)) for g in by_column[column])
    lanes_total = offset
    size = {n["id"]: node_size(n) for n in nodes}
    order = defaultdict(list)
    for n in nodes:
        order[n["group"]].append(n["id"])
    neighbours = defaultdict(list)
    for e in edges:
        neighbours[e["from"]].append(e["to"])
        neighbours[e["to"]].append(e["from"])

    def arrange():
        lane, y, boxes = {}, {}, {}
        heights = {}
        for column in columns:
            cursor = 0.0
            for g in by_column[column]:
                cols = max(1, g.get("cols", 1))
                ids = order[g["id"]]
                top = cursor
                cursor += group_title + group_pad
                for start in range(0, len(ids), cols):
                    row = ids[start:start + cols]
                    height = max(size[i][1] for i in row)
                    for k, i in enumerate(row):
                        lane[i] = base[column] + k
                        y[i] = cursor + height / 2
                    cursor += height + row_gap
                cursor += group_pad - row_gap
                boxes[g["id"]] = (top, cursor)
                cursor += group_gap
            heights[column] = cursor - group_gap
        tallest = max(heights.values())
        for column in columns:
            shift = 0.0 if ordered else (tallest - heights[column]) / 2
            for g in by_column[column]:
                for i in order[g["id"]]:
                    y[i] += shift
                boxes[g["id"]] = (boxes[g["id"]][0] + shift, boxes[g["id"]][1] + shift)
        return lane, y, boxes, tallest

    lane, y, boxes, tallest = arrange()
    for _ in range(0 if ordered else 4):
        for g in groups:
            cols = max(1, g.get("cols", 1))
            ids = order[g["id"]]
            index = {i: k for k, i in enumerate(ids)}

            def vertical(i):
                near = neighbours[i]
                return (sum(y[j] for j in near) / len(near) if near else y[i], index[i])

            ids = sorted(ids, key=vertical)
            arranged = []
            for start in range(0, len(ids), cols):
                row = ids[start:start + cols]
                row.sort(key=lambda i: (sum(lane[j] for j in neighbours[i]) / len(neighbours[i]) if neighbours[i] else lanes_total / 2, index[i]))
                arranged.extend(row)
            order[g["id"]] = arranged
        lane, y, boxes, tallest = arrange()

    group_of = {n["id"]: n["group"] for n in nodes}
    route = gutter_route(nodes, edges, lane, y, size, group_of, lanes_total, bundle=bundle)
    placed = route["placed"]
    shift = -min(0.0, route["top"])
    for m in placed.values():
        m["y"] = round(m["y"] + shift, 1)
    out_edges = [emit(e, points, lp, 0.0, shift) for e, (points, lp) in zip(edges, route["routes"])]
    clusters = []
    for g in groups:
        ids = order[g["id"]]
        if not ids:
            continue
        left = min(placed[i]["x"] - placed[i]["w"] / 2 for i in ids) - group_pad
        right = max(placed[i]["x"] + placed[i]["w"] / 2 for i in ids) + group_pad
        right = max(right, left + len(g["label"]) * 7.4 + 18)
        top, bottom = boxes[g["id"]]
        clusters.append({"group": g["id"], "label": g["label"], "x": round(left, 1), "y": round(top + shift, 1), "w": round(right - left, 1), "h": round(bottom - top, 1)})
    return {"w": round(route["width"], 1), "h": round(max(tallest, route["bottom"]) + shift, 1), "nodes": [placed[n["id"]] for n in nodes], "edges": out_edges, "clusters": clusters}


FAR_PAD = 12.0
CORRIDOR = 58.0
SIDE = 64.0


def block_part(members, edges):
    result = run_dot(members, edges, direction="LR", attrs="nodesep=0.3; ranksep=0.5;", unflatten=["-l", "3", "-f", "-c", "5"])
    lane = {}
    last = None
    count = -1
    for x, i in sorted((result["pos"][n["id"]][0], n["id"]) for n in members):
        if last is None or x - last > 2:
            count += 1
        last = x
        lane[i] = count
    y = {n["id"]: result["pos"][n["id"]][1] for n in members}
    size = {n["id"]: node_size(n) for n in members}
    route = gutter_route(members, edges, lane, y, size, {n["id"]: 0 for n in members}, count + 1, bundle=True, pad=FAR_PAD, gutter_min=24.0, outer_min=0.0, clear=11.0)
    route["lane"] = lane
    return route


def pack_rows(sizes, order, limit):
    rows = [[]]
    width = 0.0
    for g in order:
        w = sizes[g][0]
        if rows[-1] and width + w > limit:
            rows.append([])
            width = 0.0
        rows[-1].append(g)
        width += w + BLOCK_GAP
    return rows


def blocks_layout(nodes, edges, groups, order=None, ratio=1.6, stubs=False, lanes=False):
    labels = {g["id"]: g["label"] for g in groups}
    members = defaultdict(list)
    for n in nodes:
        members[n["group"]].append(n)
    group_of = {n["id"]: n["group"] for n in nodes}
    inner = defaultdict(list)
    far = []
    for i, e in enumerate(edges):
        if group_of[e["from"]] == group_of[e["to"]]:
            inner[group_of[e["from"]]].append((i, e))
        else:
            far.append((i, e))
    parts, sizes = {}, {}
    for g in groups:
        if not members[g["id"]]:
            continue
        part = block_part(members[g["id"]], [e for _, e in inner[g["id"]]])
        parts[g["id"]] = part
        title = len(labels[g["id"]]) * 7.4 + 2 * BLOCK_MARGIN
        sizes[g["id"]] = (max(part["width"] + 2 * BLOCK_MARGIN, title), part["bottom"] - part["top"] + 2 * BLOCK_MARGIN + BLOCK_TITLE)
    if order is None:
        order = [g["id"] for g in groups] if lanes else sorted(sizes, key=lambda g: (-sizes[g][1], g))
    order = [g for g in order if g in sizes]
    widest = max(w for w, _ in sizes.values())
    if lanes:
        limit = widest
    else:
        limit = max(widest, math.sqrt(sum(w * h for w, h in sizes.values()) * ratio) * 1.08)
    grid = pack_rows(sizes, order, limit)
    row_widths = [sum(sizes[g][0] for g in row) + BLOCK_GAP * (len(row) - 1) for row in grid]
    inner_width = max(row_widths)
    total_width = inner_width + 2 * SIDE
    placed, clusters = {}, []
    out_edges = [None] * len(edges)
    origin, row_of, row_top, row_bottom = {}, {}, [], []
    y = CORRIDOR
    for r, (row, row_width) in enumerate(zip(grid, row_widths)):
        x = SIDE + (0.0 if lanes else (inner_width - row_width) / 2)
        row_height = max(sizes[g][1] for g in row)
        row_top.append(y)
        row_bottom.append(y + row_height)
        for g in row:
            w, h = sizes[g]
            part = parts[g]
            dx = x + (w - part["width"]) / 2
            dy = y + BLOCK_TITLE + BLOCK_MARGIN - part["top"]
            origin[g] = dx
            row_of[g] = r
            for i, m in part["placed"].items():
                m["x"] = round(m["x"] + dx, 1)
                m["y"] = round(m["y"] + dy, 1)
                placed[i] = m
            clusters.append({"group": g, "label": labels[g], "x": round(x, 1), "y": round(y, 1), "w": round(w, 1), "h": round(row_height if lanes else h, 1)})
            for (index, e), (points, lp) in zip(inner[g], part["routes"]):
                out_edges[index] = emit(e, points, lp, dx, dy)
            x += w + BLOCK_GAP
        y += row_height + CORRIDOR
    centers = [row_top[0] - CORRIDOR / 2] + [bottom + CORRIDOR / 2 for bottom in row_bottom]
    slots = int((CORRIDOR - 14) / 4.5)
    used = defaultdict(int)

    def slot(key, count, step):
        k = used[key] % count
        used[key] += 1
        return ((k + 1) // 2) * step * (1 if k % 2 else -1)

    def escape(node, side):
        part = parts[node["group"]]
        k = part["lane"][node["id"]]
        left, width = part["gutters"][k + 1 if side > 0 else k]
        x = origin[node["group"]] + (left + FAR_PAD / 2 if side > 0 else left + width - FAR_PAD / 2)
        return x + slot(("x", node["group"], k, side), 3, 3.5)

    for index, e in far:
        a, b = placed[e["from"]], placed[e["to"]]
        ra, rb = row_of[a["group"]], row_of[b["group"]]
        sa = 1 if b["x"] >= a["x"] else -1
        sb = -sa
        row = e.get("row")
        ya = row_port(a["y"], a["h"], row) if row is not None else a["y"] + slot(("p", a["id"], sa), 5, 4.0)
        yb = b["y"] + slot(("p", b["id"], sb), 5, 4.0)
        if rb > ra:
            ca, cb = ra + 1, rb
        elif rb < ra:
            ca, cb = ra, rb + 1
        else:
            above = (a["y"] - row_top[ra]) + (b["y"] - row_top[ra])
            below = (row_bottom[ra] - a["y"]) + (row_bottom[ra] - b["y"])
            ca = cb = ra if above <= below else ra + 1
        xa, xb = escape(a, sa), escape(b, sb)
        start = (a["x"] + sa * a["w"] / 2, ya)
        end = (b["x"] + sb * b["w"] / 2, yb)
        yca = centers[ca] + slot(("c", ca), slots, 4.5)
        points = [start, (xa, ya), (xa, yca)]
        if ca == cb:
            points += [(xb, yca)]
        else:
            ycb = centers[cb] + slot(("c", cb), slots, 4.5)
            left_side = (xa + xb) / 2 <= total_width / 2
            gx = (SIDE / 2 if left_side else total_width - SIDE / 2) + slot(("g", left_side), 11, 4.5)
            points += [(gx, yca), (gx, ycb), (xb, ycb)]
        points += [(xb, yb), end]
        m = emit(e, points)
        m["far"] = 1
        if stubs:
            m["stub"] = "M%.1f %.1fL%.1f %.1f" % (start[0], start[1], start[0] + sa * 11, start[1])
        out_edges[index] = m
    return {"w": round(total_width, 1), "h": round(y, 1), "nodes": [placed[n["id"]] for n in nodes], "edges": out_edges, "clusters": clusters}
