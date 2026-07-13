# Spec: Standard Marker Template for `prepare_episode_version`

## Problem

Creating a standard episode with pre-roll, 2 midrolls, and post-roll requires 7+ tool calls
(create version, 4x markers, 4x content rules, submit). This is the default layout for most
JB episodes. Agents need a one-call path.

## Design

### New param: `midrolls` (array of numbers, optional)

Agent-facing API:

```clojure
prepare_episode_version(
  episode_id: "90b64508-...",
  midrolls: [1476.92, 2489.221]
)
```

Returns a submitted version with the standard 4-marker template. The agent then calls
`wait_for_processing` and `publish_episode`.

### Standard template (auto-generated)

| Marker | position_type | start_position | max_ads | max_duration_s | content_type |
|--------|--------------|---------------|---------|---------------|--------------|
| Pre-Roll | 0 | 0 | 2 | 90 | Campaign |
| Mid-Roll (single) | 1 | from midrolls[] | 3 | 120 | Campaign |
| Mid-Roll (2+) | 1 | from midrolls[] | 3 | 90 | Campaign |
| Post-Roll | 2 | (omitted — ART19 auto-assigns end) | 2 | 180 | Campaign |

All get content rules: `{:priority 1 :content_type "Campaign"}`.

### Behavior table

| `midrolls` | `markers` | Behavior |
|---|---|---|
| `nil` | `nil` | Copy active version markers only (current behavior, no change) |
| `nil` | provided | Copy active version audio + add explicit markers (current behavior, no change) |
| provided | `nil` | **New:** Auto-generate pre + midrolls + post. `copy_marker_points` = false. |
| provided | provided | **Error** — mutually exclusive. Agent must choose convenience or full control. |

## Key Design Decisions

### 1. `midrolls` and `markers` are mutually exclusive

If an agent passes both, return an error rather than trying to merge. Keeps the contract
simple and avoids surprising behavior (e.g., duplicated position_types).

### 2. `copy_marker_points` = false when `midrolls` is provided

This prevents the "overlap" bug. The new version starts with a clean marker slate.
The old version's markers are preserved on the old version — ART19 keeps version history.

### 3. Post-roll omits `start_position`

Per ART19 API behavior (confirmed in the UI: post-roll shows `01:17:10.88` auto-assigned
from the audio duration). Setting an explicit end time would be fragile and wrong.

### 4. Timestamp validation for midrolls

Each midroll entry must be:

- A positive number (> 0)
- Unique (no duplicates)
- In ascending order (for incremental position_type constraint)

Throws `ex-info` with `:type :bad-request` on validation failure.

## Code Changes

### Extract `build-marker-set` helper

```clojure
(defn- build-marker-set
  "Given midrolls (timestamps) or explicit markers, produce the list of marker
   configs to be created. If midrolls is non-nil, generates the standard
   template: pre-roll + midrolls + post-roll. Returns nil when neither param
   is provided (copy existing markers only)."
  [midrolls markers]
  (cond
    (some? midrolls)
    (let [deduped (distinct midrolls)
          _ (when (not= (count deduped) (count midrolls))
              (throw (ex-info "Duplicate timestamps in midrolls" {:type :bad-request})))
          _ (when (some #(<= % 0) midrolls)
              (throw (ex-info "Midroll timestamps must be positive" {:type :bad-request})))
          _ (when (not= (sort midrolls) midrolls)
              (throw (ex-info "Midroll timestamps must be in ascending order" {:type :bad-request})))]
      (vec (concat
              [{:position_type 0
                :type "AdInsertionPoint"
                :maximum_content_count default-pre-roll-count
                :maximum_content_duration default-pre-roll-duration
                :content_type "Campaign" :priority 1}]
             (mapv (fn [ts]
                     {:position_type 1 :start_position ts
                      :type "AdInsertionPoint"
                      :maximum_content_count default-midroll-count
                      :maximum_content_duration (if (= 1 (count deduped))
                                                 default-midroll-duration
                                                 default-midroll-multi-duration)
                      :content_type "Campaign" :priority 1})
                   deduped)
              [{:position_type 2
                :type "AdInsertionPoint"
                :maximum_content_count default-post-roll-count
                :maximum_content_duration default-post-roll-duration
                :content_type "Campaign" :priority 1}]))]
    (seq markers) markers
    :else nil))
```

### Update `tool-prepare-episode-version`

```clojure
(defn tool-prepare-episode-version
  [{:keys [episode_id midrolls markers status_on_completion released_at]} config]
  (when (str/blank? episode_id)
    (throw (ex-info "episode_id is required" {:type :bad-request})))
  (when (and midrolls markers)
    (throw (ex-info "midrolls and markers are mutually exclusive" {:type :bad-request})))
  (let [soc (or status_on_completion "active")
        _ (when (not (#{"active" "inactive"} soc))
            (throw (ex-info "status_on_completion must be 'active' or 'inactive'" {:type :bad-request})))
        copy-mp? (nil? midrolls)
        create-attrs (cond-> {:copy_active_version true
                              :copy_marker_points copy-mp?}
                       released_at (assoc :released_at released_at))
        create-body {:data {:type "episode_versions"
                            :attributes create-attrs
                            :relationships {:episode {:data {:type "episodes" :id episode_id}}}}}
        create-resp (api-post "/episode_versions" create-body config)]
    ;; ... rest of function same, but marker-list comes from build-marker-set
    (if (:error create-resp)
      create-resp
      (let [version-id (get-in create-resp [:data :data :id])
            marker-list (build-marker-set midrolls markers)
            marker-results (when (seq marker-list) (mapv ... marker-list))
            ...
```

### Fix pre-existing content_type bug

In the `markers` (explicit) path, when `content_type` is nil and the marker type is
`AdInsertionPoint`, default to `"Campaign"`:

```clojure
cr-attrs (cond-> {:priority cr-priority}
            (or (:content_type marker)
                (= "AdInsertionPoint" (:type marker)))
            (assoc :content_type (or (:content_type marker) "Campaign"))
```

### Tool schema update

```clojure
{:name "prepare_episode_version"
 :description "Create a new version of an episode with the standard 4-marker template
   (pre-roll + midrolls + post-roll) and submit for processing. Copies audio from the
   active version. Provide midrolls (array of timestamps in seconds) for the convenience
   path — pre-roll and post-roll are auto-generated with Campaign content rules.
   Use the markers param for full control. midrolls and markers are mutually exclusive.
   Returns the new version's status — poll get_episode_version until processing completes."
 :inputSchema {:type "object"
               :properties {:episode_id {:type "string" :description "Episode UUID"}
                            :midrolls {:type "array"
                                       :items {:type "number"}
                                       :description "Midroll timestamps in seconds.
                                         Each gets a midroll AdInsertionPoint. Ad slot defaults (tunable
                                         via constants at top of art19_mcp.bb): pre-roll 90s, single
                                         midroll 120s / 2+ midrolls 90s each, post-roll 180s. All get
                                         Campaign content rules. Mutually exclusive with markers param."}
                            :markers {:type "array"
                                      :description "Ad markers to add. Each marker gets a content
                                        rule. If omitted when midrolls is also omitted, copies
                                        existing markers from active version. Mutually exclusive
                                        with midrolls param."
                                      :items {:type "object" ...}}
                            :status_on_completion {:type "string"
                                                   :description "active or inactive. Default: active"}
                            :released_at {:type "string" :description "ISO 8601 datetime"}}
               :required []}}
```

## Test Plan

### New tests

| Test name | What it covers | Key assertions |
|-----------|---------------|----------------|
| `test-prepare-with-midrolls` | Happy path: `midrolls: [300, 900]` | 4 markers (pre + 2 mid + post); pre 90s, each mid 90s, post 180s; all Campaign; submitted; `copy_marker_points` = false |
| `test-prepare-with-midrolls-single` | Single midroll `midrolls: [600]` | 3 markers (pre + 1 mid + post); mid 120s |
| `test-prepare-with-midrolls-empty` | Edge: `midrolls: []` | Only pre + post created (2 markers), no midrolls |
| `test-prepare-with-midrolls-and-markers-errors` | Error: both params provided | Returns error about mutual exclusivity |
| `test-prepare-with-midrolls-duplicate` | Validation: `midrolls: [300, 300]` | Returns error about duplicates |
| `test-prepare-with-midrolls-negative` | Validation: `midrolls: [-1]` | Returns error about positive timestamps |
| `test-prepare-with-midrolls-unsorted` | Validation: `midrolls: [900, 300]` | Returns error about ascending order |

### Existing tests that must still pass

- `test-prepare-episode-version` (explicit markers path)
- `test-prepare-episode-version-no-markers` (copy only path)
- All 86 existing tests

### Fake API changes needed

The fake API POST `/marker_points` handler already returns `end_position` and
`start_position`. No changes needed for the new tests — the existing fixture data
and handlers cover the 4-marker template.

## Open Questions

1. **Pre-roll at position 0 with explicit start_position 0.** Is this needed? The
   existing preroll in the fixture has `start_position: nil`. The pre-roll we created
   in this session got `start_position: 0.0`. Either works based on our testing.
   Using `nil` is cleaner (let ART19 handle it). **Decision:** use `start_position: nil`
   for pre-roll, same as post-roll.

1. **Should we fix the content_type nil bug in the markers path simultaneously?**
   Yes — it's a one-line change and makes the tool more robust for agents that
   forget content_type. It affects the existing `markers` path too.

1. **Version history.** ART19 preserves old versions. The old version with just the
   two midrolls is still there. This is fine — agents can roll back if needed.
