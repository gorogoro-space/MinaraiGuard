package space.gorogoro.minaraiguard;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.world.StructureGrowEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * 見習いが置いたブロックの記録係。すべて MONITOR(確定後)で動く。
 * ・見習いが置いた → 記録
 * ・見習い以外が置いた/誰かが壊した → その位置の記録を消す
 * ・見習いが植えた苗木が育った → 木全体を見習いのものとして記録
 */
final class PlacementListener implements Listener {

    private final MinaraiGuard plugin;

    PlacementListener(MinaraiGuard plugin) {
        this.plugin = plugin;
    }

    private boolean tracked(World w) {
        return plugin.settings().apprenticeWorlds.contains(w.getName());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Block b = e.getBlockPlaced();
        World w = b.getWorld();
        if (!tracked(w)) return;
        Player p = e.getPlayer();
        boolean apprentice = plugin.apprentices().isApprentice(p);
        if (e instanceof BlockMultiPlaceEvent multi) {
            // ドアの上半分・ベッドの頭側など
            for (BlockState st : multi.getReplacedBlockStates()) {
                apply(apprentice, p, w, st.getX(), st.getY(), st.getZ());
            }
        }
        apply(apprentice, p, w, b.getX(), b.getY(), b.getZ());
    }

    private void apply(boolean apprentice, Player p, World w, int x, int y, int z) {
        if (apprentice) plugin.placements().recordPlace(p.getUniqueId(), w, x, y, z);
        else plugin.placements().clearAt(w, x, y, z);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        if (tracked(b.getWorld())) plugin.placements().clearAt(b.getWorld(), b.getX(), b.getY(), b.getZ());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGrow(StructureGrowEvent e) {
        World w = e.getWorld();
        if (!tracked(w)) return;
        List<int[]> positions = new ArrayList<>(e.getBlocks().size());
        for (BlockState st : e.getBlocks()) positions.add(new int[]{st.getX(), st.getY(), st.getZ()});
        Location l = e.getLocation();
        plugin.placements().recordGrowth(w, l.getBlockX(), l.getBlockY(), l.getBlockZ(), positions);
    }
}
