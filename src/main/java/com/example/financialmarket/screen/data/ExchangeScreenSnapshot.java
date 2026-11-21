package com.example.financialmarket.screen.data;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;

/**
 * 交易所屏幕服务端权威快照（纯只读、纯函数派生，无随机）。
 *
 * <p>规范 §3.1：每 {@code SNAPSHOT_INTERVAL_TICKS} tick 从 {@code MarketEngine} 抓取一次紧凑快照，
 * 供 {@code ExchangeScreenBlockEntity} 缓存并经方块实体更新标签同步到客户端。同 tick → 同快照
 * （确定性可复现）。快照字段的语义见 {@link ExchangeScreenDataService}。
 *
 * <p>{@code mode}（0=核心指数 / 1=大盘行情 / 2=市场总览）为<b>显示模式</b>，由右键循环切换持久化到
 * 方块实体 NBT；快照本身携带全部三类所需数据，BER 仅按 {@code mode} 选择绘制哪一子内容。
 */
public record ExchangeScreenSnapshot(
        int mode,
        boolean marketOpen,
        long tick,
        double indexValue,
        double indexChangePoint,
        double indexChangePct,
        List<BoardRow> board,
        int upCount,
        int downCount,
        int flatCount,
        double totalVolume,
        double totalAmount,
        double advDeclineRatio
) {

    /** 大盘行情行（代码 / 名称 / 现价 / 涨跌幅）。涨跌幅 sign 决定颜色。 */
    public record BoardRow(String id, String name, double price, double changePct) {}

    public ExchangeScreenSnapshot withMode(int newMode) {
        return new ExchangeScreenSnapshot(newMode, marketOpen, tick, indexValue, indexChangePoint,
                indexChangePct, board, upCount, downCount, flatCount, totalVolume, totalAmount, advDeclineRatio);
    }

    /** 序列化为 NBT（供方块实体更新标签同步到客户端）。 */
    public CompoundTag toNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("mode", mode);
        tag.putBoolean("marketOpen", marketOpen);
        tag.putLong("tick", tick);
        tag.putDouble("indexValue", indexValue);
        tag.putDouble("indexChangePoint", indexChangePoint);
        tag.putDouble("indexChangePct", indexChangePct);
        ListTag boardTag = new ListTag();
        for (BoardRow row : board) {
            CompoundTag r = new CompoundTag();
            r.putString("id", row.id());
            r.putString("name", row.name());
            r.putDouble("price", row.price());
            r.putDouble("changePct", row.changePct());
            boardTag.add(r);
        }
        tag.put("board", boardTag);
        tag.putInt("upCount", upCount);
        tag.putInt("downCount", downCount);
        tag.putInt("flatCount", flatCount);
        tag.putDouble("totalVolume", totalVolume);
        tag.putDouble("totalAmount", totalAmount);
        tag.putDouble("advDeclineRatio", advDeclineRatio);
        return tag;
    }

    /** 从 NBT 还原（缺字段用 contains 兜底，读档/同步不失败）。 */
    public static ExchangeScreenSnapshot fromNbt(CompoundTag tag) {
        if (tag == null) return empty();
        List<BoardRow> board = new ArrayList<>();
        if (tag.contains("board")) {
            ListTag boardTag = tag.getList("board", Tag.TAG_COMPOUND);
            for (Tag t : boardTag) {
                CompoundTag r = (CompoundTag) t;
                board.add(new BoardRow(
                        r.getString("id"), r.getString("name"),
                        r.getDouble("price"), r.getDouble("changePct")));
            }
        }
        return new ExchangeScreenSnapshot(
                tag.getInt("mode"),
                tag.getBoolean("marketOpen"),
                tag.getLong("tick"),
                tag.getDouble("indexValue"),
                tag.getDouble("indexChangePoint"),
                tag.getDouble("indexChangePct"),
                board,
                tag.getInt("upCount"),
                tag.getInt("downCount"),
                tag.getInt("flatCount"),
                tag.getDouble("totalVolume"),
                tag.getDouble("totalAmount"),
                tag.getDouble("advDeclineRatio"));
    }

    /** 空快照（同步尚未收到 / 服务端无引擎时的占位）。 */
    public static ExchangeScreenSnapshot empty() {
        return new ExchangeScreenSnapshot(0, false, 0L, 0.0, 0.0, 0.0,
                new ArrayList<>(), 0, 0, 0, 0.0, 0.0, 0.0);
    }
}
