package space.gorogoro.minaraiguard;

import org.bukkit.Bukkit;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 見習いの判定。イベントのたびに統計を調べないよう、
 * ログイン時と1分ごとの判定結果をセットに保持しておく。
 * 見習いになった/ログインした時点でその人の設置記録を読み込み、
 * 昇格したら記録を削除する。
 */
final class ApprenticeService {

    private final MinaraiGuard plugin;
    private final Set<UUID> apprentices = new HashSet<>();

    ApprenticeService(MinaraiGuard plugin) {
        this.plugin = plugin;
    }

    boolean isApprentice(Player p) {
        return apprentices.contains(p.getUniqueId());
    }

    /** ログイン時・設定の再読み込み時に呼ぶ */
    void refresh(Player p) {
        UUID id = p.getUniqueId();
        boolean now = qualifies(p);
        boolean was = apprentices.contains(id);
        if (now && !was) {
            apprentices.add(id);
            plugin.placements().loadOwner(id);
        } else if (!now) {
            // 見習いではない人の記録は不要なので削除(残っていなければ何もしない)
            apprentices.remove(id);
            plugin.placements().deleteOwner(id);
        }
    }

    void refreshAll() {
        for (Player p : Bukkit.getOnlinePlayers()) refresh(p);
    }

    /** ログアウト時。記録は残したままメモリから外す */
    void quit(UUID id) {
        if (apprentices.remove(id)) plugin.placements().unloadOwner(id);
    }

    /** 1分ごとに実行。オンラインの見習いだけを調べる */
    void tick() {
        if (apprentices.isEmpty()) return;
        for (UUID id : List.copyOf(apprentices)) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) {
                quit(id);
                continue;
            }
            if (qualifies(p)) continue;
            apprentices.remove(id);
            plugin.placements().deleteOwner(id);
            if (!p.hasPermission(Perms.EXEMPT)) plugin.messenger().promoted(p);
        }
    }

    String remainingText(Player p) {
        long ticks = plugin.settings().apprenticeTicks - played(p);
        if (ticks <= 0) return "まもなく";
        long minutes = (ticks + 1199) / 1200; // 切り上げ
        if (minutes < 60) return minutes + "分";
        long h = minutes / 60;
        long m = minutes % 60;
        return m == 0 ? h + "時間" : h + "時間" + m + "分";
    }

    private boolean qualifies(Player p) {
        return !p.hasPermission(Perms.EXEMPT) && played(p) < plugin.settings().apprenticeTicks;
    }

    private static long played(Player p) {
        return p.getStatistic(Statistic.PLAY_ONE_MINUTE); // 単位は tick
    }
}
