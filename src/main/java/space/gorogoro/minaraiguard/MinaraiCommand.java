package space.gorogoro.minaraiguard;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.stream.Stream;

/** /minarai [reload|setgate] */
final class MinaraiCommand implements TabExecutor {

    private final MinaraiGuard plugin;

    MinaraiCommand(MinaraiGuard plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Messenger m = plugin.messenger();
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission(Perms.ADMIN)) {
                m.chat(sender, m.render("no-permission"));
                return true;
            }
            String error = plugin.reloadSettings();
            if (error == null) m.chat(sender, m.render("reload-success"));
            else m.chat(sender, m.render("reload-failed", Placeholder.unparsed("error", error)));
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("setgate")) {
            if (!sender.hasPermission(Perms.ADMIN)) {
                m.chat(sender, m.render("no-permission"));
                return true;
            }
            if (!(sender instanceof Player p)) {
                m.chat(sender, m.render("player-only"));
                return true;
            }
            setGate(sender, p.getLocation());
            return true;
        }
        if (!(sender instanceof Player p)) {
            m.chat(sender, m.render("player-only"));
            return true;
        }
        m.status(p);
        return true;
    }

    /** 現在地と向きを資源ゲートの移動先として config.yml に保存する */
    private void setGate(CommandSender sender, Location l) {
        Messenger m = plugin.messenger();
        FileConfiguration c = plugin.getConfig();
        c.set("resource-gate.world", l.getWorld().getName());
        c.set("resource-gate.x", round(l.getX(), 100));
        c.set("resource-gate.y", round(l.getY(), 100));
        c.set("resource-gate.z", round(l.getZ(), 100));
        c.set("resource-gate.yaw", round(l.getYaw(), 10));
        c.set("resource-gate.pitch", round(l.getPitch(), 10));
        plugin.saveConfig();
        String error = plugin.reloadSettings();
        if (error == null) m.chat(sender, m.render("setgate-success"));
        else m.chat(sender, m.render("reload-failed", Placeholder.unparsed("error", error)));
    }

    private static double round(double v, int scale) {
        return Math.round(v * scale) / (double) scale;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1 || !sender.hasPermission(Perms.ADMIN)) return List.of();
        String input = args[0].toLowerCase();
        return Stream.of("reload", "setgate").filter(c -> c.startsWith(input)).toList();
    }
}
