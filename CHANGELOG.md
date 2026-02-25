# Changelog

All notable changes to this project will be documented in this file.

## [Unreleased]

### Fixed

- Fixed `list_episodes` 400 Bad Request by adding pagination defaults (page=1, page_size=100).
- Added validation for `list_episodes` - requires one of series_id, series_slug, or season_id.
- Include raw ART19 error response in tool error messages for debugging.

### Changed

- Added structured JSON logging (timestamp, level, message, data) for all tool calls and API requests/responses.
- Improved ART19 error messages to include error code, parameter, and detail.
- Updated tool descriptions to emphasize required parameters (list_episodes, get_series, list_seasons).
- Added `enclosure_url` to `list_feed_items` response (public MP3 URL).
- Updated `list_media_assets` fixture/test to match real API fields (`duration_in_ms`, `url`, `asset_type`).
- Added missing params to tool schemas to match upstream API (page, page_size, sort, etc).

### Added

- Added new tools: `update_episode_version`, `get_episode_next_sibling`, `get_episode_previous_sibling`, `upload_image`, `list_media_assets`, `get_feed_item`, `create_feed_item`, `update_feed_item`, `delete_feed_item`.

## [1.0.0] - 2026-02-20

### Fixed

- Fixed fake API body double-read bug in integration tests.
- Added nil guard to `tool-result` helper in tests.
- Fixed `handle-mcp` to correctly handle JSON parsing errors.
- Renamed `art19-mcp.bb` to `art19_mcp.bb` to satisfy clj-kondo namespace matching.
- Added explicit title validation in `create_episode`.
- Updated test expectations for slug resolution and published filters.

### Changed

- Moved tests to `tests/` directory.
- Updated `bb.edn` with `serve` task and corrected paths.
- Switched to snapshots-first workflow with feature branches.
- Initialized `.art19/state.edn` for project tracking.
