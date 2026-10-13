package com.example.invcheck;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class InvCheck extends JavaPlugin implements Listener {

    /** Marks our GUI so we can recognise (and control) it. */
    private static class ViewHolder implements InventoryHolder {
        final UUID target;
        final boolean ender;
        Inventory inv;

        ViewHolder(UUID target, boolean ender) {
            this.target = target;
            this.ender = ender;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    // Only plain pick up / place / swap with the cursor is allowed.
    // Shift-click, number keys, F (offhand), drops, double-click and
    // middle-click are all refused, so nothing can land in the viewer's inventory.
    private static final Set<InventoryAction> ALLOWED = EnumSet.of(
            InventoryAction.PICKUP_ALL,
            InventoryAction.PICKUP_HALF,
            InventoryAction.PICKUP_SOME,
            InventoryAction.PICKUP_ONE,
            InventoryAction.PLACE_ALL,
            InventoryAction.PLACE_SOME,
            InventoryAction.PLACE_ONE,
            InventoryAction.SWAP_WITH_CURSOR);

    private final Map<UUID, UUID> viewers = new HashMap<UUID, UUID>(); // viewer -> target
    private final Set<UUID> dirty = new HashSet<UUID>();               // viewers with a pending write-back

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskTimer(this, new Runnable() {
            @Override
            public void run() {
                refreshAll();
            }
        }, 10L, 10L);
    }

    // ---------- Command ----------

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cOnly players can use this command.");
            return true;
        }
        Player viewer = (Player) sender;
        if (!viewer.hasPermission("invcheck.use")) {
            viewer.sendMessage("§cYou don't have permission to use that command.");
            return true;
        }
        if (args.length < 1) {
            viewer.sendMessage("§eUsage: /invcheck <player> [ender]");
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            viewer.sendMessage("§cThat player isn't online.");
            return true;
        }
        boolean ender = args.length > 1 && args[1].equalsIgnoreCase("ender");

        ViewHolder holder = new ViewHolder(target.getUniqueId(), ender);
        Inventory inv = Bukkit.createInventory(holder,
                ender ? 27 : 54,
                (ender ? "§5Ender Chest: " : "§8Inventory: ") + target.getName());
        holder.inv = inv;
        fill(inv, target, ender);

        viewers.put(viewer.getUniqueId(), target.getUniqueId());
        viewer.openInventory(inv);
        return true;
    }

    // ---------- Layout ----------
    // Player view: 0-26 main inventory, 27-35 hotbar, 45-48 helmet/chest/legs/boots, 50 offhand.
    // Everything else is a locked divider.

    private boolean isEditable(ViewHolder h, int slot) {
        if (h.ender) return slot >= 0 && slot < 27;
        return (slot >= 0 && slot < 36) || (slot >= 45 && slot <= 48) || slot == 50;
    }

    private ItemStack pane() {
        ItemStack it = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta m = it.getItemMeta();
        if (m != null) {
            m.setDisplayName(" ");
            it.setItemMeta(m);
        }
        return it;
    }

    /** Target's real inventory -> the GUI. */
    private void fill(Inventory inv, Player target, boolean ender) {
        if (ender) {
            ItemStack[] c = target.getEnderChest().getContents();
            for (int i = 0; i < 27; i++) {
                inv.setItem(i, i < c.length ? c[i] : null);
            }
            return;
        }
        PlayerInventory pi = target.getInventory();
        ItemStack[] s = pi.getStorageContents(); // 0-8 hotbar, 9-35 main

        for (int i = 9; i < 36; i++) inv.setItem(i - 9, i < s.length ? s[i] : null);
        for (int i = 0; i < 9; i++) inv.setItem(27 + i, i < s.length ? s[i] : null);

        ItemStack p = pane();
        for (int i = 36; i < 54; i++) inv.setItem(i, p);

        inv.setItem(45, pi.getHelmet());
        inv.setItem(46, pi.getChestplate());
        inv.setItem(47, pi.getLeggings());
        inv.setItem(48, pi.getBoots());
        inv.setItem(50, pi.getItemInOffHand());
    }

    /** The GUI -> target's real inventory. */
    private void writeInto(Inventory top, ViewHolder h, Player target) {
        if (h.ender) {
            for (int i = 0; i < 27; i++) target.getEnderChest().setItem(i, top.getItem(i));
            return;
        }
        PlayerInventory pi = target.getInventory();
        for (int i = 0; i < 27; i++) pi.setItem(i + 9, top.getItem(i));   // main
        for (int i = 0; i < 9; i++) pi.setItem(i, top.getItem(27 + i));   // hotbar
        pi.setHelmet(top.getItem(45));
        pi.setChestplate(top.getItem(46));
        pi.setLeggings(top.getItem(47));
        pi.setBoots(top.getItem(48));
        pi.setItemInOffHand(top.getItem(50));
    }

    private void scheduleWriteBack(final Player viewer) {
        dirty.add(viewer.getUniqueId());
        Bukkit.getScheduler().runTask(this, new Runnable() {
            @Override
            public void run() {
                Inventory top = viewer.getOpenInventory().getTopInventory();
                if (isOurs(top)) {
                    ViewHolder h = (ViewHolder) top.getHolder();
                    Player target = Bukkit.getPlayer(h.target);
                    if (target != null) writeInto(top, h, target);
                }
                dirty.remove(viewer.getUniqueId());
            }
        });
    }

    private void refreshAll() {
        List<UUID> remove = new ArrayList<UUID>();
        for (Map.Entry<UUID, UUID> e : viewers.entrySet()) {
            Player viewer = Bukkit.getPlayer(e.getKey());
            Player target = Bukkit.getPlayer(e.getValue());
            if (viewer == null || target == null) {
                if (viewer != null) viewer.closeInventory();
                remove.add(e.getKey());
                continue;
            }
            if (dirty.contains(e.getKey())) continue; // don't overwrite a pending edit
            Inventory top = viewer.getOpenInventory().getTopInventory();
            if (isOurs(top)) {
                fill(top, target, ((ViewHolder) top.getHolder()).ender);
            } else {
                remove.add(e.getKey());
            }
        }
        for (UUID u : remove) viewers.remove(u);
    }

    // ---------- Rules ----------

    private boolean isOurs(Inventory top) {
        return top != null && top.getHolder() instanceof ViewHolder;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!isOurs(top)) return;
        Player viewer = (Player) e.getWhoClicked();
        ViewHolder h = (ViewHolder) top.getHolder();
        int raw = e.getRawSlot();

        boolean ok = viewer.hasPermission("invcheck.edit")
                && raw >= 0 && raw < top.getSize()          // inside the target's inventory only
                && isEditable(h, raw)                        // not a divider
                && (e.getClick() == ClickType.LEFT || e.getClick() == ClickType.RIGHT)
                && ALLOWED.contains(e.getAction());

        if (!ok) {
            e.setCancelled(true);
            return;
        }
        scheduleWriteBack(viewer);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!isOurs(top)) return;
        Player viewer = (Player) e.getWhoClicked();
        ViewHolder h = (ViewHolder) top.getHolder();

        boolean ok = viewer.hasPermission("invcheck.edit");
        for (int raw : e.getRawSlots()) {
            if (raw >= top.getSize() || !isEditable(h, raw)) {
                ok = false;
                break;
            }
        }
        if (!ok) {
            e.setCancelled(true);
            return;
        }
        scheduleWriteBack(viewer);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!isOurs(top)) return;
        Player viewer = (Player) e.getPlayer();
        ViewHolder h = (ViewHolder) top.getHolder();
        Player target = Bukkit.getPlayer(h.target);

        if (target != null) writeInto(top, h, target);

        // An item still held on the cursor must NOT fall into the viewer's inventory.
        ItemStack cur = viewer.getItemOnCursor();
        if (cur != null && cur.getType() != Material.AIR) {
            viewer.setItemOnCursor(null);
            if (target != null) {
                Inventory dest = h.ender ? target.getEnderChest() : target.getInventory();
                for (ItemStack left : dest.addItem(cur).values()) {
                    target.getWorld().dropItemNaturally(target.getLocation(), left);
                }
            } else {
                viewer.getWorld().dropItemNaturally(viewer.getLocation(), cur);
            }
        }
        viewers.remove(viewer.getUniqueId());
        dirty.remove(viewer.getUniqueId());
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        if (a.length == 2) return Arrays.asList("ender");
        return null;
    }
}
