package com.example.financialmarket.network.packets;

import com.example.financialmarket.attachment.MarketAttachment;
import com.example.financialmarket.company.CompanyData;
import com.example.financialmarket.engine.MarketEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 公司区域绑定包（COMPANY_SPEC §3.3，t7 落地）。
 *
 * <p>handle：取服务器玩家公司，遍历 corner1/corner2 长方体块坐标，将
 * {@link BaseContainerBlockEntity} 去重并入 boundContainers，受 CompanyLevel.maxContainers
 * 上限约束，超限拒绝并反馈。与 bind-area 命令逻辑一致。确定性、无随机。
 */
public record CompanyAreaPacket(BlockPos corner1, BlockPos corner2) implements CustomPacketPayload {
    public static final Type<CompanyAreaPacket> ID = new Type<>(ResourceLocation.fromNamespaceAndPath("financialmarket", "company_area"));
    @Override public Type<? extends CustomPacketPayload> type() { return ID; }

    public static final StreamCodec<FriendlyByteBuf, CompanyAreaPacket> STREAM_CODEC = StreamCodec.of(
            (buf, packet) -> {
                buf.writeBlockPos(packet.corner1);
                buf.writeBlockPos(packet.corner2);
            },
            buf -> new CompanyAreaPacket(buf.readBlockPos(), buf.readBlockPos())
    );

    public void send() { PacketDistributor.sendToServer(this); }

    public static void handle(CompanyAreaPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer sp) {
                MarketEngine engine = MarketAttachment.getEngine(sp.serverLevel());
                if (engine == null) return;
                CompanyData company = engine.getCompanyManager().getByOwner(sp.getUUID());
                if (company == null) {
                    sp.displayClientMessage(Component.literal("你还没有公司，请先创建"), false);
                    return;
                }
                BlockPos c1 = packet.corner1();
                BlockPos c2 = packet.corner2();
                if (c1 == null || c2 == null) return;
                int minX = Math.min(c1.getX(), c2.getX());
                int maxX = Math.max(c1.getX(), c2.getX());
                int minY = Math.min(c1.getY(), c2.getY());
                int maxY = Math.max(c1.getY(), c2.getY());
                int minZ = Math.min(c1.getZ(), c2.getZ());
                int maxZ = Math.max(c1.getZ(), c2.getZ());
                int maxContainers = company.getLevel().getMaxContainers();
                int added = 0;
                int rejected = 0;
                outer:
                for (int x = minX; x <= maxX; x++) {
                    for (int y = minY; y <= maxY; y++) {
                        for (int z = minZ; z <= maxZ; z++) {
                            if (company.getBoundContainers().size() >= maxContainers) break outer;
                            BlockPos pos = new BlockPos(x, y, z);
                            if (sp.serverLevel().getBlockEntity(pos) instanceof BaseContainerBlockEntity) {
                                if (company.addBoundContainer(pos)) added++;
                                else rejected++;
                            }
                        }
                    }
                }
                final int addedF = added;
                final int rejectedF = rejected;
                sp.displayClientMessage(Component.literal("绑定区域完成：新增 " + addedF + " 个容器"
                        + (rejectedF > 0 ? "，拒绝 " + rejectedF + " 个（超出上限 " + maxContainers + "）" : "")
                        + "，当前 " + company.getBoundContainers().size() + "/" + maxContainers), false);
            }
        });
    }
}
