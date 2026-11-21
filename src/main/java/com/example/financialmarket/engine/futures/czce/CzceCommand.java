package com.example.financialmarket.engine.futures.czce;

import com.example.financialmarket.attachment.MarketAttachment;
import com.example.financialmarket.engine.MarketEngine;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Player-facing CZCE commands.
 *
 * <pre>
 * /fm czce status
 * /fm czce list
 * /fm czce deposit 100000
 * /fm czce withdraw 50000
 * /fm czce open SR2505 long 2
 * /fm czce close SR2505 long 1
 * </pre>
 */
public final class CzceCommand {

    private CzceCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("fm")
                .then(Commands.literal("czce")
                        .then(Commands.literal("status").executes(ctx -> status(ctx.getSource())))
                        .then(Commands.literal("list").executes(ctx -> list(ctx.getSource())))
                        .then(Commands.literal("deposit")
                                .then(Commands.argument("amount", DoubleArgumentType.doubleArg(1))
                                        .executes(ctx -> deposit(ctx.getSource(),
                                                DoubleArgumentType.getDouble(ctx, "amount")))))
                        .then(Commands.literal("withdraw")
                                .then(Commands.argument("amount", DoubleArgumentType.doubleArg(1))
                                        .executes(ctx -> withdraw(ctx.getSource(),
                                                DoubleArgumentType.getDouble(ctx, "amount")))))
                        .then(Commands.literal("open")
                                .then(Commands.argument("code", StringArgumentType.word())
                                        .then(Commands.argument("side", StringArgumentType.word())
                                                .then(Commands.argument("lots", IntegerArgumentType.integer(1))
                                                        .executes(ctx -> open(ctx.getSource(),
                                                                StringArgumentType.getString(ctx, "code"),
                                                                StringArgumentType.getString(ctx, "side"),
                                                                IntegerArgumentType.getInteger(ctx, "lots")))))))
                        .then(Commands.literal("close")
                                .then(Commands.argument("code", StringArgumentType.word())
                                        .then(Commands.argument("side", StringArgumentType.word())
                                                .then(Commands.argument("lots", IntegerArgumentType.integer(1))
                                                        .executes(ctx -> close(ctx.getSource(),
                                                                StringArgumentType.getString(ctx, "code"),
                                                                StringArgumentType.getString(ctx, "side"),
                                                                IntegerArgumentType.getInteger(ctx, "lots")))))))));
    }

    private static CzceFuturesMarket market(CommandSourceStack source) {
        MarketEngine engine = MarketAttachment.getEngine(source.getLevel());
        return engine == null ? null : engine.getCzceFutures();
    }

    private static int status(CommandSourceStack source) {
        CzceFuturesMarket m = market(source);
        if (m == null) {
            source.sendFailure(Component.literal("CZCE 市场未初始化"));
            return 0;
        }
        ServerPlayer player = source.getPlayer();
        String cash = player == null ? "" : String.format(" cash=%.2f", m.cash(player.getUUID()));
        source.sendSuccess(() -> Component.literal("CZCE tick=" + m.tick()
                + " session=" + m.sessionOpen() + " contracts=" + m.contracts().size()
                + " products=" + m.products().size() + cash), false);
        return 1;
    }

    private static int list(CommandSourceStack source) {
        CzceFuturesMarket m = market(source);
        if (m == null) {
            source.sendFailure(Component.literal("CZCE 市场未初始化"));
            return 0;
        }
        int n = 0;
        for (String line : m.describe()) {
            if (n++ >= 24) {
                break;
            }
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }

    private static int deposit(CommandSourceStack source, double amount) {
        CzceFuturesMarket m = market(source);
        ServerPlayer player = source.getPlayer();
        if (m == null || player == null) {
            source.sendFailure(Component.literal("CZCE 市场/玩家不可用"));
            return 0;
        }
        String error = m.deposit(player, amount);
        if (error != null) {
            source.sendFailure(Component.literal(error));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("已划入 CZCE 账户 " + amount), false);
        return 1;
    }

    private static int withdraw(CommandSourceStack source, double amount) {
        CzceFuturesMarket m = market(source);
        ServerPlayer player = source.getPlayer();
        if (m == null || player == null) {
            source.sendFailure(Component.literal("CZCE 市场/玩家不可用"));
            return 0;
        }
        String error = m.withdraw(player, amount);
        if (error != null) {
            source.sendFailure(Component.literal(error));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("已划出 CZCE 账户 " + amount), false);
        return 1;
    }

    private static int open(CommandSourceStack source, String code, String side, int lots) {
        CzceFuturesMarket m = market(source);
        ServerPlayer player = source.getPlayer();
        if (m == null || player == null) {
            source.sendFailure(Component.literal("CZCE 市场/玩家不可用"));
            return 0;
        }
        String error = m.open(player, code.toUpperCase(), side, lots);
        if (error != null) {
            source.sendFailure(Component.literal(error));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("CZCE 开仓 " + code + " " + side + " " + lots), false);
        return 1;
    }

    private static int close(CommandSourceStack source, String code, String side, int lots) {
        CzceFuturesMarket m = market(source);
        ServerPlayer player = source.getPlayer();
        if (m == null || player == null) {
            source.sendFailure(Component.literal("CZCE 市场/玩家不可用"));
            return 0;
        }
        String error = m.close(player, code.toUpperCase(), side, lots);
        if (error != null) {
            source.sendFailure(Component.literal(error));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("CZCE 平仓 " + code + " " + side + " " + lots), false);
        return 1;
    }
}
