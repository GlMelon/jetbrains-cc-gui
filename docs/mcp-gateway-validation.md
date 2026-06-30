# MCP Gateway CLI Validation

Manual matrix for the CLI-only MCP Gateway rollout.

| Provider | Runtime | Expected |
|---|---|---|
| Claude | CLI | The command uses `--mcp-config` pointing at a temp Gateway config, and the model can call real MCP tools exposed through Gateway names. |
| Codex | CLI | The process uses temp `CODEX_HOME`, first output is not blocked by a broken real MCP server, and real tools remain available through Gateway. |
| OpenCode | CLI | The process uses temp `HOME`/`USERPROFILE`/XDG config, keeps stdin redirected to null, and only loads the Gateway MCP config. |

Scenarios to run before enabling the feature flag by default:

- Configure one healthy `idea_mcp` server and one failing HTTP MCP server.
- Start Claude, Codex, and OpenCode CLI turns with `mcpGateway.enabled=true` and `mcpGateway.cli.enabled=true`.
- Confirm the CLI only sees `melon_gateway`, while `tools/list` returns concrete tools such as `mcp__claude__idea_mcp__run_test`.
- Disable `idea_mcp`; confirm the current turn keeps its pinned revision and the next turn no longer lists that tool.
- Re-enable `idea_mcp`; confirm a new revision appears and the next turn can call it.
- Delete a server; confirm its supervisor is stopped and the node process panel does not show leaked children.
- Close the project; confirm `mcp-gateway-server.js` exits and the state file is removed.
