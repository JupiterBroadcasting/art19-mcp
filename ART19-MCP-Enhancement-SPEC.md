# ART19 MCP Enhancement Specification

## Overview
Extend the ART19 MCP wrapper to support episode_number and media_rating fields that are required for complete episode management.

## Problem Statement
The current ART19 MCP wrapper is missing critical fields that force manual dashboard intervention:
1. `episode_number` - Episode numbering (e.g., 656)
2. `media_rating` - Content rating (clean/explicit)

These fields exist in the ART19 API but are not exposed in the MCP tools.

## Current State

### Supported Fields (via MCP)
- title
- description
- summary
- release_date
- status (published/draft)
- audio_url (via episode_version)

### Missing Fields (require dashboard)
- episode_number
- media_rating (clean/explicit)
- season_number
- episode_type (full/bonus/trailer)

## Requirements

### 1. Add episode_number Support
**Location:** `create_episode`, `update_episode`

**API Reference:** The episode number is set via the `episodes` relationship to `season` or directly via episode attributes.

**Implementation:** Add `episode_number` parameter to episode create/update tools:
```python
create_episode(
    series_id="...",
    title="...",
    description="...",
    episode_number=656  # NEW
)
```

**ART19 API Behavior:**
- Can be set on create or update
- Must be integer
- Displayed in podcast feeds

### 2. Add media_rating Support
**Location:** New tool `set_media_rating` or extend `update_episode`

**API Reference:** Media ratings are a separate relationship endpoint:
- `POST /episodes/{id}/media_ratings` - Set rating
- Rating values: "clean", "explicit"

**Implementation Options:**

**Option A: New Tool**
```python
set_media_rating(episode_id="...", rating="clean")
```

**Option B: Extend Update**
```python
update_episode(episode_id="...", media_rating="clean")
```

**ART19 API Behavior:**
- Requires separate API call to media_ratings relationship
- Value must be one of: "explicit", "clean", "none"
- Affects iTunes podcast rating tags

### 3. Timezone Handling (Optional Enhancement)
**Current:** release_date accepts ISO 8601 UTC

**Enhancement:** Accept timezone-aware strings:
```python
publish_episode(episode_id="...", release_date="2026-03-01 18:00:00-08:00")  # PST
```

Auto-convert to UTC before sending to API.

## Technical Implementation

### Files to Modify
1. `/home/joe/J.O.E/modules/art19-mcp/` - MCP server source
2. `/home/joe/openclaw/workspace/skills/art19/SKILL.md` - Documentation

### API Endpoints Used
- `PATCH /episodes/{id}` - Update episode (episode_number in attributes)
- `POST /episodes/{id}/media_ratings` - Set media rating

### Testing
- Create episode with episode_number=656
- Set media_rating=clean
- Verify via `get_episode` includes new fields
- Verify RSS feed shows correct values

## Acceptance Criteria
- [ ] Can create episode with explicit episode_number
- [ ] Can update episode_number on existing episode
- [ ] Can set media_rating to "clean"
- [ ] Can set media_rating to "explicit"
- [ ] get_episode returns episode_number and media_rating
- [ ] Documentation updated with new parameters

## Priority
**High** - These fields are required for every episode publish

## Estimated Effort
2-4 hours for implementation + testing

---
**Spec Created:** 2026-03-01
**For:** ART19 MCP Enhancement
