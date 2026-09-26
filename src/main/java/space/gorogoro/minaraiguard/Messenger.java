package space.gorogoro.minaraiguard;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * メッセージ表示。
 * ・アクションバー: 制限にかかるたびに毎回
 * ・チャット案内: ログイン中に初めて制限にかかったときの1回だけ
 * ・警戒モード中: チャット案内を一定間隔で繰り返す
 */
final class Messenger {

    private final MinaraiGuard plugin;
    private final MiniMessage mm = MiniMessage.miniMessage();
    private final Set<UUID> guided = new HashSet<>();
    private final Map<UUID, Long> lastWarningChat = new HashMap<>();

    Messenger(MinaraiGuard plugin) {
        this.plugin = plugin;
    }

    /** 空文字のメッセージは null を返す(=表示しない) */
    Component render(String key, TagResolver... resolvers) {
        String raw = plugin.settings().messages.getOrDefault(key, "");
        if (raw.isEmpty()) return null;
        return mm.deserialize(raw, resolvers);
    }

    /** 接頭辞つきでチャットに送る */
    void chat(CommandSender to, Component c) {
        if (c == null) return;
        Component prefix = render("prefix");
        to.sendMessage(prefix == null ? c : prefix.append(c));
    }

    /**
     * 制限にかかったときの通知。
     * @param gate  チャット案内に /shigen 案内を付けるか
     * @param guide チャット案内に /minarai 案内を付けるか
     */
    void denied(Player p, String key, boolean gate, boolean guide, TagResolver... resolvers) {
        Component body = render(key, resolvers);
        if (body != null) p.sendActionBar(body);
        if (!gate && !guide) return;

        UUID id = p.getUniqueId();
        long now = System.currentTimeMillis();

        if (plugin.apprentices().isApprentice(p) && plugin.warnings().isWarning(id, now)) {
            Long last = lastWarningChat.get(id);
            if (last != null && now - last < plugin.settings().warningChatIntervalMs) return;
            lastWarningChat.put(id, now);
            guided.add(id);
            chat(p, render("warning"));
            line(p, render("shigen-guide"));
            line(p, render("minarai-guide"));
            return;
        }

        if (!guided.add(id)) return; // このログイン中はすでに案内済み
        chat(p, body);
        if (gate) line(p, render("shigen-guide"));
        if (guide) line(p, render("minarai-guide"));
    }

    void welcome(Player p) {
        chat(p, render("welcome", Placeholder.unparsed("hours", String.valueOf(plugin.settings().apprenticeHours))));
        line(p, render("shigen-guide"));
        line(p, render("minarai-guide"));
    }

    void status(Player p) {
        if (plugin.apprentices().isApprentice(p)) {
            chat(p, render("status-apprentice", Placeholder.unparsed("remaining", plugin.apprentices().remainingText(p))));
        } else {
            chat(p, render("status-member"));
        }
        line(p, render("shigen-guide"));
    }

    void promoted(Player p) {
        Component title = render("promoted-title");
        Component sub = render("promoted-subtitle");
        p.showTitle(Title.title(
                title == null ? Component.empty() : title,
                sub == null ? Component.empty() : sub,
                Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(4), Duration.ofSeconds(1))));
        p.playSound(p, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
    }

    void clear(UUID id) {
        guided.remove(id);
        lastWarningChat.remove(id);
    }

    /** リセットまでの残り時間を添えた案内。残りが少ないときは目立つ色で表示する */
    Component resetNotice() {
        GuardSettings s = plugin.settings();
        int day = s.resetDay;
        if (day <= 0) return null;
        ZonedDateTime now = ZonedDateTime.now(s.resetZone);
        ZonedDateTime next = resetAt(now.toLocalDate(), s);
        if (!now.isBefore(next)) {
            next = resetAt(now.toLocalDate().plusMonths(1).withDayOfMonth(1), s);
        }
        Duration left = Duration.between(now, next);
        String remaining;
        if (left.toDays() >= 1) {
            remaining = "あと" + left.toDays() + "日";
        } else if (left.toHours() >= 1) {
            remaining = "あと" + left.toHours() + "時間" + left.toMinutesPart() + "分";
        } else {
            remaining = "あと" + Math.max(1, left.toMinutes()) + "分";
        }
        String key = left.compareTo(Duration.ofDays(s.resetWarnDays)) < 0 ? "reset-notice-soon" : "reset-notice";
        return render(key,
                Placeholder.unparsed("day", String.valueOf(day)),
                Placeholder.unparsed("time", s.resetTime.getHour() + ":" + String.format("%02d", s.resetTime.getMinute())),
                Placeholder.unparsed("remaining", remaining),
                // 旧バージョンの config.yml の文言との互換用
                Placeholder.unparsed("days", String.valueOf(left.toDays())),
                Placeholder.unparsed("next_reset", next.getMonthValue() + "月" + next.getDayOfMonth() + "日"));
    }

    /** date と同じ月のリセット日時(月末を超える日付は月末に丸める) */
    private static ZonedDateTime resetAt(LocalDate date, GuardSettings s) {
        LocalDate d = date.withDayOfMonth(Math.min(s.resetDay, date.lengthOfMonth()));
        return ZonedDateTime.of(d, s.resetTime, s.resetZone);
    }

    static void line(CommandSender to, Component c) {
        if (c != null) to.sendMessage(c);
    }
}
