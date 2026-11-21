package com.example.financialmarket.command;

import com.example.financialmarket.attachment.MarketAttachment;
import com.example.financialmarket.company.AssetScanner;
import com.example.financialmarket.company.CompanyData;
import com.example.financialmarket.company.CompanyLevel;
import com.example.financialmarket.company.DelistingMonitor;
import com.example.financialmarket.engine.MarketEngine;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 玩家公司命令（A-8，t2 合并同名 CompanyCommand 后唯一入口）。
 *
 * <p>合并说明（COMPANY_SPEC §8.1）：删除死代码 {@code company/CompanyCommand.java}，
 * 本类（{@code command/CompanyCommand.java}）作为唯一命令入口，仍由
 * {@code FinancialMarket.Events.onRegisterCommands} 注册。
 *
 * <p>命令集（/fmarket company ...，§8.2）：create/level/levelup/leveldown/ipo/subscribe/
 * info/shareholders/transfer/report/dividend/delist/bind-area/scan。所有命令走确定性校验，
 * 失败 sendFailure / 成功 sendSuccess。t2 实现生命周期与查询类；t7 接线 IPO/股权/财报/分红/退市
 * 命令到确定性引擎方法（startIpo/subscribeIpo/transferShares/generateFinancialReport/
 * payDividend/DelistingMonitor.evaluate）。
 *
 * <p>权限：命令树根要求 {@code hasPermission(2)}（与既有 P2Command 共用 fmarket 根，保持基线不回归）；
 * 各子命令另行声明普通/管理 requires 以表达意图。
 */
public class CompanyCommand {
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("fmarket")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("company")
                        // create <name> —— 创建公司（创造模式）
                        .then(Commands.literal("create")
                                .requires(src -> src.hasPermission(0))
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .executes(ctx -> create(ctx))))
                        // level —— 查询等级
                        .then(Commands.literal("level")
                                .requires(src -> src.hasPermission(0))
                                .executes(ctx -> queryLevel(ctx.getSource()))
                                .then(Commands.literal("set")
                                        .requires(src -> src.hasPermission(2))
                                        .then(Commands.argument("level", IntegerArgumentType.integer(1, 5))
                                                .executes(ctx -> setLevel(ctx.getSource(),
                                                        IntegerArgumentType.getInteger(ctx, "level"))))))
                        // levelup —— 确定性门槛升级
                        .then(Commands.literal("levelup")
                                .requires(src -> src.hasPermission(0))
                                .executes(ctx -> levelUp(ctx.getSource())))
                        // leveldown —— 管理操作降级
                        .then(Commands.literal("leveldown")
                                .requires(src -> src.hasPermission(2))
                                .executes(ctx -> levelDown(ctx.getSource())))
                        // ipo —— 发起上市（定价 + 开发行窗口）
                        .then(Commands.literal("ipo")
                                .requires(src -> src.hasPermission(0))
                                .executes(ctx -> ipo(ctx.getSource())))
                        // subscribe <shares> —— 认购新股
                        .then(Commands.literal("subscribe")
                                .requires(src -> src.hasPermission(0))
                                .then(Commands.argument("shares", IntegerArgumentType.integer(1))
                                        .executes(ctx -> subscribe(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "shares")))))
                        // info —— 公司信息
                        .then(Commands.literal("info")
                                .requires(src -> src.hasPermission(0))
                                .executes(ctx -> info(ctx.getSource())))
                        // shareholders —— 股权结构（确定性排序）
                        .then(Commands.literal("shareholders")
                                .requires(src -> src.hasPermission(0))
                                .executes(ctx -> shareholders(ctx.getSource())))
                        // transfer <uuid> <shares> —— 场外股权转让
                        .then(Commands.literal("transfer")
                                .requires(src -> src.hasPermission(0))
                                .then(Commands.argument("target", StringArgumentType.word())
                                        .then(Commands.argument("shares", IntegerArgumentType.integer(1))
                                                .executes(ctx -> transfer(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "target"),
                                                        IntegerArgumentType.getInteger(ctx, "shares"))))))
                        // report —— 最近财报
                        .then(Commands.literal("report")
                                .requires(src -> src.hasPermission(0))
                                .executes(ctx -> report(ctx.getSource())))
                        // dividend —— 立即触发一次分红（管理操作）
                        .then(Commands.literal("dividend")
                                .requires(src -> src.hasPermission(2))
                                .executes(ctx -> dividend(ctx.getSource())))
                        // delist —— 立即触发退市检查（管理操作）
                        .then(Commands.literal("delist")
                                .requires(src -> src.hasPermission(2))
                                .executes(ctx -> delist(ctx.getSource())))
                        // bind-area <x1 z1 x2 z2> —— 命令行绑定区域（与 CompanyAreaPacket 等效）
                        .then(Commands.literal("bind-area")
                                .requires(src -> src.hasPermission(0))
                                .then(Commands.argument("x1", IntegerArgumentType.integer())
                                        .then(Commands.argument("z1", IntegerArgumentType.integer())
                                                .then(Commands.argument("x2", IntegerArgumentType.integer())
                                                        .then(Commands.argument("z2", IntegerArgumentType.integer())
                                                                .executes(ctx -> bindArea(ctx.getSource(),
                                                                        IntegerArgumentType.getInteger(ctx, "x1"),
                                                                        IntegerArgumentType.getInteger(ctx, "z1"),
                                                                        IntegerArgumentType.getInteger(ctx, "x2"),
                                                                        IntegerArgumentType.getInteger(ctx, "z2"))))))))
                        // scan —— 手动触发一次 AssetScanner（管理操作）
                        .then(Commands.literal("scan")
                                .requires(src -> src.hasPermission(2))
                                .executes(ctx -> scan(ctx.getSource())))
                ));
    }

    // ===== t2 生命周期命令 =====

    private static int create(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer player = playerOf(src);
        if (player == null) return 0;
        if (!player.isCreative()) {
            fail(src, "创建公司需要创造模式");
            return 0;
        }
        MarketEngine engine = engineOf(src, player);
        if (engine == null) return 0;
        String name = StringArgumentType.getString(ctx, "name");
        if (name == null || name.isEmpty()) {
            fail(src, "公司名不能为空");
            return 0;
        }
        if (engine.getCompanyManager().hasCompany(player.getUUID())) {
            fail(src, "你已拥有一家公司");
            return 0;
        }
        if (engine.getCompanyManager().getByName(name) != null) {
            fail(src, "公司名 '" + name + "' 已被占用");
            return 0;
        }
        engine.getCompanyManager().createCompany(player.getUUID(), name);
        src.sendSuccess(() -> Component.literal("公司 '" + name + "' 已创建"), true);
        return 1;
    }

    private static int queryLevel(CommandSourceStack src) {
        ServerPlayer player = playerOf(src);
        if (player == null) return 0;
        CompanyData company = companyOf(src, player);
        if (company == null) return 0;
        src.sendSuccess(() -> Component.literal("公司等级: " + company.getLevel().getDisplayName()
                + " (Lv." + company.getLevel().getLevel() + ")"), false);
        return 1;
    }

    private static int setLevel(CommandSourceStack src, int level) {
        ServerPlayer player = playerOf(src);
        if (player == null) return 0;
        CompanyData company = companyOf(src, player);
        if (company == null) return 0;
        if (level < 1 || level > 5) {
            fail(src, "等级须在 1-5 之间");
            return 0;
        }
        company.setLevel(CompanyLevel.fromLevel(level));
        src.sendSuccess(() -> Component.literal("公司等级已调整为 " + level), true);
        return 1;
    }

    private static int levelUp(CommandSourceStack src) {
        ServerPlayer player = playerOf(src);
        if (player == null) return 0;
        CompanyData company = companyOf(src, player);
        if (company == null) return 0;
        MarketEngine engine = engineOf(src, player);
        if (engine == null) return 0;
        int target = company.getLevel().getLevel() + 1;
        if (target > 5) {
            fail(src, "已是最高等级（集团公司）");
            return 0;
        }
        CompanyLevel targetLevel = CompanyLevel.fromLevel(target);
        if (company.getNetAssetValue() < targetLevel.getMinMarketCap()) {
            fail(src, "升级失败：净资产 " + fmt(company.getNetAssetValue())
                    + " 未达 " + targetLevel.getDisplayName() + " 门槛 " + fmt(targetLevel.getMinMarketCap()));
            return 0;
        }
        if (engine.getCompanyManager().tryLevelUp(company)) {
            src.sendSuccess(() -> Component.literal("公司升级成功 → " + company.getLevel().getDisplayName()
                    + " (Lv." + company.getLevel().getLevel() + ")"), true);
            return 1;
        }
        fail(src, "升级失败：状态不允许升级（退市公司不可升级）");
        return 0;
    }

    private static int levelDown(CommandSourceStack src) {
        ServerPlayer player = playerOf(src);
        if (player == null) return 0;
        CompanyData company = companyOf(src, player);
        if (company == null) return 0;
        if (company.getLevel().getLevel() <= 1) {
            fail(src, "已是最低等级");
            return 0;
        }
        company.setLevel(CompanyLevel.fromLevel(company.getLevel().getLevel() - 1));
        src.sendSuccess(() -> Component.literal("公司等级已降低 → " + company.getLevel().getDisplayName()), true);
        return 1;
    }

    private static int info(CommandSourceStack src) {
        ServerPlayer player = playerOf(src);
        if (player == null) return 0;
        CompanyData company = companyOf(src, player);
        if (company == null) return 0;
        String msg = "公司: " + company.getName()
                + " | 状态: " + company.getStatus().name()
                + " | 等级: " + company.getLevel().getDisplayName() + " (Lv." + company.getLevel().getLevel() + ")"
                + " | 净资产: " + fmt(company.getNetAssetValue())
                + " | 近期利润: " + fmt(company.getRecentProfit())
                + " | 留存收益: " + fmt(company.getRetainedEarnings())
                + " | 总股本: " + company.getTotalShares()
                + " | IPO价: " + fmt(company.getIpoPrice())
                + " | 绑定容器: " + company.getBoundContainers().size()
                + " | 机器/多方块: " + company.getBoundProductionBlocks().size()
                + " | 存货估值: " + fmt(company.getInventoryValue())
                + " | 产线估值: " + fmt(company.getProductiveAssetValue())
                + " | 重置成本: " + fmt(company.getReplacementCost())
                + " | 利用率: " + fmt(company.getAssetUtilization() * 100) + "%"
                + " | 资产风险: " + fmt(company.getAssetRisk() * 100) + "%"
                + " | 日产出: " + fmt(company.getProductionOutputValue())
                + " | 日投入: " + fmt(company.getProductionInputValue())
                + " | 存货周转: " + fmt(company.getInventoryTurnover())
                + (company.getStockId() != null ? " | 代码: " + company.getStockId() : "");
        src.sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }

    private static int shareholders(CommandSourceStack src) {
        ServerPlayer player = playerOf(src);
        if (player == null) return 0;
        CompanyData company = companyOf(src, player);
        if (company == null) return 0;
        List<Map.Entry<UUID, Integer>> list = new ArrayList<>(company.getShareholders().entrySet());
        // 确定性排序（§5.3）：股数降序，同股数按 UUID 字典序
        list.sort(Comparator.comparingInt((Map.Entry<UUID, Integer> e) -> e.getValue()).reversed()
                .thenComparing(e -> e.getKey().toString()));
        StringBuilder sb = new StringBuilder("股权结构（" + company.getName() + "）:");
        if (list.isEmpty()) {
            sb.append(" 无股东记录（全为创始人持股，未入账）");
        } else {
            for (Map.Entry<UUID, Integer> e : list) {
                sb.append(" [").append(e.getKey()).append(" 持股 ").append(e.getValue()).append("]");
            }
        }
        src.sendSuccess(() -> Component.literal(sb.toString()), false);
        return 1;
    }

    private static int bindArea(CommandSourceStack src, int x1, int z1, int x2, int z2) {
        ServerPlayer player = playerOf(src);
        if (player == null) return 0;
        CompanyData company = companyOf(src, player);
        if (company == null) return 0;
        MarketEngine engine = engineOf(src, player);
        if (engine == null) return 0;
        ServerLevel level = player.serverLevel();
        int minX = Math.min(x1, x2);
        int maxX = Math.max(x1, x2);
        int minZ = Math.min(z1, z2);
        int maxZ = Math.max(z1, z2);
        int added = 0;
        int rejected = 0;
        int machines = 0;
        int machineRejected = 0;
        int maxContainers = company.getLevel().getMaxContainers();
        int maxProduction = Math.max(64, maxContainers * 16);
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = level.getMinBuildHeight(); y < level.getMaxBuildHeight(); y++) {
                    if (company.getBoundContainers().size() >= maxContainers
                            && company.getBoundProductionBlocks().size() >= maxProduction) break;
                    BlockPos pos = new BlockPos(x, y, z);
                    if (level.getBlockEntity(pos) instanceof BaseContainerBlockEntity) {
                        if (company.addBoundContainer(pos)) added++;
                        else rejected++;
                    }
                    if (AssetScanner.isProductionBlock(level, pos)) {
                        if (company.addBoundProductionBlock(pos)) machines++;
                        else machineRejected++;
                    }
                }
                if (company.getBoundContainers().size() >= maxContainers
                        && company.getBoundProductionBlocks().size() >= maxProduction) break;
            }
            if (company.getBoundContainers().size() >= maxContainers
                    && company.getBoundProductionBlocks().size() >= maxProduction) break;
        }
        final int addedF = added;
        final int rejectedF = rejected;
        final int machinesF = machines;
        final int machineRejectedF = machineRejected;
        src.sendSuccess(() -> Component.literal("绑定资产完成：新增 " + addedF + " 个容器，"
                + machinesF + " 个机器/多方块"
                + (rejectedF + machineRejectedF > 0
                ? "，拒绝 " + (rejectedF + machineRejectedF) + " 个（容器上限 " + maxContainers
                + "，产线上限 " + maxProduction + "）" : "")
                + "，当前容器 " + company.getBoundContainers().size() + "/" + maxContainers
                + "，机器 " + company.getBoundProductionBlocks().size() + "/" + maxProduction), true);
        return 1;
    }

    private static int scan(CommandSourceStack src) {
        ServerPlayer player = playerOf(src);
        if (player == null) return 0;
        CompanyData company = companyOf(src, player);
        if (company == null) return 0;
        MarketEngine engine = engineOf(src, player);
        if (engine == null) return 0;
        AssetScanner.scanCompany(player.serverLevel(), company, engine.getCommodityMarket());
        src.sendSuccess(() -> Component.literal("扫描完成：净资产 " + fmt(company.getNetAssetValue())
                + "，近期利润 " + fmt(company.getRecentProfit())
                + "，留存收益 " + fmt(company.getRetainedEarnings())), true);
        return 1;
    }

    // ===== IPO / 股权 / 财报 / 分红 / 退市命令（调用确定性引擎方法） =====

    private static int ipo(CommandSourceStack src) {
        ServerPlayer player = playerOf(src);
        if (player == null) return 0;
        CompanyData company = companyOf(src, player);
        if (company == null) return 0;
        MarketEngine engine = engineOf(src, player);
        if (engine == null) return 0;
        String err = engine.startIpo(company);
        if (err != null) {
            fail(src, "发起 IPO 失败：" + err);
            return 0;
        }
        src.sendSuccess(() -> Component.literal("IPO 已发起，发行 " + company.getFloatShares() + " 股 @ "
                + fmt(company.getIpoPrice()) + "，请认购"), true);
        return 1;
    }

    private static int subscribe(CommandSourceStack src, int shares) {
        ServerPlayer player = playerOf(src);
        if (player == null) return 0;
        CompanyData company = companyOf(src, player);
        if (company == null) return 0;
        MarketEngine engine = engineOf(src, player);
        if (engine == null) return 0;
        String err = engine.subscribeIpo(player, company, shares);
        if (err != null) {
            fail(src, "认购失败：" + err);
            return 0;
        }
        src.sendSuccess(() -> Component.literal("认购 " + shares + " 股成功"), true);
        return 1;
    }

    private static int transfer(CommandSourceStack src, String targetStr, int shares) {
        ServerPlayer player = playerOf(src);
        if (player == null) return 0;
        CompanyData company = companyOf(src, player);
        if (company == null) return 0;
        MarketEngine engine = engineOf(src, player);
        if (engine == null) return 0;
        UUID target;
        try {
            target = UUID.fromString(targetStr);
        } catch (IllegalArgumentException e) {
            fail(src, "接收方 UUID 无效");
            return 0;
        }
        String err = engine.transferShares(company, player.getUUID(), target, shares, player.serverLevel());
        if (err != null) {
            fail(src, "转让失败：" + err);
            return 0;
        }
        src.sendSuccess(() -> Component.literal("转让 " + shares + " 股成功"), true);
        return 1;
    }

    private static int report(CommandSourceStack src) {
        ServerPlayer player = playerOf(src);
        if (player == null) return 0;
        CompanyData company = companyOf(src, player);
        if (company == null) return 0;
        CompanyData.CompanyFinancialReport r = company.getLatestReport();
        if (r == null) {
            fail(src, "暂无财报（尚未到财报周期）");
            return 0;
        }
        src.sendSuccess(() -> Component.literal("最近财报(期 " + r.periodEnd() + "): 净资产 "
                + fmt(r.netAssetValue()) + " | 净利 " + fmt(r.recentProfit())
                + " | 留存 " + fmt(r.retainedEarnings()) + " | EPS " + fmt(r.eps())
                + " | ROE " + fmt(r.roe()) + " | 市值 " + fmt(r.marketCap())), false);
        return 1;
    }

    private static int dividend(CommandSourceStack src) {
        ServerPlayer player = playerOf(src);
        if (player == null) return 0;
        CompanyData company = companyOf(src, player);
        if (company == null) return 0;
        MarketEngine engine = engineOf(src, player);
        if (engine == null) return 0;
        engine.payDividend(company, player.serverLevel());
        src.sendSuccess(() -> Component.literal("分红完成：留存收益 " + fmt(company.getRetainedEarnings())
                + "，累计分红 " + fmt(company.getDividendPaidTotal())), true);
        return 1;
    }

    private static int delist(CommandSourceStack src) {
        ServerPlayer player = playerOf(src);
        if (player == null) return 0;
        CompanyData company = companyOf(src, player);
        if (company == null) return 0;
        MarketEngine engine = engineOf(src, player);
        if (engine == null) return 0;
        boolean triggered = DelistingMonitor.evaluate(company, engine, player.serverLevel());
        if (triggered) {
            src.sendSuccess(() -> Component.literal("公司已退市（股东已回购清偿）"), true);
            return 1;
        }
        fail(src, "未触发退市条件（需 LISTED 且净资产/股价/持续经营不达标）");
        return 0;
    }

    // ===== 辅助 =====

    private static ServerPlayer playerOf(CommandSourceStack src) {
        try {
            return src.getPlayerOrException();
        } catch (Exception e) {
            fail(src, "该命令需要由玩家执行");
            return null;
        }
    }

    private static MarketEngine engineOf(CommandSourceStack src, ServerPlayer player) {
        MarketEngine engine = MarketAttachment.getEngine(player.serverLevel());
        if (engine == null) fail(src, "金融引擎未初始化");
        return engine;
    }

    private static CompanyData companyOf(CommandSourceStack src, ServerPlayer player) {
        MarketEngine engine = engineOf(src, player);
        if (engine == null) return null;
        CompanyData company = engine.getCompanyManager().getByOwner(player.getUUID());
        if (company == null) fail(src, "你还没有公司，请先创建");
        return company;
    }

    private static void fail(CommandSourceStack src, String msg) {
        src.sendFailure(Component.literal(msg));
    }

    private static String fmt(double v) {
        return String.format("%.2f", v);
    }
}
