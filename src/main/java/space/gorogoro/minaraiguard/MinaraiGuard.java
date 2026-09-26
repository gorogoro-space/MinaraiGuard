package space.gorogoro.minaraiguard;

import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

/**
 * MinaraiGuard
 * 新規プレイヤーの見習い期間とスポーン地点の保護で、他の人の建築と景観を守ります。
 */
public final class MinaraiGuard extends JavaPlugin {

    private static final long PROMOTION_CHECK_TICKS = 20L * 60; // 1分

    private GuardSettings settings;
    private ApprenticeService apprentices;
    private PlacementStore placements;
    private WarningTracker warnings;
    private Messenger messenger;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        try {
            settings = GuardSettings.load(getConfig(), getLogger());
        } catch (IllegalStateException ex) {
            getLogger().severe("config.yml の設定に誤りがあるため、MinaraiGuard を無効化します: " + ex.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        placements = new PlacementStore(this);
        try {
            placements.open();
        } catch (Exception ex) {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            getLogger().log(Level.SEVERE, "データベース(placements.db)を開けないため、MinaraiGuard を無効化します", cause);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        warnings = new WarningTracker(this);
        messenger = new Messenger(this);
        apprentices = new ApprenticeService(this);

        PluginManager pm = getServer().getPluginManager();
        pm.registerEvents(new SessionListener(this), this);
        pm.registerEvents(new BreakListener(this), this);
        pm.registerEvents(new SpawnListener(this), this);
        pm.registerEvents(new PlacementListener(this), this);

        PluginCommand cmd = getCommand("minarai");
        if (cmd != null) {
            MinaraiCommand executor = new MinaraiCommand(this);
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);
        }
        PluginCommand shigen = getCommand("shigen");
        if (shigen != null) shigen.setExecutor(new ShigenCommand(this));

        for (Player p : getServer().getOnlinePlayers()) apprentices.refresh(p);
        getServer().getScheduler().runTaskTimer(this, apprentices::tick, PROMOTION_CHECK_TICKS, PROMOTION_CHECK_TICKS);
    }

    @Override
    public void onDisable() {
        // 書き込み待ちの記録をすべて保存してからデータベースを閉じる
        if (placements != null) placements.close();
    }

    /** @return 失敗時はエラー内容、成功時は null(失敗時は以前の設定のまま動き続ける) */
    String reloadSettings() {
        reloadConfig();
        try {
            settings = GuardSettings.load(getConfig(), getLogger());
        } catch (IllegalStateException ex) {
            getLogger().severe("config.yml の再読み込みに失敗しました: " + ex.getMessage());
            return ex.getMessage();
        }
        apprentices.refreshAll();
        return null;
    }

    GuardSettings settings() {
        return settings;
    }

    ApprenticeService apprentices() {
        return apprentices;
    }

    PlacementStore placements() {
        return placements;
    }

    WarningTracker warnings() {
        return warnings;
    }

    Messenger messenger() {
        return messenger;
    }
}
