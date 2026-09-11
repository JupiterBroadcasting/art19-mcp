# Publishing Workflow — Quick Reference

## New Episode

**Data source:** EDN at `/tmp/data` — canonical, always fresh. `/tmp/data.json`
is stale legacy (last current at 682); ignore it. Extract fields with `bb`:

```bash
EP_NUM=$(bb -e '(print (:episode (clojure.edn/read-string (slurp "/tmp/data"))))')
TITLE=$(bb -e '(print (:title (clojure.edn/read-string (slurp "/tmp/data"))))')
DESC=$(bb -e '(print (:description (clojure.edn/read-string (slurp "/tmp/data"))))')
FULL_TITLE="$EP_NUM: $TITLE"
```

**Title convention:** always `"EP_NUM: Title"` — e.g. `"683: Website Speedrun"`.

**Ad timestamps:** the data source provides timestamps like `"33:52:02"`. Convert
to seconds for `start_position`: `33*60 + 52 = 2032`. Baked-in ads (nebula) get
no marker — only dynamic ad breaks do.

```clojure
;; 1. Probe
get_series(series_slug: "lu")
list_episodes(series_slug: "lu", sort: "-released_at", page_size: 1)

;; 2. Create episode (draft) — title MUST include episode number prefix
create_episode(series_slug: "lu", title: "681: Ain't Nothing But a Syncthing",
               description: "…", itunes_type: "full")
;; → episode_id: "uuid"
;; NOTE: ART19 may renumber episode_number — the API-assigned number may
;; differ from the data source. Title is what matters for the feed.

;; 3. Create version (ART19 fetches audio)
create_episode_version(episode_id: "…", source_url: "http://host/file.flac",
                       status_on_completion: "active")
;; → version_id: "uuid"

;; 4. Markers — create in order: pre → mid(s) → post
;;    Position types: 0=Pre-Roll, 1=Mid-Roll, 2=Post-Roll

;; Pre-roll: single 180s slot (fits longer host reads). Include start_position: 0 directly
create_marker_point(episode_version_id: "…", position_type: 0,
                    start_position: 0, type: "AdInsertionPoint",
                    maximum_content_count: 1, maximum_content_duration: 180)

;; Midroll(s): convert ad timestamp to seconds
create_marker_point(episode_version_id: "…", position_type: 1,
                    start_position: 2032, type: "AdInsertionPoint",
                    maximum_content_count: 3, maximum_content_duration: 90)

;; Post-roll: DO NOT pass start_position — omit it entirely.
;; Passing start_position: 0 with position_type: 2 causes a silent failure
;; (empty response / JSON parse error). The API returns start_position: null
;; for post-roll.
create_marker_point(episode_version_id: "…", position_type: 2,
                    type: "AdInsertionPoint",
                    maximum_content_count: 2, maximum_content_duration: 180)

;; 5. Content rules (one per marker)
create_marker_point_content_rule(marker_point_id: "…", priority: 1,
                                 content_type: "Campaign")

;; 6. Submit for processing
update_episode_version(version_id: "…", processing_status: "submitted",
                       status_on_completion: "active")

;; 7. Wait (~4-5 min) — see Processing Stages below
wait_for_processing(version_id: "…", timeout_seconds: 300,
                    poll_interval_seconds: 10)

;; 8. Publish
publish_episode(episode_id: "…", released_at: NOW,
                release_immediately: true)

;; 9. Verify enclosure URL
list_feed_items(episode_id: "…")
```

## Backfill: Replace Audio + Add Midroll (new version)

Used when an episode is already live and you want to (a) swap audio, (b) add a
midroll, or (c) adjust ad slots. **This is the same operation for all three** —
ART19 requires a fresh version because markers on an `active` version are locked.

> **`copy_active_version: true` reuses the existing audio — NO re-upload needed.**
> Only a reprocess (~4-5 min) + republish. `copy_marker_points: true` copies the
> existing markers onto the new draft version so you only touch what changed.

```clojure
;; 1. New version — copy audio + markers from the live episode
create_episode_version(episode_id: "…",
                       copy_active_version: true,
                       copy_marker_points: true,
                       status_on_completion: "active")
;; → version_id: "uuid"  (draft — markers are now editable)

;; 2. List copied markers, find the pre-roll + old midrolls
list_marker_points(episode_version_id: "…")
;; → pre-roll (pos 0), midrolls (pos 1), post-roll (pos 2)

;; 3. Set pre-roll to single 180s slot (current default — already 180/1 if
;;    server has the pre-roll-180 change; on the OLD server pass it explicitly):
update_marker_point(marker_point_id: "pre-roll",
                    maximum_content_duration: 180,
                    maximum_content_count: 1)

;; 4. Add a new midroll (keep existing ones — DON'T delete unless replacing).
;;    If this makes 2+ midrolls total, use 90s each; a lone midroll is 120s.
create_marker_point(episode_version_id: "…", position_type: 1,
                    start_position: 1800, type: "AdInsertionPoint",
                    maximum_content_count: 3, maximum_content_duration: 90)
create_marker_point_content_rule(marker_point_id: "new-midroll",
                                 priority: 1, content_type: "Campaign")

;; 5. Submit + wait + publish
update_episode_version(version_id: "…", processing_status: "submitted")
wait_for_processing(version_id: "…", timeout_seconds: 300)
publish_episode(episode_id: "…", released_at: NOW, release_immediately: true)

;; 6. Verify
list_feed_items(episode_id: "…")
```

**Ad slot defaults:** Pre-roll 180s × 1 ad · Midroll single 120s / 2+ midrolls 90s each · Post-roll 180s (all tunable via `def ^:const` at top of `art19_mcp.bb`). New-server backfills get them automatically; old-server (port 3007 pre-deploy) needs explicit `update_marker_point` calls (as in step 3+4).

## One-Shot (`prepare_episode_version`)

```clojure
;; Creates version + pre + midrolls + post + Campaign rules + submits
prepare_episode_version(episode_id: "…", midrolls: [1608])
;; → version_id: "uuid", already submitted

;; Then just:
wait_for_processing(version_id: "…")
publish_episode(episode_id: "…", release_immediately: true)

;; For replacements:
prepare_episode_version(episode_id: "…",
                        source_url: "http://host/new.flac",
                        midrolls: [1800, 3600])
```

> **GOTCHA: `prepare_episode_version` fails on brand-new episodes** (no active
> version to copy from). You'll get `422 no_active_version`. For new episodes,
> use the explicit path above: `create_episode_version` → markers → rules →
> submit → wait → publish. Only episodes with a published version can use the
> one-shot path.

## Processing Stages

ART19 audio processing progresses through these stages (visible via
`get_episode_version` → `processing_status`):

```
draft → downloading → segment_list_processing → processing_requested
     → processing → active
```

Typical total time: ~4 min for a 60-min FLAC. If a stage stalls beyond 10 min,
check the source URL is reachable.

## Automation Notes

**mcpc bridge session persistence:** The mcpc bridge (`mcpc connect`) creates a
stateful MCP session. However, the session ID is lost between separate shell
invocations — each new `shell` tool call or terminal session gets a fresh
process. For a reliable pipeline, run all steps in a single script:

```bash
#!/usr/bin/env bash
set -euo pipefail
cd /home/wes/src/art19-mcp
./bin/mcpc connect http://127.0.0.1:3007/mcp @art19 2>/dev/null
# ... all mcpc calls in sequence ...
```

**Fish shell:** if your default shell is fish, wrap bash-specific syntax in
`bash -c '...'` — fish doesn't support `for i in $(seq ...)` or `&&` chaining.

**Manual poll fallback:** `wait_for_processing` can exceed the mcpc bridge
timeout on long-running processing. If it hangs, poll manually:

```bash
./bin/mcpc @art19 --json tools-call get_episode_version \
  version_id: '"UUID"' 2>/dev/null | \
  python3 -c "import sys,json; a=json.loads(json.load(sys.stdin)['content'][0]['text'])['data']['attributes']; print(a['processing_status'], a['status'])"
```

## Publishing from showfactory

The NixOS service (`services.art19-mcp`, port 3007, systemd-credential auth)
is the publish path on the showfactory host. Agents there reach it two ways:

- **Hermes → injector (native):** `art19` is wired into the `default` and
  `chief` profiles' `mcp_servers`. Tools appear in the agent surface with a
  300s timeout. This is the going-forward path for agent-driven publishes.
- **Raw curl JSON-RPC (manual/scripted):** no mcpc on showfactory (Node
  toolchain absent by design). Session lifecycle: `initialize` → capture
  `Mcp-Session-Id` → `notifications/initialized` → `tools/call` per step.
  Verified working against the live instance.

**Cold-start latency:** the first API call after deploy/idle can take ~60s+
(TLS + paginated fetch). Use generous timeouts on first contact; subsequent
calls are fast. A cheap warmup is `get_series(series_slug: "lu")`.

## Critical Rules

| Rule | Detail |
|------|--------|
| **Title format** | Always `"EP_NUM: Title"` — e.g. `"681: Ain't Nothing But a Syncthing"`. Data source provides them separately. |
| **Ad slot defaults** | Pre-roll 180s × 1 ad (fits longer host reads that must run pre-roll) / Midroll single 120s, 2+ midrolls 90s each (3 ads) / Post-roll 180s (2 ads). All tunable via `def ^:const` at top of `art19_mcp.bb`. |
| **Post-roll: no start_position** | Omit `start_position` entirely for position_type 2. Passing `start_position: 0` causes a silent failure. API returns `start_position: null` for post-roll. |
| **Active versions are locked** | Once an episode_version is `active` (published), its markers can't be edited via API (`not_eligible_for_changes`). To change a published episode's markers/durations, create a NEW version (copy audio + markers) on a draft, edit, then submit + publish. |
| **Marker creation order** | Create markers in order: pre → mid(s) → post. Out-of-order creation can return 403. |
| **FLAC works** | ART19 accepts FLAC despite docs saying MP3/WAV |
| **released_at required** | Must provide `released_at` even with `release_immediately: true` (tool auto-sets if omitted) |
| **Content rules required** | Without content rules, AdInsertionPoint markers serve no ads |
| **Verify via feed_items** | After publish, `list_media_assets` returns empty — use `list_feed_items` for enclosure URL |
| **copy_marker_points** | Use on `create_episode_version` to copy pre + post from existing version |
| **Param discipline** | Tool arg names are exact — `create_marker_point` uses `maximum_content_count` (NOT `max_content_count`), `update_episode_version` uses `version_id` (NOT `episode_version_id`). Unknown args are silently dropped. |

**Editing live episodes WITHOUT a new version:** The ART19 web UI *can* edit
markers on an `active` version (it silently does a version copy + republish
under the hood — you'll see `active_version_id` change). Our MCP tools cannot
(`not_eligible_for_changes`). So: use the UI for quick one-off tweaks on already
live episodes; use the new-version flow above for batch backfills.
