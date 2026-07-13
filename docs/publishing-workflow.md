# Publishing Workflow — Quick Reference

## New Episode

```clojure
;; 1. Probe
get_series(series_slug: "lu")
list_episodes(series_slug: "lu", sort: "-released_at", page_size: 1)

;; 2. Create episode (draft)
create_episode(series_slug: "lu", title: "675: Sloppy Agent Roasting",
               description: "…", itunes_type: "full")
;; → episode_id: "uuid"

;; 3. Create version (ART19 fetches audio)
create_episode_version(episode_id: "…", source_url: "http://host/file.flac",
                       status_on_completion: "active")
;; → version_id: "uuid"

;; 4. Markers — MUST create in order: pre → mid → post
create_marker_point(episode_version_id: "…", position_type: 0,
                     type: "AdInsertionPoint",
                     max_content_count: 2, max_content_duration: 90)
;; → pre-roll marker_id

;; !!! Pre-roll fix: set start_position=0 before midrolls
update_marker_point(marker_point_id: "pre-marker", start_position: 0)

create_marker_point(episode_version_id: "…", position_type: 1,
                    start_position: 1608, type: "AdInsertionPoint",
                    max_content_count: 3, max_content_duration: 180)
;; → midroll marker_id

create_marker_point(episode_version_id: "…", position_type: 2,
                    type: "AdInsertionPoint",
                    max_content_count: 2, max_content_duration: 120)
;; → post-roll marker_id

;; 5. Content rules (one per marker)
create_marker_point_content_rule(marker_point_id: "…", priority: 1,
                                 content_type: "Campaign")

;; 6. Submit for processing
update_episode_version(version_id: "…", processing_status: "submitted",
                       status_on_completion: "active")

;; 7. Wait (~4-5 min)
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

;; 3. Set pre-roll to 90s (NEW DEFAULT — already 90 if server has the
;;    build-marker-set change; on the OLD server pass it explicitly):
update_marker_point(marker_point_id: "pre-roll",
                    maximum_content_duration: 90)

;; 4. Add a new midroll (keep existing ones — DON'T delete unless replacing)
create_marker_point(episode_version_id: "…", position_type: 1,
                    start_position: 1800, type: "AdInsertionPoint",
                    maximum_content_count: 3, maximum_content_duration: 180)
create_marker_point_content_rule(marker_point_id: "new-midroll",
                                 priority: 1, content_type: "Campaign")

;; 5. Submit + wait + publish
update_episode_version(version_id: "…", processing_status: "submitted")
wait_for_processing(version_id: "…", timeout_seconds: 300)
publish_episode(episode_id: "…", released_at: NOW, release_immediately: true)

;; 6. Verify
list_feed_items(episode_id: "…")
```

**Pre-roll default:** 90s (set in `build-marker-set`). New-server backfills get it
automatically; old-server (port 3007 pre-deploy) needs the explicit
`update_marker_point` call in step 3.

**Editing live episodes WITHOUT a new version:** The ART19 web UI *can* edit
markers on an `active` version (it silently does a version copy + republish
under the hood — you'll see `active_version_id` change). Our MCP tools cannot
(`not_eligible_for_changes`). So: use the UI for quick one-off tweaks on already
live episodes; use the new-version flow above for batch backfills.

## One-Shot (future server — `prepare_episode_version`)

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

## Critical Rules

| Rule | Detail |
|------|--------|
| **Ad slot defaults** | Pre-roll 90s / Midroll 180s / Post-roll 120s (set in `build-marker-set`). Pre-roll shortened from 120s→90s per cohost guidance. |
| **Active versions are locked** | Once an episode_version is `active` (published), its markers can't be edited via API (`not_eligible_for_changes`). To change a published episode's markers/durations, create a NEW version (copy audio + markers) on a draft, edit, then submit + publish. |
| **FLAC works** | ART19 accepts FLAC despite docs saying MP3/WAV |
| **released_at required** | Must provide `released_at` even with `release_immediately: true` (tool auto-sets if omitted) |
| **Content rules required** | Without content rules, AdInsertionPoint markers serve no ads |
| **Verify via feed_items** | After publish, `list_media_assets` returns empty — use `list_feed_items` for enclosure URL |
| **copy_marker_points** | Use on `create_episode_version` to copy pre + post from existing version |
