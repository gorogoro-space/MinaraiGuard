package space.gorogoro.minaraiguard;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;

/** ログイン・ログアウト時の処理 */
final class SessionListener implements Listener {

    private static final long WELCOME_DELAY_TICKS = 40L;

    private final MinaraiGuard plugin;

    SessionListener(MinaraiGuard plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        plugin.apprentices().refresh(p);
        if (!p.hasPlayedBefore()) {
            // 参加メッセージに埋もれないよう少し遅らせる
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (p.isOnline()) plugin.messenger().welcome(p);
            }, WELCOME_DELAY_TICKS);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        plugin.apprentices().quit(id);
        plugin.messenger().clear(id);
        plugin.warnings().clear(id);
    }
}
