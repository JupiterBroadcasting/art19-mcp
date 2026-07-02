# Plan: Publishing Workflow + Midroll Batch Tool

## What We Got Wrong

The original assumption was: create version → add markers → PATCH `processing_status: "active"` → done.

**That's not how it works.** The API only allows two PATCH values for `processing_status`: `"submitted"` and `"inactive"`. You cannot PATCH directly to `active`. Activation only happens when processing completes and `status_on_completion` is `"active"`.

The correct lifecycle:

```
POST /episode_versions
  → version in "draft" status
  → source_url provided (or copy_active_version: true)
  → processing does NOT start yet

PATCH /episode_versions/{id}
  → processing_status: "submitted"
  → status_on_completion: "active" (or "inactive")
  → server begins processing (transcoding, validation)

Automatic transitions:
  draft → submitted → processing → active | inactive | processing_failed | validation_failed

Only one version can be "active" per episode.
The old active version auto-demotes when new one becomes active.
```

**Critical constraint:** Markers can only be created/updated on `draft` or `inactive` versions. You CANNOT add markers to an `active` version. So the workflow must be: create → add markers → submit → process → activate.

---

## Part 1: Fix Existing Bugs (Must-Do)

### Bug 1: `list_marker_points` silently drops filters

**Location:** `art19_mcp.bb:497-500`

The inputSchema promises `series_id`, `season_id`, `type` filters (line 842-848), but the implementation only destructures `episode_version_id` and `episode_id`. These filters are silently ignored.

**Fix:** Pass all declared filters through to the API params (also add `ids[]` support):

```clojure
(defn tool-list-marker-points [{:keys [episode_version_id episode_id series_id season_id type ids]} config]
  (let [params (cond-> {}
                 episode_version_id (assoc "episode_version_id" episode_version_id)
                 episode_id (assoc "episode_id" episode_id)
                 series_id (assoc "series_id" series_id)
                 season_id (assoc "season_id" season_id)
                 type (assoc "type" type)
                 (seq ids) (assoc "ids[]" ids))
        ...]))
```

### Bug 2: `create_episode_version` schema says wrong `status_on_completion` values

**Location:** `art19_mcp.bb:782`

Schema says `"published, draft"`. API spec says `["active", "inactive"]`. And `status_on_completion` isn't valid on POST at all — only on PATCH.

**Fix:** Remove `status_on_completion` from `create_episode_version` schema entirely. Set it when you submit via `update_episode_version`.

### Bug 3: `list_marker_points` returns misleading field name

**Location:** `art19_mcp.bb:506`

Returns `position_type_name` (string: "preroll"/"midroll"/"postroll") but names it `position_type` in the response. The API has both `position_type` (numeric) and `position_type_name` (string).

**Fix:** Return both:
```clojure
{:id (:id mp)
 :position_type (get-in mp [:attributes :position_type])
 :position_type_name (get-in mp [:attributes :position_type_name])
 :start_position (get-in mp [:attributes :start_position])
 :maximum_content_count (get-in mp [:attributes :maximum_content_count])
 :maximum_content_duration (get-in mp [:attributes :maximum_content_duration])
 :type (get-in mp [:attributes :type])}
```

---

## Part 2: Complete Marker Point CRUD

### Missing tools to add:

| Tool | Endpoint | Purpose |
|------|----------|---------|
| `get_marker_point` | GET `/marker_points/{id}` | Inspect a single marker's full details |
| `update_marker_point` | PATCH `/marker_points/{id}` | Edit timing, max count, max duration |
| `list_marker_point_content_rules` | GET `/marker_point_content_rules` | See ad targeting rules on a marker |
| `create_marker_point_content_rule` | POST `/marker_point_content_rules` | Add ad targeting to a marker |
| `update_marker_point_content_rule` | PATCH `/marker_point_content_rules/{id}` | Change targeting |
| `delete_marker_point_content_rule` | DELETE `/marker_point_content_rules/{id}` | Remove targeting |

### Content Rules — Required for Ad Serving

**Critical:** Markers without content rules **will not serve any ads**. The API says:

> "If no content rules are supplied, the associated marker point will not serve any ads."

So the batch tool MUST create a default content rule for each marker, or warn the user.

Content rule fields:

| Field | Type | Required | Notes |
|-------|------|----------|-------|
| `priority` | integer | **Yes** | Higher = checked first |
| `content_type` | enum | No | `Campaign` (all), `LiveReadAd` (live reads), `TraditionalAd` (spots) |
| `start_at` | datetime | No | Rule becomes active at this time |
| `end_at` | datetime | No | Rule expires at this time |
| `content` | relationship | No | Target specific `brand`, `campaign`, or `advertisement` by ID |
| `marker_point` | relationship | **Yes** | The marker this rule belongs to |

### Update `list_marker_points` response to include full fields:

```clojure
;; Current (incomplete):
{:id :position_type :start_position :type}

;; Should be:
{:id :id
 :position_type numeric        ;; 0/1/2
 :position_type_name string    ;; "preroll"/"midroll"/"postroll"
 :start_position float
 :maximum_content_count int
 :maximum_content_duration float
 :type string                  ;; "AdInsertionPoint"/"EmbeddedAdPoint"
 :default_for string-or-nil    ;; "warpfeed" or nil
 :created_at datetime
 :updated_at datetime}
```

---

## Part 3: Publishing Observability

### Improve `list_episode_versions` response:

```clojure
;; Current:
{:id :processing_status :source_url :created_at}

;; Should be:
{:id :id
 :processing_status string
 :status_on_completion string-or-nil
 :source_url string
 :ad_insertion_points_count int
 :validation_errors array-or-nil
 :created_at datetime
 :updated_at datetime}
```

This gives the agent visibility into:
- Whether a version is still processing
- Whether it has markers already (`ad_insertion_points_count`)
- Why processing failed (`validation_errors`)

### Add local validation to `update_episode_version`:

```clojure
;; Currently just passes through whatever the agent sends
;; Should validate:
(when (and processing_status
           (not (#{"submitted" "inactive"} processing_status)))
  (throw (ex-info "processing_status must be 'submitted' or 'inactive'"
                  {:type :bad-request})))
```

---

## Part 4: Compound Tool — `prepare_episode_version`

### Design Philosophy

The agent shouldn't orchestrate API calls. The agent should pass a data payload and the tool handles the plumbing. One tool, one workflow, data in → result out. This is Clojure style — the tool is a function that takes data and returns data, managing all the complexity internally.

The batch use case is just "call this tool in a loop" — the agent handles that naturally.

### What This Replaces

Instead of the agent chaining:
```
1. create_episode_version    → get version_id
2. create_marker_point × N   → for each midroll
3. create_content_rule × N   → for each marker
4. update_episode_version    → submit for processing
5. get_episode_version       → poll until done
```

The agent calls one tool:
```
prepare_episode_version
  episode_id: "abc"
  markers: [{start_position: 600 ...}]
→ tool handles all 4+ API calls internally
→ returns: {version_id, status, markers_added, content_rules_created}
```

### Tool: `prepare_episode_version`

```clojure
(defn tool-prepare-episode-version
  "Create a new episode version, add markers with content rules, and submit
   for processing. The version is created by copying audio from the currently
   active version. Returns the new version's status — agent polls
   get_episode_version until processing_status is 'active' or 'processing_failed'."
  [{:keys [episode_id markers status_on_completion released_at]} config]
  ;; 1. POST /episode_versions
  ;;    copy_active_version: true
  ;;    copy_marker_points: true (preserves existing markers)
  ;;
  ;; 2. For each marker in markers:
  ;;    POST /marker_points on the new draft version
  ;;    POST /marker_point_content_rules with the marker's content rule config
  ;;      - content_type: (or (:content_type marker) "Campaign")
  ;;      - priority: (or (:priority marker) 1)
  ;;      - start_at: (:start_at marker) — optional time window
  ;;      - end_at: (:end_at marker) — optional time window
  ;;      - content: (:content_id marker) + (:content_type_target marker) — optional specific target
  ;;    If marker creation fails → skip that marker, continue with others,
  ;;    track which failed
  ;;
  ;; 3. PATCH /episode_versions/{id}
  ;;    processing_status: "submitted"
  ;;    status_on_completion: (or status_on_completion "active")
  ;;
  ;; 4. Return result map
  ;;
  ;; Internal safety:
  ;;   - 429 rate limit → retry up to 3x, exponential backoff using Retry-After
  ;;   - 429 transcoding concurrency → hard stop, clear error message
  ;;   - Version creation fails → return error immediately, no cleanup needed
  ;;   - Marker fails → log, continue, report partial success
  ;;   - Submit fails → return error with version_id so agent can retry submit
  )
```

### Input schema:

```clojure
{:name "prepare_episode_version"
 :description "Create a new version of an episode, add ad markers, and submit for processing. Copies audio from the active version. Returns the new version's status — poll get_episode_version until processing completes."
 :inputSchema
 {:type "object"
  :properties
  {:episode_id {:type "string" :description "Episode UUID"}
   :markers {:type "array"
             :description "Ad markers to add. Each marker gets a default content rule (Campaign, priority 1). Override per-marker below. If markers is omitted, copies existing markers from active version."
             :items {:type "object"
                     :properties
                     {:start_position {:type "number" :description "Seconds into audio. Required for midroll/preroll."}
                      :position_type {:type "integer" :description "0=preroll, 1=midroll, 2=postroll. Default: 1 (midroll)"}
                      :maximum_content_count {:type "integer" :description "Max ads at this marker. Default: 2"}
                      :maximum_content_duration {:type "number" :description "Max total ad seconds. Default: 120"}
                      :type {:type "string" :description "AdInsertionPoint or EmbeddedAdPoint. Default: AdInsertionPoint"}
                      :content_type {:type "string" :description "Campaign=all, LiveReadAd=live reads, TraditionalAd=spots. Default: Campaign"}
                      :priority {:type "integer" :description "Content rule priority (higher=checked first). Default: 1"}
                      :start_at {:type "string" :description "ISO 8601 datetime. Content rule becomes active at this time."}
                      :end_at {:type "string" :description "ISO 8601 datetime. Content rule expires at this time."}
                      :content_id {:type "string" :description "Target a specific brand, campaign, or advertisement UUID."}
                      :content_type_target {:type "string" :description "Type of content_id: 'brands', 'campaigns', or 'advertisements'."}}}}
   :status_on_completion {:type "string" :description "active or inactive. Default: active"}
   :released_at {:type "string" :description "ISO 8601 datetime to schedule release. Omit for immediate."}}
  :required ["episode_id"]}}
```

### Output format:

```clojure
;; Success
{:version_id "new-version-uuid"
 :processing_status "submitted"
 :status_on_completion "active"
 :markers_added 2
 :content_rules_created 2
 :warnings []}

;; Partial success (some markers failed)
{:version_id "new-version-uuid"
 :processing_status "submitted"
 :markers_added 1
 :content_rules_created 1
 :warnings ["Marker at start_position=1200 failed: position out of range"]}

;; Failure
{:error true
 :message "Episode abc-123 has no active version to copy"
 :episode_id "abc-123"}
```

### The agent's job after calling this tool:

```clojure
;; 1. Call prepare_episode_version → get result
;; 2. Poll until done:
(get_episode_version version_id: (:version_id result))
;; 3. Check processing_status:
;;    "active" → done, version is live
;;    "processing" → wait, poll again
;;    "processing_failed" → check validation_errors
;;    "validation_failed" → check validation_errors
```

### Batch use case:

The agent loops over episodes — no separate batch tool needed:

```clojure
;; Agent reads JSON file, calls prepare_episode_version for each episode:
(for [episode episodes]
  (prepare_episode_version
    episode_id (:episode_id episode)
    markers (:markers episode)))
```

Each call is independent. If one fails, the others aren't affected. The agent collects results and reports summary.

---

## Part 5: Keep Small Tools for Edge Cases

The compound tool handles the happy path. Keep the small tools for:

| Tool | Use Case |
|------|----------|
| `list_marker_points` | Debug: see what markers exist on a version |
| `get_marker_point` | Debug: inspect one marker's full details |
| `update_marker_point` | Fix: move a marker's timing without re-creating the version |
| `delete_marker_point` | Fix: remove a bad marker |
| `list_marker_point_content_rules` | Debug: see what ad targeting is configured |
| `create_marker_point_content_rule` | Custom: add non-default targeting (specific brand, time window) |
| `delete_marker_point_content_rule` | Fix: remove a targeting rule |
| `get_episode_version` | Poll: check processing status after submit |

The compound tool is the "I know what I want, just do it" path. The small tools are the "I need to inspect or fix something" path.

---

## Implementation Order

1. **Bug fixes** (Part 1) — small, safe, immediate value
2. **Marker CRUD completeness** (Part 2) — fill out the missing small tools
3. **Observability improvements** (Part 3) — richer responses
4. **Compound tool** (Part 4) — the full publish+markers workflow
5. No separate Part 5 — compound tool IS the solution, small tools remain for edge cases

Each step is independently shippable and testable.
