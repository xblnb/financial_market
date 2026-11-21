package com.example.financialmarket.command;

import com.example.financialmarket.attachment.MarketAttachment;
import com.example.financialmarket.engine.MarketEngine;
import com.example.financialmarket.engine.banking.BankCard;
import com.example.financialmarket.engine.banking.PaymentService;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/** Player-facing bank card / payment commands: /bank ... */
public final class BankCommand {

    private BankCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("bank")
                .requires(src -> src.hasPermission(0))
                .then(Commands.literal("issue")
                        .then(Commands.argument("bankId", StringArgumentType.word())
                                .executes(ctx -> issue(ctx, StringArgumentType.getString(ctx, "bankId")))))
                .then(Commands.literal("info").executes(BankCommand::info))
                .then(Commands.literal("pin")
                        .then(Commands.argument("pin", StringArgumentType.word())
                                .executes(ctx -> pin(ctx, StringArgumentType.getString(ctx, "pin")))))
                .then(Commands.literal("freeze").executes(ctx -> freeze(ctx, true)))
                .then(Commands.literal("unfreeze").executes(ctx -> freeze(ctx, false)))
                .then(Commands.literal("transfer")
                        .then(Commands.argument("toCard", StringArgumentType.word())
                                .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0.01))
                                        .then(Commands.argument("pin", StringArgumentType.word())
                                                .executes(ctx -> pay(ctx, false, ""))
                                                .then(Commands.argument("memo", StringArgumentType.greedyString())
                                                        .executes(ctx -> pay(ctx, false,
                                                                StringArgumentType.getString(ctx, "memo"))))))))
                .then(Commands.literal("pay")
                        .then(Commands.argument("toCard", StringArgumentType.word())
                                .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0.01))
                                        .then(Commands.argument("pin", StringArgumentType.word())
                                                .executes(ctx -> pay(ctx, true, ""))
                                                .then(Commands.argument("memo", StringArgumentType.greedyString())
                                                        .executes(ctx -> pay(ctx, true,
                                                                StringArgumentType.getString(ctx, "memo"))))))))
                .then(Commands.literal("statement").executes(BankCommand::statement)));
    }

    private static int issue(CommandContext<CommandSourceStack> ctx, String bankId) {
        ServerPlayer p = ctx.getSource().getPlayer();
        MarketEngine eng = p == null ? null : MarketAttachment.getEngine(p.serverLevel());
        if (p == null || eng == null) return fail(ctx, "引擎未初始化");
        if (!eng.isBankingEnabled()) return fail(ctx, "银行系统未开启");
        if (!eng.getBankCardService().isKnownBank(bankId)) return fail(ctx, "未知银行");
        if (eng.hasBankCard(p.getUUID())) {
            ctx.getSource().sendSuccess(() -> Component.literal("您已持有储蓄卡"), false);
            return 1;
        }
        String number = eng.getBankCardService().issueCard(p.getUUID(), bankId, eng.getTickCounter());
        if (number == null) return fail(ctx, "开卡失败");
        ctx.getSource().sendSuccess(() -> Component.literal("开卡成功：" + number
                + "，请用 /bank pin <4-6位密码> 设置支付密码"), false);
        return 1;
    }

    private static int info(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer p = ctx.getSource().getPlayer();
        MarketEngine eng = p == null ? null : MarketAttachment.getEngine(p.serverLevel());
        if (p == null || eng == null) return fail(ctx, "引擎未初始化");
        BankCard c = eng.getBankCardService().getCardOf(p.getUUID());
        if (c == null) return fail(ctx, "尚未办理储蓄卡，/bank issue BANK_A");
        double bal = eng.getBankManager().getBank(c.getBankId()) == null ? 0
                : eng.getBankManager().getBank(c.getBankId()).getDepositBalance(p.getUUID());
        ctx.getSource().sendSuccess(() -> Component.literal(
                "卡 " + com.example.financialmarket.engine.banking.BankCardService.mask(c.getCardNumber())
                        + " | " + c.getBankId()
                        + " | " + (c.isFrozen() ? "冻结" : "正常")
                        + " | PIN " + (c.hasPin() ? "已设置" : "未设置")
                        + " | 活期 " + fmt(bal)
                        + " | 今日已用 " + fmt(c.getSpentToday()) + "/" + fmt(c.getDailyLimit())), false);
        return 1;
    }

    private static int pin(CommandContext<CommandSourceStack> ctx, String pin) {
        ServerPlayer p = ctx.getSource().getPlayer();
        MarketEngine eng = p == null ? null : MarketAttachment.getEngine(p.serverLevel());
        if (p == null || eng == null) return fail(ctx, "引擎未初始化");
        PaymentService.Receipt r = eng.getPaymentService().setPin(p, pin);
        ctx.getSource().sendSuccess(() -> Component.literal(r.message()), false);
        return r.success() ? 1 : 0;
    }

    private static int freeze(CommandContext<CommandSourceStack> ctx, boolean frozen) {
        ServerPlayer p = ctx.getSource().getPlayer();
        MarketEngine eng = p == null ? null : MarketAttachment.getEngine(p.serverLevel());
        if (p == null || eng == null) return fail(ctx, "引擎未初始化");
        PaymentService.Receipt r = eng.getPaymentService().freeze(p, frozen);
        ctx.getSource().sendSuccess(() -> Component.literal(r.message()), false);
        return r.success() ? 1 : 0;
    }

    private static int pay(CommandContext<CommandSourceStack> ctx, boolean merchant, String memo) {
        ServerPlayer p = ctx.getSource().getPlayer();
        MarketEngine eng = p == null ? null : MarketAttachment.getEngine(p.serverLevel());
        if (p == null || eng == null) return fail(ctx, "引擎未初始化");
        String to = StringArgumentType.getString(ctx, "toCard");
        double amount = DoubleArgumentType.getDouble(ctx, "amount");
        String pin = StringArgumentType.getString(ctx, "pin");
        PaymentService.Receipt r = merchant
                ? eng.getPaymentService().pay(p, to, amount, memo, pin, eng.getTickCounter())
                : eng.getPaymentService().transfer(p, to, amount, memo, pin, eng.getTickCounter(), false);
        ctx.getSource().sendSuccess(() -> Component.literal(r.message()), false);
        return r.success() ? 1 : 0;
    }

    private static int statement(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer p = ctx.getSource().getPlayer();
        MarketEngine eng = p == null ? null : MarketAttachment.getEngine(p.serverLevel());
        if (p == null || eng == null) return fail(ctx, "引擎未初始化");
        List<PaymentService.PaymentTransaction> list = eng.getPaymentService().statement(p.getUUID(), 10);
        if (list.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("暂无交易流水"), false);
            return 1;
        }
        StringBuilder sb = new StringBuilder("最近流水：");
        for (PaymentService.PaymentTransaction tx : list) {
            sb.append("\n#").append(tx.id()).append(" ")
                    .append(tx.channel()).append(" ").append(fmt(tx.amount()))
                    .append(tx.memo().isBlank() ? "" : " " + tx.memo());
        }
        String msg = sb.toString();
        ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }

    private static int fail(CommandContext<CommandSourceStack> ctx, String msg) {
        ctx.getSource().sendFailure(Component.literal(msg));
        return 0;
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }
}
