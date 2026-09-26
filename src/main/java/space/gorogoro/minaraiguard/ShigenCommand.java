package space.gorogoro.minaraiguard;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;

/**
 * /shigen 資源ゲート前へ移動する。
 * 非同期テレポートを使うので、移動先のチャンクが未読み込みでもサーバーは止まらない。
 */
final class ShigenCommand implements CommandExecutor {

    private final MinaraiGuard plugin;

    ShigenCommand(MinaraiGuard plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Messenger m = plugin.messenger();
        if (!(sender instanceof Player p)) {
            m.chat(sender, m.render("player-only"));
            return true;
        }
        // 全員が使える前提。権限管理プラグインで明示的に false にされた場合だけ拒否する
        if (p.isPermissionSet(Perms.SHIGEN) && !p.hasPermission(Perms.SHIGEN)) {
            m.chat(p, m.render("no-permission"));
            return true;
        }
        GuardSettings s = plugin.settings();
        World w = Bukkit.getWorld(s.gateWorld);
        if (w == null) {
            plugin.getLogger().warning("資源ゲートのワールド " + s.gateWorld + " が見つかりません");
            m.chat(p, m.render("shigen-failed"));
            return true;
        }
        if (p.isInsideVehicle()) p.leaveVehicle();

        if (s.gateY != null) {
            teleport(p, new Location(w, s.gateX, s.gateY, s.gateZ, s.gateYaw, s.gatePitch));
            return true;
        }
        // 高さが未設定なら、チャンクを非同期で読み込んでから地表の高さを調べる
        int bx = (int) Math.floor(s.gateX);
        int bz = (int) Math.floor(s.gateZ);
        w.getChunkAtAsync(bx >> 4, bz >> 4).thenAccept(chunk -> {
            if (!p.isOnline()) return;
            double y = w.getHighestBlockYAt(bx, bz) + 1;
            teleport(p, new Location(w, s.gateX, y, s.gateZ, s.gateYaw, s.gatePitch));
        });
        return true;
    }

    private void teleport(Player p, Location loc) {
        Messenger m = plugin.messenger();
        p.teleportAsync(loc, PlayerTeleportEvent.TeleportCause.COMMAND).thenAccept(ok -> {
            if (!p.isOnline()) return;
            if (ok) {
                m.chat(p, m.render("shigen-teleported"));
                Messenger.line(p, m.resetNotice());
            } else {
                m.chat(p, m.render("shigen-failed"));
            }
        });
    }
}
