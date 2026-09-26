package space.gorogoro.minaraiguard;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityInteractEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerTakeLecternBookEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * スポーン地点の絶対保護。
 * ドア・ボタン・チェスト・看板などの操作は妨げず、ブロックの変化だけを止める。
 * 頻繁に発生するイベントは、範囲外なら整数比較だけで即座に抜ける。
 */
final class SpawnListener implements Listener {

    private static final int EXPLOSION_MARGIN = 64;
    private static final int PISTON_MARGIN = 16;

    private final MinaraiGuard plugin;

    SpawnListener(MinaraiGuard plugin) {
        this.plugin = plugin;
    }

    private Zone zone() {
        return plugin.settings().spawn;
    }

    private static boolean bypass(Player p) {
        return p.hasPermission(Perms.BYPASS);
    }

    private void denyChange(Player p) {
        boolean apprentice = plugin.apprentices().isApprentice(p);
        plugin.messenger().denied(p, "spawn-change", apprentice, apprentice,
                Placeholder.unparsed("remaining", plugin.apprentices().remainingText(p)));
    }

    // ===== プレイヤーによる変更 =====

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Zone zone = zone();
        boolean inside = zone.contains(e.getBlockPlaced());
        if (!inside && e instanceof BlockMultiPlaceEvent multi) {
            // ベッドやドアなど、範囲外から一部だけ範囲内にはみ出す設置
            for (BlockState st : multi.getReplacedBlockStates()) {
                if (zone.contains(st.getWorld().getName(), st.getX(), st.getZ())) {
                    inside = true;
                    break;
                }
            }
        }
        Player p = e.getPlayer();
        if (!inside || bypass(p)) return;
        // 看板など、昇格後のプレイヤーが置いてよいブロック
        if (plugin.settings().spawnMemberBlocks.contains(e.getBlockPlaced().getType())) {
            if (!plugin.apprentices().isApprentice(p)) return;
            e.setCancelled(true);
            plugin.messenger().denied(p, "spawn-member-apprentice", false, true,
                    Placeholder.unparsed("remaining", plugin.apprentices().remainingText(p)));
            return;
        }
        e.setCancelled(true);
        denyChange(p);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent e) {
        bucket(e.getPlayer(), e.getBlock(), e.getBlockClicked(), e);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent e) {
        bucket(e.getPlayer(), e.getBlock(), e.getBlockClicked(), e);
    }

    private void bucket(Player p, Block target, Block clicked, Cancellable e) {
        Zone zone = zone();
        if (!zone.contains(target) && !zone.contains(clicked)) return;
        if (bypass(p)) return;
        e.setCancelled(true);
        denyChange(p);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        Block b = e.getClickedBlock();
        if (b == null || !zone().contains(b)) return;
        Player p = e.getPlayer();
        Material type = b.getType();

        if (e.getAction() == Action.PHYSICAL) {
            // 畑の踏み荒らし・亀の卵(感圧板などはそのまま)
            if ((type == Material.FARMLAND || type == Material.TURTLE_EGG) && !bypass(p)) {
                e.setUseInteractedBlock(Event.Result.DENY);
            }
            return;
        }
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || bypass(p)) return;

        GuardSettings s = plugin.settings();
        ItemStack item = e.getItem();

        // 右クリックで状態が変わるブロック(リピーター等)
        // スニーク中にアイテムを持っている場合はブロックが操作されないので対象外
        if (s.spawnDenyBlocks.contains(type) && !(p.isSneaking() && item != null)) {
            e.setCancelled(true);
            if (e.getHand() == EquipmentSlot.HAND) plugin.messenger().denied(p, "spawn-interact", false, false);
            return;
        }

        // 道具による変化(樹皮剥ぎ・道づくり等)。アイテムの使用だけを止めるので、
        // ブロック自体の操作(ドアを開ける等)や他プラグインの処理は妨げない
        if (item != null && s.toolChangesBlock(item.getType(), type)) {
            e.setUseItemInHand(Event.Result.DENY);
            plugin.messenger().denied(p, "spawn-tool", false, false);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onTakeBook(PlayerTakeLecternBookEvent e) {
        // 書見台の本を読むのは自由、取り出すのは不可
        if (!zone().contains(e.getLectern().getBlock()) || bypass(e.getPlayer())) return;
        e.setCancelled(true);
        plugin.messenger().denied(e.getPlayer(), "spawn-interact", false, false);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFertilize(BlockFertilizeEvent e) {
        Player p = e.getPlayer();
        if (p == null || bypass(p)) return; // ディスペンサーは onDispense で扱う
        Zone zone = zone();
        boolean touches = zone.contains(e.getBlock()) || anyInside(zone, e.getBlocks());
        if (!touches) return;
        e.setCancelled(true);
        plugin.messenger().denied(p, "spawn-tool", false, false);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityPlace(EntityPlaceEvent e) {
        Player p = e.getPlayer();
        Entity entity = e.getEntity();
        if (p == null || !(entity instanceof Minecart)) return;
        if (!zone().contains(entity.getLocation())) return;
        EntityType type = entity.getType();
        if (plugin.settings().allowedCarts.contains(type) || bypass(p)) return;
        e.setCancelled(true);
        plugin.messenger().denied(p, "spawn-cart", false, false,
                Placeholder.unparsed("cart", plugin.settings().cartName(type)));
    }

    // ===== プレイヤー以外による変化 =====

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        Zone zone = zone();
        if (zone.isNear(e.getLocation(), EXPLOSION_MARGIN)) e.blockList().removeIf(zone::contains);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        Zone zone = zone();
        if (zone.isNear(e.getBlock(), EXPLOSION_MARGIN)) e.blockList().removeIf(zone::contains);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        if (crossesBoundary(e.getBlock(), e.getBlocks())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        if (crossesBoundary(e.getBlock(), e.getBlocks())) e.setCancelled(true);
    }

    /** 範囲の境界をまたいでブロックを動かすピストンか(範囲内で完結する装置は動く) */
    private boolean crossesBoundary(Block piston, List<Block> moved) {
        Zone zone = zone();
        if (!zone.isNear(piston, PISTON_MARGIN)) return false;
        if (!(piston.getBlockData() instanceof Directional directional)) return false;
        BlockFace f = directional.getFacing();
        boolean pistonInside = zone.contains(piston);
        if (zone.contains(piston.getRelative(f)) != pistonInside) return true; // ピストンヘッド
        for (Block b : moved) {
            if (zone.contains(b) != pistonInside
                    || zone.contains(b.getRelative(f)) != pistonInside
                    || zone.contains(b.getRelative(f.getOppositeFace())) != pistonInside) {
                return true;
            }
        }
        return false;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent e) {
        Zone zone = zone();
        if (zone.contains(e.getToBlock()) && !zone.contains(e.getBlock())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent e) {
        // エンダーマン、羊の草食べ、ラヴェジャー、落下ブロックなど
        if (!zone().contains(e.getBlock())) return;
        if (e.getEntity() instanceof Player p && bypass(p)) return;
        e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityTrample(EntityInteractEvent e) {
        Material type = e.getBlock().getType();
        if ((type == Material.FARMLAND || type == Material.TURTLE_EGG) && zone().contains(e.getBlock())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent e) {
        Block dispenser = e.getBlock();
        Zone zone = zone();
        if (!zone.isNear(dispenser, 1) || zone.contains(dispenser)) return;
        if (!(dispenser.getBlockData() instanceof Directional directional)) return;
        if (zone.contains(dispenser.getRelative(directional.getFacing()))) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onGrow(StructureGrowEvent e) {
        // 範囲外で育った木が範囲内に入り込むのを防ぐ(範囲内の木はそのまま育つ)
        Zone zone = zone();
        if (!zone.isNear(e.getLocation(), PISTON_MARGIN) || zone.contains(e.getLocation())) return;
        if (anyInside(zone, e.getBlocks())) e.setCancelled(true);
    }

    private static boolean anyInside(Zone zone, List<BlockState> states) {
        for (BlockState st : states) {
            if (zone.contains(st.getWorld().getName(), st.getX(), st.getZ())) return true;
        }
        return false;
    }
}
