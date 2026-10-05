# NordQueueTab 1.1.0

Separate Velocity presentation plugin for NordQueue. It renders the queue-only header,
footer, and player list. If it is disabled, queue routing continues to work normally.

Command: `/nordqueuetab` (`nordqueuetab.admin`) reloads its configuration.

Requires **NordQueue 1.1.1 or newer with the snapshot API** and the tested Velocity
4.2.1 / Java 25 environment. This is a prepared release, not a production deployment.
Do not install it alone on the current NordQueue 1.1.0 proxy.

The default `max-visible-players=80` shows the first 80 queue players; a viewer
outside that page sees the first 79 and themselves. Range: 1..80. The bound applies
to this plugin's owned entries, not additional entries supplied by another plugin.
`<position>` is now the **combined** priority + regular queue position, rather than
the old per-group position. `<estimate>` remains recognized but says
`depends on available slots`: queue position cannot predict how long a full server
will stay full. Existing custom headers remain unchanged on disk.

`update-interval-millis` accepts 500..60000; default 1000. MiniMessage `header`,
`footer`, and `player-format` support unparsed placeholders. Invalid numerical
configuration fails reload and keeps the last valid settings; no file is rewritten
during reload. Disconnect and proxy shutdown release viewer state. Entries owned
by the backend or other plugins are not adopted or removed; the queue backend must
not supply a competing full TAB. Queue header/footer should also have one owner.

Build on the Codex workstation (sources and outputs stay on the network share):

```powershell
& 'Z:\Minecraft Plagins\NordQueueTab\build.ps1' -ProxyPath 'C:\Users\artyo\Documents\Codex\nordqueuetab-test-20261003\proxy'
```

Build runs the 17 dependency-free regression scenarios automatically. See
[OPTIMIZATION-1.1.0.md](OPTIMIZATION-1.1.0.md) for scope, test evidence, deployment
requirements and limitations. `test-support` contains **local-only** helpers;
never place its probe JAR on the public proxy.
