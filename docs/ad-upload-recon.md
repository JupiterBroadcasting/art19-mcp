# ART19 ad-upload API reconnaissance (read-only)

Date: 2026-11-01 · Host: showfactory · Credentials: Series-scoped
token from `/var/lib/hermes/credentials/art19.env` (never printed) · Base: `https://art19.com`

No create/update/delete was executed. Every mutating verb below was sent with a
**completely empty body** (`--data-binary ""`), which the JSON:API parameter parser
rejects before any resource is instantiated, so nothing could be written. The single
non-empty POST bodies used were a deliberate **type mismatch** (`"type":"wrong_type_xyz"`),
which is rejected by the resource-type guard before attributes are applied.

______________________________________________________________________

> **CORRECTION (verified 2026-11-01 by direct probe):** this document claims
> `/advertisements` 400s on `page[*]` and returns all records in one page.
> That is **wrong** — pagination works normally (`page[number]=1&page[size]=100`
> returns 100 rows with `links.next`; page 3 returns the final 82). What *is*
> true is that the JSON:API-style **filters** are silently ignored
> (`filter[type]=…` returns all 282), while the non-standard `type=LiveReadAd`
> **does** filter correctly (3 rows). The publisher now uses `type=LiveReadAd`
> with a pagination fallback.

## Verdict

**PARTIAL — we can create the advertisement *record*, but we cannot attach audio.**

- **`POST /advertisements` exists and is a real JSON:API create endpoint.** High confidence
  (~90%): routed, method-accepted, and it type-validates against `advertisements`.
  *Authorization for a real payload is unverified* (see Blockers #1).
- **Audio attachment has no API route.** High confidence (~95%): `advertisement_versions`
  is read-only across every verb (POST/PATCH/PUT → 405), `/media_assets` is GET-only, and
  no nested upload route exists under `/advertisements/{id}/…`. Audio for live reads is
  uploaded through the CMS UI only.
- **Net:** a programmatic ad upload is **not achievable end-to-end today**. Best case an API
  call creates an ad with `media_present: false` — the same shape as the existing `JAXOB`
  record, which per `docs/publishing-workflow.md` "can't be bound meaningfully".
  That is not a usable replacement for the UI flow.

There is exactly **one** unverified hypothesis that could change this: that
`POST /advertisements` accepts an inline `source_url` and auto-creates the version +
`AdSegmentList` (the way `POST /images` and `POST /episode_versions` accept `source_url`).
It is plausible but **unproven** — confirming it requires one controlled write.

______________________________________________________________________

## Evidence

### 1. Published specs do not document `/advertisements`

| Spec | Paths | Contains `/advertisements`? |
|---|---|---|
| `/swagger_json/external/content.json` | 52 | no |
| `/swagger_json/internal/content.json` | 14 | no |
| `/swagger_json/ad_ops/content.json` | 14 | no |

All three return 200. `ad_ops` covers `agencies`, `brands`, `campaign_series`, `campaigns`,
`flights`, `insertion_orders`, `external_tracking_endpoints` — i.e. the ad-*ops* side, not
the creative. The only docs page (`/api-docs/index.html`) loads `?scope=external` by
default and exposes no scope switcher for ads. Other scope names probed (`ads`, `adops`,
`advertisement(s)`, `media`, `creatives`, `delivery`, `trafficking`, …) → 404.

⇒ `/advertisements` is an **undocumented legacy controller**, hand-rolled rather than
`jsonapi-resources` (evidence: no `meta`, no `links`, no pagination, `include` silently
ignored, `filter[*]` silently ignored).

### 2. Read surface (what works with our token)

```
GET /advertisements                                        200  282 records, all in one page
GET /advertisements/41a082b7-4b7f-4220-912b-7f3cac612f02    200
GET /campaigns                                             200
GET /campaigns/94e01f43-6165-4d05-833c-e8a4082dc197         200  full ad-ops attributes
GET /brands                                                200
GET /agencies                                              200
GET /ad_rep_accounts                                       200
GET /segment_lists                                         200  4,439 records
GET /segment_lists/2e9ccfd6-9bff-4b29-bf1c-3410d14972ab     200  + included segments
GET /advertisement_versions/95a89227-…fee171f               200
GET /flights                                               403
GET /insertion_orders                                      403
GET /segments, /segments/{id}                              406  (not a JSON:API route)
GET /ad_products                                           404
```

Single advertisement (LiveReadAd, `Hostinger 11/1 Ad`):

```json
{"data":{"id":"41a082b7-4b7f-4220-912b-7f3cac612f02","type":"advertisements",
 "attributes":{"created_at":"2026-10-28T14:06:54.844Z","deletable":false,"end_date":null,
   "listen_count":0,"media_present":true,"sequence":null,"start_date":null,
   "status":"active","title":"Hostinger 11/1 Ad","type":"LiveReadAd",
   "updated_at":"2026-10-28T14:07:16.509Z"},
 "relationships":{
   "agency":{"data":null},
   "campaign":{"data":{"id":"94e01f43-6165-4d05-833c-e8a4082dc197","type":"campaigns"}},
   "episode":{"data":null},
   "series":{"data":{"id":"b357b92c-4757-4cdc-8c28-a5cf2316a9bf","type":"series"}}}}}
```

Notes that matter for a tool:

- `?include=campaign,series,episode,agency` is **silently ignored** (byte-identical response,
  no `included` member).
- `filter[type]`, `filter[status]`, `filter[campaign_id]`, `filter[series_id]`,
  `filter[ids]`, `filter[ids][]`, `filter[query]` are **all silently ignored** — every
  variant returns the same 282 records.
- `page[size]` and `page[number]` → **400** (`Bad Request`, no detail). So there is no
  pagination and no server-side filtering: a tool must fetch all 282 and filter client-side.
  (`docs/publishing-workflow.md` claims "100 per page, ~270 records" — that is **no longer
  true** as of today's probe; the endpoint now returns the full set unpaginated.)
- `deletable: false` on both trafficked live reads; `true` on the untrafficked ones.

### 3. Create routes — method-routing probe

`405` is this API's generic "not routed" response — a POST to a nonexistent path
(`/definitely_not_a_route_xyz123`) and to a known GET-only path (`/genres`) both return
405\. So `422` is the reliable "route + method exist" signal.

```
POST /advertisements                       (empty body)  422  {"source":{"pointer":"/data"},
                                                            "detail":"Parameter is missing or the value is empty: “data”"}
PUT  /advertisements/{id}                  (empty body)  422  same parser error
PATCH /advertisements/{id}                 (empty body)  422  same parser error
POST /advertisements/{id}                  (empty body)  405
```

And the type guard — proof this is a typed JSON:API resource controller:

```
POST /advertisements  {"data":{"type":"wrong_type_xyz","attributes":{}}}
  → 422 {"source":{"pointer":"/data/type"},
         "detail":"The primary data type provided does not match the resource’s type."}
```

Nested / alternate ad routes — **all 405** (do not exist):

```
POST /advertisements/{id}/media_assets        POST /advertisements/{id}/media
POST /advertisements/{id}/audio               POST /advertisements/{id}/versions
POST /advertisements/{id}/version             POST /advertisements/{id}/publish
POST /advertisements/{id}/upload              POST /advertisements/{id}/media_upload
POST /advertisements/{id}/source_url          POST /advertisements/{id}/segment_lists
POST /advertisements/{id}/relationships/{campaign,series,episode,agency}
POST /advertisements/{id}/advertisement_versions
```

`OPTIONS` is useless here: `/episodes`, `/networks`, `/images`, `/advertisements`,
`/campaigns`, `/credits`, `/media_assets` **all** return 405 with no `Allow` header.

### 4. How the audio actually hangs off an ad

This is the important structural finding. `segment_lists` carries the media, and the
`AdSegmentList` type is the ad-creative equivalent of `EpisodeSegmentList`:

```
GET /segment_lists  → 4,439 records
  OriginalMediaSegmentList 1530 · EpisodeSegmentList 1453 · CleanSegmentList 1453 · AdSegmentList 3
```

Exactly 3 `AdSegmentList`s exist — matching the 3 `LiveReadAd` records (the fourth
LiveReadAd, `JAXOB`, has none and `media_present: false`). Each attaches to an
**`advertisement_versions`** record, not to the advertisement directly:

```json
{"id":"2e9ccfd6-9bff-4b29-bf1c-3410d14972ab","type":"segment_lists",
 "attributes":{"duration":"67.60451","status":"available","type":"AdSegmentList", …},
 "relationships":{"attached":{"data":{"id":"28568e8f-0d3a-4687-b43f-b651c1b34740",
                                      "type":"advertisement_versions"}}}}
```

And `advertisement_versions` mirrors `episode_versions` in shape:

```json
{"id":"95a89227-4dfc-4389-ac14-29835fee171f","type":"advertisement_versions",
 "attributes":{"created_at":"2026-11-02T01:09:20.763Z",
   "file_name":"20261102-TGludXglMjBVbnBsdWdnZWQlMjA2OTElMjAlMjhBZHMlMjkuZmxhYw-1797af3d-…",
   "status":"active","updated_at":"2026-11-02T01:09:36.014Z"},
 "relationships":{"advertisement":{"data":{"id":"41a082b7-4b7f-4220-912b-7f3cac612f02",
                                          "type":"advertisements"}}}}
```

The same ad has **two** versions, which is the active-version replacement pattern:

| version | file_name | status | created |
|---|---|---|---|
| `28568e8f-0d3a-4687-b43f-b651c1b34740` | `Hostinger-LUP-03-BlackFriday` | `inactive` | 2026-11-02T00:57:11Z |
| `95a89227-4dfc-4389-ac14-29835fee171f` | `20261102-<base64>Linux Unplugged 691 (Ads).flac</base64>-1797af3d…` | `active` | 2026-11-02T01:09:20Z |

So the *product* supports versioned ad creatives with active/inactive swap — exactly like
episodes — but **`advertisement_versions` is read-only through the API**:

```
POST   /advertisement_versions                             405
POST   /advertisement_versions/{id}/duplicate              405
PATCH  /advertisement_versions/{id}                        405
PUT    /advertisement_versions/{id}                        405
```

(Contrast: `POST /episode_versions` is documented and routed, with `source_url` +
`copy_active_version` + `copy_marker_points`. The ad equivalent was simply never exposed.)

`/media_assets` is likewise read-only and empty for ads:

```
GET  /media_assets                                       400  "Required query parameter missing"
GET  /media_assets?attachment_id=41a082b7-…&attachment_type=Advertisement   200  {"data":[]}
GET  /media_assets?attachment_id=41a082b7-…&attachment_type=Segment         200  {"data":[]}
GET  /media_assets?attachment_id=41a082b7-…&attachment_type=SegmentList     200  {"data":[]}
POST /media_assets                                       405
```

### 5. Ad-ops permissions (what our Series token can and cannot do)

```
PATCH /campaigns/94e01f43-…      403   ← authz runs BEFORE param parsing here
PATCH /advertisements/41a082b7-… 422   ← param parsing runs BEFORE authz here
POST  /campaigns                 422   ← route exists; 403 expected with a real body
POST  /flights, /brands, /agencies, /insertion_orders   422 (routes exist)
GET   /flights                   403
GET   /insertion_orders          403
```

The 403 on `PATCH /campaigns/{id}` with an empty body is meaningful: on that controller
authorization precedes parameter validation. On the advertisements controller the order is
reversed, which is why we cannot read the authz outcome for `POST /advertisements` without
sending a real payload.

______________________________________________________________________

## Proposed MCP tool

### What is safe and useful *today* (read-only)

The concrete pain this solves is the one already documented in
`docs/publishing-workflow.md`: finding the right advertisement UUID to bind into an
`EmbeddedAdPoint` content rule. Today that's a 282-record manual scan with no server-side
search.

```
list_advertisements(series_slug?, campaign_id?, type?, media_present?, query?, limit?)
  → GET /advertisements            (no params supported — fetch all, filter client-side)
  → client-side match on title substring (titles are episode-qualified,
    e.g. "688: Eyes on Deck Hostinger Live Read"), type == "LiveReadAd",
    media_present == true
  → returns [{id, title, type, status, media_present, deletable, campaign_id, series_id}]

get_advertisement(advertisement_id)
  → GET /advertisements/{id}

list_advertisement_versions(advertisement_id)
  → GET /advertisement_versions/{id} for each version discovered via segment_lists
    (there is no collection filter; cache the /segment_lists snapshot and match
     relationships.attached.type == "advertisement_versions")
```

`find_live_read_for_episode(series_slug, episode_number)` would be the high-value wrapper:
match `LiveReadAd` titles containing the episode number, require `media_present: true`,
and hand back the UUID ready for `create_marker_point_content_rule`
(`content_type: "Advertisement"`, `content_type_target: "advertisements"`).

### The write tool, if a human greenlights a controlled test

```
create_advertisement(title, type = "LiveReadAd", series_id?, campaign_id?, status = "active")
  → POST /advertisements
    {"data":{"type":"advertisements",
             "attributes":{"title":"…","type":"LiveReadAd","status":"active"},
             "relationships":{
               "series":  {"data":{"id":"…","type":"series"}},
               "campaign":{"data":{"id":"…","type":"campaigns"}}}}}
```

This is the **inferred** minimal payload — modelled on the observed record shape and on the
sibling `POST /episode_versions` / `POST /images` conventions. Attribute names beyond
`title`/`type`/`status` are **unverified**; `created_at`/`updated_at`/`deletable`/
`media_present`/`listen_count` are server-derived. `sequence`/`start_date`/`end_date` are
nullable and null on every live read observed.

A companion `upload_advertisement_audio(advertisement_id, source_url)` is **not
implementable** — no route exists. If the inline-`source_url` hypothesis holds, it would
collapse into `create_advertisement(..., source_url=…)`, but that must be tested, not assumed.

______________________________________________________________________

## Blockers

1. **Authorization for `POST /advertisements` is unverified.** The 422 we saw comes from
   parameter parsing, which on this controller runs *before* the Pundit check. A real
   payload could just as easily return 403. **Needs from a human:** approval for one
   controlled write — create a scratch `LiveReadAd` on the `lu` series with no campaign,
   read back the result, then `DELETE` it (records that were never trafficked come back
   `deletable: true`; both trafficked ones are `deletable: false`). That single test
   settles authz, the required-attribute set, and the inline-`source_url` question at once.

1. **No API path for ad audio — this is the hard blocker.** `advertisement_versions` is
   GET-only; `/media_assets` is GET-only; every nested upload route 405s. A tool could
   create an ad record but never give it audio, leaving `media_present: false` — which
   `docs/publishing-workflow.md` already establishes is not bindable. **Needs from a human:**
   an ART19/Wondery support request to expose `POST /advertisement_versions`
   (`source_url`, `copy_active_version`) the way `POST /episode_versions` already is. Until
   then the audio step stays in the CMS UI.

1. **Ad Ops scope is denied.** `/flights` and `/insertion_orders` → 403, and
   `PATCH /campaigns/{id}` → 403 even with an empty body. So even with (1) and (2) solved,
   *trafficking* the ad (campaign/flight/line-item scheduling) is out of reach for this
   token. That matches the earlier note in `docs/publishing-workflow.md` ("line-item
   scheduling stays manual in the Ad Ops UI"). A separate Ad Ops-profile credential would
   be required.

1. **No filtering or pagination on `/advertisements`.** Every `filter[*]` is ignored and
   `page[*]` → 400. Any tool must pull all 282 records and filter locally. That works today
   but degrades linearly; worth noting if the roster grows.

1. **`/segments` is not a JSON:API route** (406 with `application/vnd.api+json`, HTML 404
   otherwise), so per-segment detail is unreachable. Only `segment_list_segments` →
   `segment` relationship IDs are visible via `?include=` on `/segment_lists/{id}`.

### Explicitly unverifiable without a write

- Whether `POST /advertisements` returns 201 or 403 with a valid payload.
- The exact required-attribute set (`title` only? `type`? `status`?).
- Whether `POST /advertisements` accepts `source_url` / `copy_active_version`.
- Whether relationship IDs are mandatory or optional (existing records show both
  `campaign: null` with `series` set, and both set).
