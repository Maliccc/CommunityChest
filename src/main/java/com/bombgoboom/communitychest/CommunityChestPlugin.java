package com.bombgoboom.communitychest;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Chest;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Community Chest: a shared, non-craftable chest with 108 slots.
 * A GUI inventory can only be 54 slots, so the 108 slots are split into
 * 3 pages of 36 slots (with a navigation row at the bottom of each page).
 */
public final class CommunityChestPlugin extends JavaPlugin implements Listener, TabExecutor {

    private static final int PAGES = 3;
    private static final int PAGE_SLOTS = 36;      // usable slots per page (3 x 36 = 108)
    private static final int INV_SIZE = 45;        // 36 item slots + 9 navigation slots
    private static final int PREV_SLOT = 39;
    private static final int INFO_SLOT = 40;
    private static final int NEXT_SLOT = 41;
    private static final BlockFace[] SIDES = {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};

    private NamespacedKey chestKey;   // marks the item and the placed block
    private NamespacedKey dataKey;    // stores the chest contents on the placed block
    private NamespacedKey navKey;     // marks GUI navigation items

    private final Map<String, Session> sessions = new HashMap<>();

    // ---------------------------------------------------------------- lifecycle

    @Override
    public void onEnable() {
        chestKey = new NamespacedKey(this, "community_chest");
        dataKey = new NamespacedKey(this, "contents");
        navKey = new NamespacedKey(this, "nav");

        Bukkit.getPluginManager().registerEvents(this, this);
        var cmd = getCommand("community_chest");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
        // Safety net: save every 30 seconds in case of a crash.
        Bukkit.getScheduler().runTaskTimer(this, this::saveAll, 600L, 600L);
    }

    @Override
    public void onDisable() {
        for (Session s : new ArrayList<>(sessions.values())) {
            for (Inventory inv : s.pages) {
                for (HumanEntity v : new ArrayList<>(inv.getViewers())) {
                    v.closeInventory();
                }
            }
        }
        saveAll();
        sessions.clear();
    }

    // ---------------------------------------------------------------- items

    private ItemStack createItem(int amount) {
        ItemStack item = new ItemStack(Material.CHEST, amount);
        item.editMeta(meta -> {
            meta.displayName(plain("Community Chest", NamedTextColor.GOLD));
            meta.lore(List.of(
                    plain("Shared storage - 108 slots", NamedTextColor.GRAY),
                    plain("Anyone can open it. Only mods can break it.", NamedTextColor.DARK_GRAY)));
            meta.getPersistentDataContainer().set(chestKey, PersistentDataType.BYTE, (byte) 1);
        });
        return item;
    }

    private static Component plain(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }

    private boolean isCommunityItem(ItemStack item) {
        if (item == null || item.getType() != Material.CHEST || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(chestKey, PersistentDataType.BYTE);
    }

    private boolean isNav(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(navKey, PersistentDataType.BYTE);
    }

    private boolean isCommunityChest(Block block) {
        if (block.getType() != Material.CHEST) return false;
        return block.getState(false) instanceof Chest chest
                && chest.getPersistentDataContainer().has(chestKey, PersistentDataType.BYTE);
    }

    private boolean isCommunityHolder(InventoryHolder holder) {
        return holder instanceof Chest chest
                && chest.getPersistentDataContainer().has(chestKey, PersistentDataType.BYTE);
    }

    // ---------------------------------------------------------------- command

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || !args[0].equalsIgnoreCase("give")) {
            sender.sendMessage(Component.text("Usage: /community_chest give [player] [amount]", NamedTextColor.YELLOW));
            return true;
        }
        if (!sender.hasPermission("communitychest.give")) {
            sender.sendMessage(Component.text("You don't have permission to do that.", NamedTextColor.RED));
            return true;
        }
        Player target;
        if (args.length >= 2) {
            target = Bukkit.getPlayerExact(args[1]);
        } else if (sender instanceof Player self) {
            target = self;
        } else {
            sender.sendMessage(Component.text("Specify a player: /community_chest give <player> [amount]", NamedTextColor.RED));
            return true;
        }
        if (target == null) {
            sender.sendMessage(Component.text("That player isn't online.", NamedTextColor.RED));
            return true;
        }
        int amount = 1;
        if (args.length >= 3) {
            try {
                amount = Math.max(1, Math.min(64, Integer.parseInt(args[2])));
            } catch (NumberFormatException ex) {
                sender.sendMessage(Component.text("Amount must be a number.", NamedTextColor.RED));
                return true;
            }
        }
        Map<Integer, ItemStack> leftover = target.getInventory().addItem(createItem(amount));
        for (ItemStack extra : leftover.values()) {
            target.getWorld().dropItemNaturally(target.getLocation(), extra);
        }
        sender.sendMessage(Component.text("Gave " + amount + " Community Chest to " + target.getName() + ".", NamedTextColor.GREEN));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            out.add("give");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        } else if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            out.addAll(List.of("1", "16", "64"));
        }
        String typed = args[args.length - 1].toLowerCase();
        out.removeIf(s -> !s.toLowerCase().startsWith(typed));
        return out;
    }

    // ---------------------------------------------------------------- placing / opening / breaking

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Block block = e.getBlockPlaced();
        if (block.getType() != Material.CHEST) return;
        boolean ours = isCommunityItem(e.getItemInHand());

        // Never let a Community Chest join up with another chest into a double chest.
        for (BlockFace face : SIDES) {
            Block neighbor = block.getRelative(face);
            boolean neighborIsChest = neighbor.getType() == Material.CHEST || neighbor.getType() == Material.TRAPPED_CHEST;
            if (isCommunityChest(neighbor) || (ours && neighborIsChest)) {
                e.setCancelled(true);
                e.getPlayer().sendMessage(Component.text("A Community Chest can't be placed next to another chest.", NamedTextColor.RED));
                return;
            }
        }
        if (!ours) return;

        if (block.getState() instanceof Chest chest) {
            chest.getPersistentDataContainer().set(chestKey, PersistentDataType.BYTE, (byte) 1);
            chest.update(true, false);
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block block = e.getClickedBlock();
        if (block == null || !isCommunityChest(block)) return;

        Player player = e.getPlayer();
        // Sneaking with something in hand = the player wants to place a block against the chest.
        if (player.isSneaking() && (player.getInventory().getItemInMainHand().getType() != Material.AIR
                || player.getInventory().getItemInOffHand().getType() != Material.AIR)) {
            return;
        }
        e.setCancelled(true);
        if (e.getHand() == EquipmentSlot.HAND) {
            openChest(player, block, 0);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onBreak(BlockBreakEvent e) {
        Block block = e.getBlock();
        if (!isCommunityChest(block)) return;
        Player player = e.getPlayer();

        if (!player.hasPermission("communitychest.break")) {
            e.setCancelled(true);
            player.sendMessage(Component.text("Only moderators can break the Community Chest.", NamedTextColor.RED));
            return;
        }

        String key = keyOf(block);
        Session session = sessions.computeIfAbsent(key, k -> loadSession(block));
        for (Inventory inv : session.pages) {
            for (HumanEntity v : new ArrayList<>(inv.getViewers())) {
                v.closeInventory();
            }
        }
        Location drop = block.getLocation().add(0.5, 0.5, 0.5);
        World world = block.getWorld();
        for (Inventory inv : session.pages) {
            for (int slot = 0; slot < PAGE_SLOTS; slot++) {
                ItemStack item = inv.getItem(slot);
                if (item != null && !item.getType().isAir()) {
                    world.dropItemNaturally(drop, item);
                }
            }
        }
        sessions.remove(key);

        e.setDropItems(false);
        if (player.getGameMode() != GameMode.CREATIVE) {
            world.dropItemNaturally(drop, createItem(1));
        }
    }

    // ---------------------------------------------------------------- protection

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(this::isCommunityChest);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        e.blockList().removeIf(this::isCommunityChest);
    }

    @EventHandler(ignoreCancelled = true)
    public void onHopper(InventoryMoveItemEvent e) {
        if (isCommunityHolder(e.getSource().getHolder(false)) || isCommunityHolder(e.getDestination().getHolder(false))) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onPrepareCraft(PrepareItemCraftEvent e) {
        for (ItemStack item : e.getInventory().getMatrix()) {
            if (isCommunityItem(item)) {
                e.getInventory().setResult(null);
                return;
            }
        }
    }

    // ---------------------------------------------------------------- GUI

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof Holder holder)) return;

        Inventory clicked = e.getClickedInventory();
        if (clicked == top && e.getSlot() >= PAGE_SLOTS) {
            e.setCancelled(true);
            if (!(e.getWhoClicked() instanceof Player player)) return;
            int target = -1;
            if (e.getSlot() == PREV_SLOT && holder.page > 0) target = holder.page - 1;
            if (e.getSlot() == NEXT_SLOT && holder.page < PAGES - 1) target = holder.page + 1;
            if (target >= 0) {
                final Inventory next = holder.session.pages[target];
                Bukkit.getScheduler().runTask(this, () -> player.openInventory(next));
            }
            return;
        }
        if (isNav(e.getCurrentItem()) || isNav(e.getCursor())) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof Holder)) return;
        for (int raw : e.getRawSlots()) {
            if (raw >= PAGE_SLOTS && raw < top.getSize()) {
                e.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder() instanceof Holder holder)) return;
        Session session = holder.session;
        save(session);
        // Next tick: if nobody is looking at any page anymore, drop it from memory.
        Bukkit.getScheduler().runTask(this, () -> {
            for (Inventory inv : session.pages) {
                if (!inv.getViewers().isEmpty()) return;
            }
            sessions.remove(session.key, session);
        });
    }

    private void openChest(Player player, Block block, int page) {
        Session session = sessions.computeIfAbsent(keyOf(block), k -> loadSession(block));
        player.openInventory(session.pages[page]);
        player.playSound(block.getLocation(), Sound.BLOCK_CHEST_OPEN, 1f, 1f);
    }

    // ---------------------------------------------------------------- storage

    private String keyOf(Block b) {
        UUID world = b.getWorld().getUID();
        return world + ":" + b.getX() + ":" + b.getY() + ":" + b.getZ();
    }

    private Session loadSession(Block block) {
        Session session = new Session(keyOf(block), block.getWorld(), block.getX(), block.getY(), block.getZ());

        ItemStack[] stored = new ItemStack[0];
        if (block.getState(false) instanceof Chest chest) {
            byte[] data = chest.getPersistentDataContainer().get(dataKey, PersistentDataType.BYTE_ARRAY);
            if (data != null && data.length > 0) {
                try {
                    stored = ItemStack.deserializeItemsFromBytes(data);
                } catch (Exception ex) {
                    getLogger().warning("Could not read Community Chest contents at " + session.key + ": " + ex.getMessage());
                }
            }
        }

        for (int page = 0; page < PAGES; page++) {
            Holder holder = new Holder(session, page);
            Inventory inv = Bukkit.createInventory(holder, INV_SIZE,
                    Component.text("Community Chest (" + (page + 1) + "/" + PAGES + ")"));
            holder.inventory = inv;
            session.pages[page] = inv;

            for (int slot = 0; slot < PAGE_SLOTS; slot++) {
                int index = page * PAGE_SLOTS + slot;
                if (index < stored.length && stored[index] != null && !stored[index].getType().isAir()) {
                    inv.setItem(slot, stored[index]);
                }
            }
            decorateNav(inv, page);
        }
        return session;
    }

    private void decorateNav(Inventory inv, int page) {
        ItemStack filler = navItem(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "));
        for (int i = PAGE_SLOTS; i < INV_SIZE; i++) inv.setItem(i, filler);
        if (page > 0) inv.setItem(PREV_SLOT, navItem(Material.ARROW, plain("Previous page", NamedTextColor.YELLOW)));
        inv.setItem(INFO_SLOT, navItem(Material.PAPER, plain("Page " + (page + 1) + " of " + PAGES, NamedTextColor.GOLD)));
        if (page < PAGES - 1) inv.setItem(NEXT_SLOT, navItem(Material.ARROW, plain("Next page", NamedTextColor.YELLOW)));
    }

    private ItemStack navItem(Material material, Component name) {
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> {
            meta.displayName(name);
            meta.getPersistentDataContainer().set(navKey, PersistentDataType.BYTE, (byte) 1);
        });
        return item;
    }

    private void save(Session session) {
        if (!session.world.isChunkLoaded(session.x >> 4, session.z >> 4)) return;
        Block block = session.world.getBlockAt(session.x, session.y, session.z);
        if (!(block.getState() instanceof Chest chest)) return;
        if (!chest.getPersistentDataContainer().has(chestKey, PersistentDataType.BYTE)) return;

        ItemStack[] all = new ItemStack[PAGES * PAGE_SLOTS];
        for (int page = 0; page < PAGES; page++) {
            for (int slot = 0; slot < PAGE_SLOTS; slot++) {
                ItemStack item = session.pages[page].getItem(slot);
                all[page * PAGE_SLOTS + slot] = (item == null) ? new ItemStack(Material.AIR) : item;
            }
        }
        chest.getPersistentDataContainer().set(dataKey, PersistentDataType.BYTE_ARRAY, ItemStack.serializeItemsAsBytes(all));
        chest.update(true, false);
    }

    private void saveAll() {
        for (Session s : sessions.values()) save(s);
    }

    // ---------------------------------------------------------------- helper types

    private static final class Session {
        final String key;
        final World world;
        final int x, y, z;
        final Inventory[] pages = new Inventory[PAGES];

        Session(String key, World world, int x, int y, int z) {
            this.key = key;
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    private static final class Holder implements InventoryHolder {
        final Session session;
        final int page;
        Inventory inventory;

        Holder(Session session, int page) {
            this.session = session;
            this.page = page;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
