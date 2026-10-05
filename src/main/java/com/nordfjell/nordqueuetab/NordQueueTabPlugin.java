package com.nordfjell.nordqueuetab;

import com.google.inject.Inject;
import com.nordfjell.nordqueue.NordQueuePlugin;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.slf4j.Logger;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

@Plugin(id = "nordqueuetab", name = "NordQueueTab", version = "1.1.1",
    description = "Bounded queue-only player list", authors = {"Nord Fjell"},
    dependencies = {@Dependency(id = "nordqueue")})
public final class NordQueueTabPlugin {
    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    // Velocity tasks and events are asynchronous. One owner lock covers lifecycle AND rendering.
    private final Object lock = new Object();
    private final QueueTabRenderer renderer = new QueueTabRenderer();
    private NordQueuePlugin queue;
    private QueueTabSettings settings = QueueTabSettings.defaults();
    private ScheduledTask task;
    private long generation;
    private boolean stopped;
    private long failedAt;

    @Inject public NordQueueTabPlugin(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy; this.logger = logger; this.dataDirectory = dataDirectory;
    }

    @Subscribe public void initialize(ProxyInitializeEvent event) {
        synchronized (lock) {
            Object instance = proxy.getPluginManager().getPlugin("nordqueue")
                .flatMap(container -> container.getInstance()).orElse(null);
            if (!(instance instanceof NordQueuePlugin candidate)) {
                logger.error("NordQueue 1.1.1 or newer is required; TAB will not start."); return;
            }
            try { candidate.snapshot(); }
            catch (LinkageError error) {
                logger.error("NordQueue snapshot API is unavailable; install NordQueue 1.1.1 or newer."); return;
            }
            queue = candidate;
            reloadSettings();
            proxy.getCommandManager().register(proxy.getCommandManager().metaBuilder("nordqueuetab")
                .plugin(this).build(), new ReloadCommand());
            startTask();
            logger.info("NordQueueTab 1.1.1 enabled, at most {} owned entries per viewer.", settings.maxVisible());
        }
    }

    private void startTask() {
        if (task != null) task.cancel();
        long expected = ++generation;
        task = proxy.getScheduler().buildTask(this, () -> refresh(expected))
            .delay(1, TimeUnit.SECONDS).repeat(settings.intervalMillis(), TimeUnit.MILLISECONDS).schedule();
    }

    private void refresh(long expected) {
        synchronized (lock) {
            if (stopped || expected != generation || queue == null) return;
            try {
                var snapshot = queue.snapshot(); // One immutable snapshot for the whole refresh.
                renderer.refresh(proxy, snapshot, queue.queueServerName(), settings);
            } catch (RuntimeException exception) {
                long now = System.nanoTime();
                if (failedAt == 0 || now - failedAt > TimeUnit.SECONDS.toNanos(30)) {
                    failedAt = now; logger.error("Queue TAB refresh failed; next refresh will retry", exception);
                }
            }
        }
    }

    @Subscribe public void disconnect(DisconnectEvent event) {
        synchronized (lock) { renderer.forget(event.getPlayer()); }
    }

    @Subscribe public void connected(ServerConnectedEvent event) {
        synchronized (lock) {
            if (queue != null && proxy.getPlayer(event.getPlayer().getUniqueId()).orElse(null) == event.getPlayer())
                renderer.connected(event.getPlayer(), event.getServer().getServerInfo().getName().equalsIgnoreCase(queue.queueServerName()));
        }
    }

    @Subscribe public void shutdown(ProxyShutdownEvent event) {
        synchronized (lock) {
            stopped = true; generation++;
            if (task != null) task.cancel();
            renderer.forgetAll(); // Connections are closing: send no packets during shutdown.
        }
    }

    private boolean reloadSettings() {
        try {
            Files.createDirectories(dataDirectory);
            Path file = dataDirectory.resolve("config.properties");
            Properties p = new Properties();
            if (Files.exists(file)) try (InputStream input = Files.newInputStream(file)) { p.load(input); }
            QueueTabSettings next = QueueTabSettings.from(p);
            next.validate(MiniMessage.miniMessage());
            if (!Files.exists(file)) {
                next.writeDefaults(p);
                try (OutputStream output = Files.newOutputStream(file)) {
                    p.store(output, "NordQueueTab: bounded TAB; estimate is not a promised waiting time.");
                }
            }
            settings = next; return true;
        } catch (Exception exception) {
            logger.error("Could not load NordQueueTab configuration; keeping last valid settings", exception);
            return false;
        }
    }

    private final class ReloadCommand implements SimpleCommand {
        @Override public boolean hasPermission(Invocation invocation) {
            return invocation.source().hasPermission("nordqueuetab.admin");
        }
        @Override public void execute(Invocation invocation) {
            boolean ok;
            synchronized (lock) {
                ok = !stopped && reloadSettings();
                if (ok) startTask();
            }
            invocation.source().sendRichMessage(ok ? "<green>NordQueueTab reloaded.</green>"
                : "<red>Reload failed; previous settings are still active. Check the console.</red>");
        }
    }
}
