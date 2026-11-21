package com.example.financialmarket.company;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.core.BlockPos;
import java.util.*;

public class CompanyData {
    private UUID owner;                     // 创始人UUID
    private String name;                    // 公司名称
    private String stockId;                 // 股票代码（上市后分配）
    private CompanyLevel level;             // 当前等级
    private CompanyStatus status;           // 状态
    private double netAssetValue;           // 净资产（扫描器定期更新）
    private double recentProfit;            // 近期利润
    private int totalShares;                // 总股本
    private double ipoPrice;                // IPO价格
    private List<BlockPos> boundContainers; // 绑定的容器坐标
    private Map<UUID, Integer> shareholders; // 股东及其持股数
    private long foundedTime;               // 成立时间（游戏刻）
    private long listedTime;                // 上市时间
    // t2 生命周期/经营（COMPANY_SPEC §2.2）：可分配留存收益 + 财报/分红周期时间戳
    private double retainedEarnings;        // 可分配留存收益（分红资金池），AssetScanner 利润累积 + 分红扣减
    private long lastReportTime;            // 上次财报周期时间（tick），0 = 尚未生成
    private long lastDividendTime;          // 上次分红时间（tick），0 = 未分过红
    // t3 IPO/股权（COMPANY_SPEC §2.2/§4/§5）
    private int floatShares;                // 流通股数（IPO 发行量）；上市前 = totalShares
    private CompanyIpoState ipoState;       // IPO 状态（NONE/PRICED/SUBSCRIBING/LISTED/FAILED）
    private double subscriptionRaised;      // 本次认购已募集现金（确定性记账，供对账）
    private Map<UUID, Integer> subscriptions; // 认购意向（窗口期有效，随 blob 持久化保证可复现结算）
    private long ipoWindowStartDay;         // 认购窗口开启时的交易日序号（t3）
    // t4 财报/分红/退市（COMPANY_SPEC §6/§7）
    private List<CompanyFinancialReport> reports; // 财报快照（保留最近 MAX_REPORTS 期）
    private double dividendPaidTotal;       // 累计已派发分红
    private int lowPriceDays;               // 股价低于发行价 20% 的持续检查日数（退市判定）
    // t3 银行储蓄卡绑定（BT-6）：公司账户绑定创始人储蓄卡号（空字符串 = 未绑；无卡时因门控不会创建）
    private String boundCard;
    // World-macro (M1-M4): bottom-up player-economy aggregates, separate from AssetScanner NAV.
    private double macroProduction;
    private double macroCapex;
    private double macroRnd;
    private double macroRisk;
    private double macroTechTier;
    // Production-asset fundamentals (AssetScanner): machines/multiblocks/inventory.
    private List<BlockPos> boundProductionBlocks = new ArrayList<>();
    private double inventoryValue;
    private double productiveAssetValue;
    private double appraisedAssetValue;
    private double replacementCost;
    private double assetDepreciation;
    private double assetUtilization;
    private double assetRisk;
    private double assetQuality;
    private int machineCount;
    private int multiblockCount;
    // Actual operating flow, updated daily by TechStorageSampler.
    private double productionOutputValue;
    private double productionInputValue;
    private double productionThroughput;
    private double productionUtilization;
    private double inventoryTurnover;
    private double operatingProfit;
    private double energyConsumed;
    // AK growth state: capital, output, investment, growth, return, fair value.
    private double akCapital;
    private double akOutput;
    private double akInvestment;
    private double akGrowth;
    private double akReturn;
    private double akTfp;
    private double akFairValue;
    // Cobb–Douglas fundamentals: Y=A·K^α·L^(1−α).
    private double cdOutput;
    private double cdLabor;
    private double cdTfp;
    private double cdMpk;
    private double cdMpl;
    private double cdOptimalCapital;
    private double cdCapitalGap;

    public static final int MAX_REPORTS = 40;

    public CompanyData(UUID owner, String name, CompanyLevel level) {
        this.owner = owner;
        this.name = name;
        this.level = level != null ? level : CompanyLevel.STARTUP;
        this.status = CompanyStatus.PRIVATE;
        this.netAssetValue = 0;
        this.recentProfit = 0;
        this.totalShares = 10000;
        this.ipoPrice = 0;
        this.boundContainers = new ArrayList<>();
        this.shareholders = new HashMap<>();
        this.foundedTime = 0;
        this.listedTime = 0;
        this.retainedEarnings = 0;
        this.lastReportTime = 0;
        this.lastDividendTime = 0;
        this.floatShares = totalShares;
        this.ipoState = CompanyIpoState.NONE;
        this.subscriptionRaised = 0;
        this.subscriptions = new HashMap<>();
        this.ipoWindowStartDay = 0;
        this.reports = new ArrayList<>();
        this.dividendPaidTotal = 0;
        this.lowPriceDays = 0;
        this.boundCard = "";
    }

    // Getters and Setters
    public UUID getOwner() { return owner; }
    public void setOwner(UUID owner) { this.owner = owner; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getStockId() { return stockId; }
    public void setStockId(String stockId) { this.stockId = stockId; }
    public CompanyLevel getLevel() { return level; }
    public void setLevel(CompanyLevel level) { this.level = level; }
    public CompanyStatus getStatus() { return status; }
    public void setStatus(CompanyStatus status) { this.status = status; }
    public double getNetAssetValue() { return netAssetValue; }
    public void setNetAssetValue(double nav) { this.netAssetValue = nav; }
    public double getInventoryValue() { return inventoryValue; }
    public void setInventoryValue(double v) { this.inventoryValue = v; }
    public double getProductiveAssetValue() { return productiveAssetValue; }
    public void setProductiveAssetValue(double v) { this.productiveAssetValue = v; }
    public double getAppraisedAssetValue() { return appraisedAssetValue; }
    public void setAppraisedAssetValue(double v) { this.appraisedAssetValue = v; }
    public double getReplacementCost() { return replacementCost; }
    public void setReplacementCost(double v) { this.replacementCost = v; }
    public double getAssetDepreciation() { return assetDepreciation; }
    public void setAssetDepreciation(double v) { this.assetDepreciation = v; }
    public double getAssetUtilization() { return assetUtilization; }
    public void setAssetUtilization(double v) { this.assetUtilization = v; }
    public double getAssetRisk() { return assetRisk; }
    public void setAssetRisk(double v) { this.assetRisk = v; }
    public double getAssetQuality() { return assetQuality; }
    public void setAssetQuality(double v) { this.assetQuality = v; }
    public int getMachineCount() { return machineCount; }
    public void setMachineCount(int v) { this.machineCount = v; }
    public int getMultiblockCount() { return multiblockCount; }
    public void setMultiblockCount(int v) { this.multiblockCount = v; }
    public double getProductionOutputValue() { return productionOutputValue; }
    public void setProductionOutputValue(double v) { this.productionOutputValue = v; }
    public double getProductionInputValue() { return productionInputValue; }
    public void setProductionInputValue(double v) { this.productionInputValue = v; }
    public double getProductionThroughput() { return productionThroughput; }
    public void setProductionThroughput(double v) { this.productionThroughput = v; }
    public double getProductionUtilization() { return productionUtilization; }
    public void setProductionUtilization(double v) { this.productionUtilization = v; }
    public double getInventoryTurnover() { return inventoryTurnover; }
    public void setInventoryTurnover(double v) { this.inventoryTurnover = v; }
    public double getOperatingProfit() { return operatingProfit; }
    public void setOperatingProfit(double v) { this.operatingProfit = v; }
    public double getEnergyConsumed() { return energyConsumed; }
    public void setEnergyConsumed(double v) { this.energyConsumed = v; }
    public double getAkCapital() { return akCapital; }
    public void setAkCapital(double v) { this.akCapital = v; }
    public double getAkOutput() { return akOutput; }
    public void setAkOutput(double v) { this.akOutput = v; }
    public double getAkInvestment() { return akInvestment; }
    public void setAkInvestment(double v) { this.akInvestment = v; }
    public double getAkGrowth() { return akGrowth; }
    public void setAkGrowth(double v) { this.akGrowth = v; }
    public double getAkReturn() { return akReturn; }
    public void setAkReturn(double v) { this.akReturn = v; }
    public double getAkTfp() { return akTfp; }
    public void setAkTfp(double v) { this.akTfp = v; }
    public double getAkFairValue() { return akFairValue; }
    public void setAkFairValue(double v) { this.akFairValue = v; }
    public double getCdOutput() { return cdOutput; }
    public void setCdOutput(double v) { this.cdOutput = v; }
    public double getCdLabor() { return cdLabor; }
    public void setCdLabor(double v) { this.cdLabor = v; }
    public double getCdTfp() { return cdTfp; }
    public void setCdTfp(double v) { this.cdTfp = v; }
    public double getCdMpk() { return cdMpk; }
    public void setCdMpk(double v) { this.cdMpk = v; }
    public double getCdMpl() { return cdMpl; }
    public void setCdMpl(double v) { this.cdMpl = v; }
    public double getCdOptimalCapital() { return cdOptimalCapital; }
    public void setCdOptimalCapital(double v) { this.cdOptimalCapital = v; }
    public double getCdCapitalGap() { return cdCapitalGap; }
    public void setCdCapitalGap(double v) { this.cdCapitalGap = v; }
    public double getRecentProfit() { return recentProfit; }
    public void setRecentProfit(double profit) { this.recentProfit = profit; }
    public int getTotalShares() { return totalShares; }
    public void setTotalShares(int shares) { this.totalShares = shares; }
    public double getIpoPrice() { return ipoPrice; }
    public void setIpoPrice(double price) { this.ipoPrice = price; }
    public List<BlockPos> getBoundContainers() { return boundContainers; }

    public List<BlockPos> getBoundProductionBlocks() { return boundProductionBlocks; }

    /** Production blocks are capped more generously than containers; duplicates are ignored. */
    public boolean addBoundProductionBlock(BlockPos pos) {
        if (pos == null) return false;
        if (boundProductionBlocks.contains(pos)) return true;
        int cap = Math.max(64, level.getMaxContainers() * 16);
        if (boundProductionBlocks.size() >= cap) return false;
        boundProductionBlocks.add(pos);
        return true;
    }

    /** §3.3：加入一个绑定容器，受当前等级 maxContainers 上限约束；超限返回 false，重复返回 true。 */
    public boolean addBoundContainer(BlockPos pos) {
        if (pos == null) return false;
        if (boundContainers.contains(pos)) return true;
        if (boundContainers.size() >= level.getMaxContainers()) return false;
        boundContainers.add(pos);
        return true;
    }

    /** §3.3：清空绑定容器。 */
    public void clearBoundContainers() { boundContainers.clear(); }
    public Map<UUID, Integer> getShareholders() { return shareholders; }
    public long getFoundedTime() { return foundedTime; }
    public void setFoundedTime(long time) { this.foundedTime = time; }
    public long getListedTime() { return listedTime; }
    public void setListedTime(long time) { this.listedTime = time; }
    public double getRetainedEarnings() { return retainedEarnings; }
    public void setRetainedEarnings(double retained) { this.retainedEarnings = retained; }
    public long getLastReportTime() { return lastReportTime; }
    public void setLastReportTime(long time) { this.lastReportTime = time; }
    public long getLastDividendTime() { return lastDividendTime; }
    public void setLastDividendTime(long time) { this.lastDividendTime = time; }
    public int getFloatShares() { return floatShares; }
    public void setFloatShares(int floatShares) { this.floatShares = floatShares; }
    public CompanyIpoState getIpoState() { return ipoState; }
    public void setIpoState(CompanyIpoState ipoState) { this.ipoState = ipoState; }
    public double getSubscriptionRaised() { return subscriptionRaised; }
    public void setSubscriptionRaised(double raised) { this.subscriptionRaised = raised; }
    public Map<UUID, Integer> getSubscriptions() { return subscriptions; }
    public long getIpoWindowStartDay() { return ipoWindowStartDay; }
    public void setIpoWindowStartDay(long day) { this.ipoWindowStartDay = day; }
    public List<CompanyFinancialReport> getReports() { return reports; }
    public double getDividendPaidTotal() { return dividendPaidTotal; }
    public void setDividendPaidTotal(double total) { this.dividendPaidTotal = total; }
    public int getLowPriceDays() { return lowPriceDays; }
    public void setLowPriceDays(int days) { this.lowPriceDays = days; }

    /** t3：公司绑定创始人储蓄卡号（BT-6）；空字符串表示未绑定。 */
    public String getBoundCard() { return boundCard; }
    public void setBoundCard(String boundCard) { this.boundCard = boundCard; }

    public double getMacroProduction() { return macroProduction; }
    public void setMacroProduction(double v) { this.macroProduction = v; }
    public double getMacroCapex() { return macroCapex; }
    public void setMacroCapex(double v) { this.macroCapex = v; }
    public double getMacroRnd() { return macroRnd; }
    public void setMacroRnd(double v) { this.macroRnd = v; }
    public double getMacroRisk() { return macroRisk; }
    public void setMacroRisk(double v) { this.macroRisk = v; }
    public double getMacroTechTier() { return macroTechTier; }
    public void setMacroTechTier(double v) { this.macroTechTier = v; }

    /** §6.1：追加一期财报快照，保留最近 MAX_REPORTS 期（防膨胀）。确定性。 */
    public void addReport(CompanyFinancialReport report) {
        if (report == null) return;
        reports.add(report);
        while (reports.size() > MAX_REPORTS) {
            reports.remove(0);
        }
    }

    /** §6.1：最近一期财报；无则 null。 */
    public CompanyFinancialReport getLatestReport() {
        return reports.isEmpty() ? null : reports.get(reports.size() - 1);
    }

    /**
     * §4.2：确定性 IPO 定价（无随机，0.01 整数倍）。
     * ipoPrice = max(minIpoPrice, navPerShare * (1 + 等级溢价))，四舍五入到 0.01。
     */
    public double computeIpoPrice() {
        double navPerShare = totalShares > 0 ? netAssetValue / (double) totalShares : 0.0;
        double levelPremium = 0.0 + 0.1 * (level.getLevel() - 1);
        double minIpoPrice = 1.0;
        return round2(Math.max(minIpoPrice, navPerShare * (1 + levelPremium)));
    }

    /** 四舍五入到 0.01（与 MarketEngine.round2 同语义）。 */
    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /** §4.4：清空认购窗口（认购意向、募集现金、流通股复位为总股本）。确定性。 */
    public void clearIpoWindow() {
        this.subscriptions.clear();
        this.subscriptionRaised = 0;
        this.floatShares = this.totalShares;
        this.ipoWindowStartDay = 0;
    }

    // 序列化
    public CompoundTag write() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("owner", owner);
        tag.putString("name", name);
        if (stockId != null) tag.putString("stockId", stockId);
        tag.putInt("level", level.getLevel());
        tag.putString("status", status.name());
        tag.putDouble("netAsset", netAssetValue);
        tag.putDouble("profit", recentProfit);
        tag.putDouble("inventoryValue", inventoryValue);
        tag.putDouble("productiveAssetValue", productiveAssetValue);
        tag.putDouble("appraisedAssetValue", appraisedAssetValue);
        tag.putDouble("replacementCost", replacementCost);
        tag.putDouble("assetDepreciation", assetDepreciation);
        tag.putDouble("assetUtilization", assetUtilization);
        tag.putDouble("assetRisk", assetRisk);
        tag.putDouble("assetQuality", assetQuality);
        tag.putInt("machineCount", machineCount);
        tag.putInt("multiblockCount", multiblockCount);
        tag.putDouble("productionOutputValue", productionOutputValue);
        tag.putDouble("productionInputValue", productionInputValue);
        tag.putDouble("productionThroughput", productionThroughput);
        tag.putDouble("productionUtilization", productionUtilization);
        tag.putDouble("inventoryTurnover", inventoryTurnover);
        tag.putDouble("operatingProfit", operatingProfit);
        tag.putDouble("energyConsumed", energyConsumed);
        tag.putDouble("akCapital", akCapital);
        tag.putDouble("akOutput", akOutput);
        tag.putDouble("akInvestment", akInvestment);
        tag.putDouble("akGrowth", akGrowth);
        tag.putDouble("akReturn", akReturn);
        tag.putDouble("akTfp", akTfp);
        tag.putDouble("akFairValue", akFairValue);
        tag.putDouble("cdOutput", cdOutput);
        tag.putDouble("cdLabor", cdLabor);
        tag.putDouble("cdTfp", cdTfp);
        tag.putDouble("cdMpk", cdMpk);
        tag.putDouble("cdMpl", cdMpl);
        tag.putDouble("cdOptimalCapital", cdOptimalCapital);
        tag.putDouble("cdCapitalGap", cdCapitalGap);
        tag.putInt("shares", totalShares);
        tag.putDouble("ipo", ipoPrice);
        ListTag containers = new ListTag();
        for (BlockPos pos : boundContainers) {
            CompoundTag p = new CompoundTag();
            p.putInt("x", pos.getX());
            p.putInt("y", pos.getY());
            p.putInt("z", pos.getZ());
            containers.add(p);
        }
        tag.put("containers", containers);
        ListTag production = new ListTag();
        for (BlockPos pos : boundProductionBlocks) {
            CompoundTag p = new CompoundTag();
            p.putInt("x", pos.getX());
            p.putInt("y", pos.getY());
            p.putInt("z", pos.getZ());
            production.add(p);
        }
        tag.put("productionBlocks", production);
        ListTag holders = new ListTag();
        for (Map.Entry<UUID, Integer> e : shareholders.entrySet()) {
            CompoundTag h = new CompoundTag();
            h.putUUID("uuid", e.getKey());
            h.putInt("amount", e.getValue());
            holders.add(h);
        }
        tag.put("shareholders", holders);
        tag.putLong("founded", foundedTime);
        tag.putLong("listed", listedTime);
        tag.putDouble("retainedEarnings", retainedEarnings);
        tag.putLong("lastReportTime", lastReportTime);
        tag.putLong("lastDividendTime", lastDividendTime);
        tag.putInt("floatShares", floatShares);
        tag.putString("ipoState", ipoState.name());
        tag.putDouble("subscriptionRaised", subscriptionRaised);
        ListTag subList = new ListTag();
        for (Map.Entry<UUID, Integer> e : subscriptions.entrySet()) {
            CompoundTag s = new CompoundTag();
            s.putUUID("uuid", e.getKey());
            s.putInt("amount", e.getValue());
            subList.add(s);
        }
        tag.put("subscriptions", subList);
        tag.putLong("ipoWindowStartDay", ipoWindowStartDay);
        tag.putDouble("dividendPaidTotal", dividendPaidTotal);
        tag.putInt("lowPriceDays", lowPriceDays);
        tag.putDouble("macroProduction", macroProduction);
        tag.putDouble("macroCapex", macroCapex);
        tag.putDouble("macroRnd", macroRnd);
        tag.putDouble("macroRisk", macroRisk);
        tag.putDouble("macroTechTier", macroTechTier);
        // t3：公司绑定的创始人储蓄卡号（BT-6），随公司 blob 持久化
        if (boundCard != null) tag.putString("boundCard", boundCard);
        ListTag reportList = new ListTag();
        for (CompanyFinancialReport r : reports) {
            reportList.add(r.write());
        }
        tag.put("reports", reportList);
        return tag;
    }

    public static CompanyData read(CompoundTag tag) {
        CompanyData data = new CompanyData(tag.getUUID("owner"), tag.getString("name"),
                CompanyLevel.fromLevel(tag.getInt("level")));
        if (tag.contains("stockId")) data.stockId = tag.getString("stockId");
        data.status = CompanyStatus.valueOf(tag.getString("status"));
        data.netAssetValue = tag.getDouble("netAsset");
        data.recentProfit = tag.getDouble("profit");
        data.inventoryValue = tag.getDouble("inventoryValue");
        data.productiveAssetValue = tag.getDouble("productiveAssetValue");
        data.appraisedAssetValue = tag.getDouble("appraisedAssetValue");
        data.replacementCost = tag.getDouble("replacementCost");
        data.assetDepreciation = tag.getDouble("assetDepreciation");
        data.assetUtilization = tag.getDouble("assetUtilization");
        data.assetRisk = tag.getDouble("assetRisk");
        data.assetQuality = tag.getDouble("assetQuality");
        data.machineCount = tag.getInt("machineCount");
        data.multiblockCount = tag.getInt("multiblockCount");
        data.productionOutputValue = tag.getDouble("productionOutputValue");
        data.productionInputValue = tag.getDouble("productionInputValue");
        data.productionThroughput = tag.getDouble("productionThroughput");
        data.productionUtilization = tag.getDouble("productionUtilization");
        data.inventoryTurnover = tag.getDouble("inventoryTurnover");
        data.operatingProfit = tag.getDouble("operatingProfit");
        data.energyConsumed = tag.getDouble("energyConsumed");
        data.akCapital = tag.getDouble("akCapital");
        data.akOutput = tag.getDouble("akOutput");
        data.akInvestment = tag.getDouble("akInvestment");
        data.akGrowth = tag.getDouble("akGrowth");
        data.akReturn = tag.getDouble("akReturn");
        data.akTfp = tag.getDouble("akTfp");
        data.akFairValue = tag.getDouble("akFairValue");
        data.cdOutput = tag.getDouble("cdOutput");
        data.cdLabor = tag.getDouble("cdLabor");
        data.cdTfp = tag.getDouble("cdTfp");
        data.cdMpk = tag.getDouble("cdMpk");
        data.cdMpl = tag.getDouble("cdMpl");
        data.cdOptimalCapital = tag.getDouble("cdOptimalCapital");
        data.cdCapitalGap = tag.getDouble("cdCapitalGap");
        data.totalShares = tag.getInt("shares");
        data.ipoPrice = tag.getDouble("ipo");
        ListTag containers = tag.getList("containers", Tag.TAG_COMPOUND);
        for (int i = 0; i < containers.size(); i++) {
            CompoundTag p = containers.getCompound(i);
            data.boundContainers.add(new BlockPos(p.getInt("x"), p.getInt("y"), p.getInt("z")));
        }
        ListTag production = tag.getList("productionBlocks", Tag.TAG_COMPOUND);
        for (int i = 0; i < production.size(); i++) {
            CompoundTag p = production.getCompound(i);
            data.boundProductionBlocks.add(new BlockPos(p.getInt("x"), p.getInt("y"), p.getInt("z")));
        }
        ListTag holders = tag.getList("shareholders", Tag.TAG_COMPOUND);
        for (int i = 0; i < holders.size(); i++) {
            CompoundTag h = holders.getCompound(i);
            data.shareholders.put(h.getUUID("uuid"), h.getInt("amount"));
        }
        data.foundedTime = tag.getLong("founded");
        data.listedTime = tag.getLong("listed");
        // A-7：新增字段全部 contains 兜底，旧存档缺字段用默认重建，读档不失败
        if (tag.contains("retainedEarnings")) data.retainedEarnings = tag.getDouble("retainedEarnings");
        if (tag.contains("lastReportTime")) data.lastReportTime = tag.getLong("lastReportTime");
        if (tag.contains("lastDividendTime")) data.lastDividendTime = tag.getLong("lastDividendTime");
        if (tag.contains("floatShares")) data.floatShares = tag.getInt("floatShares");
        if (tag.contains("ipoState")) {
            try {
                data.ipoState = CompanyIpoState.valueOf(tag.getString("ipoState"));
            } catch (IllegalArgumentException e) {
                data.ipoState = CompanyIpoState.NONE;
            }
        }
        if (tag.contains("subscriptionRaised")) data.subscriptionRaised = tag.getDouble("subscriptionRaised");
        ListTag subList = tag.getList("subscriptions", Tag.TAG_COMPOUND);
        for (int i = 0; i < subList.size(); i++) {
            CompoundTag s = subList.getCompound(i);
            data.subscriptions.put(s.getUUID("uuid"), s.getInt("amount"));
        }
        if (tag.contains("ipoWindowStartDay")) data.ipoWindowStartDay = tag.getLong("ipoWindowStartDay");
        if (tag.contains("dividendPaidTotal")) data.dividendPaidTotal = tag.getDouble("dividendPaidTotal");
        if (tag.contains("lowPriceDays")) data.lowPriceDays = tag.getInt("lowPriceDays");
        if (tag.contains("macroProduction")) data.macroProduction = tag.getDouble("macroProduction");
        if (tag.contains("macroCapex")) data.macroCapex = tag.getDouble("macroCapex");
        if (tag.contains("macroRnd")) data.macroRnd = tag.getDouble("macroRnd");
        if (tag.contains("macroRisk")) data.macroRisk = tag.getDouble("macroRisk");
        if (tag.contains("macroTechTier")) data.macroTechTier = tag.getDouble("macroTechTier");
        // t3：恢复公司绑定的创始人储蓄卡号；旧存档缺字段用 contains 兜底为空串，读档不失败（BT-9）
        if (tag.contains("boundCard")) data.boundCard = tag.getString("boundCard");
        ListTag reportList = tag.getList("reports", Tag.TAG_COMPOUND);
        for (int i = 0; i < reportList.size(); i++) {
            data.reports.add(CompanyFinancialReport.read(reportList.getCompound(i)));
        }
        return data;
    }

    /**
     * §6.1：财报快照 record（确定性派生字段）。随 CompanyData.write()/read() 序列化，
     * 用于季度财报查询与退市「连续亏损」判定。
     */
    public record CompanyFinancialReport(long periodEnd, double netAssetValue, double recentProfit,
                                         double retainedEarnings, int totalShares, double eps, double roe,
                                         double ipoPrice, double marketCap) {
        public CompoundTag write() {
            CompoundTag tag = new CompoundTag();
            tag.putLong("periodEnd", periodEnd);
            tag.putDouble("netAssetValue", netAssetValue);
            tag.putDouble("recentProfit", recentProfit);
            tag.putDouble("retainedEarnings", retainedEarnings);
            tag.putInt("totalShares", totalShares);
            tag.putDouble("eps", eps);
            tag.putDouble("roe", roe);
            tag.putDouble("ipoPrice", ipoPrice);
            tag.putDouble("marketCap", marketCap);
            return tag;
        }

        public static CompanyFinancialReport read(CompoundTag tag) {
            return new CompanyFinancialReport(
                    tag.getLong("periodEnd"),
                    tag.getDouble("netAssetValue"),
                    tag.getDouble("recentProfit"),
                    tag.getDouble("retainedEarnings"),
                    tag.getInt("totalShares"),
                    tag.getDouble("eps"),
                    tag.getDouble("roe"),
                    tag.getDouble("ipoPrice"),
                    tag.getDouble("marketCap"));
        }
    }
}