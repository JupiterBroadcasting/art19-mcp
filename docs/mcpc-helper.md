# mcpc Helper — Quick Reference

## What It Is

mcpc is the universal MCP CLI companion. It manages MCP session lifecycle (auth, handshakes, session IDs) so you can just call tools via one command.

In this project it powers Art19's MCP server on `http://127.0.0.1:3007/mcp` via the `@art19` session.

## Session Setup

```bash
mcpc connect http://127.0.0.1:3007/mcp @art19
```

This does:

1. POST `/mcp` with `{"jsonrpc":"2.0","method":"initialize"}`
1. Server returns `Mcp-Session-Id` header
1. All subsequent requests include that session ID
1. Session stays alive for reuse

## MCP Tool Calls — The Core Pattern

mcpc wraps MCP's `tools-call` into the simplest possible CLI syntax. **You must use `--json`** to get parseable output instead of the MCP tool's pretty-printed text wrapper.

```bash
# ❌ Without --json — gets wrapped in MCP tool response text
mcpc @art19 tools-call list_episodes series_slug:='"linux-unplugged"'

# ✅ With --json — returns clean JSON from the server
mcpc @art19 --json tools-call list_episodes series_slug:='"linux-unplugged"'
```

## Argument Syntax

Arguments use `key:=value` format. Strings need quoting in shell:

```bash
mcpc @art19 --json tools-call list_episodes series_slug:='"linux-unplugged"'

mcpc @art19 --json tools-call create_marker_point \
  episode_version_id:='"abc-123"' \
  position_type:=1 \
  type:='"AdInsertionPoint"'

mcpc @art19 --json tools-call list_episodes \
  published:=true \
  published:=false      # boolean flags (no quotes needed)
```

## Common Patterns

### List with filters

```bash
mcpc @art19 --json tools-call list_episodes \
  series_slug:='"linux-unplugged"' \
  page_size:=5 \
  itunes_type:='"full"'
```

### Get single resource

```bash
mcpc @art19 --json tools-call get_episode episode_id:='"{uuid}"'
```

### Get with includes (ART19 specific)

```bash
mcpc @art19 --json tools-call get_episode \
  episode_id:='"{uuid}"' \
  include:='"active_version"'
```

### List nested resource (many-to-one)

```bash
mcpc @art19 --json tools-call list_feed_items \
  episode_id:='"{episode_uuid}"'

mcpc @art19 --json tools-call list_marker_points \
  episode_version_id:='"{version_uuid}"'
```

### Create

```bash
mcpc @art19 --json tools-call create_episode \
  title:='"New Episode"'\
  series_slug:='"lu"'
```

### Get tool schemas (for lookup)

```bash
mcpc tools-get create_marker_point
mcpc tools-get create_marker_point_content_rule
```

## What mcpc Handles for You

- **Session lifecycle** — init, keepalive, reconnect on 400/401/404
- **JSON-RPC framing** — wraps `{"method":"tools/call","params":{...}}` automatically
- **Session ID** — injects `Mcp-Session-Id` header on every call
- **Error formatting** — wraps tool errors in `isError: true` content blocks
- **OAuth** — `mcpc login <server>` persists tokens for future sessions

## Shell Scripting Tip

When piping to `jq` or Python:

```bash
# Extract specific field from result
mcpc @art19 --json tools-call list_feed_items \
  episode_id:='"e5559836-18a8-4450-a320-c118e6a3fd94"' \
  | jq '.[0].text' \
  | jq '.feed_items[0].enclosure_url'
```

Or in Python:

```python
result = json.loads(subprocess.check_output(["mcpc", "@art19", "--json", "tools-call", "list_episodes", "series_slug:='\"lu\"']))
```
