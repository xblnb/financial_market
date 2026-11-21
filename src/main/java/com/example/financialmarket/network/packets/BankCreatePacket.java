package com.example.financialmarket.network.packets;

import com.example.financialmarket.attachment.MarketAttachment;
import com.example.financialmarket.attachment.PlayerEconomy;
import com.example.financialmarket.engine.banking.Bank;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record BankCreatePacket(String bankName, double initialEquity) implements CustomPacketPayload {
    public static final Type<BankCreatePacket> ID = new Type<>(ResourceLocation.fromNamespaceAndPath("financialmarket", "bank_create"));
    @Override public Type<? extends CustomPacketPayload> type() { return ID; }

    public static final StreamCodec<FriendlyByteBuf, BankCreatePacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, BankCreatePacket::bankName,
            ByteBufCodecs.DOUBLE, BankCreatePacket::initialEquity,
            BankCreatePacket::new);

    public void send() { PacketDistributor.sendToServer(this); }

    public static void handle(BankCreatePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer sp) {
                var engine = MarketAttachment.getEngine(sp.serverLevel());
                if (engine != null) {
                    PlayerEconomy eco = PlayerEconomy.get(sp);
                    if (eco.getCash() >= packet.initialEquity()) {
                        eco.setCash(eco.getCash() - packet.initialEquity());
                        String bankId = "BANK_" + sp.getUUID().toString().substring(0, 8);
                        Bank bank = new Bank(bankId, packet.bankName(), sp.getUUID(),
                                packet.initialEquity(), 0.1, 0.06, 0.02);
                        engine.getBankManager().registerBank(bank);
                        // 冒烟阻断项修复：新注册玩家银行纳入储蓄卡可开户行集合（开自己银行→办卡闭环）
                        engine.refreshCardValidBanks();
                    }
                }
            }
        });
    }
}