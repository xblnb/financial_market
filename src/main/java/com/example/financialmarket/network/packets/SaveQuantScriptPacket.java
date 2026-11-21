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
 * 客户端→服务端：把量化脚本源码写入 H2 {@code quant_script} 表。
 * {@code fileName} 即原文件名 / script_id（如 {@code strategy1.js}）。
 */
public record SaveQuantScriptPacket(String fileName, String code) implements CustomPacketPayload {
    public static final Type<SaveQuantScriptPacket> ID = new Type<>(ResourceLocation.fromNamespaceAndPath("financialmarket", "save_quant_script"));
    @Override public Type<? extends CustomPacketPayload> type() { return ID; }

    public static final StreamCodec<FriendlyByteBuf, SaveQuantScriptPacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, SaveQuantScriptPacket::fileName,
            ByteBufCodecs.STRING_UTF8, SaveQuantScriptPacket::code,
            SaveQuantScriptPacket::new);

    public void send() { PacketDistributor.sendToServer(this); }

    public static void handle(SaveQuantScriptPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            // 仅在服务器线程执行：H2 单连接归服务器线程（见 H2_MIGRATION.md §5）
            H2DatabaseManager.saveQuantScript(packet.fileName(), packet.code());
            // t2：写入后立即让 QuantScriptEngine 重新编译该脚本（失效缓存即时生效）
            if (context.player() instanceof ServerPlayer sp) {
                var engine = MarketAttachment.getEngine(sp.serverLevel());
                if (engine != null) {
                    engine.getQuantScriptEngine().compile(packet.fileName(), packet.code());
                }
            }
        });
    }
}
