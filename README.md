# art19-mcp

> "Simple is the opposite of complex. Easy is the opposite of hard."
> — Rich Hickey

A Babashka Streamable HTTP MCP server wrapping the ART19 Content API. Replaces the old `art19-cli` — an AI agent is a better operator of this API than a human typing commands.

**Transport:** MCP Streamable HTTP (spec `2025-03-26`)
**Endpoint:** Single `/mcp` POST + `/health`
**Compatible with:** mcp-injector `{:art19 {:url "http://127.0.0.1:PORT/mcp"}}`

## Project Structure

```
art19-mcp/
├── art19_mcp.bb      # Single-file MCP server (the whole thing)
├── bb.edn            # Tasks: run, start, test, lint, health
├── flake.nix         # Nix package + NixOS service module
├── README.md         # This file
├── AGENTS.md         # Agent-specific context
├── .art19/
│   └── state.edn     # Project state tracking
├── docs/
│   ├── markers-and-ads.md    # Ad insertion (SSAI) vs embedded ad points
│   └── mcpc-helper.md        # How to use mcpc CLI to call the MCP server
├── tests/
│   └── test_art19_mcp.clj  # Integration tests (fake API)
├── CHANGELOG.md      # Release history
└── DEVLOG.md         # Development notes
```

## Workflow

- **Test-driven** — tests guide development, write tests that verify real client usage
- **Integration tests only** — fake API server, no mocks, test like a client would
- **Clean lint** — no warnings tolerated (`clj-kondo`)
- **Formatting** — uniform across all types:
  - Clojure/Babashka: `nix run nixpkgs#cljfmt -- fix <file>`
  - Markdown: `nix run nixpkgs#mdformat -- <file>`
  - Nix: `nix fmt .`
  - EDN: `clojure.pprint`
- **Feature branches** — commit often as snapshots, rewrite history later
- **Docs up to date** — update before commit
- **Keep bb.edn current** — tasks mirror actual commands

## Running

```bash
# Dev — OS-assigned port, logs JSON startup line
bb run

# Dev — fixed port 3007 (or ART19_MCP_PORT)
bb start

# Health check
bb health
```

Auth via env vars or `~/.config/art19/config.edn`:

```bash
export ART19_API_TOKEN="your-token"
export ART19_API_CREDENTIAL="your-credential"
```

```clojure
;; ~/.config/art19/config.edn
{:api-token "your-token" :api-credential "your-credential"}
```

## NixOS Deployment

This is managed as a NixOS service exposed by the flake module.

```nix
# In hosts/<hostname>/default.nix
services.art19-mcp = {
  enable = true;
  port = 3007;
  tokenFile = "/run/secrets/art19";  # EnvironmentFile format
};
```

Secrets file at `/run/secrets/art19`:

```
ART19_API_TOKEN=your-token
ART19_API_CREDENTIAL=your-credential
```

## mcp-injector Config

Add to `mcp-servers.edn`:

```clojure
{:servers
 {:art19
  {:url   "http://127.0.0.1:3007/mcp"
   :tools ["list_episodes" "get_episode" "create_episode" "update_episode"
           "delete_episode" "publish_episode"
           "list_series" "get_series"
           "list_seasons" "get_season"
           "list_credits" "add_credit" "update_credit" "remove_credit"
           "search_people" "get_person" "create_person"
           "list_episode_versions" "create_episode_version"
           "get_episode_version" "update_episode_version" "delete_episode_version"
           "get_episode_next_sibling" "get_episode_previous_sibling"
           "upload_image"
           "list_media_assets"
           "list_marker_points" "create_marker_point" "delete_marker_point"
           "list_feed_items" "get_feed_item" "create_feed_item"
           "update_feed_item" "delete_feed_item"]}}}
```

## Tools

### Episodes (6)

| Tool | Description |
|------|-------------|
| `list_episodes` | List episodes for a series; filter by status, date, season, search |
| `get_episode` | Full episode details; optionally include season/series/credits |
| `create_episode` | Create draft episode; accepts series slug aliases (lu, twib, cr, sh, tl) |
| `update_episode` | Update metadata, publish status, release time |
| `delete_episode` | Permanently delete |
| `publish_episode` | Publish; optionally schedule `released_at` |

### Series & Seasons (4)

| Tool | Description |
|------|-------------|
| `list_series` | All series in the account |
| `get_series` | Series details + seasons; accepts slug aliases |
| `list_seasons` | Seasons for a series |
| `get_season` | Season details |

### Credits (4)

| Tool | Description |
|------|-------------|
| `list_credits` | Credits for an episode |
| `add_credit` | Add credit; roles: HostCredit, CoHostCredit, GuestCredit, ProducerCredit, EditorCredit |
| `update_credit` | Change role on existing credit |
| `remove_credit` | Remove a credit |

### People (3)

| Tool | Description |
|------|-------------|
| `search_people` | Search by name |
| `get_person` | Person details |
| `create_person` | Create new person record |

### Episode Versions (5)

| Tool | Description |
|------|-------------|
| `list_episode_versions` | Audio versions for an episode |
| `create_episode_version` | Attach audio via source URL; ART19 fetches and processes it |
| `get_episode_version` | Version details and processing status |
| `update_episode_version` | Submit version for processing, set status_on_completion |
| `delete_episode_version` | Delete a version |

### Episode Siblings (2)

| Tool | Description |
|------|-------------|
| `get_episode_next_sibling` | Get the episode released after this one |
| `get_episode_previous_sibling` | Get the episode released before this one |

### Images (1)

| Tool | Description |
|------|-------------|
| `upload_image` | Upload image artwork for series/season/episode |

### Media Assets (1)

| Tool | Description |
|------|-------------|
| `list_media_assets` | List media assets for an episode version. NOTE: Returns empty after episode is published - use feed_items instead. |

### Marker Points / Chapters (4)

| Tool | Description |
|------|-------------|
| `list_marker_points` | Chapter/ad insertion markers on a version |
| `create_marker_point` | Add marker: 0=preroll, 1=midroll, 2=postroll |
| `update_marker_point` | Update marker timing, position type, or ad limits |
| `delete_marker_point` | Remove a marker |

## Publishing Workflow

### New Episode (Full Pipeline)

```
probe → create_episode → create_episode_version → markers → rules
  → submit → wait_for_processing → publish_episode → verify
```

1. **Probe** — `get_series`, `list_episodes` (verify API access, check last episode)
1. **Create episode** — `create_episode(series_slug, title, description, itunes_type)`
1. **Create version** — `create_episode_version(episode_id, source_url, status_on_completion="active")`
   - `source_url` points to public HTTP server (FLAC works despite docs saying MP3/WAV)
1. **Markers** — `create_marker_point` × 3 (pre-roll → midroll → post-roll), incremental `position_type` (0→1→2). Ad slot defaults: pre-roll 90s / midroll 180s / post-roll 120s (Campaign content rules)
1. **Content rules** — `create_marker_point_content_rule` × 3, `content_type: "Campaign"`
1. **Submit** — `update_episode_version(version_id, processing_status="submitted", status_on_completion="active")`
1. **Wait** — `wait_for_processing(version_id, timeout_seconds=300, poll_interval_seconds=10)`
1. **Publish** — `publish_episode(episode_id, released_at=NOW, release_immediately=true)`
1. **Verify** — `list_feed_items(episode_id)` — check `enclosure_url` is live

**One-shot shortcut** (future server): `prepare_episode_version(episode_id, midrolls=[t1, t2])` — creates version, pre-roll + midrolls + post-roll + Campaign rules + submits in one call. Then just `wait_for_processing` + `publish_episode`.

### Backfill: Replace Audio / Add Midroll (new version)

Used when an episode is already live and you want to swap audio, add a midroll,
or adjust ad slots. ART19 requires a fresh version because markers on an `active`
version are **locked** (`not_eligible_for_changes`). See `docs/publishing-workflow.md`
for the full runbook.

```
create_episode_version (copy_active_version + copy_marker_points) → edit pre-roll → add midrolls → submit → wait → publish
```

1. **New version** — `create_episode_version(episode_id, copy_active_version=true, copy_marker_points=true, status_on_completion="active")`
   - `copy_active_version: true` **reuses the existing audio — no re-upload**. Only a reprocess (~4-5 min) + republish.
   - `copy_marker_points: true` copies the existing markers onto the draft so you only touch what changed.
1. **Set pre-roll to 90s** — `update_marker_point(pre_roll_id, maximum_content_duration=90)` (new default; pass explicitly on the pre-deploy server).
1. **Add midroll** — `create_marker_point(position_type=1, start_position=…)` + `create_marker_point_content_rule(priority=1, content_type="Campaign")`
1. **Submit / Wait / Publish / Verify** — as in the new-episode flow above.

> **UI vs API:** The ART19 web UI *can* edit markers on a live episode (it silently
> does a version copy + republish). Our MCP tools cannot — use the new-version flow
> above for batch backfills; use the UI for one-off tweaks on already-live episodes.

### Marker Ordering Gotchas

- **Incremental position_type required** — create pre-roll (type 0) first, then midroll (type 1), then post-roll (type 2). ART enforces this order.
- **Pre-roll needs start_position=0** — creating pre-roll without `start_position` blocks midroll creation. After creating pre-roll with null start, update it to `start_position=0` via `update_marker_point`, then create midroll.
- **Post-roll depends on midroll** — post-roll (type 2) can only be created after at least one midroll (type 1) exists.
- **Pre-roll and post-roll don't need start_position** per se (ART19 auto-calculates post-roll from audio duration), but pre-roll DOES need one for correct ordering behavior.

### Feed Items (5)

| Tool | Description |
|------|-------------|
| `list_feed_items` | List feed items; filter by episode_id, feed_id, series_id |
| `get_feed_item` | Get a single feed item by ID |
| `create_feed_item` | Create a new feed item |
| `update_feed_item` | Update feed item metadata |
| `delete_feed_item` | Permanently delete a feed item |

## JB Show Aliases

These series slugs are hardcoded for convenience:

| Alias | Resolves to |
|-------|-------------|
| `lu` | `linux-unplugged` |
| `twib` | `this-week-in-bitcoin` |
| `tl` | `the-launch` |
| `cr` | `coder-radio` |
| `sh` | `self-hosted` |

## Architecture

### MCP Transport (Streamable HTTP, 2025-03-26)

Single `/mcp` POST endpoint. Session lifecycle:

1. Client sends `initialize` → server creates session, returns `Mcp-Session-Id` header
1. Client sends `notifications/initialized` (no response needed, 204)
1. All subsequent requests include `Mcp-Session-Id` header
1. Server validates session on every non-initialize request

### Code Structure

Everything lives in `art19_mcp.bb` — it's one file, intentionally:

```
Configuration / auth loading
│
ART19 HTTP client (api-get, api-post, api-patch, api-delete, fetch-all-pages)
│
Tool implementations (tool-*)
│
Tool registry (tools vector — the schemas the LLM sees)
│
Tool dispatch (case on name → tool-*)
│
JSON-RPC handlers (handle-initialize, handle-tools-list, handle-tools-call)
│
HTTP server (http-kit, handler, handle-mcp)
│
Entry point (-main)
```

### Error Handling

Tool errors return `{:error true :message "..."}` which dispatch-tool wraps in an MCP `isError: true` content block. The LLM sees the error message and can reason about it (e.g., retry with different args, report back to user).

API errors (4xx/5xx) are surfaced the same way — they never throw past the tool boundary.

## Development

### Adding a Tool

1. Write `tool-<name> [args config]` function that returns data or `{:error ...}`
1. Add entry to `tools` vector with `:name`, `:description`, `:inputSchema`
1. Add case branch in `dispatch-tool`
1. Add test in `tests/test_art19_mcp.clj`

### Testing

```bash
# Run integration tests (fake API, no real credentials needed)
bb test
```

Tests use a fake API server that mimics ART19's responses. Tests call the real server process via JSON-RPC, exercising the full request/response cycle. No mocks — test like a client would.

### Common Gotchas

**Port 0 allocation:** The server uses port 0 by default (OS assigns). The actual port is in the startup JSON line on stdout. For NixOS services, use a fixed port via `ART19_MCP_PORT`.

**Session validation:** mcp-injector re-initializes sessions on startup (via `warm-up!`). If the server restarts, stale session IDs in mcp-injector will hit a 400. mcp-injector handles this by re-calling `initialize` on 400/401/404 — don't fight it.

**ART19 API note:** The Content API has full CRUD. Earlier research suggested it was read-only — that was wrong. All write operations (create/update/publish/delete episode, manage credits, upload versions) are confirmed working via the OpenAPI spec.

**Pagination:** `fetch-all-pages` caps at 20 pages (2000 items). For `list_episodes` on large feeds, encourage the agent to use `released_after`/`released_before` or `year`/`month` filters rather than paginating everything.

**Enclosure/MP3 URL:** Neither `get_episode` nor episode_versions expose a direct MP3 URL field. The public audio is accessed via **feed items**. Two equivalent approaches:

```
# Via MCP tool (one call)
list_feed_items episode_id="{episode_uuid}"        →  returns enclosure_url

# Direct URL (no API call needed)
https://rss.art19.com/episodes/{episode_uuid}.mp3  →  audio/mpeg (80-100+ MB)
```

The feed_item_id always matches the episode_id for standard episodes. The `enclosure_url` from `list_feed_items` follows the pattern above and can be used before publish when the RSS endpoint isn't yet live.

**Post-publish note:** After publish, `list_media_assets` returns empty. `list_feed_items` is the canonical way to get the public MP3 URL.

## Philosophy

Follow grumpy pragmatism:

- **Actions, Calculations, Data** — tool functions are actions, keep them thin; pure extraction/formatting logic lives in `-row` helpers or inline maps
- **One file is fine** — don't split into namespaces until you genuinely need to
- **No abstractions until they hurt** — the dispatch `case` is fine, resist the urge to make it data-driven
- **Test against real services** — mock drift kills confidence
- **YAGNI** — resources/prompts MCP extensions not implemented because they're not needed yet
