# NordQueueTab

Queue-only TAB presentation for Velocity. NordQueue owns routing; disabling NordQueueTab does not stop the queue.

## Compatibility

Requires NordQueue 1.1.1 or newer with the snapshot API. The integration was tested with Velocity 4.2.1 and Java 25. NordQueue 1.1.0 lacks the required API.

## Display

`max-visible-players` defaults to 80 and accepts 1..80. A viewer inside the visible prefix sees the first entries. A viewer outside it sees the first 79 plus themselves at the default limit.

This bounds entries owned by NordQueueTab, not entries created by other plugins. The plugin does not adopt or remove another plugin's entries. Avoid a competing full TAB renderer on the queue backend; header/footer rendering should have one owner.

`<position>` is the combined priority-plus-regular position, not the old per-group position. The recognized `<estimate>` placeholder renders `depends on available slots`; it does not promise an ETA when the main server is full.

Header, footer and `player-format` use MiniMessage with unparsed placeholders. Existing custom header text is not rewritten on disk.

## Configuration

`update-interval-millis` accepts 500..60000 and defaults to 1000. Invalid numeric settings on reload retain the previous settings without rewriting the file.

Disconnect and shutdown release viewer state.

## Permissions

| Permission | Allows |
| --- | --- |
| `nordqueuetab.admin` | `/nordqueuetab` and `/nordqueuetab reload` |

Velocity's permission provider supplies player grants; the plugin registers no default player grant. A Paper/Folia permission assignment does not grant proxy access.

Viewing the queue TAB requires no separate permission.

## Build and tests

Use Maven 3.9+ and JDK 25: `mvn clean verify` or `./build.ps1`. See [BUILDING.md](BUILDING.md).

The older network-share `build.ps1 -ProxyPath` command and `C:\Users\artyo\Documents\Codex\nordqueuetab-test-20261003\proxy` directory describe a historical local fixture, not the current release build.

The 1.1.0 optimization checks include 17 dependency-free regression scenarios. The original [OPTIMIZATION-1.1.0.md](OPTIMIZATION-1.1.0.md) reference names a historical report excluded from the public repository; that file is not available here. Probe helpers belong only on isolated fixtures, never on a public proxy.
