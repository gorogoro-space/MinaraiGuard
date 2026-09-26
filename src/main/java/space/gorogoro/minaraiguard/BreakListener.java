package space.gorogoro.minaraiguard;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;

/**
 * ブロック破壊の判定(スポーン保護と見習いのルール)。
 * 掘り始め(BlockDamageEvent)でアクションバーを先に出し、掘る時間を無駄にさせない。
 */
final class BreakListener implements Listener {

    private enum Decision { ALLOW, SPAWN, BUILDING, RESOURCE }

    private final MinaraiGuard plugin;

    BreakListener(MinaraiGuard plugin) {
        this.plugin = plugin;
    }

    private Decision decide(Player p, Block b) {
        GuardSettings s = plugin.settings();
        if (s.spawn.contains(b)) {
            if (p.hasPermission(Perms.BYPASS)) return Decision.ALLOW;
            // 看板など、昇格後のプレイヤーが壊してよいブロック(他人の看板は LWC が守る)
            if (s.spawnMemberBlocks.contains(b.getType()) && !plugin.apprentices().isApprentice(p)) return Decision.ALLOW;
            return Decision.SPAWN;
        }
        if (!plugin.apprentices().isApprentice(p)) return Decision.ALLOW;
        if (!s.apprenticeWorlds.contains(b.getWorld().getName())) return Decision.ALLOW;

        Material type = b.getType();
        if (s.terrainBlocks.contains(type)) return Decision.ALLOW;
        if (plugin.placements().isOwner(p.getUniqueId(), b)) return Decision.ALLOW;
        return s.resourceBlocks.contains(type) ? Decision.RESOURCE : Decision.BUILDING;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamage(BlockDamageEvent e) {
        Decision d = decide(e.getPlayer(), e.getBlock());
        if (d != Decision.ALLOW) notify(e.getPlayer(), d);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        Block b = e.getBlock();
        Decision d = decide(p, b);
        if (d == Decision.ALLOW) return;
        e.setCancelled(true);

        if (plugin.apprentices().isApprentice(p)) {
            long now = System.currentTimeMillis();
            if (plugin.warnings().record(p.getUniqueId(), b, now)) notifyAdmins(p, b, now);
        }
        notify(p, d);
    }

    private void notify(Player p, Decision d) {
        boolean apprentice = plugin.apprentices().isApprentice(p);
        TagResolver remaining = Placeholder.unparsed("remaining", plugin.apprentices().remainingText(p));
        Messenger m = plugin.messenger();
        switch (d) {
            case SPAWN -> m.denied(p, "spawn-change", apprentice, apprentice, remaining);
            case BUILDING -> m.denied(p, "apprentice-building", false, true, remaining);
            case RESOURCE -> m.denied(p, "apprentice-resource", true, true, remaining);
            default -> {
            }
        }
    }

    private void notifyAdmins(Player p, Block b, long now) {
        if (!plugin.settings().notifyAdmins) return;
        if (!plugin.warnings().tryMarkNotified(p.getUniqueId(), now)) return;
        Component msg = plugin.messenger().render("admin-notify",
                Placeholder.unparsed("player", p.getName()),
                Placeholder.unparsed("world", b.getWorld().getName()),
                Placeholder.unparsed("x", String.valueOf(b.getX())),
                Placeholder.unparsed("y", String.valueOf(b.getY())),
                Placeholder.unparsed("z", String.valueOf(b.getZ())));
        for (Player admin : Bukkit.getOnlinePlayers()) {
            if (admin.hasPermission(Perms.NOTIFY)) plugin.messenger().chat(admin, msg);
        }
    }
}
