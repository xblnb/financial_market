package com.example.financialmarket.engine.macro.world;

import com.example.financialmarket.attachment.MarketAttachment;
import com.example.financialmarket.engine.MarketEngine;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

/**
 * M4 diagnostics: /fm macro status|explain|unmapped|profiles.
 * Shows the bottom-up economic state and the audit chain (event -> sector -> impact).
 */
public final class WorldMacroCommand {

    private WorldMacroCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("fm")
                .then(Commands.literal("macro")
                        .then(Commands.literal("status").executes(ctx -> status(ctx.getSource())))
                        .then(Commands.literal("explain")
                                .then(Commands.argument("filter", StringArgumentType.greedyString())
                                        .executes(ctx -> explain(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "filter")))))
                        .then(Commands.literal("unmapped").executes(ctx -> unmapped(ctx.getSource())))
                        .then(Commands.literal("profiles").executes(ctx -> profiles(ctx.getSource())))));
    }

    private static WorldMacroEngine engine(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        MarketEngine market = MarketAttachment.getEngine(level);
        return market == null ? null : market.getWorldMacro();
    }

    private static int status(CommandSourceStack source) {
        WorldMacroEngine macro = engine(source);
        if (macro == null) {
            source.sendFailure(Component.literal("世界宏观引擎未初始化"));
            return 0;
        }
        WorldMacroSnapshot s = macro.snapshot();
        source.sendSuccess(() -> Component.literal(
                "世界宏观 day=" + s.day() + " production=" + fmt(s.productionIndex())
                        + " capacity=" + fmt(s.capacityUtil()) + " ppi=" + fmt(s.ppi())
                        + " cpi=" + fmt(s.cpi()) + " risk=" + fmt(s.eventRisk())
                        + " novelty=" + fmt(s.novelty())), false);
        source.sendSuccess(() -> Component.literal(
                "事件=" + s.eventsProcessed() + " 丢弃=" + s.eventsDropped()
                        + " 未映射=" + s.unmappedResources() + " 画像=" + s.resourceProfiles()
                        + " 待处理行动=" + macro.pendingActions()), false);
        for (var e : s.sectors().entrySet()) {
            var v = e.getValue();
            source.sendSuccess(() -> Component.literal("  [" + e.getKey() + "] output="
                    + fmt(v.outputIndex()) + " capex=" + fmt(v.capex()) + " rnd=" + fmt(v.rnd())
                    + " risk=" + fmt(v.risk()) + " margin=" + fmt(v.margin())), false);
        }
        return 1;
    }

    private static int explain(CommandSourceStack source, String filter) {
        WorldMacroEngine macro = engine(source);
        if (macro == null) {
            source.sendFailure(Component.literal("世界宏观引擎未初始化"));
            return 0;
        }
        var entries = macro.ledger().explain(filter, 12);
        if (entries.isEmpty()) {
            source.sendSuccess(() -> Component.literal("没有匹配 '" + filter + "' 的事件"), false);
            return 0;
        }
        for (var e : entries) {
            source.sendSuccess(() -> Component.literal("t=" + e.tick() + " " + e.type()
                    + " " + e.companyId() + "/" + e.sector() + " VA=" + fmt(e.valueAdded())
                    + " novelty=" + fmt(e.novelty()) + " impact=" + fmt(e.impact())
                    + " <- " + e.provenance()), false);
        }
        return 1;
    }

    private static int unmapped(CommandSourceStack source) {
        WorldMacroEngine macro = engine(source);
        if (macro == null) {
            source.sendFailure(Component.literal("世界宏观引擎未初始化"));
            return 0;
        }
        var ids = macro.registry().unmapped();
        if (ids.isEmpty()) {
            source.sendSuccess(() -> Component.literal("没有未映射资源"), false);
            return 1;
        }
        int n = 0;
        for (String id : ids) {
            if (n++ >= 20) {
                break;
            }
            source.sendSuccess(() -> Component.literal("  " + id), false);
        }
        source.sendSuccess(() -> Component.literal("共 " + ids.size() + " 个未映射资源（低权重 OTHER）"), false);
        return 1;
    }

    private static int profiles(CommandSourceStack source) {
        WorldMacroEngine macro = engine(source);
        if (macro == null) {
            source.sendFailure(Component.literal("世界宏观引擎未初始化"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("画像=" + macro.registry().size()
                + " 配方边=" + macro.recipeGraph().size()), false);
        for (String line : macro.registry().describe(15)) {
            source.sendSuccess(() -> Component.literal("  " + line), false);
        }
        return 1;
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.3f", v);
    }
}
