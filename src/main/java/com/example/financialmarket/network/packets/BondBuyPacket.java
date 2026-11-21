package com.example.financialmarket.network.packets;

import com.example.financialmarket.attachment.MarketAttachment;
import com.example.financialmarket.attachment.PlayerEconomy;
import com.example.financialmarket.engine.banking.GovernmentBond;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import java.util.UUID;

public record BondBuyPacket(UUID bondId) implements CustomPacketPayload {
    public static final Type<BondBuyPacket> ID = new Type<>(ResourceLocation.fromNamespaceAndPath("financialmarket", "bond_buy"));
    @Override public Type<? extends CustomPacketPayload> type() { return ID; }

    public static final StreamCodec<FriendlyByteBuf, BondBuyPacket> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> buf.writeUUID(p.bondId),
            buf -> new BondBuyPacket(buf.readUUID())
    );

    public void send() { PacketDistributor.sendToServer(this); }

    public static void handle(BondBuyPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer sp) {
                var engine = MarketAttachment.getEngine(sp.serverLevel());
                if (engine != null) {
                    for (GovernmentBond bond : engine.getGovernment().getIssuedBonds()) {
                        if (bond.getBondId().equals(packet.bondId())) {
                            double cost = bond.calculateCurrentValue(sp.serverLevel().getGameTime() / 24000, 0.03);
                            PlayerEconomy eco = PlayerEconomy.get(sp);
                            if (eco.getCash() >= cost) {
                                eco.setCash(eco.getCash() - cost);
                                bond.setHolderUuid(sp.getUUID());
                            }
                            break;
                        }
                    }
                }
            }
        });
    }
}