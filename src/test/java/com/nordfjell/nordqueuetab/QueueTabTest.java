package com.nordfjell.nordqueuetab;

import com.nordfjell.nordqueue.QueueSnapshot;
import com.velocitypowered.api.proxy.*;
import com.velocitypowered.api.proxy.server.ServerInfo;
import com.velocitypowered.api.proxy.player.*;
import com.velocitypowered.api.util.GameProfile;
import net.kyori.adventure.text.Component;
import java.lang.reflect.*;
import java.net.InetSocketAddress;
import java.util.*;

/** Dependency-free regression tests with fake Velocity connections; never connect to production. */
public final class QueueTabTest {
    static int passed;
    static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    static void pass(String name) { passed++; System.out.println("PASS " + name); }
    @SuppressWarnings("unchecked") static <T> T fake(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }
    static Object objectMethod(Object proxy, Method method, Object[] args) {
        return switch (method.getName()) {
            case "equals" -> proxy == args[0]; case "hashCode" -> System.identityHashCode(proxy);
            case "toString" -> "fixture"; default -> null;
        };
    }
    static final class Entry implements InvocationHandler {
        final GameProfile profile; final TabList tab; Component name;
        int order, latency, mutations; boolean listed = true;
        final TabListEntry api = fake(TabListEntry.class, this);
        Entry(TabList tab, GameProfile profile, Component name, int latency, int order) {
            this.tab = tab; this.profile = profile; this.name = name; this.latency = latency; this.order = order;
        }
        @Override public Object invoke(Object proxy, Method method, Object[] args) {
            return switch (method.getName()) {
                case "getProfile" -> profile; case "getTabList" -> tab;
                case "getDisplayNameComponent" -> Optional.ofNullable(name);
                case "getListOrder" -> order; case "getLatency" -> latency;
                case "isListed" -> listed; case "getGameMode" -> 0;
                case "setDisplayName" -> { name = (Component) args[0]; mutations++; yield api; }
                case "setListOrder" -> { order = (int) args[0]; mutations++; yield api; }
                case "setLatency" -> { latency = (int) args[0]; mutations++; yield api; }
                case "setListed" -> { listed = (boolean) args[0]; mutations++; yield api; }
                default -> objectMethod(proxy, method, args);
            };
        }
    }
    static final class Client implements InvocationHandler {
        final UUID id; final String name; final GameProfile profile;
        final Map<UUID, TabListEntry> entries = new HashMap<>();
        boolean active = true; String server = "queue";
        int adds, removes, headers, clears; Component header;
        final TabList tab = fake(TabList.class, (proxy, method, args) -> {
            return switch (method.getName()) {
                case "getEntry" -> Optional.ofNullable(entries.get(args[0]));
                case "containsEntry" -> entries.containsKey(args[0]);
                case "getEntries" -> List.copyOf(entries.values());
                case "addEntry" -> { var entry = (TabListEntry) args[0]; entries.put(entry.getProfile().getId(), entry); adds++; yield null; }
                case "removeEntry" -> { removes++; yield Optional.ofNullable(entries.remove(args[0])); }
                case "buildEntry" -> new Entry((TabList) proxy, (GameProfile) args[0], (Component) args[1],
                    (int) args[2], (int) args[6]).api;
                case "clearAll" -> throw new AssertionError("Foreign entries must not be cleared");
                default -> objectMethod(proxy, method, args);
            };
        });
        final Player api = fake(Player.class, this);
        Client(UUID id, String name) { this.id = id; this.name = name; profile = new GameProfile(id, name, List.of()); }
        Client(int i) { this(new UUID(0, i + 1), "Test" + i); }
        @Override public Object invoke(Object proxy, Method method, Object[] args) {
            return switch (method.getName()) {
                case "getUniqueId" -> id; case "getUsername" -> name; case "getGameProfile" -> profile;
                case "getTabList" -> tab; case "isActive" -> active;
                case "getCurrentServer" -> Optional.of(fake(ServerConnection.class, (p, m, a) ->
                    m.getName().equals("getServerInfo") ? new ServerInfo(server, new InetSocketAddress("127.0.0.1", 1)) : objectMethod(p, m, a)));
                case "sendPlayerListHeaderAndFooter" -> { headers++; header = (Component) args[0]; yield null; }
                case "clearPlayerListHeaderAndFooter" -> { clears++; yield null; }
                default -> objectMethod(proxy, method, args);
            };
        }
        int writes() { return adds + removes + headers + clears + entries.values().stream()
            .mapToInt(entry -> ((Entry) Proxy.getInvocationHandler(entry)).mutations).sum(); }
    }
    static final class Fixture {
        final List<Client> clients = new ArrayList<>();
        final QueueTabRenderer renderer = new QueueTabRenderer();
        long version = 1; int profileReads;
        QueueTabSettings settings = QueueTabSettings.defaults();
        final ProxyServer proxy = fake(ProxyServer.class, (p, m, a) -> switch (m.getName()) {
            case "getAllPlayers" -> clients.stream().map(c -> c.api).toList();
            case "getPlayer" -> { profileReads++; yield clients.stream().filter(c -> c.id.equals(a[0])).map(c -> c.api).findFirst(); }
            default -> objectMethod(p, m, a);
        });
        Fixture(int size) { for (int i = 0; i < size; i++) clients.add(new Client(i)); }
        void refresh() {
            List<UUID> ids = clients.stream().filter(c -> c.active && c.server.equals("queue")).map(c -> c.id).toList();
            Map<UUID, Integer> positions = new HashMap<>();
            for (int i = 0; i < ids.size(); i++) positions.put(ids.get(i), i + 1);
            renderer.refresh(proxy, new QueueSnapshot(version, ids, positions, positions, Set.of(), ids.size(), 0), "queue", settings);
        }
        int writes() { return clients.stream().mapToInt(Client::writes).sum(); }
    }
    public static void main(String[] args) throws Exception {
        Fixture f = new Fixture(1000);
        long start = System.nanoTime(); f.refresh();
        check(f.renderer.viewerCount() == 1000 && f.renderer.ownedCount() == 80000, "Bounded state");
        for (Client c : f.clients) { check(c.entries.size() == 80, "Entry cap"); check(c.entries.containsKey(c.id), "Self visible"); }
        System.out.printf("SYNTHETIC initial 1000 viewers x 80 entries: %.1fms%n", (System.nanoTime() - start) / 1e6);
        pass("1000 synthetic viewers, maximum 80 entries, self always visible");
        int writes = f.writes(), reads = f.profileReads; f.refresh();
        check(f.writes() == writes && f.profileReads == reads, "Unchanged update writes no packets and rebuilds no profiles");
        pass("unchanged snapshot: zero writes and no profile rebuild");
        check(f.clients.get(999).entries.containsKey(f.clients.get(78).id)
            && !f.clients.get(999).entries.containsKey(f.clients.get(79).id), "Leading 79 + self");
        pass("outside first page: first 79 plus viewer");
        Client left = f.clients.removeFirst(); f.version++; f.refresh();
        check(f.renderer.viewerCount() == 999 && !f.clients.getFirst().entries.containsKey(left.id), "Stale sessions and rows removed");
        pass("departure shifts positions and reaps missing viewer");
        Client old = f.clients.getLast(), newer = new Client(old.id, old.name);
        f.clients.set(f.clients.size() - 1, newer); f.version++; f.refresh();
        f.renderer.forget(old.api); f.renderer.clear(old.api);
        check(f.renderer.viewerCount() == 999 && newer.entries.size() == 80, "Late disconnect must not clear new session");
        pass("reconnected UUID survives stale disconnect and clear");
        f.renderer.forget(newer.api); check(f.renderer.viewerCount() == 998, "Disconnect releases viewer");
        pass("disconnect immediately releases owned viewer state");
        Fixture small = new Fixture(5); small.settings = new QueueTabSettings(1000, 3, "<position>/<size> <estimate>", "footer", "<position> <player>");
        Client viewer = small.clients.getLast();
        Entry foreign = new Entry(viewer.tab, small.clients.getFirst().profile, Component.text("FOREIGN"), 55, 2);
        viewer.entries.put(small.clients.getFirst().id, foreign.api); small.refresh();
        check(viewer.entries.get(small.clients.getFirst().id) == foreign.api && foreign.mutations == 0, "Never adopt foreign entry");
        pass("existing foreign TAB entry remains unchanged");
        UUID ownedId = small.clients.get(1).id;
        Entry replacement = new Entry(viewer.tab, small.clients.get(1).profile, Component.text("BACKEND"), 5, 1);
        viewer.entries.put(ownedId, replacement.api); small.renderer.clear(viewer.api);
        check(viewer.entries.get(ownedId) == replacement.api && viewer.entries.size() == 2, "Cleanup preserves replacements and other foreign rows");
        pass("cleanup removes own entries only, preserves backend replacements");
        viewer.server = "main"; small.version++; small.refresh();
        check(small.renderer.viewerCount() == 4, "No view retained on main");
        pass("backend transition releases queue presentation");
        small.settings = new QueueTabSettings(1000, 1, "header", "footer", "<player>"); small.refresh();
        for (Client c : small.clients) if (c.server.equals("queue"))
            check(c.entries.size() == 1 && c.entries.containsKey(c.id), "Cap 1 must show self");
        pass("reload to cap 1 keeps self and shrinks owned rows");
        small.renderer.forgetAll(); check(small.renderer.viewerCount() == 0 && small.renderer.ownedCount() == 0, "Shutdown releases state");
        pass("shutdown releases retained state");
        for (String[] invalid : new String[][]{{"max-visible-players","0"},{"max-visible-players","81"},
            {"max-visible-players","oops"},{"update-interval-millis","499"},{"update-interval-millis","60001"}}) {
            Properties p = new Properties(); p.setProperty(invalid[0], invalid[1]);
            try { QueueTabSettings.from(p); throw new AssertionError("Invalid config accepted"); }
            catch (IllegalArgumentException expected) { }
        }
        pass("invalid configuration rejected rather than silently replaced");
        Properties p = new Properties(); QueueTabSettings.defaults().writeDefaults(p);
        check(QueueTabSettings.defaults().equals(QueueTabSettings.from(p)), "Roundtrip defaults");
        pass("configuration defaults roundtrip");
        Fixture churn = new Fixture(0);
        for (int i = 0; i < 2000; i++) {
            churn.clients.clear(); churn.clients.add(new Client(i)); churn.version++;
            churn.refresh(); churn.renderer.forget(churn.clients.getFirst().api);
            check(churn.renderer.viewerCount() == 0, "Session leak");
        }
        pass("2000 create/disconnect cycles release viewer state");
        Fixture switching = new Fixture(1); switching.refresh();
        switching.renderer.connected(switching.clients.getFirst().api, false);
        switching.refresh();
        check(switching.renderer.viewerCount() == 0, "Connected event precedes currentServer assignment; must not recreate view");
        switching.renderer.connected(switching.clients.getFirst().api, true); switching.refresh();
        check(switching.renderer.viewerCount() == 1, "Returning to queue restores presentation");
        pass("server switch event cannot be undone by stale currentServer value");
        lifecycle();
        System.out.println("ALL " + passed + " SCENARIOS PASSED");
    }
    static void lifecycle() throws Exception {
        var active = new java.util.concurrent.atomic.AtomicInteger();
        var maximum = new java.util.concurrent.atomic.AtomicInteger();
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        ProxyServer proxy = fake(ProxyServer.class, (p, m, a) -> {
            if (m.getName().equals("getAllPlayers")) {
                int concurrent = active.incrementAndGet(); maximum.accumulateAndGet(concurrent, Math::max);
                try { reads.incrementAndGet(); Thread.sleep(2); return List.of(); }
                finally { active.decrementAndGet(); }
            }
            return objectMethod(p, m, a);
        });
        org.slf4j.Logger logger = fake(org.slf4j.Logger.class, (p, m, a) -> objectMethod(p, m, a));
        var directory = java.nio.file.Files.createTempDirectory("nordqueuetab-regression-");
        var plugin = new NordQueueTabPlugin(proxy, logger, directory);
        var queue = new com.nordfjell.nordqueue.NordQueuePlugin(proxy, logger, directory);
        Field queueField = plugin.getClass().getDeclaredField("queue"); queueField.setAccessible(true); queueField.set(plugin, queue);
        Method refresh = plugin.getClass().getDeclaredMethod("refresh", long.class); refresh.setAccessible(true);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(6);
        try {
            List<java.util.concurrent.Future<?>> jobs = new ArrayList<>();
            for (int i = 0; i < 60; i++) jobs.add(pool.submit(() -> {
                try { refresh.invoke(plugin, 0L); } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
            }));
            for (var job : jobs) job.get();
        } finally { pool.shutdownNow(); }
        check(reads.get() == 60 && maximum.get() == 1, "Six scheduler threads must serialize actual renderer execution");
        pass("60 refresh calls from six threads never overlap rendering");
        Field generation = plugin.getClass().getDeclaredField("generation"); generation.setAccessible(true); generation.setLong(plugin, 1);
        refresh.invoke(plugin, 0L); check(reads.get() == 60, "Old task generation must not render");
        plugin.shutdown(null); refresh.invoke(plugin, 2L);
        check(reads.get() == 60, "No refresh after shutdown");
        pass("cancelled task generation and shutdown both suppress late refreshes");
    }
}
