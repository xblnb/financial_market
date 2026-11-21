package com.example.financialmarket.engine.futures.czce;

import com.example.financialmarket.attachment.MarketAttachment;
import com.example.financialmarket.engine.MarketEngine;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/** /fm option chain &lt;contract&gt; — SABR option chain display. */
public final class CzceOptionCommand {

    private CzceOptionCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("fm")
                .then(Commands.literal("option")
                        .then(Commands.literal("chain")
                                .then(Commands.argument("code", StringArgumentType.word())
                                        .executes(ctx -> chain(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "code")))))));
    }

    private static int chain(CommandSourceStack source, String code) {
        MarketEngine engine = MarketAttachment.getEngine(source.getLevel());
        CzceOptionDesk desk = engine == null ? null : engine.getCzceOptions();
        if (desk == null) {
            source.sendFailure(Component.literal("期权台未初始化"));
            return 0;
        }
        String c = code.toUpperCase();
        source.sendSuccess(() -> Component.literal(desk.describe(c)), false);
        int n = 0;
        for (CzceOptionMarket.Quote q : desk.chain(c, new double[]{30, 60})) {
            if (n++ >= 20) {
                break;
            }
            String line = String.format(Locale.ROOT,
                    "%s %s K=%.2f T=%dd IV=%.2f%% P=%.4f delta=%.3f vega=%.4f",
                    q.underlying(), q.type(), q.strike(), (int) q.expiryDays(),
                    q.greeks().impliedVol() * 100.0, q.greeks().price(),
                    q.greeks().delta(), q.greeks().vega());
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }
}
