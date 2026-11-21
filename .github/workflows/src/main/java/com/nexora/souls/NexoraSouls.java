package com.nexora.souls;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;

public final class NexoraSouls extends JavaPlugin implements Listener {
    private YamlConfiguration data;
    private File dataFile;
    private NamespacedKey categoryKey, productKey;
    private final Map<String, Long> killCooldowns = new HashMap<>();
    private static final String MAIN_TITLE = "§5NEXORA §8| §dLoja de Almas";
    private static final String PREFIX = "§5[NEXORA] §d";

    @Override public void onEnable() {
        saveDefaultConfig();
        categoryKey = new NamespacedKey(this, "category");
        productKey = new NamespacedKey(this, "product");
        dataFile = new File(getDataFolder(), "balances.yml");
        if (!dataFile.exists()) {
            getDataFolder().mkdirs();
            try { dataFile.createNewFile(); } catch (IOException e) { getLogger().severe("Não foi possível criar balances.yml: " + e.getMessage()); }
        }
        data = YamlConfiguration.loadConfiguration(dataFile);
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("almas")).setExecutor((sender, command, label, args) -> handleSouls(sender, args));
        Objects.requireNonNull(getCommand("loja")).setExecutor((sender, command, label, args) -> {
            if (!(sender instanceof Player p)) { sender.sendMessage("Apenas jogadores podem abrir a loja."); return true; }
            openMainShop(p); return true;
        });
        getLogger().info("NEXORA SOULS 1.1.0 ativado.");
    }

    @Override public void onDisable() { saveData(); }

    private double balance(UUID id) { return data.getDouble("players." + id, 0); }
    private void setBalance(UUID id, double amount) { data.set("players." + id, Math.max(0, round(amount))); saveData(); }
    private void addBalance(UUID id, double amount) { if (amount > 0) setBalance(id, balance(id) + amount); }
    private double round(double n) { return Math.floor(n * 100.0 + 1e-8) / 100.0; }
    private String fmt(double n) { return n == Math.floor(n) ? Long.toString((long)n) : String.format(Locale.US, "%.2f", n); }
    private void saveData() {
        if (data == null || dataFile == null) return;
        try { data.save(dataFile); } catch (IOException e) { getLogger().severe("Falha ao salvar saldos: " + e.getMessage()); }
    }

    private boolean handleSouls(org.bukkit.command.CommandSender sender, String[] args) {
        if (args.length == 0) {
            if (sender instanceof Player p) sender.sendMessage(PREFIX + "Seu saldo: §f" + fmt(balance(p.getUniqueId())) + " almas.");
            else sender.sendMessage("Use /almas dar|retirar|definir <jogador> <quantidade>");
            return true;
        }
        if (!sender.hasPermission("nexorasouls.admin")) { sender.sendMessage("§cSem permissão."); return true; }
        if (args.length != 3 || !List.of("dar","retirar","definir").contains(args[0].toLowerCase(Locale.ROOT))) {
            sender.sendMessage("§cUso: /almas dar|retirar|definir <jogador> <quantidade>"); return true;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) { sender.sendMessage("§cEsse jogador precisa estar online."); return true; }
        double amount;
        try { amount = Double.parseDouble(args[2]); } catch (NumberFormatException ex) { sender.sendMessage("§cQuantidade inválida."); return true; }
        if (!Double.isFinite(amount) || amount <= 0) { sender.sendMessage("§cInforme uma quantidade maior que zero."); return true; }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "dar" -> addBalance(target.getUniqueId(), amount);
            case "retirar" -> setBalance(target.getUniqueId(), balance(target.getUniqueId()) - amount);
            case "definir" -> setBalance(target.getUniqueId(), amount);
        }
        sender.sendMessage("§aSaldo de " + target.getName() + " atualizado.");
        target.sendMessage(PREFIX + "Seu saldo agora é §f" + fmt(balance(target.getUniqueId())) + " almas.");
        return true;
    }

    @EventHandler public void onMobDeath(EntityDeathEvent event) {
        if (event.getEntity() instanceof Player) return;
        Player killer = event.getEntity().getKiller();
        if (killer == null) return;
        String mob = event.getEntityType().name();
        double reward = getConfig().getDouble("mob-rewards." + mob, getConfig().getDouble("mob-rewards.default", 1));
        if (reward <= 0) return;
        addBalance(killer.getUniqueId(), reward);
        killer.sendMessage(PREFIX + "§a+" + fmt(reward) + " almas §7por derrotar " + mob + ".");
    }

    @EventHandler public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity(), killer = victim.getKiller();
        if (killer == null || killer.getUniqueId().equals(victim.getUniqueId())) return;
        long now = System.currentTimeMillis();
        long cooldown = Math.max(0, getConfig().getLong("pvp-transfer-cooldown-seconds", 300)) * 1000L;
        String pair = killer.getUniqueId() + ":" + victim.getUniqueId();
        Long previous = killCooldowns.get(pair);
        if (previous != null && now - previous < cooldown) {
            killer.sendMessage(PREFIX + "§7Essa eliminação não transferiu almas: proteção anti-farm.");
            return;
        }
        killCooldowns.put(pair, now);
        double victimBalance = balance(victim.getUniqueId());
        double reward = round(victimBalance * 0.5);
        if (reward <= 0) {
            killer.sendMessage(PREFIX + "§7A vítima não tinha almas para transferir.");
            return;
        }
        setBalance(victim.getUniqueId(), victimBalance - reward);
        addBalance(killer.getUniqueId(), reward);
        killer.sendMessage(PREFIX + "§aVocê recebeu §f" + fmt(reward) + " almas §a(50% do saldo da vítima)!");
        victim.sendMessage(PREFIX + "§cVocê perdeu §f" + fmt(reward) + " almas §cpara " + killer.getName() + ".");
    }

    private void openMainShop(Player p) {
        Inventory inv = Bukkit.createInventory(null, 27, MAIN_TITLE);
        addCategory(inv, 10, Material.BRICKS, "§eConstrução e decoração", "construction");
        addCategory(inv, 12, Material.DIAMOND_SWORD, "§cCombate", "combat");
        addCategory(inv, 14, Material.DIAMOND_PICKAXE, "§bMineração", "mining");
        addCategory(inv, 16, Material.GOLDEN_APPLE, "§aSobrevivência", "survival");
        ItemStack wallet = new ItemStack(Material.SOUL_LANTERN);
        ItemMeta meta = wallet.getItemMeta();
        meta.setDisplayName("§dSeu saldo: §f" + fmt(balance(p.getUniqueId())) + " almas");
        wallet.setItemMeta(meta); inv.setItem(22, wallet);
        p.openInventory(inv);
    }

    private void addCategory(Inventory inv, int slot, Material material, String name, String key) {
        ItemStack item = new ItemStack(material); ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        meta.getPersistentDataContainer().set(categoryKey, PersistentDataType.STRING, key);
        meta.setLore(List.of("§7Clique para abrir esta categoria"));
        item.setItemMeta(meta); inv.setItem(slot, item);
    }

    private void openCategory(Player p, String category) {
        String title = "§5NEXORA §8| §d" + categoryLabel(category);
        Inventory inv = Bukkit.createInventory(null, 54, title);
        ConfigurationSection section = getConfig().getConfigurationSection("shop." + category);
        if (section != null) {
            int slot = 0;
            for (String id : section.getKeys(false)) {
                if (slot >= 45) break;
                String path = "shop." + category + "." + id;
                Material mat = Material.matchMaterial(getConfig().getString(path + ".material", "STONE"));
                if (mat == null) continue;
                ItemStack display = new ItemStack(mat);
                ItemMeta meta = display.getItemMeta();
                meta.setDisplayName("§f" + getConfig().getString(path + ".name", id));
                double price = getConfig().getDouble(path + ".price", 1);
                String rarity = getConfig().getString(path + ".rarity", "Comum");
                meta.setLore(List.of("§7Raridade: " + rarity, "§dPreço: §f" + fmt(price) + " almas", "§eClique para comprar"));
                meta.getPersistentDataContainer().set(productKey, PersistentDataType.STRING, category + "." + id);
                display.setItemMeta(meta); inv.setItem(slot++, display);
            }
        }
        ItemStack back = new ItemStack(Material.ARROW); ItemMeta bm = back.getItemMeta();
        bm.setDisplayName("§eVoltar"); back.setItemMeta(bm); inv.setItem(49, back);
        p.openInventory(inv);
    }

    @EventHandler public void onInventoryClick(InventoryClickEvent event) {
        String title = event.getView().getTitle();
        if (!title.startsWith("§5NEXORA §8| §d")) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player p)) return;
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) return;
        ItemMeta meta = clicked.getItemMeta();
        if (MAIN_TITLE.equals(title)) {
            String category = meta.getPersistentDataContainer().get(categoryKey, PersistentDataType.STRING);
            if (category != null) openCategory(p, category);
            return;
        }
        if (event.getRawSlot() == 49) { openMainShop(p); return; }
        String product = meta.getPersistentDataContainer().get(productKey, PersistentDataType.STRING);
        if (product != null) purchase(p, product);
    }

    private void purchase(Player p, String product) {
        String path = "shop." + product;
        if (!getConfig().contains(path)) { p.sendMessage(PREFIX + "§cItem não encontrado."); return; }
        double price = getConfig().getDouble(path + ".price", -1);
        if (price < 0) { p.sendMessage(PREFIX + "§cPreço inválido."); return; }
        double current = balance(p.getUniqueId());
        if (current < price) {
            p.sendMessage(PREFIX + "§cVocê precisa de mais " + fmt(price - current) + " almas.");
            return;
        }
        Material material = Material.matchMaterial(getConfig().getString(path + ".material", "STONE"));
        if (material == null || material.isAir()) { p.sendMessage(PREFIX + "§cMaterial inválido na configuração."); return; }
        int amount = Math.max(1, Math.min(64, getConfig().getInt(path + ".amount", 1)));
        ItemStack bought = new ItemStack(material, amount);
        ItemMeta meta = bought.getItemMeta();
        String displayName = getConfig().getString(path + ".name");
        if (displayName != null && !displayName.isBlank()) meta.setDisplayName("§f" + displayName);
        ConfigurationSection enchants = getConfig().getConfigurationSection(path + ".enchants");
        if (enchants != null) {
            for (String enchantName : enchants.getKeys(false)) {
                Enchantment enchant = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(enchantName.toLowerCase(Locale.ROOT)));
                int level = enchants.getInt(enchantName, 1);
                if (enchant != null && level > 0) meta.addEnchant(enchant, level, true);
            }
        }
        bought.setItemMeta(meta);
        Map<Integer, ItemStack> leftovers = p.getInventory().addItem(bought);
        if (!leftovers.isEmpty()) { p.sendMessage(PREFIX + "§cLibere espaço no inventário antes de comprar."); return; }
        setBalance(p.getUniqueId(), current - price);
        p.sendMessage(PREFIX + "§aCompra realizada: §f" + (displayName == null ? material.name() : displayName) + " §apor " + fmt(price) + " almas.");
    }

    private String categoryLabel(String key) {
        return switch (key) {
            case "construction" -> "Construção";
            case "combat" -> "Combate";
            case "mining" -> "Mineração";
            default -> "Sobrevivência";
        };
    }
          }
                                     
