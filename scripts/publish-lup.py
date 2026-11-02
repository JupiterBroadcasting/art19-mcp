#!/usr/bin/env python3
"""LUP episode publisher (showfactory) — v3, human-in-the-loop.

Design rule: **never assume** how a region should be marked. Embedded vs
dynamic is new territory, so the publisher presents a plan and stops for
confirmation before it writes anything.

Two ways to run:
  --dry-run     print the plan, write nothing (default if stdin isn't a tty)
  --confirm     proceed with the plan as printed
  interactive   asks y/N per step when stdin is a tty

Sponsor policy is explicit. A sponsor not in POLICY is never guessed at —
the run aborts with the question spelled out.
"""
import argparse
import csv
import os
import json
import re
import sys
import time
import urllib.request

API = "https://art19.com"
MCP = "http://127.0.0.1:3007/mcp"
CRED = os.environ.get("ART19_CRED_FILE", "/var/lib/hermes/credentials/art19.env")
OUT = "/home/wes/opencode/publish-log.jsonl"

# ── Sponsor policy: the ONLY place marking decisions live ────────────────
# "skip"     -> baked in, not ART19-managed, no marker
# "embedded" -> ART19-managed baked read -> EmbeddedAdPoint bound to a LiveReadAd
# Anything else -> the run ABORTS and asks. Never guess.
POLICY = {
    "nebula": "skip",  # JB bakes these; not ART19 inventory. Documented exception.
    "hostinger": "embedded",
}

# ── Config (fill per episode) ────────────────────────────────────────────
EPISODE = "689"
TITLE = "FILL ME"
DESC = "FILL ME"
FLAC = "FILL ME"
ADS_CSV = f"/home/wes/Downloads/workdir/Linux Unplugged {EPISODE} (Ads) Ads.csv"

# ── Slot standards (mirror `def ^:const default-*` in art19_mcp.bb) ─────
PRE_DUR, PRE_COUNT = 180, 1
MID_DUR_SINGLE, MID_DUR_MULTI, MID_COUNT = 120, 90, 3
POST_DUR, POST_COUNT = 180, 2
EMBEDDED_COUNT = 1

DYNAMIC_MAX_WIDTH = 5.0  # seconds — wider regions are reads, not break points

RID = [0]
SID = [None]


def load_creds():
    c = {}
    with open(CRED) as fh:
        for line in fh:
            if "=" in line and not line.startswith("#"):
                k, v = line.strip().split("=", 1)
                c[k] = v
    return 'Token token="%s", credential="%s"' % (
        c.get("ART19_API_TOKEN"), c.get("ART19_API_CREDENTIAL")
    )


_AUTH = []


def auth():
    """Lazily load the ART19 auth header (keeps the module importable in tests)."""
    if not _AUTH:
        _AUTH.append(load_creds())
    return _AUTH[0]


def api_get(path, params=None):
    url = API + path + ("?" + "&".join(f"{k}={v}" for k, v in params.items()) if params else "")
    req = urllib.request.Request(
        url, headers={"Accept": "application/vnd.api+json", "Authorization": auth()}
    )
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.loads(r.read().decode())


def rpc(method, params=None):
    RID[0] += 1
    body = json.dumps({"jsonrpc": "2.0", "id": RID[0], "method": method, "params": params or {}}).encode()
    headers = {"Content-Type": "application/json", "Accept": "application/json, text/event-stream"}
    if SID[0]:
        headers["Mcp-Session-Id"] = SID[0]
    req = urllib.request.Request(MCP, data=body, headers=headers)
    with urllib.request.urlopen(req, timeout=120) as r:
        return r.read().decode(), dict(r.headers)


def init():
    raw, headers = rpc("initialize", {"protocolVersion": "2025-03-26", "capabilities": {},
                                      "clientInfo": {"name": "publisher", "version": "3"}})
    SID[0] = headers.get("Mcp-Session-Id") or headers.get("mcp-session-id")
    rpc("notifications/initialized", {})


def tool(name, args, retries=2):
    for attempt in range(retries + 1):
        try:
            raw, _ = rpc("tools/call", {"name": name, "arguments": args})
            d = json.loads(raw)
            if "error" in d and d["error"]:
                raise RuntimeError(str(d["error"])[:300])
            return json.loads(d["result"]["content"][0]["text"])
        except Exception as ex:
            if "session" in str(ex).lower() and attempt < retries:
                init()
                continue
            raise


def log(**kw):
    print(json.dumps(kw), flush=True)
    open(OUT, "a").write(json.dumps(kw) + "\n")


def read_regions(path):
    rows = []
    with open(path, newline="") as fh:
        for r in csv.DictReader(fh):
            name = (r.get("name") or "").strip()
            start, end = float(r["start_seconds"]), float(r["end_seconds"])
            rows.append({"name": name, "start": start, "end": end,
                         "width": round(end - start, 2),
                         "tc": r.get("start_timecode", "")})
    return rows


def list_livereads():
    """Only LiveReadAd records are bindable to EmbeddedAdPoints."""
    out, page = [], 1
    while page <= 30:
        d = api_get("/advertisements", {"page[number]": page, "page[size]": 100})
        rows = (d or {}).get("data") or []
        if not rows:
            break
        out.extend(rows)
        if not ((d or {}).get("links") or {}).get("next"):
            break
        page += 1
    return [a for a in out if a.get("attributes", {}).get("type") == "LiveReadAd"]


def norm(s):
    return re.sub(r"[^a-z0-9]", "", (s or "").lower())


def match_ad(region_name, livereads, used):
    """Return (ad_id, title, how) or (None, reason, detail).

    Ranking matters: sponsors reuse names across weeks ("Hostinger"), so a
    naive first-substring match binds this week's read to last week's ad and
    serves the wrong audio. Prefer, in order:
      1. records that actually have audio (media_present)
      2. titles that START with the sponsor name ("Hostinger 11/1 Ad" beats
         "688: Eyes on Deck Hostinger Live Read")
      3. most recently created
    """
    key = norm(region_name)
    if not key:
        return None, "no-name", None

    cands = []
    for a in livereads:
        at = a["attributes"]
        t = norm(at.get("title"))
        if not t or not (key in t or t in key):
            continue
        if a["id"] in used:
            continue
        cands.append((a, t))

    if not cands:
        # No title match. Do NOT fall back to "the only unused read" — that
        # would bind an unrelated sponsor to whatever ad happens to be spare,
        # which is exactly the silent-wrong-audio failure we're guarding
        # against. Surface it and let a human decide.
        spare = [a for a in livereads
                 if a["id"] not in used and a["attributes"].get("media_present")]
        if spare:
            return None, "no-title-match", [a["attributes"].get("title") for a in spare]
        return None, "no-liveread", None

    def score(pair):
        a, t = pair
        at = a["attributes"]
        return (
            1 if at.get("media_present") else 0,          # audio first
            1 if t.startswith(key) else 0,                # sponsor-name prefix
            at.get("created_at") or "",                   # newest
        )

    cands.sort(key=score, reverse=True)
    best, _ = cands[0]
    how = "title-match"
    if not best["attributes"].get("media_present"):
        how = "title-match-NO-AUDIO"
    elif not score(cands[0])[1]:
        how = "title-match-substring"
    return best["id"], best["attributes"].get("title"), how


def build_plan(rows, livereads):
    """-> (plan, questions). questions non-empty means: DO NOT PUBLISH."""
    plan, questions, used = [], [], []
    unknown_sponsors = set()

    for r in rows:
        name = r["name"]
        key = name.lower().strip()
        width = r["width"]
        if width <= DYNAMIC_MAX_WIDTH:
            # A break point, not a sponsor. Narrow regions are always dynamic
            # regardless of name; only wide (read-shaped) regions need POLICY.
            plan.append({**r, "action": "dynamic",
                         "detail": f"AdInsertionPoint midroll @ {int(r['start'])}s"})
            continue
        action = POLICY.get(key)
        if action is None:
            unknown_sponsors.add(name)
            plan.append({**r, "action": "UNKNOWN",
                         "detail": "sponsor not in POLICY — needs a decision"})
            continue
        if action == "skip":
            plan.append({**r, "action": "skip", "detail": "policy: never marked"})
            continue
        # embedded — never skip; abort and ask if it can't be resolved
        ad_id, ad_title, how = match_ad(name, livereads, used)
        if ad_id:
            used.append(ad_id)
            plan.append({**r, "action": "embedded", "ad_id": ad_id, "ad_title": ad_title,
                         "detail": f"EmbeddedAdPoint {r['start']:.2f}-{r['end']:.2f} "
                                   f"bound to {ad_title!r} [{how}]"})
            if how.endswith("NO-AUDIO"):
                questions.append(
                    f"  '{name}' matched {ad_title!r} but that record has NO AUDIO "
                    f"(media_present: false). Upload the read under series -> Content -> "
                    f"Advertisements before publishing.")
        else:
            reason, detail = ad_title, how
            plan.append({**r, "action": "UNRESOLVED",
                         "detail": f"no bindable LiveReadAd ({reason}: {detail})"})
            questions.append(
                f"  '{name}' ({r['start']:.2f}-{r['end']:.2f}s) is a baked sponsor read "
                f"and MUST be marked (never skipped). No bindable LiveReadAd ({reason}: {detail}).\n"
                f"    Upload it under series -> Content -> Advertisements (type Live Read), "
                f"then re-run.")

    if unknown_sponsors:
        detail = "\n".join(
            f"    - {s} ({next(x['width'] for x in rows if x['name'] == s)}s wide)"
            for s in sorted(unknown_sponsors)
        )
        questions.insert(
            0,
            "NEW SPONSOR(S) not in POLICY — decide how to mark before publishing:\n"
            + detail
            + "\n    Add each to POLICY in publish-lup.py as 'skip' (baked, not "
            "ART19) or 'embedded' (ART19-managed).",
        )

    n_dyn = sum(1 for p in plan if p["action"] == "dynamic")
    plan.insert(0, {"action": "pre", "detail": f"AdInsertionPoint pre-roll {PRE_DUR}s x {PRE_COUNT} @ 0"})
    plan.append({"action": "post", "detail": f"AdInsertionPoint post-roll {POST_DUR}s x {POST_COUNT}"})
    plan[1:1] = [{"action": "dynamic-summary",
                  "detail": f"{n_dyn} midroll(s) at "
                            f"{MID_DUR_SINGLE if n_dyn==1 else MID_DUR_MULTI}s x {MID_COUNT}"}]
    return plan, questions


def print_plan(title, plan, questions):
    print(f"\n{'='*66}\nPUBLISH PLAN — {title}\n{'='*66}")
    for p in plan:
        tag = {"pre": "PRE ", "post": "POST", "dynamic-summary": "MID ",
               "dynamic": "MID ", "skip": "SKIP", "embedded": "EMBED",
               "UNRESOLVED": "!!!!"}.get(p["action"], "    ")
        print(f"  [{tag}] {p['detail']}")
    if questions:
        print(f"\n{'!'*66}\nQUESTIONS — resolve these before publishing\n{'!'*66}")
        print("\n\n".join(questions))
    print(f"{'='*66}\n")


def ask(prompt):
    if not sys.stdin.isatty():
        return False
    return input(f"{prompt} [y/N] ").strip().lower() in ("y", "yes")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--confirm", action="store_true")
    ap.add_argument("--episode")
    ap.add_argument("--title")
    ap.add_argument("--desc")
    ap.add_argument("--flac")
    ap.add_argument("--csv")
    a = ap.parse_args()

    global EPISODE, TITLE, DESC, FLAC, ADS_CSV
    if a.episode:
        EPISODE = a.episode
    if a.title:
        TITLE = a.title
    if a.desc:
        DESC = a.desc
    if a.flac:
        FLAC = a.flac
    if a.csv:
        ADS_CSV = a.csv

    if TITLE == "FILL ME" or FLAC == "FILL ME":
        print("Set EPISODE/TITLE/DESC/FLAC (or pass --title/--flac).")
        sys.exit(1)

    init()
    full_title = f"{EPISODE}: {TITLE}"
    rows = read_regions(ADS_CSV)
    livereads = list_livereads()
    plan, questions = build_plan(rows, livereads)
    print_plan(full_title, plan, questions)

    if questions:
        log(step="blocked", reason="unresolved questions", n=len(questions))
        print("NOT PUBLISHING — unanswered questions above.\n")
        sys.exit(2)

    if a.dry_run:
        print("dry run — nothing written.\n")
        sys.exit(0)
    if not a.confirm and not ask("Publish as planned?"):
        print("aborted.\n")
        sys.exit(3)

    # ---- from here on it writes ----
    ep = tool("create_episode", {"series_slug": "lu", "title": full_title,
                                 "description": DESC, "itunes_type": "full"})
    ep_id = ep["data"]["id"]
    ver = tool("create_episode_version", {"episode_id": ep_id, "source_url": FLAC,
                                          "status_on_completion": "active"})
    ver_id = ver["data"]["id"]
    log(step="created", ep=ep_id, ver=ver_id)
    print(f"\n  episode {ep_id}  version {ver_id}")

    def rule(mid, **kw):
        tool("create_marker_point_content_rule",
             {"marker_point_id": mid, "priority": 1, **kw})

    m = tool("create_marker_point", {"episode_version_id": ver_id, "position_type": 0,
                                     "start_position": 0, "type": "AdInsertionPoint",
                                     "maximum_content_duration": PRE_DUR,
                                     "maximum_content_count": PRE_COUNT})["data"]["id"]
    rule(m, content_type="Campaign")

    mids = [p for p in plan if p["action"] == "dynamic"]
    dur = MID_DUR_SINGLE if len(mids) == 1 else MID_DUR_MULTI
    for p in mids:
        mid = tool("create_marker_point", {
            "episode_version_id": ver_id, "position_type": 1,
            "start_position": int(p["start"]), "type": "AdInsertionPoint",
            "maximum_content_duration": dur, "maximum_content_count": MID_COUNT})["data"]["id"]
        rule(mid, content_type="Campaign")
        print(f"  midroll @{int(p['start'])}s ({dur}s x {MID_COUNT})")

    for p in [x for x in plan if x["action"] == "embedded"]:
        e = tool("create_marker_point", {
            "episode_version_id": ver_id, "position_type": None,  # MUST be null
            "type": "EmbeddedAdPoint", "start_position": p["start"],
            "end_position": p["end"],
            "maximum_content_duration": max(60, int(p["width"])),
            "maximum_content_count": EMBEDDED_COUNT})["data"]["id"]
        rule(e, content_type="Advertisement", content_id=p["ad_id"],
             content_type_target="advertisements")
        print(f"  embedded {p['name']} {p['start']:.2f}-{p['end']:.2f} -> {p['ad_title']!r}")

    post = tool("create_marker_point", {"episode_version_id": ver_id, "position_type": 2,
                                        "type": "AdInsertionPoint",
                                        "maximum_content_duration": POST_DUR,
                                        "maximum_content_count": POST_COUNT})["data"]["id"]
    rule(post, content_type="Campaign")

    tool("update_episode_version", {"version_id": ver_id, "processing_status": "submitted",
                                    "status_on_completion": "active"})
    final = None
    for i in range(40):
        g = tool("get_episode_version", {"version_id": ver_id})
        st = g["data"]["attributes"]["processing_status"]
        print(f"  poll {i}: {st}", flush=True)
        if st in ("active", "inactive", "processing_failed", "validation_failed"):
            final = st
            break
        time.sleep(15)
    ve = tool("get_episode_version", {"version_id": ver_id})["data"]["attributes"].get("validation_errors")
    log(step="processing", result=final, validation_errors=ve)
    if final != "active":
        print(f"\nPROCESSING {final}: {ve}\nEpisode created but NOT published. "
              f"Do not leave it half-done — delete {ep_id} or fix and retry.\n")
        sys.exit(4)

    tool("publish_episode", {"episode_id": ep_id, "release_immediately": True})
    fi = tool("list_feed_items", {"episode_id": ep_id})
    item = fi["feed_items"][0]
    assert item["title"] == full_title
    log(step="done", enclosure=item["enclosure_url"], released_at=item["released_at"])
    print(f"\nLIVE: {item['enclosure_url']}\n")


if __name__ == "__main__":
    main()