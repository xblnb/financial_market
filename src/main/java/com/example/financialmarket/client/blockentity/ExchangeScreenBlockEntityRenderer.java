package com.example.financialmarket.client.blockentity;

import com.example.financialmarket.block.ExchangeScreenBlockEntity;
import com.example.financialmarket.block.ExchangeScreenGrid;
import com.example.financialmarket.screen.data.ExchangeScreenColors;
import com.example.financialmarket.screen.data.ExchangeScreenSnapshot;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.joml.Matrix4f;

import java.util.List;

/**
 * 交易所屏幕方块实体渲染器（BER，规范 §5/§6/§11）。
 *
 * <p><b>仅客户端</b>（{@code @OnlyIn(Dist.CLIENT)}），经 {@code ClientRenderInit} 注册到
 * {@code EntityRenderersEvent.RegisterRenderers}。服务端<b>永不加载</b>本类（RuntimeDistCleaner 安全）。
 *
 * <p>渲染数据：客户端 {@code ExchangeScreenBlockEntity} 从服务端同步的快照（{@code latestSnapshot}）；
 * 未收到快照时绘制「—— / NO DATA」占位，不阻塞主逻辑。
 *
 * <p><b>三模式</b>（{@code snapshot.mode}）：0=核心指数 / 1=大盘行情 / 2=市场总览；红涨绿跌（CN 色）：
 * {@link ExchangeScreenColors#RISE 红}（涨）/ {@link ExchangeScreenColors#FALL 绿}（跌）/
 * {@link ExchangeScreenColors#FLAT 灰}（平）。
 *
 * <p><b>拼接</b>（§5.2）：内容 = {@code f(snapshot)} 纯函数，所有块用同一函数生成整屏内容；
 * 本块按其在组内的 {@code (col,row)}（{@link ExchangeScreenGrid#compute}）只绘制属于自己的子矩形
 * （内容像素空间平移 + 每块独立取景），去中心化、确定性、无块间通信。屏幕贴墙，按朝向旋转取景。
 */
@OnlyIn(Dist.CLIENT)
public class ExchangeScreenBlockEntityRenderer implements BlockEntityRenderer<ExchangeScreenBlockEntity> {

    private final Font font;

    public ExchangeScreenBlockEntityRenderer(BlockEntityRendererProvider.Context context) {
        this.font = Minecraft.getInstance().font;
    }

    @Override
    public void render(ExchangeScreenBlockEntity be, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        if (be == null || be.getLevel() == null) return;
        ExchangeScreenSnapshot snap = be.getLatestSnapshot();
        ExchangeScreenGrid.GridInfo grid = ExchangeScreenGrid.compute(be.getLevel(), be.getBlockPos());

        poseStack.pushPose();
        // 移动到方块中心并按朝向旋转，使屏幕法线朝观察者
        poseStack.translate(0.5, 0.5, 0.5);
        Direction facing = be.getBlockState().getValue(HorizontalDirectionalBlock.FACING);
        float yaw = yawFor(facing);
        poseStack.mulPose(Axis.YP.rotationDegrees(yaw));
        poseStack.translate(0.0, 0.0, 0.501); // 贴墙面略前移，避免与方块面深度冲突

        if (snap == null || (snap.tick() == 0 && snap.indexValue() == 0 && snap.board().isEmpty())) {
            drawString(poseStack, bufferSource, "—— NO DATA ——", 0xFFFFFFFF, 0, 0, 1.0f, packedLight);
            poseStack.popPose();
            return;
        }

        // 拼接：每块只绘制自己的 (col,row) 子矩形（内容像素空间平移）
        int cellW = 120;
        int cellH = 14;
        poseStack.scale(0.008f, 0.008f, 0.008f);
        float ox = -grid.col() * cellW;
        float oy = -grid.row() * cellH;
        poseStack.translate(ox, oy, 0.0f);

        // 每块内容 = f(snapshot)，覆盖整屏宽高（W×H 格），叠放后拼成连续显示屏
        drawModeContent(snap, grid, poseStack, bufferSource, packedLight);
        poseStack.popPose();
    }

    private void drawModeContent(ExchangeScreenSnapshot snap, ExchangeScreenGrid.GridInfo grid,
                                 PoseStack poseStack, MultiBufferSource bufferSource, int packedLight) {
        int mode = snap.mode();
        if (mode == 0) {
            drawIndexMode(snap, poseStack, bufferSource, packedLight);
        } else if (mode == 1) {
            drawBoardMode(snap, poseStack, bufferSource, packedLight);
        } else {
            drawOverviewMode(snap, poseStack, bufferSource, packedLight);
        }
    }

    // ---------- 模式 0：核心指数 ----------
    private void drawIndexMode(ExchangeScreenSnapshot snap, PoseStack poseStack,
                               MultiBufferSource bufferSource, int packedLight) {
        int color = colorFor(snap.indexChangePct());
        drawString(poseStack, bufferSource, "优经24 INDEX", 0xFFFFFFFF, 0, 4, 1.0f, packedLight);
        drawString(poseStack, bufferSource, String.format("%.2f", snap.indexValue()), color, 0, 20, 2.4f, packedLight);
        String sign = snap.indexChangePoint() > 0 ? "+" : "";
        drawString(poseStack, bufferSource, sign + String.format("%.2f  (%.2f%%)",
                snap.indexChangePoint(), snap.indexChangePct()), color, 0, 60, 1.2f, packedLight);
        drawStatusLine(snap, poseStack, bufferSource, packedLight);
    }

    // ---------- 模式 1：大盘行情 ----------
    private void drawBoardMode(ExchangeScreenSnapshot snap, PoseStack poseStack,
                               MultiBufferSource bufferSource, int packedLight) {
        // 表头
        drawString(poseStack, bufferSource, "代码  现价  涨幅", 0xFFFFFFFF, 0, 2, 0.9f, packedLight);
        List<ExchangeScreenSnapshot.BoardRow> board = snap.board();
        int lineH = 12;
        // 拼接：本块只画属于本行带的行（去中心化、确定性）
        for (int i = 0; i < board.size(); i++) {
            ExchangeScreenSnapshot.BoardRow row = board.get(i);
            int y = 18 + i * lineH;
            int c = colorFor(row.changePct());
            drawString(poseStack, bufferSource,
                    String.format("%-6s %7.2f %+6.2f%%", row.id(), row.price(), row.changePct()),
                    c, 0, y, 0.9f, packedLight);
        }
        drawStatusLine(snap, poseStack, bufferSource, packedLight);
    }

    // ---------- 模式 2：市场总览 ----------
    private void drawOverviewMode(ExchangeScreenSnapshot snap, PoseStack poseStack,
                                  MultiBufferSource bufferSource, int packedLight) {
        drawString(poseStack, bufferSource, "市场总览 OVERVIEW", 0xFFFFFFFF, 0, 4, 1.2f, packedLight);
        int upColor = snap.upCount() >= 0 ? ExchangeScreenColors.RISE : ExchangeScreenColors.FLAT;
        int downColor = ExchangeScreenColors.FALL;
        drawString(poseStack, bufferSource, String.format("涨家数 %d", snap.upCount()), upColor, 0, 26, 1.0f, packedLight);
        drawString(poseStack, bufferSource, String.format("跌家数 %d", snap.downCount()), downColor, 0, 40, 1.0f, packedLight);
        drawString(poseStack, bufferSource, String.format("平家数 %d", snap.flatCount()), ExchangeScreenColors.FLAT, 0, 54, 1.0f, packedLight);
        drawString(poseStack, bufferSource, String.format("总成交额 %.1f", snap.totalAmount()), 0xFFFFFFFF, 0, 70, 1.0f, packedLight);
        drawString(poseStack, bufferSource, String.format("涨跌比 %.2f", snap.advDeclineRatio()), 0xFFFFFFFF, 0, 84, 1.0f, packedLight);
        drawStatusLine(snap, poseStack, bufferSource, packedLight);
    }

    private void drawStatusLine(ExchangeScreenSnapshot snap, PoseStack poseStack,
                                MultiBufferSource bufferSource, int packedLight) {
        String status = snap.marketOpen() ? "● 开市 OPEN" : "○ 休市 CLOSED";
        int c = snap.marketOpen() ? ExchangeScreenColors.RISE : ExchangeScreenColors.FLAT;
        drawString(poseStack, bufferSource, status, c, 0, 98, 1.0f, packedLight);
    }

    /** 涨跌颜色语义（红涨绿跌，CN）：涨=红，跌=绿，平=灰。 */
    private int colorFor(double changePct) {
        if (changePct > 0) return ExchangeScreenColors.RISE;
        if (changePct < 0) return ExchangeScreenColors.FALL;
        return ExchangeScreenColors.FLAT;
    }

    private void drawString(PoseStack poseStack, MultiBufferSource bufferSource, String text, int color,
                            float x, float y, float scale, int packedLight) {
        Matrix4f matrix = poseStack.last().pose();
        float sx = x * scale;
        float sy = y * scale;
        font.drawInBatch(text, sx, sy, color, false, matrix, bufferSource,
                Font.DisplayMode.NORMAL, 0, packedLight);
    }

    private static float yawFor(Direction facing) {
        switch (facing) {
            case SOUTH: return 0f;
            case WEST: return 90f;
            case EAST: return -90f;
            case NORTH:
            default: return 180f;
        }
    }
}
