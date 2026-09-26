package space.gorogoro.minaraiguard;

import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.plugin.IllegalPluginAccessException;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 見習いが置いたブロックの記録(plugins/MinaraiGuard/placements.db)。
 *
 * TPS に影響させないための方針:
 * ・データベースの読み書きはすべて専用スレッド(MinaraiGuard-DB)で行う
 * ・メインスレッドはメモリ上のデータだけを見る
 * ・「自分が置いたブロックか」を調べるのは見習い本人の行動時だけなので、
 *   見習いのログイン時にその人の記録だけを読み込んでおく
 * ・書き込みはキューに溜め、1秒ごとに1回のトランザクションでまとめて反映する
 * ・昇格した人の記録は削除し、ファイルが大きくなり続けないようにする
 */
final class PlacementStore {

    // ---- 書き込みキューの操作 ----
    private sealed interface Op permits Upsert, Delete, DeleteOwner {
    }

    private record Upsert(String world, int x, int y, int z, UUID owner) implements Op {
    }

    private record Delete(String world, int x, int y, int z) implements Op {
    }

    private record DeleteOwner(UUID owner) implements Op {
    }

    /** オンラインの見習い1人分の記録(メインスレッド専用) */
    private static final class OwnerData {
        final Map<String, Set<Long>> blocks = new HashMap<>();
        final Map<String, Set<Long>> removedWhileLoading = new HashMap<>();
        boolean loaded;

        boolean contains(String world, long k) {
            Set<Long> s = blocks.get(world);
            return s != null && s.contains(k);
        }

        void add(String world, long k) {
            blocks.computeIfAbsent(world, w -> new HashSet<>()).add(k);
            if (!loaded) {
                Set<Long> removed = removedWhileLoading.get(world);
                if (removed != null) removed.remove(k);
            }
        }

        void remove(String world, long k) {
            Set<Long> s = blocks.get(world);
            if (s != null) s.remove(k);
            if (!loaded) removedWhileLoading.computeIfAbsent(world, w -> new HashSet<>()).add(k);
        }
    }

    private final MinaraiGuard plugin;
    private final Logger log;
    private final File file;
    private final ScheduledExecutorService executor;
    private final ConcurrentLinkedQueue<Op> queue = new ConcurrentLinkedQueue<>();
    private final Map<UUID, OwnerData> owners = new HashMap<>();

    // 以下は DB スレッドからのみ使う
    private Connection conn;
    private PreparedStatement upsertStmt;
    private PreparedStatement deleteStmt;
    private PreparedStatement deleteOwnerStmt;
    private PreparedStatement selectOwnerBlocksStmt;
    private PreparedStatement selectOwnerAtStmt;

    PlacementStore(MinaraiGuard plugin) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
        this.file = new File(plugin.getDataFolder(), "placements.db");
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "MinaraiGuard-DB");
            t.setDaemon(true);
            return t;
        });
    }

    /** 起動時に一度だけ呼ぶ。失敗したら例外 */
    void open() throws Exception {
        executor.submit(() -> {
            openConnection();
            return null;
        }).get(10, TimeUnit.SECONDS);
        executor.scheduleWithFixedDelay(this::flushSafely, 1, 1, TimeUnit.SECONDS);
    }

    /** 停止時に呼ぶ。書き込み待ちをすべて保存してから閉じる */
    void close() {
        try {
            executor.execute(() -> {
                flush();
                closeConnection();
            });
        } catch (Exception ignored) {
            // すでに停止済み
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                log.warning("データベースの保存が時間内に終わりませんでした");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        owners.clear();
    }

    // ================================================================
    //  メインスレッドから呼ぶ API
    // ================================================================

    boolean isOwner(UUID id, Block b) {
        OwnerData d = owners.get(id);
        return d != null && d.contains(b.getWorld().getName(), key(b.getX(), b.getY(), b.getZ()));
    }

    /** 見習いになったとき・見習いがログインしたときに、その人の記録を読み込む */
    void loadOwner(UUID id) {
        OwnerData data = new OwnerData();
        owners.put(id, data);
        executor.execute(() -> {
            Map<String, Set<Long>> loaded = new HashMap<>();
            try {
                flush(); // 書き込み待ちを先に反映して、最新の状態を読む
                selectOwnerBlocksStmt.setString(1, id.toString());
                try (ResultSet rs = selectOwnerBlocksStmt.executeQuery()) {
                    while (rs.next()) {
                        loaded.computeIfAbsent(rs.getString(1), w -> new HashSet<>())
                                .add(key(rs.getInt(2), rs.getInt(3), rs.getInt(4)));
                    }
                }
            } catch (SQLException e) {
                log.log(Level.WARNING, "設置記録の読み込みに失敗しました: " + id, e);
            }
            runSync(() -> {
                if (owners.get(id) != data) return; // 読み込み中にログアウトした
                for (Map.Entry<String, Set<Long>> e : loaded.entrySet()) {
                    Set<Long> removed = data.removedWhileLoading.getOrDefault(e.getKey(), Set.of());
                    Set<Long> target = data.blocks.computeIfAbsent(e.getKey(), w -> new HashSet<>());
                    for (Long k : e.getValue()) {
                        if (!removed.contains(k)) target.add(k);
                    }
                }
                data.loaded = true;
                data.removedWhileLoading.clear();
            });
        });
    }

    /** ログアウト時。メモリから外すだけで記録は残す */
    void unloadOwner(UUID id) {
        owners.remove(id);
    }

    /** 昇格時など。その人の記録をすべて削除する */
    void deleteOwner(UUID id) {
        owners.remove(id);
        queue.add(new DeleteOwner(id));
    }

    /** 見習いがブロックを置いた */
    void recordPlace(UUID owner, World w, int x, int y, int z) {
        String world = w.getName();
        long k = key(x, y, z);
        clearMemory(world, k); // 別の見習いの古い記録があれば外す
        OwnerData d = owners.get(owner);
        if (d != null) d.add(world, k);
        queue.add(new Upsert(world, x, y, z, owner));
    }

    /** 見習い以外が置いた・誰かが壊した位置の記録を消す */
    void clearAt(World w, int x, int y, int z) {
        String world = w.getName();
        clearMemory(world, key(x, y, z));
        queue.add(new Delete(world, x, y, z));
    }

    /** 苗木が育った。苗木を置いた見習いがいれば、木全体をその人のものとして記録する */
    void recordGrowth(World w, int sx, int sy, int sz, List<int[]> positions) {
        String world = w.getName();
        long saplingKey = key(sx, sy, sz);

        // 植えた見習いがオンラインならメモリだけで分かる
        UUID onlineOwner = null;
        for (Map.Entry<UUID, OwnerData> e : owners.entrySet()) {
            if (e.getValue().contains(world, saplingKey)) {
                onlineOwner = e.getKey();
                break;
            }
        }
        if (onlineOwner != null) {
            for (int[] p : positions) recordPlace(onlineOwner, w, p[0], p[1], p[2]);
            return;
        }

        // オフラインの見習いが植えた可能性があるので、DB スレッドで確認する
        executor.execute(() -> {
            UUID owner = null;
            try {
                flush();
                selectOwnerAtStmt.setString(1, world);
                selectOwnerAtStmt.setInt(2, sx);
                selectOwnerAtStmt.setInt(3, sy);
                selectOwnerAtStmt.setInt(4, sz);
                try (ResultSet rs = selectOwnerAtStmt.executeQuery()) {
                    if (rs.next()) owner = UUID.fromString(rs.getString(1));
                }
            } catch (SQLException | IllegalArgumentException e) {
                log.log(Level.WARNING, "苗木の設置者の確認に失敗しました", e);
            }
            if (owner == null) return;
            for (int[] p : positions) queue.add(new Upsert(world, p[0], p[1], p[2], owner));
            flush();
            UUID found = owner;
            runSync(() -> {
                OwnerData d = owners.get(found);
                if (d != null) {
                    for (int[] p : positions) d.add(world, key(p[0], p[1], p[2]));
                }
            });
        });
    }

    // ================================================================
    //  内部処理
    // ================================================================

    private void clearMemory(String world, long k) {
        for (OwnerData d : owners.values()) d.remove(world, k);
    }

    private void runSync(Runnable r) {
        if (!plugin.isEnabled()) return;
        try {
            plugin.getServer().getScheduler().runTask(plugin, r);
        } catch (IllegalPluginAccessException ignored) {
            // 停止処理中
        }
    }

    private void openConnection() throws Exception {
        File folder = file.getParentFile();
        if (folder != null && !folder.exists() && !folder.mkdirs()) {
            throw new IllegalStateException("フォルダを作成できません: " + folder);
        }
        Class.forName("org.sqlite.JDBC");
        conn = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA synchronous=NORMAL");
            st.execute("CREATE TABLE IF NOT EXISTS placements ("
                    + "world TEXT NOT NULL, x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL, "
                    + "owner TEXT NOT NULL, PRIMARY KEY (world, x, y, z)) WITHOUT ROWID");
            st.execute("CREATE INDEX IF NOT EXISTS idx_placements_owner ON placements (owner)");
        }
        upsertStmt = conn.prepareStatement(
                "INSERT OR REPLACE INTO placements (world, x, y, z, owner) VALUES (?, ?, ?, ?, ?)");
        deleteStmt = conn.prepareStatement(
                "DELETE FROM placements WHERE world = ? AND x = ? AND y = ? AND z = ?");
        deleteOwnerStmt = conn.prepareStatement("DELETE FROM placements WHERE owner = ?");
        selectOwnerBlocksStmt = conn.prepareStatement("SELECT world, x, y, z FROM placements WHERE owner = ?");
        selectOwnerAtStmt = conn.prepareStatement(
                "SELECT owner FROM placements WHERE world = ? AND x = ? AND y = ? AND z = ?");
    }

    private void closeConnection() {
        if (conn == null) return;
        try {
            conn.close();
        } catch (SQLException e) {
            log.log(Level.WARNING, "データベースを閉じる際にエラーが発生しました", e);
        }
        conn = null;
    }

    private void flushSafely() {
        try {
            flush();
        } catch (Throwable t) {
            log.log(Level.WARNING, "データベースへの書き込み中にエラーが発生しました", t);
        }
    }

    /** DB スレッド専用。キューに溜まった操作を1回のトランザクションで反映する */
    private void flush() {
        if (conn == null || queue.isEmpty()) return;
        try {
            conn.setAutoCommit(false);
            Op op;
            while ((op = queue.poll()) != null) {
                switch (op) {
                    case Upsert u -> {
                        upsertStmt.setString(1, u.world());
                        upsertStmt.setInt(2, u.x());
                        upsertStmt.setInt(3, u.y());
                        upsertStmt.setInt(4, u.z());
                        upsertStmt.setString(5, u.owner().toString());
                        upsertStmt.executeUpdate();
                    }
                    case Delete d -> {
                        deleteStmt.setString(1, d.world());
                        deleteStmt.setInt(2, d.x());
                        deleteStmt.setInt(3, d.y());
                        deleteStmt.setInt(4, d.z());
                        deleteStmt.executeUpdate();
                    }
                    case DeleteOwner o -> {
                        deleteOwnerStmt.setString(1, o.owner().toString());
                        deleteOwnerStmt.executeUpdate();
                    }
                }
            }
            conn.commit();
        } catch (SQLException e) {
            log.log(Level.WARNING, "データベースへの書き込みに失敗しました", e);
            try {
                conn.rollback();
            } catch (SQLException ignored) {
                // 何もしない
            }
        } finally {
            try {
                conn.setAutoCommit(true);
            } catch (SQLException ignored) {
                // 何もしない
            }
        }
    }

    /** 座標を1つの long にまとめる(x, z は各26ビット、y は12ビット) */
    static long key(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | ((long) y & 0xFFFL);
    }
}
