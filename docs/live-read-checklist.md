# Live-read (EmbeddedAdPoint) checklist

**Read this before every episode that has a baked sponsor read.**

Ad Ops are exacting — a read that looks fine in ART19 but isn't trafficked
plays ART19-only and silently never reaches Spotify or Apple. There is no
error. It just quietly doesn't count. Every item below exists because
something went wrong once.

______________________________________________________________________

## 0. Classify the regions first

Source: `~/Downloads/workdir/Linux Unplugged NNN (Ads) Ads.csv`

| CSV shape | Meaning | Action |
|---|---|---|
| ~1s wide (`Dynamic N`) | dynamic break | `AdInsertionPoint` midroll |
| 30–120s wide, `Nebula` | **NEVER MARKED** | none — documented exception |
| 30–120s wide, any other sponsor | ART19-managed live read | `EmbeddedAdPoint` (this checklist) |

Nebula is the *only* skip. Every other baked read MUST be marked — if it
can't be bound, **abort the publish**, do not ship without it.

______________________________________________________________________

## 1. Ad record — created in the ART19 CMS, not the API

Ad creative upload has **no API route** (verified against ART19's own
OpenAPI specs — see `ad-upload-recon.md`). A human does this step.

- **Type:** `LIVE READ` (not TraditionalAd)
- **Title:** episode/date-specific, e.g. `Hostinger 11/1 Ad`.
  **Never** reuse last week's title. Ep691 nearly bound 688's read
  (`688: Eyes on Deck Hostinger Live Read`, 63.5s) instead of its own
  72.48s read — same sponsor, different creative, silent wrong audio.
- **Audio:** the read exactly as baked into the Ads FLAC.

## 2. Trafficking — the part that actually decides Spotify/Apple

An ad with no campaign is the ART19-only failure mode. Compare:

| Ep | Ad | campaign |
|---|---|---|
| 688 | `983bba60` "688: Eyes on Deck Hostinger Live Read" | **nil** → ART19-only |
| 691 | `41a082b7` "Hostinger 11/1 Ad" | `94e01f43` → carries everywhere |

Required chain on the ad record:

```
advertisement
  └─ campaign          Campaigns::Paid, status active
       ├─ brand
       ├─ insertion_order
       └─ line item    "<Sponsor> (<date> - Faked In) - Linux Unplugged"
```

- **"Faked In"** in the line-item name is the documented pattern for
  targeting a single episode so a dynamic ad behaves like a baked-in one.
- **Ad Ops** field should read `True Native Media` (matches the campaign's
  `ad_rep_account`).

## 3. Pre-publish verification (API)

```bash
# the ad exists, is a live read, and HAS audio
GET /advertisements?type=LiveReadAd
#   -> must include your episode-specific title
#   -> media_present MUST be true   (false = unbindable, e.g. JAXOB)

# the trafficking chain is real
GET /advertisements/<id>            # relationships.campaign must NOT be nil
GET /campaigns/<campaign-id>        # status active, brand + insertion_order set
```

**Duration cross-check** — the API does not expose ad-audio duration, so
compare by hand:

```
CSV region width  ==  ad page duration
ep691: 1127.48 - 1055.00 = 72.48s   vs   00:01:12.48  ✓
```

A mismatch means the matcher picked the wrong creative. Stop.

## 4. Publish — the binding

```clojure
;; marker: position_type MUST be null for EmbeddedAdPoint
create_marker_point(episode_version_id: "…", position_type: null,
                    type: "EmbeddedAdPoint",
                    start_position: 1055.00, end_position: 1127.48,
                    maximum_content_duration: <region width>, maximum_content_count: 1)

;; rule MUST bind the advertisement — a bare Campaign rule fails processing
create_marker_point_content_rule(marker_point_id: "…", priority: 1,
                                 content_type: "Advertisement",
                                 content_id: "<advertisement uuid>",
                                 content_type_target: "advertisements")
```

`publish-lup.py` does this automatically. It refuses to bind an ad whose
title names a different episode, and it aborts if no bindable ad exists.

## 5. Post-publish verification

**Dashboard** (episode page → Markers) must read:

```
Pre-Roll 1   00:00:00.00        Max 180 Secs & 1 Position
Embedded Ad  00:17:35–00:18:47  Live Read: Hostinger 11/1 Ad
Mid-Roll 1   00:38:07.00        Max 120 Secs & 3 Positions
Post-Roll 1  01:19:59.66        Max 180 Secs & 2 Positions
```

- the embedded row must name **this episode's** ad, not a previous one
- **Ad page** → Line Item populated, Brand populated, Ad Ops = True Native Media

**Line item cannot be verified via API** — `/insertion_orders` is 403 for the
Series-scoped token. That one needs a human glance in the Ad Ops UI.

______________________________________________________________________

## Failure modes this prevents

| Failure | Symptom | Guard |
|---|---|---|
| Bare `Campaign` rule | version `processing_failed`, no validation message | bind `content_type: "Advertisement"` + `content_id` |
| `position_type` as integer | 422 "Position type must be blank" | send `null` |
| Reused ad title | wrong audio serves, no error | episode-specific title + matcher ranking + episode-number guard |
| Un-trafficked ad | ART19-only, never reaches Spotify/Apple | campaign → brand → IO → line item |
| `media_present: false` | marker unbindable, version fails | check before publishing |
| Failed version on a live episode | **episode goes offline, enclosure 404s** | never experiment on a live version; test on an unpublished scratch episode |
