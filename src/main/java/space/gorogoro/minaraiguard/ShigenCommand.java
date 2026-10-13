package space.gorogoro.minaraiguard;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.function.Function;

/**
 * /shigen・/shigennether 資源ゲート・資源ネザーゲート前へ移動する。
 * 非同期テレポートを使うので、移動先のチャンクが未読み込みでもサーバーは止まらない。
 */
final class ShigenCommand implements CommandExecutor {

    private final MinaraiGuard plugin;
    private final Function<GuardSettings, GuardSettings.Gate> gate;
    private final String permission;
    private final String messagePrefix; // メッセージキーの接頭辞(shigen / shigennether)

    ShigenCommand(MinaraiGuard plugin, Function<GuardSettings, GuardSettings.Gate> gate,
                  String permission, String messagePrefix) {
        this.plugin = plugin;
        this.gate = gate;
        this.permission = permission;
        this.messagePrefix = messagePrefix;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Messenger m = plugin.messenger();
        if (!(sender instanceof Player p)) {
            m.chat(sender, m.render("player-only"));
            return true;
        }
        // 全員が使える前提。権限管理プラグインで明示的に false にされた場合だけ拒否する
        if (p.isPermissionSet(permission) && !p.hasPermission(permission)) {
            m.chat(p, m.render("no-permission"));
            return true;
        }
        GuardSettings.Gate g = gate.apply(plugin.settings());
        if (g == null) {
            plugin.getLogger().warning("/" + label + " の移動先が設定されていません(/minarai setgate nether で設定してください)");
            m.chat(p, m.render(messagePrefix + "-failed"));
            return true;
        }
        World w = Bukkit.getWorld(g.world());
        if (w == null) {
            plugin.getLogger().warning("/" + label + " の移動先のワールド " + g.world() + " が見つかりません");
            m.chat(p, m.render(messagePrefix + "-failed"));
            return true;
        }
        if (p.isInsideVehicle()) p.leaveVehicle();

        if (g.y() != null) {
            teleport(p, new Location(w, g.x(), g.y(), g.z(), g.yaw(), g.pitch()));
            return true;
        }
        // 高さが未設定なら、チャンクを非同期で読み込んでから地表の高さを調べる
        int bx = (int) Math.floor(g.x());
        int bz = (int) Math.floor(g.z());
        w.getChunkAtAsync(bx >> 4, bz >> 4).thenAccept(chunk -> {
            if (!p.isOnline()) return;
            double y = w.getHighestBlockYAt(bx, bz) + 1;
            teleport(p, new Location(w, g.x(), y, g.z(), g.yaw(), g.pitch()));
        });
        return true;
    }

    private void teleport(Player p, Location loc) {
        Messenger m = plugin.messenger();
        p.teleportAsync(loc, PlayerTeleportEvent.TeleportCause.COMMAND).thenAccept(ok -> {
            if (!p.isOnline()) return;
            if (ok) {
                m.chat(p, m.render(messagePrefix + "-teleported"));
                Messenger.line(p, m.resetNotice());
            } else {
                m.chat(p, m.render(messagePrefix + "-failed"));
            }
        });
    }
}
