package space.gorogoro.minaraiguard;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * スポーン保護範囲。高さは問わず X・Z のみで判定し、境界を含む。
 * 頻繁に呼ばれるため、整数比較を先に行いワールド名の比較は最後にする。
 */
record Zone(String world, int minX, int minZ, int maxX, int maxZ) {

    static Zone of(String world, int x1, int z1, int x2, int z2) {
        return new Zone(world, Math.min(x1, x2), Math.min(z1, z2), Math.max(x1, x2), Math.max(z1, z2));
    }

    boolean contains(String worldName, int x, int z) {
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ && world.equals(worldName);
    }

    boolean contains(Block b) {
        int x = b.getX();
        int z = b.getZ();
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ && world.equals(b.getWorld().getName());
    }

    boolean contains(Location l) {
        World w = l.getWorld();
        return w != null && contains(w.getName(), l.getBlockX(), l.getBlockZ());
    }

    boolean isNear(String worldName, int x, int z, int margin) {
        return x >= minX - margin && x <= maxX + margin
                && z >= minZ - margin && z <= maxZ + margin
                && world.equals(worldName);
    }

    boolean isNear(Block b, int margin) {
        return isNear(b.getWorld().getName(), b.getX(), b.getZ(), margin);
    }

    boolean isNear(Location l, int margin) {
        World w = l.getWorld();
        return w != null && isNear(w.getName(), l.getBlockX(), l.getBlockZ(), margin);
    }
}
