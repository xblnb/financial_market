package com.example.financialmarket.network.packets;

import com.example.financialmarket.engine.leverage.LeveragedNote;
import com.example.financialmarket.engine.leverage.LeveragedNoteMarket;
import com.example.financialmarket.engine.leverage.LeveragedNotePosition;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 服务端→客户端：玩家杠杆票据持仓（AS-L1，ML-8）。
 * 承载 noteId/units/avgEntryPrice 与最新价估值（lastPrice/marketValue/pnl），供前端持仓页展示。
 */
public record LeveragedNotePositionSyncPacket(List<PositionDTO> positions) implements CustomPacketPayload {
    public record PositionDTO(String noteId, int units, double avgEntryPrice, double lastPrice,
                              double marketValue, double pnl) {}

    public static final Type<LeveragedNotePositionSyncPacket> ID = new Type<>(ResourceLocation.fromNamespaceAndPath("financialmarket", "leverage_position_sync"));
    @Override public Type<? extends CustomPacketPayload> type() { return ID; }

    public static final StreamCodec<FriendlyByteBuf, LeveragedNotePositionSyncPacket> STREAM_CODEC = StreamCodec.of(
            LeveragedNotePositionSyncPacket::write,
            LeveragedNotePositionSyncPacket::read
    );

    private static void write(FriendlyByteBuf buf, LeveragedNotePositionSyncPacket p) {
        List<PositionDTO> list = p.positions();
        buf.writeVarInt(list == null ? 0 : list.size());
        if (list != null) {
            for (PositionDTO d : list) {
                buf.writeUtf(d.noteId() == null ? "" : d.noteId());
                buf.writeVarInt(d.units());
                buf.writeDouble(d.avgEntryPrice());
                buf.writeDouble(d.lastPrice());
                buf.writeDouble(d.marketValue());
                buf.writeDouble(d.pnl());
            }
        }
    }

    private static LeveragedNotePositionSyncPacket read(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        List<PositionDTO> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            list.add(new PositionDTO(buf.readUtf(), buf.readVarInt(), buf.readDouble(),
                    buf.readDouble(), buf.readDouble(), buf.readDouble()));
        }
        return new LeveragedNotePositionSyncPacket(list);
    }

    public static LeveragedNotePositionSyncPacket from(LeveragedNoteMarket lm, UUID playerId) {
        List<PositionDTO> list = new ArrayList<>();
        for (LeveragedNotePosition pos : lm.getPositions(playerId)) {
            LeveragedNote note = lm.getNote(pos.noteId);
            double last = note != null ? note.lastPrice : pos.avgEntryPrice;
            double marketValue = last * pos.units;
            double pnl = (last - pos.avgEntryPrice) * pos.units;
            list.add(new PositionDTO(pos.noteId, pos.units, pos.avgEntryPrice, last, marketValue, pnl));
        }
        return new LeveragedNotePositionSyncPacket(list);
    }
}
