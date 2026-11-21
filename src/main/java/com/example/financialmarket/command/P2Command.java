package com.example.financialmarket.command;

import com.example.financialmarket.attachment.MarketAttachment;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * P2 微观结构运行时开关命令：/fmarket p2 on|off（F2 修复）。
 *
 * <p>提供真实引擎路径下「关闭/开启 P2」的命令入口，使 P2 因子隔离开关可在运行时触达：
 * {@code setP2Enabled(false)} 同时关闭内生定价（委托 {@code MultiAgentManager.setBehaviorParams}）
 * 与 ZI/Hawkes 订单流，并使 OrderBook 撮合回退 P0 纯随机滑点语义 → 「关 P2 → 与 P0/P1 基线
 * 逐位一致」可在真实引擎代码路径验证（而不仅是 t7 的镜像确定性链）。
 */
public class P2Command {
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("fmarket")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("p2")
                        .then(Commands.literal("on").executes(ctx -> setP2(ctx, true)))
                        .then(Commands.literal("off").executes(ctx -> setP2(ctx, false)))));
    }

    private static int setP2(CommandContext<CommandSourceStack> ctx, boolean enabled) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        var engine = MarketAttachment.getEngine(player.serverLevel());
        if (engine == null) {
            ctx.getSource().sendFailure(Component.literal("金融引擎未初始化"));
            return 0;
        }
        engine.setP2Enabled(enabled);
        ctx.getSource().sendSuccess(() -> Component.literal("P2 微观结构已"
                + (enabled ? "开启" : "关闭") + " (endogenous=" + engine.isEndogenousEnabled()
                + ", orderFlow=" + engine.isOrderFlowEnabled()
                + ", P0回退撮合=" + !engine.isP2Enabled() + ")"), true);
        return 1;
    }
}
