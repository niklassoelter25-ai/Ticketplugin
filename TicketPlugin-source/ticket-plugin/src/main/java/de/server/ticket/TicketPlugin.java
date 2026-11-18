package de.ticketplugin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Ticket-System mit Discord-Anbindung.
 *
 * - /ticket <Text>          erstellt ein Ticket (neuer Post im Discord-Forum)
 * - /ticket <Text>          schreibt bei offenem Ticket eine Antwort ans Team
 * - /ticket antworten <Text> dasselbe, explizit
 * - /ticket schliessen      schliesst das eigene Ticket
 *
 * Antworten aus Discord werden per Bot-Token abgefragt (Polling) und dem Spieler privat zugestellt.
 * Schreibt das Team "!close" in den Post, wird das Ticket geschlossen.
 */
public final class TicketPlugin extends JavaPlugin implements Listener, TabExecutor {

    private static final String API = "https://discord.com/api/v10";
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** Offene Tickets: Thread-ID -> Ticket. Wird nur im Haupt-Thread veraendert. */
    private final Map<String, Ticket> tickets = new HashMap<>();
    /** Nachrichten fuer Offline-Spieler: {art, autor, text}. art = "msg" oder "closed". */
    private final Map<UUID, List<String[]>> pending = new HashMap<>();
    private final Map<UUID, Long> lastTicket = new HashMap<>();
    private final Map<UUID, Long> lastReply = new HashMap<>();

    private volatile String webhookUrl = "";
    private volatile String botToken = "";
    private volatile long lastWarn = 0;

    private File dataFile;
    private boolean polling = false;
    private long pauseUntil = 0;
    private int pollTaskId = -1;

    private static final class Ticket {
        final String threadId;
        final UUID uuid;
        final String name;
        String lastId;

        Ticket(String threadId, UUID uuid, String name, String lastId) {
            this.threadId = threadId;
            this.uuid = uuid;
            this.name = name;
            this.lastId = lastId;
        }
    }

    private record Result(String threadId, int code, String body) {
    }

    // ------------------------------------------------------------------ Lebenszyklus

    @Override
    public void onEnable() {
        saveDefaultConfig();
        dataFile = new File(getDataFolder(), "data.yml");
        loadSettings();
        loadData();

        if (getCommand("ticket") != null) {
            getCommand("ticket").setExecutor(this);
            getCommand("ticket").setTabCompleter(this);
        }
        Bukkit.getPluginManager().registerEvents(this, this);
        startPolling();
    }

    @Override
    public void onDisable() {
        saveData();
    }

    private void loadSettings() {
        webhookUrl = getConfig().getString("webhook-url", "").trim();
        botToken = getConfig().getString("bot-token", "").trim();
    }

    private boolean webhookConfigured() {
        return webhookUrl.contains("/api/webhooks/");
    }

    private boolean botConfigured() {
        return !botToken.isBlank() && !botToken.startsWith("HIER");
    }

    private void startPolling() {
        if (pollTaskId != -1) {
            Bukkit.getScheduler().cancelTask(pollTaskId);
            pollTaskId = -1;
        }
        if (!botConfigured()) {
            getLogger().warning("Kein bot-token gesetzt: Antworten aus Discord werden NICHT ins Spiel uebertragen.");
            return;
        }
        long seconds = Math.max(2, getConfig().getLong("poll-interval-seconds", 5));
        pollTaskId = Bukkit.getScheduler().runTaskTimer(this, this::poll, 100L, seconds * 20L).getTaskId();
    }

    // ------------------------------------------------------------------ Nachrichten

    private Component msg(String key, TagResolver... resolvers) {
        String prefix = getConfig().getString("messages.prefix", "");
        String body = getConfig().getString("messages." + key, key);
        return MM.deserialize(prefix + body, resolvers);
    }

    private void sendTo(UUID uuid, Component component) {
        Player p = Bukkit.getPlayer(uuid);
        if (p != null) {
            p.sendMessage(component);
        }
    }

    private void warnThrottled(String text) {
        long now = System.currentTimeMillis();
        if (now - lastWarn > 60_000) {
            lastWarn = now;
            getLogger().warning(text);
        }
    }

    // ------------------------------------------------------------------ Befehl

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload") && sender.hasPermission("ticket.admin")) {
            reloadConfig();
            loadSettings();
            startPolling();
            sender.sendMessage(msg("reloaded"));
            return true;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage("Dieser Befehl ist nur ingame nutzbar.");
            return true;
        }
        if (!webhookConfigured()) {
            player.sendMessage(msg("not-configured"));
            return true;
        }
        if (args.length == 0) {
            player.sendMessage(msg("usage"));
            return true;
        }

        String first = args[0].toLowerCase(Locale.ROOT);
        if (first.equals("schliessen") || first.equals("schlie\u00dfen") || first.equals("close")) {
            closeByPlayer(player);
            return true;
        }

        Ticket open = openTicketOf(player.getUniqueId());

        if (first.equals("antworten") || first.equals("reply")) {
            String text = String.join(" ", Arrays.copyOfRange(args, 1, args.length)).trim();
            reply(player, open, text);
            return true;
        }

        String text = String.join(" ", args).trim();
        if (open != null) {
            reply(player, open, text);
        } else {
            create(player, text);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>(List.of("antworten", "schliessen"));
            if (sender.hasPermission("ticket.admin")) {
                options.add("reload");
            }
            String prefix = args[0].toLowerCase(Locale.ROOT);
            options.removeIf(o -> !o.startsWith(prefix));
            return options;
        }
        return List.of();
    }

    private Ticket openTicketOf(UUID uuid) {
        for (Ticket t : tickets.values()) {
            if (t.uuid.equals(uuid)) {
                return t;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ Ticket erstellen / antworten / schliessen

    private void create(Player player, String text) {
        int min = getConfig().getInt("min-length", 5);
        if (text.length() < min) {
            player.sendMessage(msg("too-short"));
            return;
        }

        UUID uuid = player.getUniqueId();
        String name = player.getName();
        long now = System.currentTimeMillis();
        long cooldownMs = getConfig().getLong("cooldown-seconds", 60) * 1000L;
        Long last = lastTicket.get(uuid);
        if (last != null && now - last < cooldownMs) {
            long left = (cooldownMs - (now - last) + 999) / 1000;
            player.sendMessage(msg("cooldown", Placeholder.unparsed("seconds", String.valueOf(left))));
            return;
        }
        lastTicket.put(uuid, now);

        boolean forum = getConfig().getBoolean("forum-channel", true);
        String roleId = getConfig().getString("ping-role-id", "").trim();

        String content = "**Neues Ticket von " + name + "**\n" + text;
        if (!roleId.isEmpty()) {
            content = "<@&" + roleId + ">\n" + content;
        }

        JsonObject body = webhookBody(name, uuid, content);
        if (!roleId.isEmpty()) {
            JsonArray roles = new JsonArray();
            roles.add(roleId);
            body.getAsJsonObject("allowed_mentions").add("roles", roles);
        }
        if (forum) {
            String title = "Ticket: " + name + " - " + text.replace('\n', ' ');
            body.addProperty("thread_name", title.length() > 90 ? title.substring(0, 90) : title);
        }

        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                HttpResponse<String> res = postWebhook(body, null);
                if (res.statusCode() / 100 != 2) {
                    getLogger().warning("Webhook-Fehler " + res.statusCode() + ": " + res.body());
                    failCreate(uuid);
                    return;
                }
                JsonObject json = JsonParser.parseString(res.body()).getAsJsonObject();
                String threadId = json.get("channel_id").getAsString();
                String firstId = json.get("id").getAsString();
                if (!isEnabled()) {
                    return;
                }
                Bukkit.getScheduler().runTask(this, () -> {
                    if (forum) {
                        tickets.put(threadId, new Ticket(threadId, uuid, name, firstId));
                        saveData();
                    }
                    String key = (forum && botConfigured()) ? "sent-twoway" : "sent";
                    sendTo(uuid, msg(key));
                });
            } catch (Exception e) {
                getLogger().warning("Ticket konnte nicht gesendet werden: " + e.getMessage());
                failCreate(uuid);
            }
        });
    }

    private void failCreate(UUID uuid) {
        if (!isEnabled()) {
            return;
        }
        Bukkit.getScheduler().runTask(this, () -> {
            lastTicket.remove(uuid);
            sendTo(uuid, msg("error"));
        });
    }

    private void reply(Player player, Ticket ticket, String text) {
        if (ticket == null) {
            player.sendMessage(msg("no-open-ticket"));
            return;
        }
        if (text.isEmpty()) {
            player.sendMessage(msg("usage-reply"));
            return;
        }
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        Long last = lastReply.get(uuid);
        if (last != null && now - last < 2000) {
            player.sendMessage(msg("cooldown", Placeholder.unparsed("seconds", "2")));
            return;
        }
        lastReply.put(uuid, now);

        JsonObject body = webhookBody(player.getName(), uuid, text);
        String threadId = ticket.threadId;

        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                HttpResponse<String> res = postWebhook(body, threadId);
                if (res.statusCode() / 100 != 2) {
                    getLogger().warning("Webhook-Fehler beim Antworten " + res.statusCode() + ": " + res.body());
                    if (isEnabled()) {
                        Bukkit.getScheduler().runTask(this, () -> sendTo(uuid, msg("error")));
                    }
                    return;
                }
                if (isEnabled()) {
                    Bukkit.getScheduler().runTask(this, () -> sendTo(uuid, msg("reply-sent")));
                }
            } catch (Exception e) {
                getLogger().warning("Antwort konnte nicht gesendet werden: " + e.getMessage());
                if (isEnabled()) {
                    Bukkit.getScheduler().runTask(this, () -> sendTo(uuid, msg("error")));
                }
            }
        });
    }

    private void closeByPlayer(Player player) {
        Ticket ticket = openTicketOf(player.getUniqueId());
        if (ticket == null) {
            player.sendMessage(msg("no-open-ticket"));
            return;
        }
        tickets.remove(ticket.threadId);
        saveData();
        player.sendMessage(msg("closed-by-player"));

        JsonObject body = webhookBody(player.getName(), player.getUniqueId(),
                "*" + player.getName() + " hat das Ticket geschlossen.*");
        String threadId = ticket.threadId;
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                postWebhook(body, threadId);
            } catch (Exception e) {
                getLogger().warning("Schliess-Hinweis konnte nicht gesendet werden: " + e.getMessage());
            }
        });
    }

    // ------------------------------------------------------------------ Discord: Webhook

    private JsonObject webhookBody(String name, UUID uuid, String content) {
        JsonObject body = new JsonObject();
        body.addProperty("content", content.length() > 1900 ? content.substring(0, 1900) : content);
        body.addProperty("username", name);
        body.addProperty("avatar_url", "https://mc-heads.net/avatar/" + uuid + "/128");
        JsonObject allowed = new JsonObject();
        allowed.add("parse", new JsonArray()); // keine @everyone/@here/Rollen-Pings aus Spielertext
        body.add("allowed_mentions", allowed);
        return body;
    }

    private HttpResponse<String> postWebhook(JsonObject body, String threadId) throws IOException, InterruptedException {
        String url = webhookUrl + (webhookUrl.contains("?") ? "&" : "?") + "wait=true";
        if (threadId != null) {
            url += "&thread_id=" + threadId;
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    // ------------------------------------------------------------------ Discord: Antworten abfragen

    private void poll() {
        if (polling || System.currentTimeMillis() < pauseUntil || tickets.isEmpty()) {
            return;
        }
        List<String[]> work = new ArrayList<>();
        for (Ticket t : tickets.values()) {
            work.add(new String[]{t.threadId, t.lastId == null ? t.threadId : t.lastId});
        }
        polling = true;
        final String token = botToken;

        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            List<Result> results = new ArrayList<>();
            long pauseMs = 0;
            for (String[] w : work) {
                try {
                    HttpRequest request = HttpRequest.newBuilder(
                                    URI.create(API + "/channels/" + w[0] + "/messages?limit=50&after=" + w[1]))
                            .timeout(Duration.ofSeconds(15))
                            .header("Authorization", "Bot " + token)
                            .header("User-Agent", "DiscordBot (TicketPlugin, 1.1)")
                            .GET()
                            .build();
                    HttpResponse<String> res = http.send(request, HttpResponse.BodyHandlers.ofString());
                    if (res.statusCode() == 429) {
                        pauseMs = 5000;
                        try {
                            pauseMs = (long) (JsonParser.parseString(res.body()).getAsJsonObject()
                                    .get("retry_after").getAsDouble() * 1000);
                        } catch (Exception ignored) {
                            // Standardwert bleibt
                        }
                        break;
                    }
                    results.add(new Result(w[0], res.statusCode(), res.body()));
                } catch (Exception e) {
                    warnThrottled("Discord nicht erreichbar: " + e.getMessage());
                }
            }
            final long pause = pauseMs;
            if (!isEnabled()) {
                return;
            }
            Bukkit.getScheduler().runTask(this, () -> {
                if (pause > 0) {
                    pauseUntil = System.currentTimeMillis() + pause;
                }
                applyResults(results);
                polling = false;
            });
        });
    }

    private void applyResults(List<Result> results) {
        String closeKeyword = getConfig().getString("close-keyword", "!close").trim();
        boolean changed = false;

        for (Result r : results) {
            Ticket ticket = tickets.get(r.threadId());
            if (ticket == null) {
                continue;
            }
            if (r.code() == 404) {
                // Post wurde in Discord geloescht
                tickets.remove(ticket.threadId);
                deliver(ticket, "closed", "", "");
                changed = true;
                continue;
            }
            if (r.code() != 200) {
                warnThrottled("Discord-API-Fehler " + r.code() + " bei Ticket " + r.threadId()
                        + " (Bot-Rechte im Forum-Kanal pruefen): " + r.body());
                continue;
            }

            List<JsonObject> messages = new ArrayList<>();
            try {
                for (JsonElement el : JsonParser.parseString(r.body()).getAsJsonArray()) {
                    messages.add(el.getAsJsonObject());
                }
            } catch (Exception e) {
                continue;
            }
            messages.sort(Comparator.comparingLong((JsonObject o) -> Long.parseLong(o.get("id").getAsString())));

            for (JsonObject m : messages) {
                ticket.lastId = m.get("id").getAsString();
                changed = true;

                if (m.has("webhook_id")) {
                    continue; // eigene Nachrichten (von Spielern via Webhook)
                }
                JsonObject author = m.has("author") ? m.getAsJsonObject("author") : null;
                if (author != null && author.has("bot") && author.get("bot").getAsBoolean()) {
                    continue;
                }
                int type = m.has("type") ? m.get("type").getAsInt() : 0;
                if (type != 0 && type != 19) {
                    continue; // nur normale Nachrichten und Antworten
                }

                String content = str(m, "content");
                content = content == null ? "" : content.trim();
                if (m.has("attachments") && m.getAsJsonArray("attachments").size() > 0) {
                    content = (content + " [Anhang]").trim();
                }
                if (content.isEmpty()) {
                    continue;
                }

                if (!closeKeyword.isEmpty() && content.equalsIgnoreCase(closeKeyword)) {
                    tickets.remove(ticket.threadId);
                    deliver(ticket, "closed", "", "");
                    break;
                }

                String authorName = str(author, "global_name");
                if (authorName == null) {
                    authorName = str(author, "username");
                }
                if (authorName == null) {
                    authorName = "Team";
                }
                if (content.length() > 1000) {
                    content = content.substring(0, 1000) + "...";
                }
                deliver(ticket, "msg", authorName, content);
            }
        }
        if (changed) {
            saveData();
        }
    }

    private static String str(JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) {
            return null;
        }
        return o.get(key).getAsString();
    }

    // ------------------------------------------------------------------ Zustellung an Spieler

    private void deliver(Ticket ticket, String kind, String author, String text) {
        Player p = Bukkit.getPlayer(ticket.uuid);
        String[] entry = new String[]{kind, author, text};
        if (p != null) {
            show(p, entry);
        } else {
            pending.computeIfAbsent(ticket.uuid, k -> new ArrayList<>()).add(entry);
        }
    }

    private void show(Player p, String[] entry) {
        if (entry[0].equals("closed")) {
            p.sendMessage(msg("closed-by-team"));
        } else {
            p.sendMessage(msg("from-team",
                    Placeholder.unparsed("author", entry[1]),
                    Placeholder.unparsed("message", entry[2])));
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        if (!pending.containsKey(uuid)) {
            return;
        }
        Bukkit.getScheduler().runTaskLater(this, () -> {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null) {
                return;
            }
            List<String[]> list = pending.remove(uuid);
            if (list == null) {
                return;
            }
            for (String[] entry : list) {
                show(p, entry);
            }
            saveData();
        }, 40L);
    }

    // ------------------------------------------------------------------ Speichern / Laden (data.yml)

    private void loadData() {
        tickets.clear();
        pending.clear();
        if (!dataFile.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(dataFile);

        ConfigurationSection ts = yaml.getConfigurationSection("tickets");
        if (ts != null) {
            for (String key : ts.getKeys(false)) {
                try {
                    String threadId = key.startsWith("t") ? key.substring(1) : key;
                    UUID uuid = UUID.fromString(ts.getString(key + ".uuid", ""));
                    String name = ts.getString(key + ".name", "?");
                    String lastId = ts.getString(key + ".last-id", threadId);
                    tickets.put(threadId, new Ticket(threadId, uuid, name, lastId));
                } catch (IllegalArgumentException ignored) {
                    // kaputter Eintrag wird uebersprungen
                }
            }
        }

        ConfigurationSection ps = yaml.getConfigurationSection("pending");
        if (ps != null) {
            for (String key : ps.getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(key);
                    List<String[]> list = new ArrayList<>();
                    for (Map<?, ?> m : ps.getMapList(key)) {
                        list.add(new String[]{
                                String.valueOf(m.get("kind")),
                                String.valueOf(m.get("author")),
                                String.valueOf(m.get("text"))});
                    }
                    if (!list.isEmpty()) {
                        pending.put(uuid, list);
                    }
                } catch (IllegalArgumentException ignored) {
                    // kaputter Eintrag wird uebersprungen
                }
            }
        }
    }

    private void saveData() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Ticket t : tickets.values()) {
            String path = "tickets.t" + t.threadId;
            yaml.set(path + ".uuid", t.uuid.toString());
            yaml.set(path + ".name", t.name);
            yaml.set(path + ".last-id", t.lastId);
        }
        for (Map.Entry<UUID, List<String[]>> e : pending.entrySet()) {
            List<Map<String, String>> list = new ArrayList<>();
            for (String[] a : e.getValue()) {
                Map<String, String> m = new LinkedHashMap<>();
                m.put("kind", a[0]);
                m.put("author", a[1]);
                m.put("text", a[2]);
                list.add(m);
            }
            yaml.set("pending." + e.getKey(), list);
        }
        try {
            yaml.save(dataFile);
        } catch (IOException e) {
            getLogger().warning("data.yml konnte nicht gespeichert werden: " + e.getMessage());
        }
    }
}
