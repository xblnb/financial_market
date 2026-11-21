package com.example.financialmarket.command;

import com.example.financialmarket.FinancialMarket;
import com.example.financialmarket.engine.role.RoleSystem;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * 服务端上层建筑角色任命命令（t2/SR-1）。
 *
 * <p>{@code /fmarket role assign <player> <role>} —— 任命玩家为央行行长/财政部长；
 * {@code /fmarket role revoke <player>} —— 撤销玩家管理角色，降回散户。
 *
 * <p>服务端为唯一任命者（需 op 2 级，对齐现有 {@code command/CompanyCommand.java}）；
 * 客户端只读展示当前角色。角色写入服务端权威表 {@link RoleSystem}。
 *
 * <p>通过 {@link EventBusSubscriber} 自注册，无需改动 {@code FinancialMarket#Events}。
 */
@EventBusSubscriber(modid = FinancialMarket.MODID)
public class RoleCommand {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("fmarket")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("role")
                        .then(Commands.literal("assign")
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .then(Commands.argument("role", StringArgumentType.word())
                                                .executes(ctx -> assign(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "player"),
                                                        StringArgumentType.getString(ctx, "role"))))))
                        .then(Commands.literal("revoke")
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .executes(ctx -> revoke(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "player")))))));
    }

    private static int assign(CommandSourceStack source, String playerName, String roleName) {
        RoleSystem.Role role = RoleSystem.Role.fromName(roleName);
        if (role == null) {
            source.sendFailure(Component.literal("未知角色 '" + roleName
                    + "'，可用：RETAIL / CENTRAL_BANKER / FINANCE_MINISTER"));
            return 0;
        }
        ServerPlayer target = resolvePlayer(source, playerName);
        if (target == null) {
            source.sendFailure(Component.literal("找不到玩家 '" + playerName + "'"));
            return 0;
        }
        RoleSystem.getInstance().appoint(target.getUUID(), role);
        source.sendSuccess(() -> Component.literal(
                "✔ 已任命 " + target.getName().getString() + " 为 " + role.name()), true);
        return 1;
    }

    private static int revoke(CommandSourceStack source, String playerName) {
        ServerPlayer target = resolvePlayer(source, playerName);
        if (target == null) {
            source.sendFailure(Component.literal("找不到玩家 '" + playerName + "'"));
            return 0;
        }
        RoleSystem.getInstance().revoke(target.getUUID());
        source.sendSuccess(() -> Component.literal(
                "✔ 已撤销 " + target.getName().getString() + " 的管理角色，降回 RETAIL"), true);
        return 1;
    }

    /** 按玩家名解析在线玩家。 */
    private static ServerPlayer resolvePlayer(CommandSourceStack source, String name) {
        if (source.getServer() == null) return null;
        return source.getServer().getPlayerList().getPlayerByName(name);
    }
}
