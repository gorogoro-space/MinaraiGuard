package space.gorogoro.minaraiguard;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.EntityType;

import java.time.DateTimeException;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * config.yml を読み込んだ結果。イベントのたびに設定ファイルを読まないよう、
 * 起動時・リロード時に一度だけ解析してここに保持する。
 */
final class GuardSettings {

    private static final List<String> MESSAGE_KEYS = List.of(
            "prefix",
            "apprentice-building", "apprentice-resource",
            "spawn-change", "spawn-interact", "spawn-tool", "spawn-cart",
            "warning",
            "shigen-guide", "reset-notice", "reset-notice-soon", "minarai-guide",
            "shigen-teleported", "shigen-failed", "setgate-success",
            "welcome", "promoted-title", "promoted-subtitle",
            "status-apprentice", "status-member", "admin-notify",
            "reload-success", "reload-failed", "no-permission", "player-only");

    private static final List<EntityType> MINECART_TYPES = List.of(
            EntityType.MINECART, EntityType.CHEST_MINECART, EntityType.FURNACE_MINECART,
            EntityType.HOPPER_MINECART, EntityType.TNT_MINECART, EntityType.SPAWNER_MINECART,
            EntityType.COMMAND_BLOCK_MINECART);

    // スポーン・資源ゲート
    Zone spawn;
    String gateWorld;
    double gateX;
    Double gateY; // null なら地表に移動
    double gateZ;
    float gateYaw;
    float gatePitch;
    int resetDay; // 0 なら案内しない
    int resetWarnDays;
    LocalTime resetTime;
    ZoneId resetZone;

    // 見習い
    int apprenticeHours;
    long apprenticeTicks;
    Set<String> apprenticeWorlds;
    EnumSet<Material> terrainBlocks;
    EnumSet<Material> resourceBlocks;

    // スポーン内の操作
    EnumSet<Material> spawnDenyBlocks;
    Set<EntityType> allowedCarts;

    // 道具ごとの「変化させられるブロック」
    EnumSet<Material> axeTargets;
    EnumSet<Material> shovelTargets;
    EnumSet<Material> hoeTargets;
    EnumSet<Material> shearsTargets;
    EnumSet<Material> brushTargets;
    EnumSet<Material> copperBlocks;

    // 警戒モード
    long warningWindowMs;
    double distinctWeight;
    double sameWeight;
    double enterScore;
    double exitScore;
    long warningChatIntervalMs;
    boolean notifyAdmins;
    long notifyCooldownMs;

    // メッセージ
    Map<String, String> messages;
    Map<EntityType, String> cartNames;

    private GuardSettings() {
    }

    /** @throws IllegalStateException 必須設定が無い・不正な場合 */
    static GuardSettings load(FileConfiguration c, Logger log) {
        GuardSettings s = new GuardSettings();

        s.spawn = Zone.of(
                requireString(c, "spawn-protection.world"),
                requireInt(c, "spawn-protection.min-x"),
                requireInt(c, "spawn-protection.min-z"),
                requireInt(c, "spawn-protection.max-x"),
                requireInt(c, "spawn-protection.max-z"));

        s.gateWorld = requireString(c, "resource-gate.world");
        s.gateX = requireCoordinate(c, "resource-gate.x");
        s.gateZ = requireCoordinate(c, "resource-gate.z");
        s.gateY = c.contains("resource-gate.y", true) && isNumber(c, "resource-gate.y")
                ? c.getDouble("resource-gate.y") : null;
        s.gateYaw = (float) c.getDouble("resource-gate.yaw", 0);
        s.gatePitch = (float) c.getDouble("resource-gate.pitch", 0);
        s.resetDay = Math.max(0, Math.min(31, c.getInt("resource-reset.day-of-month", 1)));
        s.resetWarnDays = Math.max(0, c.getInt("resource-reset.warn-days", 3));
        s.resetTime = parseTime(c.getString("resource-reset.time"), log);
        s.resetZone = parseZone(c.getString("resource-reset.time-zone"), log);

        s.apprenticeHours = Math.max(0, c.getInt("apprentice.hours", 5));
        s.apprenticeTicks = s.apprenticeHours * 72_000L; // 1時間 = 72000tick
        s.apprenticeWorlds = new HashSet<>(c.getStringList("apprentice.worlds"));
        s.terrainBlocks = materials(c.getStringList("apprentice.terrain-blocks"), "apprentice.terrain-blocks", log);
        s.resourceBlocks = materials(c.getStringList("apprentice.resource-blocks"), "apprentice.resource-blocks", log);

        s.spawnDenyBlocks = materials(c.getStringList("spawn-interaction.deny-blocks"), "spawn-interaction.deny-blocks", log);
        s.allowedCarts = entityTypes(c.getStringList("spawn-interaction.allowed-minecarts"), log);

        s.copperBlocks = copperBlocks();
        s.axeTargets = materials(List.of("#logs", "BAMBOO_BLOCK"), "(axe)", log);
        s.axeTargets.addAll(s.copperBlocks);
        s.shovelTargets = materials(List.of("GRASS_BLOCK", "DIRT", "COARSE_DIRT", "PODZOL", "MYCELIUM",
                "ROOTED_DIRT", "#campfires"), "(shovel)", log);
        s.hoeTargets = materials(List.of("GRASS_BLOCK", "DIRT", "DIRT_PATH", "COARSE_DIRT", "ROOTED_DIRT"), "(hoe)", log);
        s.shearsTargets = materials(List.of("PUMPKIN"), "(shears)", log);
        s.brushTargets = materials(List.of("SUSPICIOUS_SAND", "SUSPICIOUS_GRAVEL"), "(brush)", log);

        s.warningWindowMs = Math.round(c.getDouble("warning.window-seconds", 30) * 1000);
        s.distinctWeight = c.getDouble("warning.distinct-block-weight", 1.0);
        s.sameWeight = c.getDouble("warning.same-block-weight", 0.25);
        s.enterScore = c.getDouble("warning.enter-score", 8.0);
        s.exitScore = Math.min(s.enterScore, c.getDouble("warning.exit-score", 4.0));
        s.warningChatIntervalMs = Math.round(c.getDouble("warning.chat-interval-seconds", 15) * 1000);
        s.notifyAdmins = c.getBoolean("warning.notify-admins", false);
        s.notifyCooldownMs = Math.round(c.getDouble("warning.notify-cooldown-seconds", 300) * 1000);

        s.messages = new HashMap<>();
        for (String key : MESSAGE_KEYS) {
            // 既定値を引数で渡すと jar 内の既定値が使われなくなるため、引数なしで取得する
            String v = c.getString("messages." + key);
            s.messages.put(key, v == null ? "" : v);
        }
        s.cartNames = new EnumMap<>(EntityType.class);
        for (EntityType type : MINECART_TYPES) {
            String v = c.getString("messages.cart-names." + type.name());
            s.cartNames.put(type, v == null ? type.name() : v);
        }
        return s;
    }

    /** 道具 tool で target を右クリックしたときにブロックが変化するか */
    boolean toolChangesBlock(Material tool, Material target) {
        String n = tool.name();
        if (n.endsWith("_AXE")) return axeTargets.contains(target); // PICKAXE は "_AXE" で終わらない
        if (n.endsWith("_SHOVEL")) return shovelTargets.contains(target);
        if (n.endsWith("_HOE")) return hoeTargets.contains(target);
        if (tool == Material.SHEARS) return shearsTargets.contains(target);
        if (tool == Material.HONEYCOMB) return copperBlocks.contains(target);
        if (tool == Material.BRUSH) return brushTargets.contains(target);
        return false;
    }

    String cartName(EntityType type) {
        return cartNames.getOrDefault(type, type.name());
    }

    // ---- 解析ヘルパー ----

    private static String requireString(FileConfiguration c, String path) {
        if (!c.contains(path, true) || !c.isString(path)) {
            throw new IllegalStateException(path + " が設定されていません");
        }
        String v = c.getString(path);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException(path + " が空です");
        }
        return v;
    }

    private static int requireInt(FileConfiguration c, String path) {
        if (!c.contains(path, true) || !c.isInt(path)) {
            throw new IllegalStateException(path + " は整数で設定してください");
        }
        return c.getInt(path);
    }

    private static LocalTime parseTime(String raw, Logger log) {
        if (raw == null || raw.isBlank()) return LocalTime.MIDNIGHT;
        try {
            return LocalTime.parse(raw.trim(), DateTimeFormatter.ofPattern("H:mm"));
        } catch (DateTimeParseException ex) {
            log.warning("resource-reset.time: " + raw + " を時刻として読めないため 0:00 とします");
            return LocalTime.MIDNIGHT;
        }
    }

    private static ZoneId parseZone(String raw, Logger log) {
        if (raw == null || raw.isBlank()) return ZoneId.systemDefault();
        try {
            return ZoneId.of(raw.trim());
        } catch (DateTimeException ex) {
            log.warning("resource-reset.time-zone: " + raw + " が見つからないため、サーバーの設定を使います");
            return ZoneId.systemDefault();
        }
    }

    /** 整数ならブロックの中心(+0.5)、小数ならその値 */
    private static double requireCoordinate(FileConfiguration c, String path) {
        if (!c.contains(path, true) || !isNumber(c, path)) {
            throw new IllegalStateException(path + " は数値で設定してください");
        }
        return c.isInt(path) ? c.getInt(path) + 0.5 : c.getDouble(path);
    }

    private static boolean isNumber(FileConfiguration c, String path) {
        return c.isInt(path) || c.isDouble(path) || c.isLong(path);
    }

    private static EnumSet<Material> materials(List<String> names, String path, Logger log) {
        EnumSet<Material> set = EnumSet.noneOf(Material.class);
        for (String raw : names) {
            String name = raw.trim();
            if (name.startsWith("#")) {
                NamespacedKey key = NamespacedKey.fromString(name.substring(1).toLowerCase(Locale.ROOT));
                Tag<Material> tag = key == null ? null : Bukkit.getTag(Tag.REGISTRY_BLOCKS, key, Material.class);
                if (tag == null) {
                    log.warning(path + ": タグ " + name + " が見つからないため無視します");
                    continue;
                }
                set.addAll(tag.getValues());
            } else {
                Material m = Material.matchMaterial(name);
                if (m == null || !m.isBlock()) {
                    log.warning(path + ": ブロック " + name + " が見つからないため無視します");
                    continue;
                }
                set.add(m);
            }
        }
        return set;
    }

    private static Set<EntityType> entityTypes(List<String> names, Logger log) {
        Set<EntityType> set = EnumSet.noneOf(EntityType.class);
        for (String raw : names) {
            try {
                set.add(EntityType.valueOf(raw.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ex) {
                log.warning("spawn-interaction.allowed-minecarts: " + raw + " が見つからないため無視します");
            }
        }
        return set;
    }

    /** 斧で削れる・ハニカムでロウ引きできる銅系ブロック */
    private static EnumSet<Material> copperBlocks() {
        EnumSet<Material> set = EnumSet.noneOf(Material.class);
        for (Material m : Material.values()) {
            String n = m.name();
            if (n.startsWith("LEGACY_") || !n.contains("COPPER") || n.contains("ORE") || n.equals("RAW_COPPER_BLOCK")) {
                continue;
            }
            if (m.isBlock()) set.add(m);
        }
        return set;
    }
}
