package com.example.financialmarket.block;

import com.example.financialmarket.attachment.MarketAttachment;
import com.example.financialmarket.engine.MarketEngine;
import com.example.financialmarket.init.BlockInit;
import com.example.financialmarket.init.SoundInit;
import com.example.financialmarket.screen.data.ExchangeScreenDataService;
import com.example.financialmarket.screen.data.ExchangeScreenSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 交易所屏幕方块实体（规范 §3/§6/§8/§10）。
 *
 * <p>职责（服务端 tick）：
 * <ol>
 *   <li><b>快照同步</b>：每 {@link ExchangeScreenDataService#SNAPSHOT_INTERVAL_TICKS} tick 从引擎抓取
 *       一帧权威快照，经方块实体更新标签（{@link #getUpdateTag}）同步到客户端（BER 读取绘制）。</li>
 *   <li><b>开市/休市钟声</b>：观察 {@code engine.getCalendar().isMarketOpen()} 的跳变
 *       （false→true 开市、true→false 休市）触发一次；仅<b>组基准块</b>（{@code col==0&&row==0}）
 *       播报以去重。确定性、无随机。</li>
 *   <li><b>模式切换</b>：{@link #cycleMode()} 在 0/1/2 间循环，持久化到 NBT 并同步客户端。</li>
 * </ol>
 *
 * <p>持久化（§10）：{@code mode}/{@code lastMarketOpen} 存<b>方块实体 NBT</b>（读档兜底不失败），
 * 快照为瞬态（不落盘），引擎 H2/NBT 零改动。
 */
public class ExchangeScreenBlockEntity extends BlockEntity {
    /** 显示模式：0=核心指数 / 1=大盘行情 / 2=市场总览。 */
    private int mode = 0;
    /** 去抖态：最近一次观察到的开市状态。null 表示尚未获得基准（首次 tick 不响铃）。 */
    private Boolean lastMarketOpen;
    /** 客户端渲染用最新快照（瞬态，不持久化）。 */
    private transient ExchangeScreenSnapshot latestSnapshot = ExchangeScreenSnapshot.empty();

    public ExchangeScreenBlockEntity(BlockPos pos, BlockState state) {
        this(BlockInit.EXCHANGE_SCREEN_BE_TYPE, pos, state);
    }

    protected ExchangeScreenBlockEntity(net.minecraft.world.level.block.entity.BlockEntityType<?> type,
                                        BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public int getMode() { return mode; }
    public ExchangeScreenSnapshot getLatestSnapshot() { return latestSnapshot; }

    /**
     * 右键循环切换显示模式：0→1→2→0（服务端调用，持久化到 NBT 并通知客户端）。
     * @return 新的模式
     */
    public int cycleMode() {
        mode = (mode + 1) % 3;
        setChanged();
        refreshSnapshotAndSync();
        return mode;
    }

    /** 服务端每 tick 调用：快照节流同步 + 开市/休市钟声。 */
    public void tick() {
        if (level == null || level.isClientSide) return;
        if (!(level instanceof ServerLevel sl)) return;
        MarketEngine engine = MarketAttachment.getEngine(sl);
        if (engine == null) return;

        // 快照同步（tick 门控，确定性节流）
        if (engine.getTickCounter() % ExchangeScreenDataService.SNAPSHOT_INTERVAL_TICKS == 0) {
            refreshSnapshotAndSync();
        }

        // 开市/休市钟声（日历跳变触发，组基准块去重，确定性、无随机）
        boolean nowOpen = engine.getCalendar().isMarketOpen();
        boolean changed = (lastMarketOpen == null) || (lastMarketOpen != nowOpen);
        if (changed) {
            if (lastMarketOpen != null) {
                ExchangeScreenGrid.GridInfo grid = ExchangeScreenGrid.compute(sl, worldPosition);
                if (grid.isPrime()) {
                    net.minecraft.sounds.SoundEvent bell = nowOpen
                            ? SoundInit.EXCHANGE_OPEN_BELL
                            : SoundInit.EXCHANGE_CLOSE_BELL;
                    sl.playSound(null, worldPosition, bell, SoundSource.BLOCKS, 0.6f, 1.0f);
                }
            }
            lastMarketOpen = nowOpen;
            setChanged();
        }
    }

    /** 抓取一帧快照并同步给客户端（服务端调用；节流到 SNAPSHOT_INTERVAL_TICKS）。 */
    private void refreshSnapshotAndSync() {
        MarketEngine engine = level instanceof ServerLevel sl ? MarketAttachment.getEngine(sl) : null;
        if (engine == null) return;
        ExchangeScreenSnapshot snap = ExchangeScreenDataService.capture(engine, mode);
        latestSnapshot = snap;
        setChanged();
        if (level != null) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_ALL);
        }
    }

    // ---------- 持久化（方块实体 NBT，§10；读档兜底不失败） ----------

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.saveAdditional(tag, provider);
        tag.putInt("mode", mode);
        if (lastMarketOpen != null) tag.putBoolean("lastMarketOpen", lastMarketOpen);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.loadAdditional(tag, provider);
        if (tag.contains("mode")) mode = tag.getInt("mode");
        // lastMarketOpen 缺省：由下一次 tick 跟随当前引擎状态（读档兜底不失败）
        if (tag.contains("lastMarketOpen")) lastMarketOpen = tag.getBoolean("lastMarketOpen");
    }

    // ---------- 客户端同步（更新标签带快照 + 模式） ----------

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider provider) {
        CompoundTag tag = super.getUpdateTag(provider);
        tag.putInt("mode", mode);
        if (lastMarketOpen != null) tag.putBoolean("lastMarketOpen", lastMarketOpen);
        if (latestSnapshot != null) tag.put("snapshot", latestSnapshot.toNbt());
        return tag;
    }

    @Override
    public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider provider) {
        super.handleUpdateTag(tag, provider);
        if (tag.contains("mode")) mode = tag.getInt("mode");
        if (tag.contains("lastMarketOpen")) lastMarketOpen = tag.getBoolean("lastMarketOpen");
        if (tag.contains("snapshot")) latestSnapshot = ExchangeScreenSnapshot.fromNbt(tag.getCompound("snapshot"));
    }
}
