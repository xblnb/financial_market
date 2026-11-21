package com.example.financialmarket.company;

import com.example.financialmarket.engine.CommodityEntry;
import com.example.financialmarket.engine.CommodityMarket;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * A-2/§3：玩家公司资产扫描器（t2 落地，替代原占位符）。
 *
 * <p>把玩家公司绑定的容器资产（{@code boundContainers}）扫描估值，写入
 * {@link CompanyData#setNetAssetValue(double)}，并把实物增值按 {@code profitCaptureRatio}
 * 计入近期利润与留存收益。全程确定性、无随机（COMPANY_SPEC §3.2）。
 */
public class AssetScanner {
    /** 每游戏日（24000 tick）扫描一次。 */
    public static final long SCAN_INTERVAL_TICKS = 24000L;
    /** 净资产增值中计入可分配利润的比例（COMPANY_SPEC §10）。 */
    public static final double PROFIT_CAPTURE_RATIO = 0.10;

    private AssetScanner() {
    }

    /** 扫描全部玩家公司（由 {@code MarketEngine.tick} 周期调用）。 */
    public static void scanAll(ServerLevel level, CompanyManager manager, CommodityMarket market) {
        if (level == null || manager == null) return;
        for (CompanyData company : manager.getAllCompanies()) {
            scanCompany(level, company, market);
        }
    }

    /**
     * 单公司扫描：遍历绑定的容器，统计容器内容并确定性估值写入净资产。
     * 净资产下降时近期利润为 0（不扣留存收益），避免存货回撤吃掉已实现利润。
     */
    public static void scanCompany(ServerLevel level, CompanyData company, CommodityMarket market) {
        if (level == null || company == null) return;
        double inventory = 0.0;
        for (BlockPos pos : company.getBoundContainers()) {
            if (pos == null) continue;
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof BaseContainerBlockEntity container) {
                for (int i = 0; i < container.getContainerSize(); i++) {
                    ItemStack stack = container.getItem(i);
                    if (stack != null && !stack.isEmpty()) {
                        inventory += valueOf(stack, market) * stack.getCount();
                    }
                }
            }
        }

        java.util.List<ProductionAssetModel.Asset> assets = new java.util.ArrayList<>();
        double actualUtilization = company.getProductionUtilization();
        for (BlockPos pos : company.getBoundProductionBlocks()) {
            if (pos == null || !level.isLoaded(pos)) continue;
            BlockEntity be = level.getBlockEntity(pos);
            if (be == null) continue;
            var blockId = BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock());
            if (blockId == null) continue;
            String ns = blockId.getNamespace();
            String path = blockId.getPath();
            String beClass = be.getClass().getSimpleName();
            if (!ProductionAssetModel.looksLikeProductionAsset(ns, path, beClass)) continue;
            double replacement = ProductionAssetModel.replacementCost(ns, path, beClass);
            boolean multiblock = ProductionAssetModel.looksLikeMultiblock(ns, path, beClass);
            // No per-machine uptime counter yet: conservative base utilization, inventory/energy
            // buffers are already reflected in the replacement value.
            assets.add(new ProductionAssetModel.Asset(ns + ":" + path + "@" + pos,
                    replacement, multiblock, actualUtilization));
        }
        ProductionAssetModel.Valuation v = ProductionAssetModel.value(inventory, assets);
        double prevInventory = company.getInventoryValue();
        company.setInventoryValue(v.inventoryValue());
        company.setReplacementCost(v.replacementCost());
        company.setAssetDepreciation(Math.max(0.0, v.replacementCost() - v.depreciatedValue()));
        company.setProductiveAssetValue(v.productiveValue());
        company.setAppraisedAssetValue(v.appraisedValue());
        company.setAssetUtilization(v.utilization());
        company.setAssetRisk(v.assetRisk());
        company.setAssetQuality(v.assetQuality());
        company.setMachineCount(v.machines());
        company.setMultiblockCount(v.multiblocks());
        company.setNetAssetValue(v.appraisedValue());

        double inventoryGain = v.inventoryValue() - prevInventory;
        double operating = company.getOperatingProfit();
        double productionProfit = operating != 0.0
                ? operating * 0.5
                : v.productiveValue() * 0.0002 * v.utilization();
        double profit = (inventoryGain > 0 ? inventoryGain * PROFIT_CAPTURE_RATIO : 0.0)
                + productionProfit;
        company.setRecentProfit(profit);
        company.setRetainedEarnings(Math.max(0.0, company.getRetainedEarnings() + profit));
    }

    /** True when a block entity looks like a machine/controller rather than plain storage. */
    public static boolean isProductionBlock(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null || !level.isLoaded(pos)) return false;
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) return false;
        var id = BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock());
        if (id == null) return false;
        return ProductionAssetModel.looksLikeProductionAsset(id.getNamespace(), id.getPath(),
                be.getClass().getSimpleName());
    }

    /** 确定性估值：优先用 CommodityMarket 的基准价（非波动实时价），否则用确定性默认价。 */
    private static double valueOf(ItemStack stack, CommodityMarket market) {
        Item item = stack.getItem();
        if (item == null) return 0.0;
        if (market != null) {
            CommodityEntry entry = market.getCommodity(item);
            if (entry != null) return entry.getBasePrice();
        }
        return defaultPrice(item);
    }

    /** 确定性默认价（无随机、纯常量表，按物品资源路径归类）。 */
    private static double defaultPrice(Item item) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
        String path = id != null ? id.getPath() : "";
        if (path.contains("ingot")) return 10.0;
        if (path.contains("gem") || path.contains("diamond") || path.contains("emerald")) return 25.0;
        if (path.contains("dust")) return 3.0;
        if (path.contains("nugget")) return 1.5;
        if (path.contains("block")) return 90.0;
        if (path.contains("ore")) return 8.0;
        if (path.contains("log")) return 2.0;
        if (path.contains("planks")) return 0.5;
        if (path.contains("sword") || path.contains("pickaxe") || path.contains("axe")
                || path.contains("shovel") || path.contains("hoe")) return 15.0;
        if (path.contains("helmet") || path.contains("chestplate") || path.contains("leggings")
                || path.contains("boots")) return 50.0;
        if (path.contains("wheat") || path.contains("carrot") || path.contains("potato")
                || path.contains("apple")) return 1.0;
        return 1.0;
    }
}
