package com.example.financialmarket.network.packets;

import com.example.financialmarket.attachment.MarketAttachment;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端→服务端：请求杠杆票据列表 + 当前玩家持仓（AS-L1，ML-8）。
 * 服务端以绑定玩家身份回发 {@link LeveragedNoteListPacket} + {@link LeveragedNotePositionSyncPacket}。
 */
public record RequestLeveragedNoteDataPacket() implements CustomPacketPayload {
    public static final Type<RequestLeveragedNoteDataPacket> ID = new Type<>(ResourceLocation.fromNamespaceAndPath("financialmarket", "request_leverage_data"));
    @Override public Type<? extends CustomPacketPayload> type() { return ID; }

    public static final StreamCodec<FriendlyByteBuf, RequestLeveragedNoteDataPacket> STREAM_CODEC = StreamCodec.unit(new RequestLeveragedNoteDataPacket());

    public void send() { PacketDistributor.sendToServer(this); }

    public static void handle(RequestLeveragedNoteDataPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer sp) {
                var engine = MarketAttachment.getEngine(sp.serverLevel());
                if (engine == null || !engine.isLeverageEnabled()) return;
                var lm = engine.getLeverageMarket();
                sp.connection.send(LeveragedNoteListPacket.from(lm));
                sp.connection.send(LeveragedNotePositionSyncPacket.from(lm, sp.getUUID()));
            }
        });
    }
}
