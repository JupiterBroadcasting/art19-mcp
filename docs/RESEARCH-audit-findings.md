# ART19 API Audit — Research Findings

Source date: 2026-07-02
API spec: `/home/wes/src/art19-mcp/docs/art19_json_api_spec_2026-02-21.json`
MCP server: `/home/wes/src/art19-mcp/art19_mcp.bb`
Tests: `/home/wes/src/art19-mcp/tests/test_art19_mcp.clj`

## Version Lifecycle (from API spec)

### Status values

| Status | Meaning |
|--------|---------|
| `draft` | Initial state, not yet submitted |
| `inactive` | Processed but not the live version |
| `processing` | Currently transcoding |
| `active` | The live version serving listeners |
| `processing_failed` | Error during processing |
| `validation_failed` | Validation failed (see `validation_errors`) |

### Valid PATCH transitions

PATCH `/episode_versions/{id}` only accepts:
- `processing_status: "submitted"` — starts processing
- `processing_status: "inactive"` — demotes an active version

**You CANNOT PATCH directly to `active`.** Activation only happens when processing completes and `status_on_completion` is `"active"`.

### Status on completion

`status_on_completion` (enum: `["active", "inactive"]`) — controls what status the version gets when processing finishes. Only valid on PATCH, not POST.

### Validation error codes

| Code | Description |
|------|-------------|
| `content_type_not_allowed` | Source is not audio/video |
| `file_size_exceeds_maximum` | Exceeds 2GB |
| `marker_point_out_of_range` | Marker timestamp past end of audio |
| `source_file_not_accessible` | Can't download source URL |

---

## Marker Points (from API spec)

### Marker point types

| `type` value | Meaning |
|-------------|---------|
| `AdInsertionPoint` | Dynamic ad insertion (server-side) |
| `EmbeddedAdPoint` | Ads baked into audio |

### Position types

| `position_type` | `position_type_name` | Meaning |
|-----------------|---------------------|---------|
| `0` | `preroll` | Before content |
| `1` | `midroll` | During content |
| `2` | `postroll` | After content |

### Required fields on POST

- `type` (always `"AdInsertionPoint"`)
- `position_type` (0/1/2)
- `maximum_content_count` (integer)
- `maximum_content_duration` (float, seconds)

### Optional fields

- `start_position` (float, seconds — `null` = end of content)
- `default_for` (`"warpfeed"` or null — CMS template marker)

### Constraints

- Can only create/update on `draft` or `inactive` episode versions
- Deleting from `active` version is permanent — must revert to `inactive` to re-add

---

## Content Rules (from API spec)

### Required for ad serving

> "If no content rules are supplied, the associated marker point will not serve any ads."

### Fields

| Field | Type | Required | Notes |
|-------|------|----------|-------|
| `priority` | integer | Yes | Higher = checked first |
| `content_type` | enum | No | `Campaign` (all), `LiveReadAd`, `TraditionalAd` |
| `start_at` | datetime | No | Rule becomes active |
| `end_at` | datetime | No | Rule expires |
| `content` | relationship | No | Target specific brand/campaign/advertisement |
| `marker_point` | relationship | Yes | Parent marker |

### Filtering on GET

- `marker_point_id` — required (or `ids[]`)
- `sort` — `created_at`, `priority`, `updated_at`

---

## MCP Server Gaps (from audit)

### Tools that exist vs API endpoints

| API Endpoint | MCP Tool | Status |
|---|---|---|
| POST `/episode_versions` | `create_episode_version` | ✅ |
| GET `/episode_versions/{id}` | `get_episode_version` | ✅ |
| PATCH `/episode_versions/{id}` | `update_episode_version` | ✅ |
| DELETE `/episode_versions/{id}` | `delete_episode_version` | ✅ |
| GET `/episode_versions` | `list_episode_versions` | ✅ |
| PATCH `/episodes/{id}` (publish) | `publish_episode` | ✅ but too simple |
| GET `/marker_points` | `list_marker_points` | ✅ but drops filters |
| POST `/marker_points` | `create_marker_point` | ✅ |
| DELETE `/marker_points/{id}` | `delete_marker_point` | ✅ |
| GET `/marker_points/{id}` | — | ❌ Missing |
| PATCH `/marker_points/{id}` | — | ❌ Missing |
| GET `/marker_point_content_rules` | — | ❌ Missing |
| POST `/marker_point_content_rules` | — | ❌ Missing |
| PATCH `/marker_point_content_rules/{id}` | — | ❌ Missing |
| DELETE `/marker_point_content_rules/{id}` | — | ❌ Missing |

### Specific bugs

1. **`list_marker_points`** — schema promises `series_id`, `season_id`, `type` filters but implementation ignores them (line 497-500 vs 842-848)
2. **`create_episode_version`** — schema says `status_on_completion: "published, draft"` but API uses `"active", "inactive"` and it's not valid on POST (line 782)
3. **`list_marker_points`** — returns `position_type_name` as `position_type` in response (line 506), confusing since API has both numeric and string versions
4. **`list_marker_points`** — missing `maximum_content_count`, `maximum_content_duration`, `default_for`, `created_at`, `updated_at` from response
5. **`list_episode_versions`** — missing `ad_insertion_points_count`, `status_on_completion`, `validation_errors`, `updated_at` from response

### Test gaps

- No tests for `list_seasons` or `get_season`
- No tests for `copy_active_version` or `copy_marker_points` parameters
- No tests for `create_marker_point` with required ad-insertion params
- Hardcoded tool count assertion (34) breaks on every new tool
- Fake API doesn't actually filter feed items by series_id/episode_id
