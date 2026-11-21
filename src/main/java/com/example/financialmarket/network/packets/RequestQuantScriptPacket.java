package com.example.financialmarket.network.packets;

import com.example.financialmarket.attachment.MarketAttachment;
import com.example.financialmarket.storage.db.H2DatabaseManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端→服务端：请求从 H2 {@code quant_script} 表加载一个量化脚本。
 * 服务端把脚本内容通过 {@link QuantScriptDataPacket} 回传给请求的客户端。
 */
public record RequestQuantScriptPacket(String fileName) implements CustomPacketPayload {
    public static final Type<RequestQuantScriptPacket> ID = new Type<>(ResourceLocation.fromNamespaceAndPath("financialmarket", "request_quant_script"));
    @Override public Type<? extends CustomPacketPayload> type() { return ID; }

    public static final StreamCodec<FriendlyByteBuf, RequestQuantScriptPacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, RequestQuantScriptPacket::fileName,
            RequestQuantScriptPacket::new);

    public void send() { PacketDistributor.sendToServer(this); }

    public static void handle(RequestQuantScriptPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer sp) {
                // 仅在服务器线程执行（H2 单连接归服务器线程，见 H2_MIGRATION.md §5）
                String code = H2DatabaseManager.loadQuantScript(packet.fileName());
                sp.connection.send(new QuantScriptDataPacket(packet.fileName(), code == null ? "" : code));
                // t2：读回后同时触发 QuantScriptEngine 编译（脚本即可被引擎执行）
                var engine = MarketAttachment.getEngine(sp.serverLevel());
                if (engine != null && code != null) {
                    engine.getQuantScriptEngine().compile(packet.fileName(), code);
                }
            }
        });
    }
}
