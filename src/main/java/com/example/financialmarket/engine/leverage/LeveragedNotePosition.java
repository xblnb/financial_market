package com.example.financialmarket.engine.leverage;

import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

/**
 * 玩家杠杆票据持仓（AS-L1，ML-6）：noteId/units/avgEntryPrice。确定性、可持久化。
 */
public class LeveragedNotePosition {
    public final UUID playerId;
    public final String noteId;
    public int units;
    /** 加权平均买入成本。 */
    public double avgEntryPrice;

    public LeveragedNotePosition(UUID playerId, String noteId, int units, double avgEntryPrice) {
        this.playerId = playerId;
        this.noteId = noteId;
        this.units = units;
        this.avgEntryPrice = avgEntryPrice;
    }

    /** 加仓（按加权平均成本合并）。 */
    public void addUnits(int qty, double price) {
        double total = avgEntryPrice * units + price * qty;
        units += qty;
        avgEntryPrice = (units > 0) ? total / units : 0.0;
    }

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putUUID("player", playerId);
        t.putString("noteId", noteId);
        t.putInt("units", units);
        t.putDouble("avg", avgEntryPrice);
        return t;
    }

    public static LeveragedNotePosition load(CompoundTag t) {
        return new LeveragedNotePosition(
                t.getUUID("player"), t.getString("noteId"), t.getInt("units"), t.getDouble("avg"));
    }
}
