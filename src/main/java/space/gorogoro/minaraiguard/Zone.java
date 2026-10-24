package space.gorogoro.minaraiguard;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * スポーン保護範囲。X・Z と高さの下限(minY 以上)で判定し、境界を含む。
 * minY より下(地下の共同農場・植林場)は範囲に含まない。
 * 頻繁に呼ばれるため、整数比較を先に行いワールド名の比較は最後にする。
 */
record Zone(String world, int minX, int minY, int minZ, int maxX, int maxZ) {

    static Zone of(String world, int x1, int z1, int x2, int z2, int minY) {
        return new Zone(world, Math.min(x1, x2), minY, Math.min(z1, z2), Math.max(x1, x2), Math.max(z1, z2));
    }

    boolean contains(String worldName, int x, int y, int z) {
        return y >= minY && x >= minX && x <= maxX && z >= minZ && z <= maxZ && world.equals(worldName);
    }

    boolean contains(Block b) {
        return contains(b.getWorld().getName(), b.getX(), b.getY(), b.getZ());
    }

    boolean contains(Location l) {
        World w = l.getWorld();
        return w != null && contains(w.getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ());
    }

    /** 高さを問わず X・Z だけで判定する(地下の畑の踏み荒らし防止用) */
    boolean containsColumn(Block b) {
        int x = b.getX();
        int z = b.getZ();
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ && world.equals(b.getWorld().getName());
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
