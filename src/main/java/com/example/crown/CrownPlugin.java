package com.example.crown;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.EquippableComponent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

public class CrownPlugin extends JavaPlugin implements Listener {

    private NamespacedKey crownKey;

    @Override
    public void onEnable() {
        crownKey = new NamespacedKey(this, "crown");
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("crown").setExecutor(this);
    }

    // ---------- Crown item ----------

    private ItemStack createCrown() {
        // A stick (not a real helmet) so Minecraft shows OUR model on the head.
        // Real helmets always show the vanilla armor look instead.
        ItemStack item = new ItemStack(Material.STICK);
        ItemMeta meta = item.getItemMeta();

        meta.displayName(Component.text("The Crown", NamedTextColor.GOLD)
                .decoration(TextDecoration.ITALIC, false));
        meta.setUnbreakable(true);
        meta.setMaxStackSize(1);

        // Makes the stick wearable on the head (no armor points)
        EquippableComponent equip = meta.getEquippable();
        equip.setSlot(EquipmentSlot.HEAD);
        meta.setEquippable(equip);

        // Resource pack model: assets/crown/items/crown.json
        meta.setItemModel(new NamespacedKey("crown", "crown"));

        // Tag so the plugin can recognise it
        meta.getPersistentDataContainer().set(crownKey, PersistentDataType.BYTE, (byte) 1);

        item.setItemMeta(meta);
        return item;
    }

    private boolean isCrown(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(crownKey, PersistentDataType.BYTE);
    }

    private boolean hasCrown(Player player) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (isCrown(item)) return true;
        }
        return false;
    }

    private void removeCrown(Player player) {
        PlayerInventory inv = player.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            if (isCrown(inv.getItem(i))) inv.setItem(i, null);
        }
    }

    // Puts the crown straight on the player's head. Old helmet goes to inventory.
    private void giveCrown(Player player) {
        PlayerInventory inv = player.getInventory();
        ItemStack oldHelmet = inv.getHelmet();
        inv.setHelmet(createCrown());

        if (oldHelmet != null && !oldHelmet.getType().isAir()) {
            inv.addItem(oldHelmet).values()
                    .forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
        }

        getConfig().set("holder", player.getUniqueId().toString());
        saveConfig();
    }

    // ---------- Command ----------

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage("Player not found.");
                return true;
            }
            for (Player p : Bukkit.getOnlinePlayers()) removeCrown(p);
            giveCrown(target);
            sender.sendMessage("Crown given to " + target.getName() + ".");
            return true;
        }
        sender.sendMessage("Usage: /crown give <player>");
        return true;
    }

    // ---------- Hot potato ----------

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player victim = e.getEntity();
        if (!hasCrown(victim)) return;

        // Never let the crown hit the ground
        e.getDrops().removeIf(this::isCrown);

        Player killer = victim.getKiller();
        if (killer != null && !killer.equals(victim)) {
            removeCrown(victim);   // covers keepInventory = true
            giveCrown(killer);
            killer.sendMessage(Component.text("You took the crown!", NamedTextColor.GOLD));
        } else if (!e.getKeepInventory()) {
            // Died to mobs, fall damage, etc: victim keeps the crown on respawn
            e.getItemsToKeep().add(createCrown());
        }
    }

    // Remove duplicate if an offline ex-holder logs back in
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        String holder = getConfig().getString("holder", "");
        if (hasCrown(p) && !p.getUniqueId().toString().equals(holder)) {
            removeCrown(p);
        }
    }

    // ---------- Can't drop it ----------

    @EventHandler
    public void onDrop(PlayerDropItemEvent e) {
        if (isCrown(e.getItemDrop().getItemStack())) e.setCancelled(true);
    }

    // Backup: if a crown item ever spawns in the world, block it
    @EventHandler
    public void onItemSpawn(ItemSpawnEvent e) {
        if (isCrown(e.getEntity().getItemStack())) e.setCancelled(true);
    }

    // Can't move it into chests, shulkers, anvils, crafting grid, etc.
    @EventHandler
    public void onClick(InventoryClickEvent e) {
        boolean touchesCrown = isCrown(e.getCurrentItem()) || isCrown(e.getCursor());

        if (e.getClick() == ClickType.NUMBER_KEY && e.getHotbarButton() >= 0) {
            touchesCrown |= isCrown(e.getWhoClicked().getInventory().getItem(e.getHotbarButton()));
        }
        if (e.getClick() == ClickType.SWAP_OFFHAND) {
            touchesCrown |= isCrown(e.getWhoClicked().getInventory().getItemInOffHand());
        }
        if (!touchesCrown) return;

        InventoryType top = e.getView().getTopInventory().getType();
        InventoryType.SlotType slot = e.getSlotType();

        boolean ownInventoryOnly = top == InventoryType.CRAFTING
                && slot != InventoryType.SlotType.CRAFTING
                && slot != InventoryType.SlotType.RESULT;

        if (!ownInventoryOnly) e.setCancelled(true);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (!isCrown(e.getOldCursor())) return;
        int topSize = e.getView().getTopInventory().getSize();
        if (e.getRawSlots().stream().anyMatch(slot -> slot < topSize)) e.setCancelled(true);
    }

    // Can't put it on item frames / armor stands
    @EventHandler
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        PlayerInventory inv = e.getPlayer().getInventory();
        if (isCrown(inv.getItemInMainHand()) || isCrown(inv.getItemInOffHand())) {
            e.setCancelled(true);
        }
    }

    // ---------- Unbreakable ----------

    @EventHandler
    public void onItemDamage(PlayerItemDamageEvent e) {
        if (isCrown(e.getItem())) e.setCancelled(true);
    }
}
