package com.example.financialmarket.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/**
 * 交易所屏幕「拼接显示屏」分组判定（规范 §5.2）——面墙上朝向一致、正交相邻的若干
 * {@code exchange_screen} 方块在逻辑上组成一块连续显示屏。
 *
 * <p><b>确定性、无中心协调器、无随机</b>：本类仅基于世界方块状态，由「组内 min (x,z/y) 的
 * {@code (col,row)} 基准坐标系」给出本块在组内的几何分片，供 {@link #compute 方块实体}（铃声去重）
 * 与 BER（画面切割）共用。
 *
 * <p>坐标约定：显示屏贴在竖直墙面上（统一朝向），组内横向为墙面的水平方向（X 或 Z，取决于朝向），
 * 纵向为世界 Y（高度）。{@code col} 沿水平方向、{@code row} 沿垂直方向，均从组内最小角（{@code col==0
 * && row==0}，即组基准块）起算。
 */
public final class ExchangeScreenGrid {
    private ExchangeScreenGrid() {}

    /** 组宽/高上限（超过则按 1×1 退化，规范 §5.2 第1条）。 */
    public static final int MAX_GRID_W = 8;
    public static final int MAX_GRID_H = 8;

    /** 本块在组内的几何分片信息。 */
    public record GridInfo(int col, int row, int width, int height, boolean isPrime) {
        /** 单块退化（超出组宽高上限 / 无法判定）。 */
        public static GridInfo single() { return new GridInfo(0, 0, 1, 1, true); }
    }

    /**
     * 计算本块在其显示屏组内的 {@code (col,row)} 分片。
     *
     * @param level 含屏幕方块的世界（服务端/客户端通用，无客户端依赖）
     * @param pos   本屏幕方块位置
     */
    public static GridInfo compute(Level level, BlockPos pos) {
        if (level == null || pos == null) return GridInfo.single();
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof com.example.financialmarket.block.ExchangeScreenBlock)) {
            return GridInfo.single();
        }
        Direction facing = state.getValue(HorizontalDirectionalBlock.FACING);
        boolean hIsX = (facing == Direction.NORTH || facing == Direction.SOUTH);

        // 组内平面四个正交邻接偏移（水平沿墙面 X 或 Z，纵向沿 Y）
        int[][] inPlane = hIsX
                ? new int[][]{{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}}
                : new int[][]{{0, 0, 1}, {0, 0, -1}, {0, 1, 0}, {0, -1, 0}};

        Set<Long> visited = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        visited.add(pos.asLong());
        queue.add(pos);

        while (!queue.isEmpty()) {
            BlockPos cur = queue.poll();
            for (int[] off : inPlane) {
                BlockPos nb = cur.offset(off[0], off[1], off[2]);
                if (visited.contains(nb.asLong())) continue;
                if (isSameScreen(level, nb, facing)) {
                    visited.add(nb.asLong());
                    queue.add(nb);
                }
            }
        }

        if (visited.isEmpty()) return GridInfo.single();

        int minH = Integer.MAX_VALUE, maxH = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        for (long l : visited) {
            BlockPos p = BlockPos.of(l);
            int h = hIsX ? p.getX() : p.getZ();
            if (h < minH) minH = h;
            if (h > maxH) maxH = h;
            if (p.getY() < minY) minY = p.getY();
            if (p.getY() > maxY) maxY = p.getY();
        }

        int width = maxH - minH + 1;
        int height = maxY - minY + 1;
        if (width > MAX_GRID_W || height > MAX_GRID_H) return GridInfo.single();

        int col = (hIsX ? pos.getX() : pos.getZ()) - minH;
        int row = pos.getY() - minY;
        boolean prime = (col == 0 && row == 0);
        return new GridInfo(col, row, width, height, prime);
    }

    private static boolean isSameScreen(Level level, BlockPos nb, Direction facing) {
        if (!level.isLoaded(nb)) return false;
        BlockState s = level.getBlockState(nb);
        if (!(s.getBlock() instanceof com.example.financialmarket.block.ExchangeScreenBlock)) return false;
        return s.getValue(HorizontalDirectionalBlock.FACING) == facing;
    }
}
