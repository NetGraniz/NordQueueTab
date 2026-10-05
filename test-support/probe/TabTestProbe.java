package com.nordfjell.nordqueuetabtest;

import com.google.inject.Inject;
import com.google.gson.Gson;
import com.nordfjell.nordqueue.NordQueuePlugin;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.player.TabListEntry;
import com.velocitypowered.api.util.GameProfile;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;
import java.util.*;

/** LOCAL TEST ONLY. Inspect proxy-side TAB and inject one foreign row. Never deploy to production. */
public final class TabTestProbe {
    private final ProxyServer proxy; private final Logger logger;
    private final Gson gson = new Gson(); private NordQueuePlugin queue; private Object tabPlugin;
    private final UUID foreignId = new UUID(111, 222);
    @Inject public TabTestProbe(ProxyServer proxy, Logger logger) { this.proxy = proxy; this.logger = logger; }
    @Subscribe public void initialize(ProxyInitializeEvent event) {
        queue = (NordQueuePlugin) proxy.getPluginManager().getPlugin("nordqueue").orElseThrow().getInstance().orElseThrow();
        tabPlugin = proxy.getPluginManager().getPlugin("nordqueuetab").orElseThrow().getInstance().orElseThrow();
        proxy.getCommandManager().register(proxy.getCommandManager().metaBuilder("tabtest").plugin(this).build(),
            (SimpleCommand) invocation -> {
                if (invocation.source() instanceof Player) return;
                String[] args = invocation.arguments(); if (args.length < 2) return;
                if (args[0].equals("foreign")) {
                    Player player = proxy.getPlayer(args[1]).orElseThrow();
                    var tab = player.getTabList();
                    if (tab.getEntry(foreignId).isEmpty()) tab.addEntry(TabListEntry.builder().tabList(tab)
                        .profile(new GameProfile(foreignId, "ForeignRow", List.of())).displayName(Component.text("FOREIGN_ROW"))
                        .latency(12).gameMode(0).listOrder(0).build());
                } else if (args[0].equals("state")) {
                    try {
                        var lockField = tabPlugin.getClass().getDeclaredField("lock"); lockField.setAccessible(true);
                        synchronized (lockField.get(tabPlugin)) {
                            var rendererField = tabPlugin.getClass().getDeclaredField("renderer"); rendererField.setAccessible(true);
                            Object renderer = rendererField.get(tabPlugin);
                            var viewerCount = renderer.getClass().getDeclaredMethod("viewerCount"); viewerCount.setAccessible(true);
                            var ownedCount = renderer.getClass().getDeclaredMethod("ownedCount"); ownedCount.setAccessible(true);
                            var settingsField = tabPlugin.getClass().getDeclaredField("settings"); settingsField.setAccessible(true);
                            var settings = settingsField.get(tabPlugin);
                            var max = settings.getClass().getDeclaredMethod("maxVisible"); max.setAccessible(true);
                            var snapshot = queue.snapshot();
                            Map<String, Object> tabs = new TreeMap<>();
                            for (Player player : proxy.getAllPlayers()) {
                                var entries = player.getTabList().getEntries().stream()
                                    .sorted(Comparator.comparingInt(TabListEntry::getListOrder).reversed())
                                    .map(e -> e.getProfile().getName()).toList();
                                tabs.put(player.getUsername(), Map.of("entries", entries, "self", player.getTabList().containsEntry(player.getUniqueId()),
                                    "server", player.getCurrentServer().map(s -> s.getServerInfo().getName()).orElse("none")));
                            }
                            logger.info("TABSTATE {} {}", args[1], gson.toJson(Map.of("tabs", tabs,
                                "waiting", snapshot.orderedIds().stream().map(id -> proxy.getPlayer(id).orElseThrow().getUsername()).toList(),
                                "viewers", viewerCount.invoke(renderer), "owned", ownedCount.invoke(renderer), "cap", max.invoke(settings),
                                "tasks", proxy.getScheduler().tasksByPlugin(tabPlugin).size())));
                        }
                    } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
                }
            });
        logger.info("LOCAL_TAB_PROBE_READY");
    }
}
