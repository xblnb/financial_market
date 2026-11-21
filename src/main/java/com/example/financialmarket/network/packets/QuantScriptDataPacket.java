package com.example.financialmarket.network.packets;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 服务端→客户端：回传一个量化脚本源码，客户端 {@link com.example.financialmarket.screen.FinancialTabletScreen}
 * 据此填充代码编辑器。
 */
public record QuantScriptDataPacket(String fileName, String code) implements CustomPacketPayload {
    public static final Type<QuantScriptDataPacket> ID = new Type<>(ResourceLocation.fromNamespaceAndPath("financialmarket", "quant_script_data"));
    @Override public Type<? extends CustomPacketPayload> type() { return ID; }

    public static final StreamCodec<FriendlyByteBuf, QuantScriptDataPacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, QuantScriptDataPacket::fileName,
            ByteBufCodecs.STRING_UTF8, QuantScriptDataPacket::code,
            QuantScriptDataPacket::new);
}
