package com.example.financialmarket.network.packets;

import com.example.financialmarket.engine.leverage.LeveragedNote;
import com.example.financialmarket.engine.leverage.LeveragedNoteMarket;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务端→客户端：杠杆票据列表（AS-L1，ML-8）。
 * 承载全市场在售/已了结票据的元数据与最新价，供前端票据页展示。
 */
public record LeveragedNoteListPacket(List<NoteDTO> notes) implements CustomPacketPayload {
    public record NoteDTO(String noteId, String name, String underlyingType, String underlyingKey,
                          double leverage, double lastPrice, double prevSettlePrice, long outstanding,
                          String status, long maturityTicks, double unitNotional) {}

    public static final Type<LeveragedNoteListPacket> ID = new Type<>(ResourceLocation.fromNamespaceAndPath("financialmarket", "leverage_note_list"));
    @Override public Type<? extends CustomPacketPayload> type() { return ID; }

    public static final StreamCodec<FriendlyByteBuf, LeveragedNoteListPacket> STREAM_CODEC = StreamCodec.of(
            LeveragedNoteListPacket::write,
            LeveragedNoteListPacket::read
    );

    private static void write(FriendlyByteBuf buf, LeveragedNoteListPacket p) {
        List<NoteDTO> list = p.notes();
        buf.writeVarInt(list == null ? 0 : list.size());
        if (list != null) {
            for (NoteDTO d : list) {
                buf.writeUtf(d.noteId() == null ? "" : d.noteId());
                buf.writeUtf(d.name() == null ? "" : d.name());
                buf.writeUtf(d.underlyingType() == null ? "STOCK" : d.underlyingType());
                buf.writeUtf(d.underlyingKey() == null ? "" : d.underlyingKey());
                buf.writeDouble(d.leverage());
                buf.writeDouble(d.lastPrice());
                buf.writeDouble(d.prevSettlePrice());
                buf.writeLong(d.outstanding());
                buf.writeUtf(d.status() == null ? "LISTED" : d.status());
                buf.writeLong(d.maturityTicks());
                buf.writeDouble(d.unitNotional());
            }
        }
    }

    private static LeveragedNoteListPacket read(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        List<NoteDTO> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            list.add(new NoteDTO(buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readUtf(),
                    buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readLong(),
                    buf.readUtf(), buf.readLong(), buf.readDouble()));
        }
        return new LeveragedNoteListPacket(list);
    }

    public static LeveragedNoteListPacket from(LeveragedNoteMarket lm) {
        List<NoteDTO> list = new ArrayList<>();
        for (LeveragedNote n : lm.getNotes().values()) {
            list.add(new NoteDTO(n.noteId, n.name == null ? "" : n.name,
                    n.underlyingType == null ? "STOCK" : n.underlyingType.name(),
                    n.underlyingKey == null ? "" : n.underlyingKey,
                    n.leverage, n.lastPrice, n.prevSettlePrice, n.outstanding,
                    n.status == null ? "LISTED" : n.status.name(), n.maturityTicks, n.unitNotional));
        }
        return new LeveragedNoteListPacket(list);
    }
}
