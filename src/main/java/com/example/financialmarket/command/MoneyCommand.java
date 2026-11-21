package com.example.financialmarket.command;

import com.example.financialmarket.attachment.MarketAttachment;
import com.example.financialmarket.attachment.PlayerEconomy;
import com.example.financialmarket.engine.MarketEngine;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * 用户测试最小命令（captain 紧急交办；非核心经济语义，仅测试辅助）：
 *
 * <ul>
 *   <li>{@code /fmarket money add <金额>} —— 给执行者加现金（{@code PlayerEconomy.addCash}），
 *       供用户测试快速获得可交易资金；未持卡时顺带提示办卡入口（银行平板「办卡」或 /fmarket card issue）。</li>
 *   <li>{@code /fmarket card issue [bankId]} —— 命令行办理储蓄卡（绕过平板 UI；默认 BANK_A，
 *       复用 {@code BankCardService.issueCard}：确定性、Luhn 合法、每玩家 1 张）。</li>
 * </ul>
 *
 * <p>权限：根 {@code fmarket} 要求 {@code hasPermission(2)}（与既有 CompanyCommand/P2Command 一致）。
 * 全部确定性、无随机、不改货币系统公式。注：money add 直接增记玩家现金（测试便利），
 * 未走央行 M0 记账——仅限测试/开发使用，正式玩法引导不应依赖。
 */
public class MoneyCommand {
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("fmarket")
                .requires(src -> src.hasPermission(2))
                // /fmarket money add <amount>
                .then(Commands.literal("money")
                        .then(Commands.literal("add")
                                .then(Commands.argument("amount", DoubleArgumentType.doubleArg(1.0))
                                        .executes(ctx -> moneyAdd(ctx, DoubleArgumentType.getDouble(ctx, "amount"))))))
                // /fmarket card issue [bankId]
                .then(Commands.literal("card")
                        .then(Commands.literal("issue")
                                .executes(ctx -> cardIssue(ctx, null))
                                .then(Commands.argument("bankId", StringArgumentType.word())
                                        .executes(ctx -> cardIssue(ctx, StringArgumentType.getString(ctx, "bankId")))))));
    }

    /** /fmarket money add <amount>：给执行者加现金（测试用，确定性、无随机）。 */
    private static int moneyAdd(CommandContext<CommandSourceStack> ctx, double amount) throws CommandSyntaxException {
        if (amount <= 0) {
            ctx.getSource().sendFailure(Component.literal("金额须为正数"));
            return 0;
        }
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        PlayerEconomy eco = PlayerEconomy.get(player);
        if (eco == null) {
            ctx.getSource().sendFailure(Component.literal("玩家经济账户不可用"));
            return 0;
        }
        eco.addCash(amount);
        MarketEngine engine = MarketAttachment.getEngine(player.serverLevel());
        StringBuilder msg = new StringBuilder("✅ 已充值 " + fmt(amount) + " 元，当前现金 "
                + fmt(eco.getCash()));
        if (engine != null && engine.isBankingEnabled() && !engine.hasBankCard(player.getUUID())) {
            msg.append("（提示：您尚未办理储蓄卡，请用银行平板「办卡」或 /fmarket card issue 办卡后即可交易）");
        }
        ctx.getSource().sendSuccess(() -> Component.literal(msg.toString()), true);
        return 1;
    }

    /** /fmarket card issue [bankId]：命令行办储蓄卡（确定性；复用 BankCardService.issueCard）。 */
    private static int cardIssue(CommandContext<CommandSourceStack> ctx, String bankId) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        MarketEngine engine = MarketAttachment.getEngine(player.serverLevel());
        if (engine == null) {
            ctx.getSource().sendFailure(Component.literal("金融引擎未初始化"));
            return 0;
        }
        if (!engine.isBankingEnabled()) {
            ctx.getSource().sendFailure(Component.literal("银行系统未开启，无法办卡"));
            return 0;
        }
        String bid = (bankId == null || bankId.isEmpty()) ? "BANK_A" : bankId;
        if (!engine.getBankCardService().isKnownBank(bid)) {
            ctx.getSource().sendFailure(Component.literal("未知银行 " + bid
                    + "（默认 BANK_A / BANK_B / BANK_C / BANK_D）"));
            return 0;
        }
        if (engine.hasBankCard(player.getUUID())) {
            ctx.getSource().sendSuccess(() -> Component.literal("您已持有储蓄卡（每玩家限 1 张），无需重复办理"), true);
            return 1;
        }
        String number = engine.getBankCardService().issueCard(player.getUUID(), bid, engine.getTickCounter());
        if (number != null) {
            ctx.getSource().sendSuccess(() -> Component.literal("✅ 储蓄卡开卡成功：卡号 " + number
                    + "（开户行 " + bid + "），可开始交易"), true);
            return 1;
        }
        ctx.getSource().sendFailure(Component.literal("开卡失败（卡号碰撞重试耗尽）"));
        return 0;
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", Math.round(v * 100.0) / 100.0);
    }
}
