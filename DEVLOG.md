# Devlog

## 2026-02-20

### MVP Alignment

Initial assessment revealed several discrepancies between the intended architecture and the implementation:

1. Integration tests were failing due to http-kit body stream reading issues.
1. The MCP server was crashing on invalid JSON due to a missing return early.
1. Clj-kondo was unhappy with the filename/namespace mismatch.

Fixed these and moved tests to a proper `tests/` directory. Added `.art19/state.edn` to track the project state. All 31 tests (73 assertions) are now passing.

Definition of Done met: tests green, lint clean, health endpoint functional.
