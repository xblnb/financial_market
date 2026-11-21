package com.example.financialmarket.network.packets;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 服务端→客户端：融资融券账户数据（t3 加 additive {@code positions} 持仓明细；
 * t2 加多级风险区带/追保计数/宽限截止/追保需补金额/限额用量，AS-M1/AS-M3/AS-M2）。
 *
 * <p>保持既有 {@code balance/risk} 前两字段不变（旧 handler / 前端仍可解，向后兼容）。
 * 旧两参/三参构造保留（新增字段取默认 NORMAL/0/0/0/0.0），旧存档/旧调用不失败。
 */
public record MarginDataResponsePacket(double balance, double risk, List<MarginPositionDTO> positions,
                                       String riskBand, int marginCallStreak, long marginCallDeadline,
                                       double requiredTopUp, double limitUsage) implements CustomPacketPayload {
    /** 单笔持仓明细（确定性视图）。 */
    public record MarginPositionDTO(String assetId, int quantity, double entryPrice, boolean isLong, double unrealizedPnl) {}

    /** 向后兼容：旧两参构造（positions 空，新字段默认）。 */
    public MarginDataResponsePacket(double balance, double risk) {
        this(balance, risk, Collections.emptyList(), "NORMAL", 0, 0L, 0.0, 0.0);
    }

    /** 向后兼容：旧三参构造（新字段默认）。 */
    public MarginDataResponsePacket(double balance, double risk, List<MarginPositionDTO> positions) {
        this(balance, risk, positions, "NORMAL", 0, 0L, 0.0, 0.0);
    }

    public static final Type<MarginDataResponsePacket> ID = new Type<>(ResourceLocation.fromNamespaceAndPath("financialmarket", "margin_data_response"));
    @Override public Type<? extends CustomPacketPayload> type() { return ID; }

    public static final StreamCodec<FriendlyByteBuf, MarginDataResponsePacket> STREAM_CODEC = StreamCodec.of(
            MarginDataResponsePacket::write,
            MarginDataResponsePacket::read
    );

    private static void write(FriendlyByteBuf buf, MarginDataResponsePacket p) {
        buf.writeDouble(p.balance());
        buf.writeDouble(p.risk());
        List<MarginPositionDTO> pos = p.positions();
        buf.writeVarInt(pos == null ? 0 : pos.size());
        if (pos != null) {
            for (MarginPositionDTO d : pos) {
                buf.writeUtf(d.assetId() == null ? "" : d.assetId());
                buf.writeVarInt(d.quantity());
                buf.writeDouble(d.entryPrice());
                buf.writeBoolean(d.isLong());
                buf.writeDouble(d.unrealizedPnl());
            }
        }
        buf.writeUtf(p.riskBand() == null ? "NORMAL" : p.riskBand());
        buf.writeVarInt(p.marginCallStreak());
        buf.writeLong(p.marginCallDeadline());
        buf.writeDouble(p.requiredTopUp());
        buf.writeDouble(p.limitUsage());
    }

    private static MarginDataResponsePacket read(FriendlyByteBuf buf) {
        double balance = buf.readDouble();
        double risk = buf.readDouble();
        int n = buf.readVarInt();
        List<MarginPositionDTO> pos = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String asset = buf.readUtf();
            int qty = buf.readVarInt();
            double entry = buf.readDouble();
            boolean isLong = buf.readBoolean();
            double pnl = buf.readDouble();
            pos.add(new MarginPositionDTO(asset, qty, entry, isLong, pnl));
        }
        String band = buf.readUtf();
        int streak = buf.readVarInt();
        long deadline = buf.readLong();
        double topUp = buf.readDouble();
        double limitUsage = buf.readDouble();
        return new MarginDataResponsePacket(balance, risk, pos, band, streak, deadline, topUp, limitUsage);
    }
}
