package com.example.financialmarket.engine.microstructure;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * MarS 市场影响模型（MP-2）的成交量来源：每资产滚动平均日成交量（ADV，Average Daily Volume）。
 *
 * <p>Δ = α·σ·√(Q/V) 中的 V 取本类的 {@link #getAdv(String)}：近 {@code advWindowDays}（默认 5，来自
 * {@link com.example.financialmarket.engine.calibration.CalibrationParams#getAdvWindowDays()} 先验）个
 * 交易日的成交量均值——市场深度越厚（V 越大）下单量冲击越小，符合「下单量对价格影响与盘口深度有关」。
 *
 * <p><b>确定性</b>：纯 Java、无随机（不消耗任何随机源）；成交量经 {@link #onTrade} 增量累计，交易日边界
 * {@link #onTradingDayStart} 滚入窗口（窗口为代码常量/先验，不调参）。ADV 为窗口内成交量均值，迭代序无关。
 *
 * <p><b>持久化</b>：{@link #save()}/{@link #load(CompoundTag)} 随 MarketEngine 主 blob（NBT + H2
 * engine_kv.engine_main）整体序列化；旧存档缺字段用 {@code contains} 兜底重建，读档不失败（MP-5）。
 */
public class VolumeTracker {
    /** 每资产最近 advWindowDays 个交易日的成交量（Deque：队尾为最近一日）。 */
    private final Map<String, Deque<Double>> dailyVolumes = new HashMap<>();
    /** 每资产当日累计成交量（交易日边界滚入 dailyVolumes 后清零）。 */
    private final Map<String, Double> currentDayVolume = new HashMap<>();
    /** ADV 滚动窗口（交易日数，CalibrationParams 先验，默认 5）。 */
    private final int advWindowDays;

    public VolumeTracker() {
        this(5);
    }

    public VolumeTracker(int advWindowDays) {
        this.advWindowDays = advWindowDays >= 1 && advWindowDays <= 30 ? advWindowDays : 5;
    }

    /** 记录一笔成交的成交量（增量累计到当日；确定性、无随机）。 */
    public void onTrade(String assetId, double quantity) {
        if (assetId == null || quantity <= 0) return;
        currentDayVolume.merge(assetId, quantity, Double::sum);
    }

    /**
     * 交易日边界：把当日累计成交量滚入窗口（保留最近 advWindowDays 日），并清零当日累计。
     * 由 MarketEngine 在交易日开始处调用（与 dailyTrades 清空同点）；确定性、无随机。
     */
    public void onTradingDayStart() {
        for (Map.Entry<String, Double> e : currentDayVolume.entrySet()) {
            Deque<Double> dq = dailyVolumes.computeIfAbsent(e.getKey(), k -> new ArrayDeque<>());
            dq.addLast(Math.max(0.0, e.getValue()));
            while (dq.size() > advWindowDays) dq.pollFirst();
        }
        currentDayVolume.clear();
    }

    /**
     * 滚动 ADV（近 advWindowDays 交易日成交量均值）。尚无完整交易日时回落当日累计量（暖启动代理，
     * 保证开市首日 V>0 即有市场深度吸收）；无任何成交时返回 0（影响项按 0 处理，不改变既有行为）。
     */
    public double getAdv(String assetId) {
        Deque<Double> dq = dailyVolumes.get(assetId);
        if (dq != null && !dq.isEmpty()) {
            double sum = 0;
            for (double v : dq) sum += v;
            return sum / dq.size();
        }
        Double cur = currentDayVolume.get(assetId);
        return cur != null ? cur : 0.0;
    }

    /** ADV 滚动窗口（交易日数，先验常量）。 */
    public int getAdvWindowDays() {
        return advWindowDays;
    }

    // ---------- 持久化（MP-5：新增状态入 save/load，旧档 contains 兜底） ----------

    /** 序列化窗口成交量 + 当日累计（随引擎 blob 整体落盘）。 */
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("advWindowDays", advWindowDays);
        ListTag days = new ListTag();
        for (Map.Entry<String, Deque<Double>> e : dailyVolumes.entrySet()) {
            CompoundTag at = new CompoundTag();
            at.putString("assetId", e.getKey());
            ListTag vols = new ListTag();
            for (double v : e.getValue()) {
                CompoundTag vt = new CompoundTag();
                vt.putDouble("v", v);
                vols.add(vt);
            }
            at.put("vols", vols);
            days.add(at);
        }
        tag.put("dailyVolumes", days);
        CompoundTag cur = new CompoundTag();
        for (Map.Entry<String, Double> e : currentDayVolume.entrySet()) {
            cur.putDouble(e.getKey(), e.getValue());
        }
        tag.put("currentDayVolume", cur);
        return tag;
    }

    /** 读档恢复 ADV 状态；旧存档缺字段用空窗口重建（读档不失败，MP-5）。 */
    public void load(CompoundTag tag) {
        if (tag == null || tag.isEmpty()) return;
        dailyVolumes.clear();
        currentDayVolume.clear();
        if (tag.contains("dailyVolumes", Tag.TAG_LIST)) {
            ListTag days = tag.getList("dailyVolumes", Tag.TAG_COMPOUND);
            for (int i = 0; i < days.size(); i++) {
                CompoundTag at = days.getCompound(i);
                String assetId = at.getString("assetId");
                ListTag vols = at.getList("vols", Tag.TAG_COMPOUND);
                Deque<Double> dq = new ArrayDeque<>();
                for (int j = 0; j < vols.size(); j++) {
                    dq.addLast(vols.getCompound(j).getDouble("v"));
                }
                if (!dq.isEmpty()) dailyVolumes.put(assetId, dq);
            }
        }
        if (tag.contains("currentDayVolume")) {
            CompoundTag cur = tag.getCompound("currentDayVolume");
            for (String k : cur.getAllKeys()) {
                currentDayVolume.put(k, cur.getDouble(k));
            }
        }
    }
}
