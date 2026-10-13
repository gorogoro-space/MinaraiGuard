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

/** /minarai [reload|setgate [nether]] */
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
            if (args.length > 1 && args[1].equalsIgnoreCase("nether")) {
                setGate(sender, p.getLocation(), "resource-nether-gate", "setgate-nether-success");
            } else {
                setGate(sender, p.getLocation(), "resource-gate", "setgate-success");
            }
            return true;
        }
        if (!(sender instanceof Player p)) {
            m.chat(sender, m.render("player-only"));
            return true;
        }
        m.status(p);
        return true;
    }

    /** 現在地と向きを資源ゲート(path)の移動先として config.yml に保存する */
    private void setGate(CommandSender sender, Location l, String path, String successKey) {
        Messenger m = plugin.messenger();
        FileConfiguration c = plugin.getConfig();
        c.set(path + ".world", l.getWorld().getName());
        c.set(path + ".x", round(l.getX(), 100));
        c.set(path + ".y", round(l.getY(), 100));
        c.set(path + ".z", round(l.getZ(), 100));
        c.set(path + ".yaw", round(l.getYaw(), 10));
        c.set(path + ".pitch", round(l.getPitch(), 10));
        plugin.saveConfig();
        String error = plugin.reloadSettings();
        if (error == null) m.chat(sender, m.render(successKey));
        else m.chat(sender, m.render("reload-failed", Placeholder.unparsed("error", error)));
    }

    private static double round(double v, int scale) {
        return Math.round(v * scale) / (double) scale;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission(Perms.ADMIN)) return List.of();
        if (args.length == 1) {
            String input = args[0].toLowerCase();
            return Stream.of("reload", "setgate").filter(c -> c.startsWith(input)).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("setgate")) {
            String input = args[1].toLowerCase();
            return Stream.of("nether").filter(c -> c.startsWith(input)).toList();
        }
        return List.of();
    }
}
