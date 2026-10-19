package tech.hollowsmp.skins;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.NotNull;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class HollowSkins extends JavaPlugin implements Listener, TabExecutor {
    private final Map<String, Texture> cache = new ConcurrentHashMap<>();
    private final Map<UUID, Long> generation = new ConcurrentHashMap<>();
    private HttpClient http;
    private File selectionsFile;
    private YamlConfiguration selections;
    private static final Pattern ID = Pattern.compile("\\\"id\\\"\\s*:\\s*\\\"([0-9a-fA-F]{32})\\\"");
    private static final Pattern VALUE = Pattern.compile("\\\"value\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private static final Pattern SIGNATURE = Pattern.compile("\\\"signature\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_]{3,16}");
    private record Texture(String value, String signature) {}

    @Override public void onEnable() {
        saveDefaultConfig();
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(7)).build();
        selectionsFile = new File(getDataFolder(), "selections.yml");
        selections = YamlConfiguration.loadConfiguration(selectionsFile);
        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("hskin") != null) getCommand("hskin").setExecutor(this);
        getLogger().info("HollowSkins ready. Use /hskin to open the menu.");
        // Prefetch without blocking the server thread.
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            for (String name : getConfig().getStringList("catalog")) {
                try { fetch(name); } catch (Exception ignored) {}
            }
        });
    }

    @Override public void onDisable() { saveSelections(); }

    private synchronized void saveSelections() {
        try { selections.save(selectionsFile); }
        catch (IOException e) { getLogger().warning("Cannot save selections: " + e.getMessage()); }
    }
    private synchronized String selected(UUID id) { return selections.getString("players." + id); }
    private synchronized void choose(UUID id, String name) {
        if (name == null) selections.set("players." + id, null);
        else selections.set("players." + id, name);
        saveSelections();
    }

    @EventHandler public void onJoin(PlayerJoinEvent e) {
        Player player = e.getPlayer();
        if (isBedrock(player)) return; // Bedrock skins are managed by Floodgate/Geyser.
        String saved = selected(player.getUniqueId());
        if (saved != null && getConfig().getBoolean("restore-on-join", true)) {
            apply(player, saved, false);
        } else if (saved == null && getConfig().getBoolean("auto-fetch-on-join", true)) {
            apply(player, player.getName(), false);
        }
    }

    private boolean isBedrock(Player p) {
        try {
            Class<?> api = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            Object instance = api.getMethod("getInstance").invoke(null);
            return Boolean.TRUE.equals(api.getMethod("isFloodgatePlayer", UUID.class).invoke(instance, p.getUniqueId()));
        } catch (ReflectiveOperationException | LinkageError ignored) { return false; }
    }

    private Texture fetch(String username) throws Exception {
        String key = username.toLowerCase(java.util.Locale.ROOT);
        Texture old = cache.get(key);
        if (old != null) return old;
        int timeout = Math.max(1000, getConfig().getInt("http-timeout-ms", 7000));
        String encoded = URLEncoder.encode(username, StandardCharsets.UTF_8);
        HttpRequest lookup = HttpRequest.newBuilder(URI.create("https://api.mojang.com/users/profiles/minecraft/" + encoded))
            .timeout(Duration.ofMillis(timeout)).GET().build();
        HttpResponse<String> idResponse = http.send(lookup, HttpResponse.BodyHandlers.ofString());
        if (idResponse.statusCode() != 200) throw new IOException("No Mojang profile for " + username + " (HTTP " + idResponse.statusCode() + ")");
        Matcher id = ID.matcher(idResponse.body());
        if (!id.find()) throw new IOException("Missing Mojang ID for " + username);
        HttpRequest textureReq = HttpRequest.newBuilder(URI.create("https://sessionserver.mojang.com/session/minecraft/profile/" + id.group(1) + "?unsigned=false"))
            .timeout(Duration.ofMillis(timeout)).GET().build();
        HttpResponse<String> response = http.send(textureReq, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IOException("Texture lookup HTTP " + response.statusCode());
        Matcher val = VALUE.matcher(response.body());
        Matcher sig = SIGNATURE.matcher(response.body());
        if (!val.find()) throw new IOException("No textures for " + username);
        Texture result = new Texture(val.group(1), sig.find() ? sig.group(1) : null);
        cache.put(key, result);
        return result;
    }

    private void apply(Player player, String name, boolean notify) {
        if (!USERNAME.matcher(name).matches()) { if (notify) player.sendMessage("§cInvalid Minecraft username."); return; }
        if (isBedrock(player)) { if (notify) player.sendMessage("§eBedrock skins are handled by Geyser/Floodgate."); return; }
        UUID id = player.getUniqueId();
        long request = generation.merge(id, 1L, Long::sum);
        if (notify) player.sendMessage("§b[HollowSkins] §7Loading skin for §f" + name + "§7...");
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                Texture texture = fetch(name);
                Bukkit.getScheduler().runTask(this, () -> {
                    if (!player.isOnline() || generation.getOrDefault(id, 0L) != request) return;
                    try {
                        PlayerProfile profile = player.getPlayerProfile();
                        profile.setProperty(new ProfileProperty("textures", texture.value(), texture.signature()));
                        player.setPlayerProfile(profile);
                        // A client may need a relog to see its own changed skin.
                        if (notify) {
                            choose(id, name);
                            player.sendMessage("§a[HollowSkins] Skin applied! If you don't see it, reconnect.");
                        }
                    } catch (Exception ex) {
                        getLogger().warning("Failed to apply skin to " + player.getName() + ": " + ex);
                        if (notify) player.sendMessage("§cCouldn't apply that skin. Ask an admin to check the console.");
                    }
                });
            } catch (Exception ex) {
                if (notify) Bukkit.getScheduler().runTask(this, () -> {
                    if (player.isOnline()) player.sendMessage("§c[HollowSkins] Skin unavailable: " + name);
                });
                getLogger().fine("Skin fetch failed for " + name + ": " + ex.getMessage());
            }
        });
    }

    private static class SkinMenu implements InventoryHolder {
        final List<String> names;
        final int page;
        Inventory inventory;
        SkinMenu(List<String> names, int page) { this.names = names; this.page = page; }
        @Override public @NotNull Inventory getInventory() { return inventory; }
    }

    private ItemStack named(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', name));
        item.setItemMeta(meta);
        return item;
    }
    private void menu(Player player, int page) {
        List<String> names = getConfig().getStringList("catalog").stream().filter(n -> USERNAME.matcher(n).matches()).toList();
        int maxPage = Math.max(0, (names.size() - 1) / 45);
        page = Math.max(0, Math.min(page, maxPage));
        SkinMenu holder = new SkinMenu(names, page);
        Inventory inv = Bukkit.createInventory(holder, 54, "HollowSkins | Page " + (page + 1));
        holder.inventory = inv;
        for (int i = 0; i < 45 && page * 45 + i < names.size(); i++) {
            String username = names.get(page * 45 + i);
            ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) skull.getItemMeta();
            meta.setDisplayName("§b" + username);
            // Set owning player name for head preview; Paper/Mojang may resolve asynchronously.
            meta.setOwningPlayer(Bukkit.getOfflinePlayer(username));
            meta.setLore(List.of("§7Click to wear this skin"));
            skull.setItemMeta(meta);
            inv.setItem(i, skull);
        }
        if (page > 0) inv.setItem(45, named(Material.ARROW, "&ePrevious page"));
        inv.setItem(49, named(Material.BARRIER, "&cReset to your username skin"));
        if (page < maxPage) inv.setItem(53, named(Material.ARROW, "&eNext page"));
        player.openInventory(inv);
    }

    @EventHandler public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof SkinMenu holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() != event.getView().getTopInventory()) return;
        int slot = event.getRawSlot();
        if (slot == 45 && holder.page > 0) { menu(player, holder.page - 1); return; }
        if (slot == 53 && (holder.page + 1) * 45 < holder.names.size()) { menu(player, holder.page + 1); return; }
        if (slot == 49) { player.closeInventory(); choose(player.getUniqueId(), null); apply(player, player.getName(), true); return; }
        int index = holder.page * 45 + slot;
        if (slot >= 0 && slot < 45 && index < holder.names.size()) {
            player.closeInventory(); apply(player, holder.names.get(index), true);
        }
    }
    @EventHandler public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof SkinMenu) event.setCancelled(true);
    }

    @Override public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, @NotNull String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("hollowskins.admin")) { sender.sendMessage("§cNo permission."); return true; }
            reloadConfig(); cache.clear(); sender.sendMessage("§aHollowSkins config reloaded."); return true;
        }
        if (!(sender instanceof Player player)) { sender.sendMessage("Players: /hskin | /hskin set <name> | /hskin reset"); return true; }
        if (!player.hasPermission("hollowskins.use")) { player.sendMessage("§cNo permission."); return true; }
        if (args.length == 0 || args[0].equalsIgnoreCase("menu")) { menu(player, 0); return true; }
        switch (args[0].toLowerCase(java.util.Locale.ROOT)) {
            case "set" -> {
                if (args.length < 2) { player.sendMessage("§eUsage: /hskin set <premium username>"); return true; }
                apply(player, args[1], true);
            }
            case "reset" -> {
                choose(player.getUniqueId(), null);
                apply(player, player.getName(), false);
                player.sendMessage("§eSkin selection cleared; attempting your matching Mojang username skin.");
            }
            case "help" -> {
                player.sendMessage("§b§lHollowSkins §7commands:");
                player.sendMessage("§e/hskin §7- Open skin menu");
                player.sendMessage("§e/hskin set <username> §7- Apply a Mojang skin");
                player.sendMessage("§e/hskin reset §7- Restore matching username skin");
            }
            default -> player.sendMessage("§eUse /hskin help");
        }
        return true;
    }
    @Override public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            List<String> choices = new ArrayList<>(List.of("menu", "set", "reset", "help"));
            if (sender.hasPermission("hollowskins.admin")) choices.add("reload");
            return choices.stream().filter(s -> s.startsWith(args[0].toLowerCase(java.util.Locale.ROOT))).toList();
        }
        return List.of();
    }
}
