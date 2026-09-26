package space.gorogoro.minaraiguard;

import org.bukkit.block.Block;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 警戒モードの判定。直近の一定時間に拒否された回数を重み付きで合計する。
 * 別々のブロックへの試行は重く、同じブロックの叩き直しは軽く数える。
 */
final class WarningTracker {

    private record Hit(long time, double weight) {
    }

    private static final class State {
        final ArrayDeque<Hit> hits = new ArrayDeque<>();
        long lastBlock = Long.MIN_VALUE;
        boolean warning;
        long lastNotify = Long.MIN_VALUE;
    }

    private final MinaraiGuard plugin;
    private final Map<UUID, State> states = new HashMap<>();

    WarningTracker(MinaraiGuard plugin) {
        this.plugin = plugin;
    }

    /** 拒否を記録し、今回新たに警戒モードに入ったら true */
    boolean record(UUID id, Block b, long now) {
        GuardSettings s = plugin.settings();
        State st = states.computeIfAbsent(id, k -> new State());
        long key = PlacementStore.key(b.getX(), b.getY(), b.getZ());
        st.hits.addLast(new Hit(now, key == st.lastBlock ? s.sameWeight : s.distinctWeight));
        st.lastBlock = key;
        boolean was = st.warning;
        update(st, now, s);
        return !was && st.warning;
    }

    boolean isWarning(UUID id, long now) {
        State st = states.get(id);
        if (st == null) return false;
        update(st, now, plugin.settings());
        return st.warning;
    }

    /** 管理者通知の間隔を守れるなら記録して true */
    boolean tryMarkNotified(UUID id, long now) {
        State st = states.get(id);
        if (st == null) return false;
        if (st.lastNotify != Long.MIN_VALUE && now - st.lastNotify < plugin.settings().notifyCooldownMs) return false;
        st.lastNotify = now;
        return true;
    }

    void clear(UUID id) {
        states.remove(id);
    }

    private static void update(State st, long now, GuardSettings s) {
        long cutoff = now - s.warningWindowMs;
        while (!st.hits.isEmpty() && st.hits.peekFirst().time() < cutoff) st.hits.pollFirst();
        double score = 0;
        for (Hit h : st.hits) score += h.weight();
        if (!st.warning && score >= s.enterScore) st.warning = true;
        else if (st.warning && score < s.exitScore) st.warning = false;
    }

}
