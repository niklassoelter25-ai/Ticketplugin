package de.server.ticket;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class TicketPlugin extends JavaPlugin {

    private final MiniMessage mm = MiniMessage.miniMessage();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final Map<UUID, Long> lastTicket = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("ticket.reload")) return true;
            reloadConfig();
            send(sender, "reloaded");
            return true;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage("Nur Spieler können Tickets erstellen.");
            return true;
        }
        if (!player.hasPermission("ticket.use")) return true;

        String url = getConfig().getString("webhook-url", "");
        if (url.isBlank() || url.contains("HIER_WEBHOOK")) {
            send(player, "not-configured");
            return true;
        }

        String text = String.join(" ", args).trim();
        if (args.length == 0) {
            send(player, "usage");
            return true;
        }
        if (text.length() < getConfig().getInt("min-length", 5)) {
            send(player, "too-short");
            return true;
        }

        long cooldownMs = getConfig().getLong("cooldown-seconds", 60) * 1000L;
        long now = System.currentTimeMillis();
        Long last = lastTicket.get(player.getUniqueId());
        if (last != null && now - last < cooldownMs) {
            long left = (cooldownMs - (now - last) + 999) / 1000;
            player.sendMessage(prefix().append(mm.deserialize(
                    getConfig().getString("messages.cooldown", ""),
                    Placeholder.unparsed("seconds", String.valueOf(left)))));
            return true;
        }
        lastTicket.put(player.getUniqueId(), now);

        // Daten im Main-Thread sammeln, HTTP-Request asynchron senden
        Location loc = player.getLocation();
        String payload = buildPayload(
                player.getName(), player.getUniqueId().toString(), text,
                loc.getWorld().getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());

        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            boolean ok = post(url, payload);
            getServer().getScheduler().runTask(this, () -> {
                if (!player.isOnline()) return;
                if (ok) {
                    send(player, "sent");
                } else {
                    lastTicket.remove(player.getUniqueId());
                    send(player, "error");
                }
            });
        });
        return true;
    }

    private boolean post(String url, String json) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) {
                getLogger().warning("Discord antwortete " + res.statusCode() + ": " + res.body());
                return false;
            }
            return true;
        } catch (Exception e) {
            getLogger().warning("Ticket konnte nicht gesendet werden: " + e.getMessage());
            return false;
        }
    }

    private String buildPayload(String name, String uuid, String text,
                                String world, int x, int y, int z) {
        boolean forum = getConfig().getBoolean("forum-channel", true);
        String roleId = getConfig().getString("ping-role-id", "").trim();

        String title = "Ticket von " + name;
        String description = cut(text, 3500);

        StringBuilder sb = new StringBuilder("{");
        sb.append("\"username\":\"Ingame Tickets\",");
        sb.append("\"avatar_url\":\"https://mc-heads.net/avatar/").append(uuid).append("\",");
        if (forum) {
            sb.append("\"thread_name\":\"").append(esc(cut(title + ": " + text, 100))).append("\",");
        }
        if (!roleId.isEmpty()) {
            sb.append("\"content\":\"<@&").append(esc(roleId)).append(">\",");
            sb.append("\"allowed_mentions\":{\"roles\":[\"").append(esc(roleId)).append("\"]},");
        } else {
            sb.append("\"allowed_mentions\":{\"parse\":[]},");
        }
        sb.append("\"embeds\":[{");
        sb.append("\"title\":\"").append(esc(title)).append("\",");
        sb.append("\"description\":\"").append(esc(description)).append("\",");
        sb.append("\"color\":16753920,");
        sb.append("\"thumbnail\":{\"url\":\"https://mc-heads.net/avatar/").append(uuid).append("\"},");
        sb.append("\"fields\":[");
        sb.append("{\"name\":\"Spieler\",\"value\":\"").append(esc(name)).append("\",\"inline\":true},");
        sb.append("{\"name\":\"Welt\",\"value\":\"").append(esc(world)).append("\",\"inline\":true},");
        sb.append("{\"name\":\"Position\",\"value\":\"").append(x).append(" ").append(y).append(" ").append(z)
                .append("\",\"inline\":true},");
        sb.append("{\"name\":\"UUID\",\"value\":\"").append(esc(uuid)).append("\"}");
        sb.append("],");
        sb.append("\"timestamp\":\"").append(Instant.now()).append("\"");
        sb.append("}]}");
        return sb.toString();
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    /** Minimales JSON-Escaping. */
    private static String esc(String s) {
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.toString();
    }

    private Component prefix() {
        return mm.deserialize(getConfig().getString("messages.prefix", ""));
    }

    private void send(CommandSender to, String key) {
        to.sendMessage(prefix().append(mm.deserialize(getConfig().getString("messages." + key, ""))));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1 && sender.hasPermission("ticket.reload")) return List.of("reload");
        return List.of();
    }
}
