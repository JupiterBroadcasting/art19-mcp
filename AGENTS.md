# AGENTS.md — art19-mcp

> "Simple is the opposite of complex. Easy is the opposite of hard."
> — Rich Hickey

## What This Is

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
├── .art19/
│   └── state.edn     # Project state tracking
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
- **No origin IPs in notes** — session notes use `http://<audio-origin>:<port>/…`, never the real fetch-origin IP (episode/version UUIDs are fine — episode IDs are public via RSS)
- **Keep bb.edn current** — tasks mirror actual commands

## Critical operational rules (learned the hard way — read before publishing)

- **A FAILED version kills the live episode.** A version submitted with
  `status_on_completion: "active"` that fails processing still deactivates the
  previously-live version. The episode stays `published: true` with
  `active_version_id: null` and its enclosure URL 404s — a silent outage that
  took ~13 min to fix on ep688. **Never experiment on a live episode's
  version.** Test risky marker changes on an unpublished scratch episode
  (`create_episode`, never `publish_episode`).
- **Never `release_immediately: true` on a live episode.** It rewrites
  `released_at` to now, so old episodes resurface as new in feed readers.
  Pin the original pubDate instead.
- **Verify content rules after every version copy.** `copy_marker_points`
  drops rules nondeterministically (~7% observed). A marker with no rule
  serves no ads.
- **EmbeddedAdPoint needs `position_type: null` AND a bound advertisement.**
  Integer `position_type` → 422 "Position type must be blank". A
  `content_type: "Campaign"` rule with no binding → the whole version
  `processing_failed`. Correct form: `content_type: "Advertisement"` +
  `content_id` + `content_type_target: "advertisements"`.
- **Nebula is never marked.** Baked in, not ART19-managed. Other baked
  sponsor reads (Hostinger) do get an `EmbeddedAdPoint`.
- **Ad slot defaults:** pre-roll **180s × 1** · mid single 120s / 2+ mids
  90s each (3 ads) · post-roll 180s × 2. All Campaign, priority 1.

Full reference: `docs/publishing-workflow.md`.

## Running

```bash
# Dev — OS-assigned port, logs JSON startup line
bb run

# Dev — fixed port 3007 (or ART19_MCP_PORT)
bb start

# Health check
bb health
```

Auth precedence: env vars → systemd credential (`$CREDENTIALS_DIRECTORY/art19.env`, when the unit sets `LoadCredential`) → `~/.config/art19/config.edn`:

```bash
export ART19_API_TOKEN="your-token"
export ART19_API_CREDENTIAL="your-credential"
```

```clojure
;; ~/.config/art19/config.edn
{:api-token "your-token" :api-credential "your-credential"}
```

## NixOS Deployment

This is managed as a NixOS service.

```nix
# In hosts/<hostname>/default.nix
services.art19-mcp = {
  enable = true;
  port = 3007;
  # Preferred: brokered by systemd, never in the process environment.
  credentialFile = "/run/secrets/art19";  # KEY=value format
  # Or: tokenFile = "/run/secrets/art19";  # classic EnvironmentFile path
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
           "get_episode_version" "update_episode_version" "wait_for_processing"
           "delete_episode_version"
           "get_episode_next_sibling" "get_episode_previous_sibling"
           "upload_image" "list_media_assets"
           "list_marker_points" "create_marker_point" "update_marker_point"
           "get_marker_point" "delete_marker_point"
           "list_marker_point_content_rules" "create_marker_point_content_rule"
           "update_marker_point_content_rule" "delete_marker_point_content_rule"
           "prepare_episode_version"
           "list_feed_items" "get_feed_item"]}]}}
```

## Tools

Full list (42). Arg names are exact — unknown args are silently dropped.

| Tool | Description |
|------|-------------|
| `list_episodes` | List episodes (needs series_id/series_slug/season_id) |
| `get_episode` | Full episode detail |
| `create_episode` | Create draft episode |
| `update_episode` | Update episode metadata |
| `delete_episode` | Delete an episode |
| `publish_episode` | Publish — never `release_immediately` on live eps, pin `released_at` |
| `list_series` | List series |
| `get_series` | Series detail |
| `list_seasons` | List seasons |
| `get_season` | Season detail |
| `list_credits` | Episode credits |
| `add_credit` | Add credit |
| `update_credit` | Change credit role |
| `remove_credit` | Remove credit |
| `search_people` | Search people |
| `get_person` | Person detail |
| `create_person` | Create person |
| `list_episode_versions` | List versions of an episode |
| `create_episode_version` | New audio version (`copy_active_version` + `copy_marker_points` for backfills) |
| `get_episode_version` | Version status + `validation_errors` |
| `delete_episode_version` | Delete a version |
| `wait_for_processing` | Poll a version until processing finishes |
| `update_episode_version` | Submit a version (`processing_status: submitted`) |
| `get_episode_next_sibling` | Next episode by release |
| `get_episode_previous_sibling` | Previous episode by release |
| `upload_image` | Upload series/season/episode artwork |
| `list_media_assets` | Audio asset details (empty after publish) |
| `list_marker_points` | List markers (includes `end_position`) |
| `create_marker_point` | Add marker — `position_type` 0/1/2; EmbeddedAdPoint uses `null` |
| `delete_marker_point` | Delete marker |
| `get_marker_point` | Marker detail |
| `update_marker_point` | Edit marker (durations, counts, positions) |
| `list_marker_point_content_rules` | List a marker's rules |
| `create_marker_point_content_rule` | Add targeting rule — **REQUIRED**, a marker with no rule serves nothing |
| `update_marker_point_content_rule` | Edit a targeting rule |
| `delete_marker_point_content_rule` | Delete a targeting rule |
| `prepare_episode_version` | One-shot: version + markers + rules + submit (**fails on brand-new episodes**) |
| `list_feed_items` | Feed items — use for the enclosure URL |
| `get_feed_item` | Feed item detail |
| `create_feed_item` | Create feed item |
| `update_feed_item` | Update feed item |
| `delete_feed_item` | Delete feed item |

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

**list_episodes requires filters:** The ART19 API requires one of `series_id`, `series_slug`, or `season_id`. Without these, ART19 returns 400 "Bad Request". The MCP server now validates this and returns a clear error message.

**Pagination:** `fetch-all-pages` caps at 20 pages (2000 items). For `list_episodes` on large feeds, encourage the agent to use `released_after`/`released_before` or `year`/`month` filters rather than paginating everything.

**Post-publish media_assets:** After an episode is published, `list_media_assets` returns an empty array. This is ART19 API behavior. To get the public MP3 URL after publish, use `list_feed_items` (returns `enclosure_url`) or `get_episode` (includes `enclosure_url` in response).

**Logging:** All requests are logged as structured JSON to stdout. Each tool call, API request/response, and error is logged with timestamp, level, message, and data. Great for debugging issues.

## Philosophy

Follow the same grumpy pragmatism as the rest of J.O.E.:

- **Actions, Calculations, Data** — tool functions are actions, keep them thin; pure extraction/formatting logic lives in `-row` helpers or inline maps
- **One file is fine** — don't split into namespaces until you genuinely need to
- **No abstractions until they hurt** — the dispatch `case` is fine, resist the urge to make it data-driven
- **Test against real services** — mock drift kills confidence
- **YAGNI** — resources/prompts MCP extensions not implemented because they're not needed yet
