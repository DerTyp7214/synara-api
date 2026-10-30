#!/usr/bin/env python3
"""Catalogue of the server-driven UI contributions as one self-contained HTML page.

See USAGE below, or run with --help.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
TITLE = "Synara"
SOURCE_URL = "https://github.com/DerTyp7214/synara-api/blob/master/"

USAGE = """Reads docs/SERVER_UI_CONTRIBUTIONS.json (written by the generateUiContributionDocs Gradle task) and writes docs/UI_CATALOGUE.html,
one self-contained interactive page (data inlined, no network requests) to open locally in a browser.
Shows every UI slot with its contributions and a filterable list of all contributions with their access rules, source and implementing class.
Contributions whose data carries a "render" (tree, toolbar, error) open a mockup: an approximate drawing of the tree with a layer view and an inspector.
-i reads the data from elsewhere, -o writes the page elsewhere.
The same data gives a byte-identical page. Standard library only."""


def render(data: dict, title: str) -> str:
    payload = json.dumps(data, separators=(",", ":"), ensure_ascii=False)
    payload = payload.replace("<", "\\u003c").replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
    safe_title = (title.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"))
    return TEMPLATE.replace("__TITLE__", safe_title).replace("__SOURCE_URL__", SOURCE_URL).replace("__DATA__", payload)


def parse_arguments():
    parser = argparse.ArgumentParser(description=USAGE, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("-i", "--input", type=Path, default=REPO_ROOT / "docs" / "SERVER_UI_CONTRIBUTIONS.json", help="contribution data to read (default: docs/SERVER_UI_CONTRIBUTIONS.json in this repository)")
    parser.add_argument("-o", "--output", type=Path, default=REPO_ROOT / "docs" / "UI_CATALOGUE.html", help="page to write (default: docs/UI_CATALOGUE.html in this repository)")
    parser.add_argument("--title", default=TITLE, help=f"page title (default: {TITLE})")
    return parser.parse_args()


def main() -> int:
    args = parse_arguments()
    if not args.input.is_file():
        print(f"Input not found: {args.input}", file=sys.stderr)
        return 1
    try:
        data = json.loads(args.input.read_text(encoding="utf-8"))
    except json.JSONDecodeError as e:
        print(f"Could not parse {args.input}: {e}", file=sys.stderr)
        return 1
    for key in ("slots", "sources", "contributions"):
        if not isinstance(data.get(key), list):
            print(f"{args.input} has no '{key}' list.", file=sys.stderr)
            return 1
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(render(data, args.title), encoding="utf-8", newline="\n")
    print(f"Wrote {args.output} ({args.output.stat().st_size / 1024:,.0f} KB, {len(data['contributions'])} contributions)")
    return 0


TEMPLATE = r'''<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>__TITLE__ UI catalogue</title>
<style>
:root {
  color-scheme: light;
  --bg: #fafbfc; --raised: #ffffff; --ink: #14171c; --ink2: #4a515c; --muted: #6b727d;
  --grid: #e7eaee; --axis: #c5cad2; --border: rgba(20,23,28,.13); --wash: rgba(20,23,28,.05);
  --s0: #2a78d6; --s1: #eb6834; --s2: #1baf7a; --s3: #eda100; --s4: #e87ba4; --s5: #008300;
  --s6: #4a3aa7; --s7: #e34948; --other: #9aa1ab; --all: #3d4450;
  --shadow: 0 6px 24px rgba(20,23,28,.12), 0 1px 3px rgba(20,23,28,.10);
}
@media (prefers-color-scheme: dark) {
  :root:not([data-theme="light"]) {
    color-scheme: dark;
    --bg: #14161a; --raised: #1f2228; --ink: #f3f5f7; --ink2: #b9c0ca; --muted: #8b929d;
    --grid: #262a31; --axis: #3d424b; --border: rgba(255,255,255,.14); --wash: rgba(255,255,255,.07);
    --s0: #3987e5; --s1: #d95926; --s2: #199e70; --s3: #c98500; --s4: #d55181; --s5: #008300;
    --s6: #9085e9; --s7: #e66767; --other: #5d646f; --all: #c9cfd8;
    --shadow: 0 8px 28px rgba(0,0,0,.5), 0 1px 3px rgba(0,0,0,.4);
  }
}
:root[data-theme="dark"] {
  color-scheme: dark;
  --bg: #14161a; --raised: #1f2228; --ink: #f3f5f7; --ink2: #b9c0ca; --muted: #8b929d;
  --grid: #262a31; --axis: #3d424b; --border: rgba(255,255,255,.14); --wash: rgba(255,255,255,.07);
  --s0: #3987e5; --s1: #d95926; --s2: #199e70; --s3: #c98500; --s4: #d55181; --s5: #008300;
  --s6: #9085e9; --s7: #e66767; --other: #5d646f; --all: #c9cfd8;
  --shadow: 0 8px 28px rgba(0,0,0,.5), 0 1px 3px rgba(0,0,0,.4);
}
* { box-sizing: border-box; }
html { -webkit-text-size-adjust: 100%; }
body {
  margin: 0; background: var(--bg); color: var(--ink);
  font: 15px/1.5 system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;
}
.wrap { max-width: 1180px; margin: 0 auto; padding: 0 28px; }
button, input { font: inherit; color: inherit; }
button { cursor: pointer; }
a { color: var(--s0); }
:focus-visible { outline: 2px solid var(--ink); outline-offset: 2px; border-radius: 4px; }
code, .mono { font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; }

/* ---------- header ---------- */
.top { padding: 44px 0 14px; display: flex; gap: 16px; align-items: flex-start; justify-content: space-between; }
h1 { margin: 0; font-size: clamp(30px, 5.4vw, 52px); line-height: 1.04; font-weight: 680; letter-spacing: -0.028em; }
#lede { margin: 12px 0 0; color: var(--ink2); font-size: 16px; max-width: 62ch; }
#theme {
  flex: none; margin-top: 8px; background: none; border: 1px solid var(--border); border-radius: 999px;
  padding: 5px 13px; font-size: 13px; color: var(--ink2); white-space: nowrap;
}
#theme:hover { background: var(--wash); }

/* ---------- panels ---------- */
main { padding-bottom: 40px; }
.panel { padding: 30px 0 6px; min-width: 0; }
.panel + .panel, .stats + .panel { border-top: 1px solid var(--grid); }
h2 { margin: 0; font-size: 19px; line-height: 1.25; font-weight: 650; letter-spacing: -0.012em; }
.sub { margin: 3px 0 12px; color: var(--muted); font-size: 13.5px; max-width: 70ch; }

/* ---------- figures ---------- */
.stats { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 26px 28px; padding: 30px 0 32px; border-top: 1px solid var(--grid); }
.stat .v { font-size: clamp(22px, 2.9vw, 31px); font-weight: 650; letter-spacing: -0.02em; line-height: 1.1; white-space: nowrap; }
.stat .l { color: var(--ink2); font-size: 13.5px; margin-top: 5px; }

/* ---------- slot grid ---------- */
.slots { display: grid; grid-template-columns: repeat(auto-fill, minmax(250px, 1fr)); gap: 14px; margin-bottom: 24px; }
.slot { background: var(--raised); border: 1px solid var(--border); border-radius: 10px; padding: 12px 14px; min-width: 0; }
.slot.empty { opacity: .5; }
.slot h3 { margin: 0 0 8px; font-size: 14px; font-weight: 650; display: flex; justify-content: space-between; gap: 8px; }
.slot h3 .n { color: var(--muted); font-weight: 400; font-variant-numeric: tabular-nums; }
.slot ul { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 2px; }
.slot li button { width: 100%; text-align: left; background: none; border: 0; border-radius: 6px; padding: 3px 7px; margin: 0 -7px; width: calc(100% + 14px);
  font-size: 13px; color: var(--ink2); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; display: flex; align-items: center; gap: 7px; }
.slot li button:hover { background: var(--wash); color: var(--ink); }
.slot .none { color: var(--muted); font-size: 13px; }
.dot { width: 9px; height: 9px; border-radius: 3px; flex: none; display: inline-block; }

/* ---------- filters ---------- */
.filters { display: grid; grid-template-columns: max-content 1fr; gap: 8px 14px; align-items: center; margin-bottom: 14px; }
.filters .fl { color: var(--muted); font-size: 12.5px; }
.chips { display: flex; flex-wrap: wrap; gap: 6px; }
.chip { background: none; border: 1px solid var(--border); border-radius: 999px; padding: 2px 11px; font-size: 13px; color: var(--ink2); }
.chip:hover { background: var(--wash); }
.chip[aria-pressed="true"] { background: var(--ink); color: var(--bg); border-color: var(--ink); font-weight: 600; }
.chip .n { opacity: .65; margin-left: 6px; font-variant-numeric: tabular-nums; font-size: 12px; }
#q { width: 100%; max-width: 440px; background: var(--raised); border: 1px solid var(--border); border-radius: 8px; padding: 6px 11px; font-size: 14px; }
#count { color: var(--muted); font-size: 13px; margin: 0 0 12px; }

/* ---------- contribution cards ---------- */
#list { display: grid; grid-template-columns: repeat(auto-fill, minmax(340px, 1fr)); gap: 14px; margin-bottom: 30px; }
.card { background: var(--raised); border: 1px solid var(--border); border-radius: 10px; padding: 14px 16px; min-width: 0; }
.card.hit { box-shadow: 0 0 0 2px var(--s0), var(--shadow); }
.card .cid { font-size: 12.5px; color: var(--muted); overflow-wrap: anywhere; }
.card h3 { margin: 2px 0 0; font-size: 16.5px; font-weight: 650; letter-spacing: -0.01em; }
.card p { margin: 4px 0 0; color: var(--ink2); font-size: 13.5px; }
.card .tags { display: flex; flex-wrap: wrap; gap: 5px; margin-top: 10px; }
.tag { font-size: 11.5px; color: var(--ink2); border: 1px solid var(--border); border-radius: 999px; padding: 0 8px; line-height: 19px; white-space: nowrap; }
.tag.kind { color: var(--bg); border-color: transparent; font-weight: 600; }
.tag.admin { color: var(--s7); border-color: var(--s7); }
.tag.cap { color: var(--s3); border-color: var(--s3); }
.card dl { margin: 10px 0 0; display: grid; grid-template-columns: max-content 1fr; gap: 3px 12px; font-size: 13px; }
.card dt { color: var(--muted); }
.card dd { margin: 0; overflow-wrap: anywhere; }
.card dd small { color: var(--muted); }
.empty-msg { color: var(--muted); padding: 20px 0; }
.card .mock-btn { margin-top: 12px; }
.card .mock-btn.bad { color: var(--s7); border-color: var(--s7); }

/* ---------- mockup dialog ---------- */
#mock { width: min(1180px, 96vw); height: min(900px, 94vh); max-width: none; max-height: none; padding: 0; border: 1px solid var(--border); border-radius: 14px;
  background: var(--raised); color: var(--ink); box-shadow: var(--shadow); overflow: hidden; }
#mock[open] { display: flex; flex-direction: column; }
#mock::backdrop { background: rgba(10,12,15,.45); }
.mk-head { display: flex; gap: 16px; align-items: flex-start; justify-content: space-between; padding: 14px 18px 12px; border-bottom: 1px solid var(--grid); }
.mk-head .cid { font-size: 12.5px; color: var(--muted); }
.mk-head h2 { font-size: 18px; }
.mk-body { flex: 1; min-height: 0; display: grid; grid-template-columns: minmax(0, 430px) minmax(0, 1fr); }
.mk-stage { overflow: auto; padding: 18px; background: var(--wash); display: flex; flex-direction: column; align-items: center; gap: 10px; }
.mk-note { color: var(--muted); font-size: 12px; text-align: center; max-width: 380px; }
.mk-side { min-height: 0; display: grid; grid-template-rows: auto minmax(0, 1fr) minmax(0, .8fr); border-left: 1px solid var(--grid); }
.mk-tools { display: flex; gap: 8px; align-items: center; justify-content: space-between; padding: 10px 14px; border-bottom: 1px solid var(--grid); font-size: 12.5px; color: var(--muted); }
.mk-layers { overflow: auto; padding: 6px 0; font-size: 12.5px; }
.mk-insp { overflow: auto; border-top: 1px solid var(--grid); padding: 10px 14px; font-size: 12.5px; }
.mk-insp h4 { margin: 0 0 6px; font-size: 12px; font-weight: 600; color: var(--muted); text-transform: uppercase; letter-spacing: .04em; }
.mk-insp pre { margin: 0 0 10px; padding: 8px 10px; background: var(--wash); border-radius: 6px; font-size: 12px; white-space: pre-wrap; overflow-wrap: anywhere; }
.mk-insp .path { color: var(--ink2); margin-bottom: 8px; overflow-wrap: anywhere; }
.mk-insp .none { color: var(--muted); }
.mk-group { padding: 6px 14px 2px; font-size: 11px; font-weight: 600; color: var(--muted); text-transform: uppercase; letter-spacing: .05em; }
.ly-row { display: flex; align-items: center; gap: 6px; width: 100%; background: none; border: 0; padding: 2px 10px 2px 0; text-align: left; white-space: nowrap; color: var(--ink2); }
.ly-row:hover, .ly-row.hov { background: var(--wash); color: var(--ink); }
.ly-row.sel { background: color-mix(in srgb, var(--s0) 16%, transparent); color: var(--ink); }
.ly-row .car { width: 14px; flex: none; text-align: center; color: var(--muted); font-size: 10px; }
.ly-row .ty { font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: 11.5px; color: var(--s0); }
.ly-row .lb { overflow: hidden; text-overflow: ellipsis; min-width: 0; }
.ly-row .rl { font-size: 10.5px; color: var(--muted); border: 1px solid var(--border); border-radius: 999px; padding: 0 6px; line-height: 15px; }
.ly.closed > .ly-kids { display: none; }

/* ---------- mockup drawing: a rough Material-like phone screen ---------- */
.m-dev { width: 380px; max-width: 100%; flex: none; background: var(--bg); border: 1px solid var(--border); border-radius: 26px; padding: 10px 0 14px;
  box-shadow: var(--shadow); font-size: 13.5px; line-height: 1.4; color: var(--ink); overflow: hidden; }
.m-bar { display: flex; align-items: center; gap: 8px; padding: 6px 14px 10px; border-bottom: 1px solid var(--grid); min-height: 44px; }
.m-bar .m-ttl { flex: 1; min-width: 0; font-size: 17px; font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.m-bar .m-back { color: var(--muted); font-size: 18px; }
.m-screen { padding: 14px; display: flex; flex-direction: column; gap: 12px; }
.m-msg { padding: 30px 14px; text-align: center; color: var(--muted); }
.m-msg.bad { color: var(--s7); white-space: pre-wrap; overflow-wrap: anywhere; text-align: left; font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: 12px; }
.m-n { cursor: default; min-width: 0; }
.m-dev.outl .m-n { outline: 1px dashed color-mix(in srgb, var(--s0) 55%, transparent); outline-offset: -1px; }
.m-dev .m-n.m-hov { outline: 2px dashed var(--s1); outline-offset: 1px; }
.m-dev .m-n.m-sel { outline: 2px solid var(--s0); outline-offset: 1px; }
.m-col { display: flex; flex-direction: column; }
.m-row { display: flex; flex-direction: row; flex-wrap: wrap; }
.m-grid { display: grid; }
.m-surf { background: var(--raised); border: 1px solid var(--border); border-radius: 14px; padding: 12px 14px; display: flex; flex-direction: column; gap: 10px;
  box-shadow: 0 1px 2px rgba(0,0,0,.08); }
.m-head { display: flex; align-items: center; gap: 10px; }
.m-head .m-t { font-weight: 600; font-size: 15px; }
.m-head .m-s { color: var(--muted); font-size: 12.5px; }
.m-foot { display: flex; flex-wrap: wrap; gap: 8px; justify-content: flex-end; align-items: center; }
.m-sec { display: flex; flex-direction: column; gap: 10px; }
.m-sec-h { display: flex; align-items: center; justify-content: space-between; color: var(--s0); font-size: 13px; font-weight: 600; letter-spacing: .01em; }
.m-sec.collapsed > .m-sec-b { opacity: .4; }
.m-sec-b { display: flex; flex-direction: column; gap: 10px; }
.m-g { display: inline-flex; align-items: center; justify-content: center; flex: none; min-width: 26px; height: 22px; padding: 0 5px; border-radius: 7px;
  font: 600 9px/1 ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; letter-spacing: .02em; color: var(--s0);
  background: color-mix(in srgb, currentColor 13%, transparent); }
.m-g.big { min-width: 56px; height: 44px; border-radius: 14px; font-size: 11px; color: var(--muted); }
.m-g.sm { min-width: 0; height: 15px; padding: 0 4px; border-radius: 5px; font-size: 8px; }
.m-g.m-img { color: var(--muted); background: repeating-linear-gradient(135deg, var(--wash) 0 5px, transparent 5px 10px), var(--grid); width: 36px; height: 36px; border-radius: 8px; }
.m-g.m-img.round { border-radius: 50%; }
.m-image { display: flex; align-items: center; justify-content: center; width: 100%; max-width: 220px; aspect-ratio: 4 / 3; border-radius: 4px; color: var(--muted); font-size: 11.5px;
  background: repeating-linear-gradient(135deg, var(--wash) 0 8px, transparent 8px 16px), var(--grid); overflow: hidden; text-align: center; padding: 6px; overflow-wrap: anywhere; }
.m-image.round { border-radius: 50%; max-width: 110px; aspect-ratio: 1; }
.m-tx { overflow-wrap: anywhere; }
.m-tx.TITLE { font-size: 20px; font-weight: 600; line-height: 1.25; }
.m-tx.SUBTITLE { font-size: 15.5px; font-weight: 600; }
.m-tx.CAPTION { font-size: 12px; color: var(--muted); }
.m-tx.CODE { font: 12px/1.45 ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; white-space: pre-wrap; background: var(--wash); border-radius: 6px; padding: 6px 8px; }
.m-tx.LOW { opacity: .68; }
.m-tx.HIGH { font-weight: 650; }
.m-badge { display: inline-flex; align-items: center; gap: 4px; border-radius: 999px; padding: 1px 9px; font-size: 11.5px; font-weight: 600;
  background: color-mix(in srgb, currentColor 14%, transparent); white-space: nowrap; }
.m-stat { display: flex; flex-direction: column; gap: 2px; }
.m-stat .m-sl { display: flex; align-items: center; gap: 6px; font-size: 12px; color: var(--muted); }
.m-stat .m-sv { font-size: 24px; font-weight: 650; letter-spacing: -.02em; line-height: 1.15; font-variant-numeric: tabular-nums; }
.m-stat .m-su { font-size: 13px; font-weight: 500; color: var(--muted); margin-left: 3px; }
.m-prog { display: flex; flex-direction: column; gap: 5px; font-size: 12px; color: var(--ink2); }
.m-prog .m-tr { height: 6px; border-radius: 3px; background: var(--grid); overflow: hidden; }
.m-prog .m-tr i { display: block; height: 100%; border-radius: 3px; background: var(--s0); }
.m-prog .m-tr.ind i { width: 100%; background: repeating-linear-gradient(90deg, var(--s0) 0 18px, transparent 18px 30px); }
.m-tile { display: flex; align-items: center; gap: 10px; border-radius: 14px; padding: 12px; background: color-mix(in srgb, currentColor 7%, var(--raised)); border: 1px solid var(--border); }
.m-tile .m-tc { flex: 1; min-width: 0; color: var(--ink); }
.m-tile .m-t { font-weight: 600; }
.m-tile .m-s { font-size: 12px; color: var(--muted); }
.m-btn { display: inline-flex; align-items: center; justify-content: center; gap: 6px; border-radius: 999px; padding: 7px 16px; font-size: 13px; font-weight: 600;
  white-space: nowrap; border: 1px solid transparent; }
.m-btn.PRIMARY { background: var(--s0); color: #fff; }
.m-btn.DESTRUCTIVE { background: var(--s7); color: #fff; }
.m-btn.SECONDARY { border-color: var(--axis); color: var(--s0); }
.m-btn.TEXT { color: var(--s0); padding-left: 8px; padding-right: 8px; }
.m-btn .m-g { color: inherit; height: 17px; min-width: 0; }
.m-off { opacity: .42; }
.m-badge .m-g, .m-tile .m-g { color: inherit; }
.m-badge .m-g { background: none; padding: 0; }
.m-g.m-img.sm { width: 20px; height: 20px; border-radius: 5px; }
.m-g.m-img.sm.round { border-radius: 50%; }
/* stretched columns would widen small inline elements to the full width */
:is(.m-col.st, .m-surf, .m-sec-b, .m-screen, .m-ph) > :is(.m-g, .m-badge, .m-btn) { align-self: flex-start; }
.m-li { display: flex; align-items: center; gap: 12px; padding: 8px 2px; }
.m-li .m-lc { flex: 1; min-width: 0; }
.m-li .m-t { overflow: hidden; text-overflow: ellipsis; }
.m-li .m-s { font-size: 12px; color: var(--muted); }
.m-li .m-tr, .m-chev { color: var(--muted); font-size: 12.5px; white-space: nowrap; }
.m-tbl { overflow-x: auto; border: 1px solid var(--border); border-radius: 10px; }
.m-tbl table { border-collapse: collapse; width: 100%; font-size: 12px; }
.m-tbl th { text-align: left; font-weight: 600; color: var(--ink2); background: var(--wash); }
.m-tbl th, .m-tbl td { padding: 5px 8px; border-bottom: 1px solid var(--grid); white-space: nowrap; }
.m-tbl tr:last-child td { border-bottom: 0; }
.m-tbl td.act { color: var(--muted); width: 1px; }
.m-tbl .none { color: var(--muted); text-align: center; }
.m-div { height: 1px; background: var(--grid); align-self: stretch; }
.m-ph { border: 1.5px dashed var(--axis); border-radius: 10px; padding: 8px 10px; color: var(--muted); font-size: 12px; display: flex; flex-direction: column; gap: 6px; }
.m-ph > .m-phl { font: 600 10.5px/1.3 ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; text-transform: uppercase; letter-spacing: .04em; }
.m-ph.live { border-color: color-mix(in srgb, var(--s2) 60%, transparent); }
.m-ph.live > .m-phl { color: var(--s2); }
.m-ph.native { border-color: color-mix(in srgb, var(--s6) 60%, transparent); }
.m-ph.native > .m-phl { color: var(--s6); }
.m-ph.fb { border-color: color-mix(in srgb, var(--s3) 60%, transparent); }
.m-ph.fb > .m-phl { color: var(--s3); }
.m-ph.unk { border-color: color-mix(in srgb, var(--s7) 55%, transparent); }
.m-ph.unk > .m-phl { color: var(--s7); }
.m-empty { display: flex; flex-direction: column; align-items: center; text-align: center; gap: 6px; padding: 22px 10px; }
.m-empty .m-t { font-weight: 650; font-size: 15.5px; }
.m-empty .m-s { color: var(--muted); font-size: 12.5px; max-width: 30ch; }
.m-empty .m-foot { justify-content: center; margin-top: 6px; }
.m-log { height: 150px; overflow: auto; display: flex; flex-direction: column-reverse; background: var(--wash); border-radius: 8px; padding: 6px 8px;
  font: 11px/1.45 ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; color: var(--ink2); }
.m-log div { white-space: pre-wrap; overflow-wrap: anywhere; }
.m-field { display: flex; flex-direction: column; gap: 4px; }
.m-lbl { font-size: 12px; font-weight: 600; color: var(--ink2); }
.m-lbl .m-req { color: var(--s7); }
.m-box { display: flex; align-items: center; gap: 8px; min-height: 36px; border: 1px solid var(--axis); border-radius: 8px; padding: 6px 10px; background: var(--raised); }
.m-box.err { border-color: var(--s7); }
.m-box.multi { align-items: flex-start; min-height: 70px; }
.m-box .m-v { flex: 1; min-width: 0; overflow-wrap: anywhere; }
.m-box .m-v.multi { white-space: pre-wrap; }
.m-box .m-ph2 { flex: 1; min-width: 0; color: var(--muted); }
.m-box .m-aux { color: var(--muted); font-size: 11.5px; white-space: nowrap; }
.m-box .m-pick { border: 1px solid var(--axis); border-radius: 6px; padding: 2px 8px; font-size: 12px; font-weight: 600; color: var(--s0); white-space: nowrap; }
.m-help { font-size: 11.5px; color: var(--muted); }
.m-err { font-size: 11.5px; color: var(--s7); }
.m-kbd { display: flex; flex-wrap: wrap; gap: 6px; align-items: center; justify-content: flex-end; border-top: 1px dashed var(--grid); padding-top: 5px; }
.m-kbd .m-kl { margin-right: auto; font-size: 10.5px; color: var(--muted); }
.m-sw { display: flex; align-items: center; gap: 12px; }
.m-sw .m-swc { flex: 1; min-width: 0; }
.m-sw .m-trk { flex: none; width: 38px; height: 22px; border-radius: 11px; background: var(--axis); position: relative; }
.m-sw .m-trk::after { content: ""; position: absolute; top: 3px; left: 3px; width: 16px; height: 16px; border-radius: 50%; background: var(--raised); }
.m-sw .m-trk.on { background: var(--s0); }
.m-sw .m-trk.on::after { left: 19px; }

footer { border-top: 1px solid var(--grid); padding: 22px 0 60px; color: var(--muted); font-size: 13px; }
footer p { margin: 0 0 8px; max-width: 86ch; }
footer code { font-size: 12px; color: var(--ink2); }

@media (max-width: 640px) {
  .wrap { padding: 0 16px; }
  .top { padding-top: 26px; }
  .stats { grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 20px 16px; padding: 24px 0; }
  #list { grid-template-columns: minmax(0, 1fr); }
}
@media (max-width: 860px) {
  .mk-body { grid-template-columns: minmax(0, 1fr); overflow: auto; }
  .mk-stage { overflow: visible; }
  .mk-side { border-left: 0; border-top: 1px solid var(--grid); grid-template-rows: auto auto auto; }
}
@media (prefers-reduced-motion: reduce) { * { transition: none !important; } }
</style>
</head>
<body>
<div class="wrap">
  <header class="top">
    <div>
      <h1>__TITLE__ UI catalogue</h1>
      <p id="lede">Every server-driven UI contribution: where it appears, who may see it and which class provides it, with a rough mockup of what it renders.</p>
    </div>
    <button id="theme" type="button" title="Switch colour theme">Theme: auto</button>
  </header>
  <div class="stats" id="stats"></div>
  <main>
    <section class="panel">
      <h2>Slots</h2>
      <p class="sub">Every slot clients render, with the contributions registered in it. Select a contribution to jump to its card. Slots without contributions are dimmed.</p>
      <div class="slots" id="slots"></div>
    </section>
    <section class="panel" id="all">
      <h2>Contributions</h2>
      <p class="sub">Chips within one row match any of the selected values, rows combine. The search looks at id, title, description and class.</p>
      <div class="filters" id="filters"></div>
      <p id="count"></p>
      <div id="list"></div>
    </section>
  </main>
  <footer id="foot"></footer>
</div>
<dialog id="mock" aria-labelledby="mock-title">
  <div class="mk-head">
    <div><div class="cid mono" id="mock-id"></div><h2 id="mock-title"></h2></div>
    <button class="chip" id="mock-close" type="button">Close</button>
  </div>
  <div class="mk-body">
    <div class="mk-stage" id="mock-stage"></div>
    <div class="mk-side">
      <div class="mk-tools"><span>Layers</span><button class="chip" id="mock-outl" type="button" aria-pressed="false">Show layers</button></div>
      <div class="mk-layers" id="mock-layers"></div>
      <div class="mk-insp" id="mock-insp"></div>
    </div>
  </div>
</dialog>
<noscript><p style="padding:24px">This page is drawn with JavaScript. Enable it to see the catalogue.</p></noscript>
<script type="application/json" id="data">__DATA__</script>
<script>
(() => {
'use strict';
const D = JSON.parse(document.getElementById('data').textContent);
const SRC = '__SOURCE_URL__';
const C = D.contributions;
const byId = new Map(C.map(c => [c.id, c]));
const srcOf = new Map(D.sources.map(s => [s.id, s]));
const nf = new Intl.NumberFormat('en-US');
const KINDS = ['SLOT', 'PAGE', 'HOME_CARD'];
const KIND_LABEL = { SLOT: 'Slot entry', PAGE: 'Page', HOME_CARD: 'Home card' };
const KIND_COL = { SLOT: 'var(--s0)', PAGE: 'var(--s2)', HOME_CARD: 'var(--s6)' };
const ACCESS = ['admin', 'capability', 'everyone'];

function h(tag, props, ...kids) {
  const e = document.createElement(tag);
  if (props) for (const k in props) {
    const v = props[k];
    if (v == null || v === false) continue;
    if (k === 'class') e.className = v;
    else if (k === 'text') e.textContent = v;
    else if (k === 'style') e.style.cssText = v;
    else if (k.startsWith('on')) e.addEventListener(k.slice(2), v);
    else e.setAttribute(k, v === true ? '' : v);
  }
  for (const kid of kids.flat(Infinity)) if (kid != null) e.append(kid);
  return e;
}
const accessOf = c => {
  const a = [];
  if (c.access.requiresAdmin) a.push('admin');
  if (c.access.capabilities.length) a.push('capability');
  return a.length ? a : ['everyone'];
};
const kindDot = k => h('i', { class: 'dot', style: 'background:' + KIND_COL[k] });

/* ---------- header figures ---------- */
const count = k => C.filter(c => c.kind === k).length;
const stats = document.getElementById('stats');
[[C.length, 'Contributions'], [count('PAGE'), 'Pages'], [count('SLOT'), 'Slot entries'], [count('HOME_CARD'), 'Home cards'], [D.sources.length, 'Sources']]
  .forEach(([v, l]) => stats.append(h('div', { class: 'stat' }, h('div', { class: 'v', text: nf.format(v) }), h('div', { class: 'l', text: l }))));

/* ---------- filter state ---------- */
const state = { kind: new Set(), slot: new Set(), source: new Set(), access: new Set(), q: '' };
const NOSLOT = '(no slot)';
const slotOf = c => c.slot == null ? NOSLOT : c.slot;
const slotIds = D.slots.map(s => s.id);
const slotValues = slotIds.concat(C.some(c => c.slot == null) ? [NOSLOT] : []);
const GROUPS = [
  ['kind', 'Kind', KINDS, c => c.kind, k => KIND_LABEL[k]],
  ['slot', 'Slot', slotValues, slotOf, s => s],
  ['source', 'Source', D.sources.map(s => s.id), c => c.source, s => s + (srcOf.get(s).plugin ? ' (plugin)' : '')],
  ['access', 'Access', ACCESS, null, a => a === 'everyone' ? 'Everyone' : a === 'admin' ? 'Admin only' : 'Needs capability'],
];
const matches = (c, skip) => {
  for (const [key, , , get] of GROUPS) {
    if (key === skip || !state[key].size) continue;
    if (key === 'access') { if (!accessOf(c).some(a => state.access.has(a))) return false; }
    else if (!state[key].has(get(c))) return false;
  }
  if (state.q) {
    const hay = [c.id, c.title, c.description || '', c.className].join('\n').toLowerCase();
    if (!state.q.split(/\s+/).every(w => hay.includes(w))) return false;
  }
  return true;
};

const filters = document.getElementById('filters');
function drawFilters() {
  const rows = [];
  for (const [key, label, values, get, name] of GROUPS) {
    const chips = values.map(v => {
      const n = C.filter(c => matches(c, key) && (key === 'access' ? accessOf(c).includes(v) : get(c) === v)).length;
      return h('button', { class: 'chip', type: 'button', 'aria-pressed': state[key].has(v), onclick: () => { toggle(key, v); } },
        name(v), h('span', { class: 'n', text: String(n) }));
    });
    rows.push(h('span', { class: 'fl', text: label }), h('div', { class: 'chips' }, chips));
  }
  const q = h('input', { id: 'q', type: 'search', placeholder: 'Search id, title, description, class', 'aria-label': 'Search contributions', value: state.q,
    oninput: e => { state.q = e.target.value.trim().toLowerCase(); drawList(); drawFilters(); q2focus(); } });
  rows.push(h('span', { class: 'fl', text: 'Search' }), q);
  filters.replaceChildren(...rows);
}
function q2focus() {
  const q = document.getElementById('q'); q.focus(); q.setSelectionRange(q.value.length, q.value.length);
}
function toggle(key, v) {
  if (state[key].has(v)) state[key].delete(v); else state[key].add(v);
  drawFilters(); drawList();
}

/* ---------- cards ---------- */
const list = document.getElementById('list'), countEl = document.getElementById('count');
function card(c) {
  const src = srcOf.get(c.source) || { plugin: false };
  const tags = [h('span', { class: 'tag kind', style: 'background:' + KIND_COL[c.kind], text: KIND_LABEL[c.kind] })];
  if (c.access.requiresAdmin) tags.push(h('span', { class: 'tag admin', text: 'admin' }));
  for (const cap of c.access.capabilities) tags.push(h('span', { class: 'tag cap', text: cap }));
  if (accessOf(c)[0] === 'everyone') tags.push(h('span', { class: 'tag', text: 'everyone' }));
  const rows = [
    ['Slot', c.slot == null ? '-' : c.slot],
    ['Source', h('span', null, c.source, src.plugin ? h('small', { text: ' when the plugin is enabled' }) : null)],
    ['Hooks', c.hooks.length ? c.hooks.join(', ') : 'none'],
    ['Order', String(c.order)],
  ];
  if (c.cardSize) rows.push(['Card size', c.cardSize]);
  if (c.icon) rows.push(['Icon', c.icon]);
  rows.push(['Class', h('a', { class: 'mono', href: SRC + encodeURI(c.file), target: '_blank', rel: 'noopener', title: c.file, text: c.className })]);
  return h('article', { class: 'card', id: 'c:' + c.id },
    h('div', { class: 'cid mono', text: c.id }),
    h('h3', { text: c.title }),
    c.description ? h('p', { text: c.description }) : null,
    h('div', { class: 'tags' }, tags),
    h('dl', null, rows.flatMap(([k, v]) => [h('dt', { text: k }), h('dd', null, v)])),
    c.render ? h('button', { class: 'chip mock-btn' + (c.render.error ? ' bad' : ''), type: 'button', onclick: () => openMock(c) },
      c.render.error ? 'Mockup: render failed' : 'Open mockup') : null);
}
function drawList() {
  const shown = C.filter(c => matches(c));
  countEl.textContent = shown.length === C.length ? 'All ' + nf.format(C.length) + ' contributions' : nf.format(shown.length) + ' of ' + nf.format(C.length) + ' contributions';
  list.replaceChildren(...(shown.length ? shown.map(card) : [h('p', { class: 'empty-msg', text: 'No contribution matches these filters.' })]));
}

/* ---------- slot grid ---------- */
function jump(id) {
  for (const k of ['kind', 'slot', 'source', 'access']) state[k].clear();
  state.q = '';
  drawFilters(); drawList();
  const el = document.getElementById('c:' + id);
  if (!el) return;
  document.querySelectorAll('.card.hit').forEach(e => e.classList.remove('hit'));
  el.classList.add('hit');
  el.scrollIntoView({ block: 'center' });
}
const slotsEl = document.getElementById('slots');
D.slots.forEach(s => {
  const items = s.contributions.map(id => byId.get(id)).filter(Boolean);
  slotsEl.append(h('div', { class: 'slot' + (items.length ? '' : ' empty') },
    h('h3', null, h('span', { class: 'mono', text: s.id }), h('span', { class: 'n', text: String(items.length) })),
    items.length
      ? h('ul', null, items.map(c => h('li', null, h('button', { type: 'button', title: c.id, onclick: () => jump(c.id) }, kindDot(c.kind), c.title))))
      : h('div', { class: 'none', text: 'No contributions' })));
});

/* ---------- mockups: an approximate drawing of a contribution's render tree, built when its dialog opens ---------- */
const SPACE = { NONE: 0, SMALL: 6, MEDIUM: 12, LARGE: 20 };
const ALIGN = { START: 'flex-start', CENTER: 'center', END: 'flex-end', STRETCH: 'stretch', SPACE_BETWEEN: 'stretch' };
const TONE = { DEFAULT: 'var(--ink)', PRIMARY: 'var(--s0)', SUCCESS: 'var(--s2)', WARNING: 'var(--s3)', ERROR: 'var(--s7)', MUTED: 'var(--muted)' };
/* fields that hold nested components; "action" and "submit" are UiActions and stay properties */
const KID_KEYS = ['children', 'actions', 'child', 'fallback', 'toolbar'];
const isNode = v => v != null && typeof v === 'object' && !Array.isArray(v) && typeof v.type === 'string';
const short = (s, n) => { s = String(s).replace(/\s+/g, ' ').trim(); return s.length > n ? s.slice(0, n - 1) + '…' : s; };
const toned = t => 'color:' + (TONE[t] || TONE.DEFAULT);
const gap = s => 'gap:' + (SPACE[s] ?? 12) + 'px';

/* icons are drawn as their name in a small box, images as a hatched placeholder */
function glyph(icon, cls) {
  if (icon == null) return null;
  const named = typeof icon === 'string' ? icon : icon.type === 'named' ? icon.name : null;
  const t = named != null ? String(named).toLowerCase() : icon.type === 'url' ? 'url' : icon.type === 'image' ? 'img' : String(icon.type || '?');
  return h('span', { class: 'm-g' + (cls ? ' ' + cls : ''), title: named || icon.url || icon.imageId || '', text: t });
}
const imgRef = (ref, cls) => h('span', { class: 'm-g m-img' + (ref.rounded === false ? '' : ' round') + (cls ? ' ' + cls : ''), title: 'image ' + (ref.imageId || ''), text: 'img' });
const lead = (p, cls) => p.image ? imgRef(p.image, cls) : glyph(p.icon, cls);
const actTitle = a => a ? a.type + ' ' + (a.actionId || a.pageId || a.url || a.name || a.entityType || '') : '';
const actHint = a => a == null ? null : h('span', { class: 'm-chev', title: actTitle(a), text: a.type === 'openMenu' ? '⋯' : a.type === 'openUrl' ? '↗' : '›' });
const val = (v, ph, multi) => v != null && v !== '' ? h('span', { class: 'm-v' + (multi ? ' multi' : ''), text: v }) : h('span', { class: 'm-ph2', text: ph || '' });
const box = (p, extra, ...kids) => h('div', { class: 'm-box' + (p.error ? ' err' : '') + (extra ? ' ' + extra : '') }, kids);
function field(p, kid, inner) {
  return h('div', { class: 'm-field' + (p.enabled === false ? ' m-off' : '') },
    h('div', { class: 'm-lbl' }, p.label, p.required ? h('span', { class: 'm-req', text: ' *' }) : null),
    inner,
    p.error ? h('div', { class: 'm-err', text: p.error }) : p.helper ? h('div', { class: 'm-help', text: p.helper }) : null,
    p.toolbar && p.toolbar.length ? h('div', { class: 'm-kbd' }, h('span', { class: 'm-kl', text: 'keyboard toolbar' }), p.toolbar.map(t => kid(t, 'toolbar'))) : null);
}
/* weights only stretch along a row; a column has no fixed height to share */
function flexKids(p, kid, row) {
  const ws = Array.isArray(p.weights) ? p.weights : null;
  return (p.children || []).map((ch, i) => {
    const el = kid(ch), w = ws ? ws[i] : null;
    if (typeof w === 'number') { if (row) el.style.flex = w > 0 ? w + ' 1 0' : 'none'; else el.style.flexGrow = w; }
    return el;
  });
}
const kidsOf = (p, kid) => (p.children || []).map(ch => kid(ch));
const footRow = (list, kid) => list && list.length ? h('div', { class: 'm-foot' }, list.map(a => kid(a, 'actions'))) : null;

/* one renderer per @SerialName: (props, child builder, raw node) -> element */
const R = {
  column: (p, kid) => h('div', { class: 'm-col' + (p.align === 'STRETCH' ? ' st' : ''),
    style: `${gap(p.spacing)};align-items:${ALIGN[p.align] || 'stretch'};justify-content:${p.align === 'SPACE_BETWEEN' ? 'space-between' : 'flex-start'}` }, flexKids(p, kid, false)),
  row: (p, kid) => h('div', { class: 'm-row',
    style: `${gap(p.spacing)};align-items:${p.align === 'SPACE_BETWEEN' ? 'center' : ALIGN[p.align] || 'center'};justify-content:${p.align === 'SPACE_BETWEEN' ? 'space-between' : 'flex-start'}` }, flexKids(p, kid, true)),
  grid: (p, kid) => h('div', { class: 'm-grid', style: `${gap(p.spacing)};grid-template-columns:repeat(${Math.max(1, Math.min(6, p.columns | 0 || 1))},minmax(0,1fr))` }, kidsOf(p, kid)),
  card: (p, kid) => {
    const tone = p.tone !== 'DEFAULT' && TONE[p.tone];
    return h('div', { class: 'm-surf', style: tone ? `background:color-mix(in srgb, ${tone} 8%, var(--raised));border-color:color-mix(in srgb, ${tone} 45%, transparent)` : null },
      p.title || p.subtitle || p.icon || p.image ? h('div', { class: 'm-head' }, lead(p),
        h('div', null, p.title ? h('div', { class: 'm-t', text: p.title }) : null, p.subtitle ? h('div', { class: 'm-s', text: p.subtitle }) : null)) : null,
      kidsOf(p, kid), footRow(p.actions, kid));
  },
  section: (p, kid) => h('div', { class: 'm-sec' + (p.collapsed ? ' collapsed' : '') },
    h('div', { class: 'm-sec-h' }, h('span', { text: p.title }), p.collapsible ? h('span', { class: 'm-chev', text: p.collapsed ? '▸' : '▾' }) : null),
    h('div', { class: 'm-sec-b' }, kidsOf(p, kid))),
  form: (p, kid) => h('div', { class: 'm-col st', style: 'gap:12px' }, kidsOf(p, kid),
    h('div', { class: 'm-foot' }, (p.actions || []).map(a => kid(a, 'actions')),
      p.cancelLabel ? h('span', { class: 'm-btn TEXT', text: p.cancelLabel }) : null,
      h('span', { class: 'm-btn PRIMARY', title: actTitle(p.submit), text: p.submitLabel || 'Submit' }))),
  text: p => h('div', { class: `m-tx ${p.style || ''} ${p.emphasis || ''}`, style: p.tone !== 'DEFAULT' ? toned(p.tone) : null, text: p.text }),
  icon: p => { const g = glyph(p.icon || '?'); g.style.cssText = toned(p.tone === 'DEFAULT' ? 'PRIMARY' : p.tone); return g; },
  image: p => h('div', { class: 'm-image' + (p.rounded ? ' round' : ''), text: p.url ? 'image · ' + short(p.url.replace(/^https?:\/\//, ''), 40) : 'image' }),
  badge: p => h('span', { class: 'm-badge', style: toned(p.tone === 'DEFAULT' ? 'MUTED' : p.tone) }, glyph(p.icon, 'sm'), p.text),
  stat: p => h('div', { class: 'm-stat' }, h('div', { class: 'm-sl' }, glyph(p.icon, 'sm'), p.label),
    h('div', { class: 'm-sv', style: p.tone !== 'DEFAULT' ? toned(p.tone) : null }, p.value, p.unit ? h('span', { class: 'm-su', text: p.unit }) : null)),
  progress: p => {
    const v = typeof p.value === 'number' ? Math.max(0, Math.min(1, p.value)) : null;
    return h('div', { class: 'm-prog' },
      p.label || v != null ? h('div', { style: 'display:flex;justify-content:space-between;gap:8px' }, h('span', { text: p.label || '' }), v != null ? h('span', { text: Math.round(v * 100) + '%' }) : null) : null,
      h('div', { class: 'm-tr' + (v == null ? ' ind' : '') }, h('i', { style: v == null ? null : 'width:' + (v * 100).toFixed(1) + '%' })));
  },
  tile: p => h('div', { class: 'm-tile', style: toned(p.tone === 'DEFAULT' ? 'PRIMARY' : p.tone) }, glyph(p.icon),
    h('div', { class: 'm-tc' }, h('div', { class: 'm-t', text: p.title }), p.subtitle ? h('div', { class: 'm-s', text: p.subtitle }) : null), actHint(p.action)),
  button: p => h('span', { class: `m-btn ${p.style || ''}` + (p.enabled === false ? ' m-off' : ''), title: actTitle(p.action) },
    glyph(p.icon, 'sm'), p.label, p.action && p.action.type === 'openMenu' ? ' ▾' : null),
  listItem: p => h('div', { class: 'm-li' }, lead(p),
    h('div', { class: 'm-lc' }, h('div', { class: 'm-t', text: p.title }), p.subtitle ? h('div', { class: 'm-s', text: p.subtitle }) : null),
    p.trailing ? h('span', { class: 'm-tr', text: p.trailing }) : null, actHint(p.action)),
  table: p => {
    const rows = p.rows || [], act = rows.some(r => r && r.action);
    const n = Math.max((p.columns || []).length, ...rows.map(r => ((r && r.cells) || []).length));
    const cols = Array.from({ length: n }, (_, i) => (p.columns || [])[i] ?? '');
    return h('div', { class: 'm-tbl' }, h('table', null,
      h('thead', null, h('tr', null, cols.map(t => h('th', { text: t })), act ? h('th') : null)),
      h('tbody', null, rows.length
        ? rows.map(r => h('tr', null, cols.map((_, i) => h('td', { text: ((r && r.cells) || [])[i] ?? '' })), act ? h('td', { class: 'act', text: r && r.action ? '›' : '' }) : null))
        : h('tr', null, h('td', { class: 'none', colspan: Math.max(1, n), text: 'No rows' })))));
  },
  spacer: p => h('div', { style: `flex:none;width:${SPACE[p.size] ?? 12}px;height:${SPACE[p.size] ?? 12}px` }),
  divider: () => h('div', { class: 'm-div' }),
  fallback: p => h('div', { class: 'm-ph fb' }, h('span', { class: 'm-phl', text: 'fallback' }), h('span', { text: p.text || 'This content needs a newer app version.' })),
  emptyState: (p, kid) => h('div', { class: 'm-empty' }, glyph(p.icon, 'big'), h('div', { class: 'm-t', text: p.title }),
    p.description ? h('div', { class: 'm-s', text: p.description }) : null, footRow(p.actions, kid)),
  log: p => {
    const lines = (p.lines || []).slice(-Math.max(1, p.maxLines || 500));
    return h('div', { class: 'm-log' }, h('div', null, lines.length ? lines.map(l => h('div', { text: l })) : h('div', { style: 'opacity:.6', text: '(no lines yet)' })));
  },
  live: (p, kid) => h('div', { class: 'm-ph live' }, h('span', { class: 'm-phl', text: 'live · ' + p.key }), p.child ? kid(p.child, 'child') : null),
  native: (p, kid) => {
    const ps = Object.entries(p.params || {});
    return h('div', { class: 'm-ph native' }, h('span', { class: 'm-phl', text: 'native portal · ' + p.name }),
      ps.length ? h('span', { class: 'mono', text: ps.map(([k, v]) => k + ' = ' + v).join(', ') }) : null,
      p.fallback ? kid(p.fallback, 'fallback') : null);
  },
  textField: (p, kid) => field(p, kid, box(p, p.multiline || p.kind === 'MULTILINE_URLS' ? 'multi' : '',
    p.secret ? val(null, '•••••••• (unchanged)') : val(p.value, p.placeholder, true),
    p.kind && p.kind !== 'TEXT' ? h('span', { class: 'm-aux', text: p.kind.toLowerCase().replace(/_/g, ' ') }) : null)),
  fileField: (p, kid) => field(p, kid, box(p, '', h('span', { class: 'm-pick', text: 'Choose file' }),
    p.secret ? val(null, '•••• (unchanged)') : val(p.value != null ? short(p.value, 60) : null, 'No file'),
    h('span', { class: 'm-aux', text: [(p.accept || []).join(' '), p.binary ? 'binary' : '', p.allowPaste ? 'paste' : ''].filter(Boolean).join(' · ') }))),
  searchField: (p, kid) => field(p, kid, box(p, '', glyph('SEARCH', 'sm'), p.value ? lead(p.value, 'sm') : null,
    val(p.value ? p.value.label : null, p.placeholder || 'Type ' + p.minChars + '+ characters'))),
  numberField: (p, kid) => field(p, kid, box(p, '', val(p.value != null ? nf.format(p.value) : null, ''),
    p.min != null || p.max != null ? h('span', { class: 'm-aux', text: (p.min != null ? nf.format(p.min) : '') + '–' + (p.max != null ? nf.format(p.max) : '') }) : null,
    h('span', { class: 'm-aux', text: '−  +' }))),
  switch: p => h('div', { class: 'm-sw' + (p.enabled === false ? ' m-off' : '') },
    h('div', { class: 'm-swc' }, h('div', { text: p.label }), p.helper ? h('div', { class: 'm-help', text: p.helper }) : null),
    h('span', { class: 'm-trk' + (p.value ? ' on' : '') })),
  select: (p, kid) => {
    const o = (p.options || []).find(x => x && x.value === p.value);
    return field(p, kid, box(p, '', o ? lead(o, 'sm') : null, val(o ? o.label : p.value, 'Select…'), h('span', { class: 'm-aux', text: (p.options || []).length + ' options ▾' })));
  },
  unknown: (p, kid, n) => h('div', { class: 'm-ph unk' }, h('span', { class: 'm-phl', text: isNode(n) ? n.type : 'not a component' }),
    KID_KEYS.flatMap(key => { const v = isNode(n) ? n[key] : null; return isNode(v) ? [kid(v, key)] : Array.isArray(v) ? v.filter(isNode).map(x => kid(x, key)) : []; })),
};

/* walks the tree once: every node gets an entry (preorder index = data-k on its element) for the layer tree */
function draw(c) {
  const E = [], r = c.render || {};
  const add = (n, parent, role) => {
    const k = E.length, e = { n, parent, role, kids: [], el: null };
    E.push(e);
    if (parent != null) E[parent].kids.push(k);
    const type = isNode(n) ? n.type : '?';
    const known = type !== 'unknown' && Object.hasOwn(R, type);
    let el;
    try { el = (known ? R[type] : R.unknown)(n, (m, rl) => add(m, k, rl || 'children'), n); }
    catch (err) { el = h('div', { class: 'm-ph unk' }, h('span', { class: 'm-phl', text: type }), h('span', { text: 'Could not draw: ' + err.message })); }
    el.classList.add('m-n');
    el.dataset.k = k;
    return (e.el = el);
  };
  const bar = h('div', { class: 'm-bar' }, h('span', { class: 'm-back', text: c.kind === 'PAGE' ? '←' : '' }), h('span', { class: 'm-ttl', text: c.title }));
  const toolbar = [];
  if (!r.error && Array.isArray(r.toolbar)) for (const t of r.toolbar) { toolbar.push(E.length); bar.append(add(t, null, 'toolbar')); }
  let screen, tree = null;
  if (r.error) screen = h('div', { class: 'm-msg bad', text: r.error });
  else if (r.tree != null) { tree = E.length; screen = h('div', { class: 'm-screen' }, add(r.tree, null, 'tree')); }
  else screen = h('div', { class: 'm-msg', text: 'No render' });
  return { E, toolbar, tree, dev: h('div', { class: 'm-dev' + (outlines ? ' outl' : '') }, bar, screen) };
}

function layerLabel(n) {
  if (!isNode(n)) return '';
  switch (n.type) {
    case 'column': case 'row': case 'grid': return (n.children || []).length + ' children';
    case 'icon': return n.icon ? String(n.icon.name || n.icon.type || '') : '';
    case 'image': return short(n.url || n.imageId || '', 48);
    case 'stat': return short([n.label, n.value].filter(v => v != null).join(': '), 48);
    case 'progress': return n.label || (typeof n.value === 'number' ? Math.round(n.value * 100) + '%' : 'indeterminate');
    case 'table': return (n.columns || []).length + ' columns · ' + (n.rows || []).length + ' rows';
    case 'log': return (n.lines || []).length + ' lines';
    case 'spacer': return (n.size || 'MEDIUM').toLowerCase();
    case 'form': return n.id || '';
  }
  const s = [n.text, n.title, n.label, n.key, n.name].find(v => typeof v === 'string');
  return s ? short(s, 48) : '';
}

const dlg = document.getElementById('mock'), stage = document.getElementById('mock-stage');
const layersEl = document.getElementById('mock-layers'), insp = document.getElementById('mock-insp'), outlBtn = document.getElementById('mock-outl');
let outlines = false, cur = null;
function layer(k, depth) {
  const e = cur.E[k];
  const car = h('span', { class: 'car', text: e.kids.length ? '▾' : '' });
  e.row = h('button', { class: 'ly-row', type: 'button', style: 'padding-left:' + (6 + depth * 14) + 'px',
      onclick: () => select(k, false), onmouseenter: () => hover(k), onmouseleave: () => hover(null) },
    car, h('span', { class: 'ty', text: isNode(e.n) ? e.n.type : '?' }), h('span', { class: 'lb', text: layerLabel(e.n) }),
    e.parent != null && e.role !== 'children' ? h('span', { class: 'rl', text: e.role }) : null);
  e.wrap = h('div', { class: 'ly' }, e.row, e.kids.length ? h('div', { class: 'ly-kids' }, e.kids.map(j => layer(j, depth + 1))) : null);
  e.unfold = closed => { e.wrap.classList.toggle('closed', closed); car.textContent = closed ? '▸' : '▾'; };
  if (e.kids.length) car.addEventListener('click', ev => { ev.stopPropagation(); e.unfold(!e.wrap.classList.contains('closed')); });
  return e.wrap;
}
function hover(k) {
  if (cur.hov != null) { const e = cur.E[cur.hov]; e.el.classList.remove('m-hov'); e.row.classList.remove('hov'); }
  cur.hov = k;
  if (k != null) { const e = cur.E[k]; e.el.classList.add('m-hov'); e.row.classList.add('hov'); }
}
function select(k, fromMockup) {
  if (cur.sel != null) { const e = cur.E[cur.sel]; e.el.classList.remove('m-sel'); e.row.classList.remove('sel'); }
  cur.sel = k;
  const e = cur.E[k];
  e.el.classList.add('m-sel'); e.row.classList.add('sel');
  for (let p = e.parent; p != null; p = cur.E[p].parent) cur.E[p].unfold(false);
  (fromMockup ? e.row : e.el).scrollIntoView({ block: 'nearest' });
  inspect(k);
}
/* the node's own JSON: nested components are listed by count, they have their own layers */
function inspect(k) {
  if (k == null) {
    insp.replaceChildren(h('h4', { text: 'Properties' }), h('p', { class: 'none', text: cur.E.length ? 'Select a layer or click an element in the mockup.' : 'Nothing to inspect.' }));
    return;
  }
  const e = cur.E[k], n = e.n, own = {}, nested = [], path = [];
  if (isNode(n)) for (const key of Object.keys(n)) {
    const v = n[key];
    if (KID_KEYS.includes(key) && (isNode(v) || Array.isArray(v) && v.length && v.every(isNode))) nested.push(key + ': ' + (Array.isArray(v) ? v.length : 1));
    else own[key] = v;
  }
  for (let p = k; p != null; p = cur.E[p].parent) path.unshift(isNode(cur.E[p].n) ? cur.E[p].n.type : '?');
  insp.replaceChildren(h('div', null,
    h('h4', { text: 'Properties' }),
    h('div', { class: 'path mono', text: path.join(' › ') }),
    h('pre', { class: 'mono', text: JSON.stringify(isNode(n) ? own : n, null, 2) }),
    nested.length ? h('p', { class: 'none', text: 'Nested components: ' + nested.join(', ') }) : null));
}
function openMock(c) {
  const m = draw(c);
  cur = { E: m.E, dev: m.dev, sel: null, hov: null };
  document.getElementById('mock-id').textContent = c.id;
  document.getElementById('mock-title').textContent = c.title;
  const where = KIND_LABEL[c.kind] + (c.slot ? ' in ' + c.slot : '');
  stage.replaceChildren(m.dev, h('p', { class: 'mk-note', text: where + '. Drawn approximately: clients render these nodes with their own widgets.' }));
  const groups = [];
  if (m.toolbar.length) groups.push(h('div', { class: 'mk-group', text: 'App bar' }), m.toolbar.map(k => layer(k, 0)));
  if (m.tree != null) groups.push(h('div', { class: 'mk-group', text: 'Content' }), layer(m.tree, 0));
  layersEl.replaceChildren(...(groups.length ? groups.flat() : [h('div', { class: 'mk-group', text: 'No layers' })]));
  const at = ev => { const t = ev.target.closest('[data-k]'); return t && m.dev.contains(t) ? +t.dataset.k : null; };
  m.dev.addEventListener('click', ev => { const k = at(ev); if (k != null) select(k, true); });
  m.dev.addEventListener('mouseover', ev => hover(at(ev)));
  m.dev.addEventListener('mouseleave', () => hover(null));
  inspect(null);
  if (!dlg.open) dlg.showModal();
  stage.scrollTop = 0; layersEl.scrollTop = 0;
}
outlBtn.addEventListener('click', () => {
  outlines = !outlines;
  outlBtn.setAttribute('aria-pressed', String(outlines));
  if (cur) cur.dev.classList.toggle('outl', outlines);
});
document.getElementById('mock-close').addEventListener('click', () => dlg.close());
dlg.addEventListener('click', ev => { if (ev.target === dlg) dlg.close(); });

/* theme: follows the system unless the reader picks one */
const themeBtn = document.getElementById('theme'), MODES = ['auto', 'light', 'dark'];
let mode = 'auto';
function setTheme(mo, save) {
  mode = mo;
  if (mo === 'auto') document.documentElement.removeAttribute('data-theme'); else document.documentElement.setAttribute('data-theme', mo);
  themeBtn.textContent = 'Theme: ' + mo;
  if (save) try { localStorage.setItem('ui-catalogue-theme', mo); } catch (e) {}
}
themeBtn.addEventListener('click', () => setTheme(MODES[(MODES.indexOf(mode) + 1) % 3], true));
const params = new URLSearchParams(location.hash.slice(1));
let saved = null; try { saved = localStorage.getItem('ui-catalogue-theme'); } catch (e) {}
setTheme(MODES.includes(params.get('theme')) ? params.get('theme') : MODES.includes(saved) ? saved : 'auto');

const foot = document.getElementById('foot');
const plugins = D.sources.filter(s => s.plugin).map(s => s.id);
[
  'Kinds: a slot entry is shown inside a slot of an existing screen, a page is opened by other contributions, a home card sits on the home screen.',
  `Access: admin means the contribution is only offered to administrators, capability lists the capabilities a user needs, no restriction means everyone.${plugins.length ? ' Contributions of ' + plugins.join(', ') + ' are registered when the plugin is enabled.' : ''} External plugin JARs register under their own plugin id.`,
  'Mockups draw the component tree a contribution rendered when the data was generated, with this page\'s colours instead of a client\'s widgets. Sizes, icons and images are placeholders.',
  'Regenerate with ./gradlew generateDocs and python3 scripts/ui-catalogue.py.',
].forEach(t => foot.append(h('p', { text: t })));

drawFilters(); drawList();
})();
</script>
</body>
</html>
'''


if __name__ == "__main__":
    sys.exit(main())
