#!/usr/bin/env python3
"""Regression tests for the sponsor -> LiveReadAd matcher.

The bug this locks down (ep 691): a naive first-substring match bound the
episode to the OLDEST Hostinger ad ("688: Eyes on Deck Hostinger Live Read",
63.5s) instead of that week's read ("Hostinger 11/1 Ad", 72.48s). Serving
the wrong ad audio is silent — it processes fine and sounds wrong.

Run: python3 tests/test_matcher.py
"""
import importlib.util
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location("publish_lup", ROOT / "scripts" / "publish-lup.py")
pl = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pl)


def ad(id_, title, media=True, created="2026-01-01T00:00:00Z"):
    return {"id": id_, "attributes": {"title": title, "media_present": media,
                                      "created_at": created, "type": "LiveReadAd"}}


FAILS = []


def check(name, got, want):
    if got != want:
        FAILS.append(f"{name}: got {got!r}, want {want!r}")
        print(f"  FAIL {name}: got {got!r} want {want!r}")
    else:
        print(f"  ok   {name}")


# ── 1. THE 691 BUG: two Hostinger ads, must pick this week's ────────────────
roster = [
    ad("688aaa", "688: Eyes on Deck Hostinger Live Read", True, "2026-10-11T00:00:00Z"),
    ad("691bbb", "Hostinger 11/1 Ad", True, "2026-11-01T00:00:00Z"),
]
got_id, got_title, how = pl.match_ad("Hostinger", roster, [])
check("prefers sponsor-name prefix / newest over older substring",
      got_id, "691bbb")

# ── 2. media_present outranks recency ──────────────────────────────────────
roster = [
    ad("old_ok", "Hostinger 11/1 Ad", True, "2026-10-01T00:00:00Z"),
    ad("new_empty", "Hostinger 11/8 Ad", False, "2026-11-08T00:00:00Z"),
]
got_id, _, _ = pl.match_ad("Hostinger", roster, [])
check("has-audio beats newer-but-empty", got_id, "old_ok")

# ── 3. used ads are excluded (two breaks, one sponsor) ─────────────────────
roster = [
    ad("a1", "Hostinger 11/1 Ad", True, "2026-11-01T00:00:00Z"),
    ad("a2", "Hostinger 11/1 Ad B", True, "2026-10-01T00:00:00Z"),
]
got_id, _, _ = pl.match_ad("Hostinger", roster, ["a1"])
check("already-used ad is skipped", got_id, "a2")

# ── 4. no candidate at all -> must not silently pick anything ──────────────
got_id, reason, detail = pl.match_ad("AcmeCorp", [ad("x", "Hostinger 11/1 Ad")], [])
check("unknown sponsor returns None", got_id, None)
check("unknown sponsor surfaces candidates instead of guessing", reason, "no-title-match")
check("unknown sponsor lists what was available", detail, ["Hostinger 11/1 Ad"])

# ── 4b. nothing spare at all ────────────────────────────────────────────────
got_id, reason, _ = pl.match_ad("AcmeCorp", [], [])
check("empty roster -> no-liveread", reason, "no-liveread")

# ── 5. ambiguity surfaces instead of guessing ──────────────────────────────
roster = [
    ad("m1", "Some Other Ad One", True, "2026-01-01T00:00:00Z"),
    ad("m2", "Some Other Ad Two", True, "2026-02-01T00:00:00Z"),
]
got_id, reason, detail = pl.match_ad("AcmeCorp", roster, [])
check("multiple non-matching reads -> never a guess", reason, "no-title-match")
check("multiple non-matching returns no id", got_id, None)
check("both candidates surfaced", sorted(detail), ["Some Other Ad One", "Some Other Ad Two"])

# ── 6. case/space/punctuation insensitive ──────────────────────────────────
got_id, _, _ = pl.match_ad("Hostinger", [ad("z", "hostinger 11/1 ad")], [])
check("normalisation (case) matches", got_id, "z")

print()
if FAILS:
    print(f"FAILED: {len(FAILS)}")
    for f in FAILS:
        print("  -", f)
    sys.exit(1)
print("all matcher tests passed")


# ── 7. episode-number guard inside build_plan ──────────────────────────────
# A title like "688: Eyes on Deck ..." names a different episode; binding it
# serves last week's creative. build_plan must raise a question, not proceed.
print()
rows = [{"name": "Hostinger", "start": 1055.0, "end": 1127.48,
         "width": 72.48, "tc": "00:17:35"}]
roster = [ad("688aaa", "688: Eyes on Deck Hostinger Live Read", True,
             "2026-10-11T00:00:00Z")]
_plan, questions = pl.build_plan(rows, roster)
check("different-episode ad raises a question",
      any("names episode 688" in q for q in questions), True)

# and this week's ad (no episode prefix) must NOT raise that question
rows = [{"name": "Hostinger", "start": 1055.0, "end": 1127.48,
         "width": 72.48, "tc": "00:17:35"}]
roster = [ad("691bbb", "Hostinger 11/1 Ad", True, "2026-11-01T00:00:00Z")]
_plan, questions = pl.build_plan(rows, roster)
check("current-week ad raises no question", questions, [])

print()
if FAILS:
    print(f"FAILED: {len(FAILS)}")
    sys.exit(1)
print("all matcher tests passed")
