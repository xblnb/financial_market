package com.example.financialmarket.network.packets;

import com.example.financialmarket.attachment.MarketAttachment;
import com.example.financialmarket.engine.role.RoleSystem;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 政府操作意图包（t2/SR-2 客户端/服务端权威）。
 *
 * <p>客户端仅提交<strong>意图</strong>（{@code action}/{@code param}），服务端为唯一权威：
 * {@code handle} 在服务端按 {@code action} 归类到对应角色，并用 {@link RoleSystem}
 * 校验玩家角色后才执行。取代原泛 {@code hasPermissions(2)} 放行 ——
 * 现在只有被任命为 {@code CENTRAL_BANKER} 的玩家能执行货币政策
 * （{@code set_rate}/{@code set_reserve}），只有 {@code FINANCE_MINISTER} 能执行财政操作
 * （{@code issue_bond}/{@code spend}）；散户 {@code RETAIL} 无任何政策权限。
 *
 * <p>不新增字段：服务端从 {@code context.player()} 取 UUID，旧客户端（发 {@code action}/{@code param}）
 * 完全兼容，行为从「op 放行」变为「角色校验」。
 */
public record GovernmentActionPacket(String action, double param) implements CustomPacketPayload {
    public static final Type<GovernmentActionPacket> ID = new Type<>(ResourceLocation.fromNamespaceAndPath("financialmarket", "government_action"));
    @Override public Type<? extends CustomPacketPayload> type() { return ID; }

    public static final StreamCodec<FriendlyByteBuf, GovernmentActionPacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, GovernmentActionPacket::action,
            ByteBufCodecs.DOUBLE, GovernmentActionPacket::param,
            GovernmentActionPacket::new);

    public void send() { PacketDistributor.sendToServer(this); }

    /** 按动作归类所需角色；未知动作返回 {@code null}（不允许执行）。 */
    public static RoleSystem.Role actionRequiredRole(String action) {
        if (action == null) return null;
        return switch (action) {
            case "set_rate", "set_reserve" -> RoleSystem.Role.CENTRAL_BANKER;   // 货币政策
            case "issue_bond", "spend" -> RoleSystem.Role.FINANCE_MINISTER;     // 财政操作
            default -> null;
        };
    }

    public static void handle(GovernmentActionPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            // 服务端唯一权威：按动作归类所需角色，用服务端角色表校验（替代 hasPermissions(2)）
            RoleSystem.Role required = actionRequiredRole(packet.action());
            if (required == null) {
                reject(context, "未知或不受支持的操作: " + packet.action());
                return;
            }
            if (!(context.player() instanceof ServerPlayer sp)) {
                reject(context, "非服务端玩家无法执行政府操作");
                return;
            }
            RoleSystem roleSystem = RoleSystem.getInstance();
            if (!roleSystem.hasRole(sp.getUUID(), required)) {
                reject(context, "权限不足：「" + packet.action() + "」需要角色 "
                        + required.name() + "（当前: " + roleSystem.get(sp.getUUID()).name() + "）");
                return;
            }

            var engine = MarketAttachment.getEngine(sp.serverLevel());
            if (engine == null) {
                reject(context, "引擎未就绪");
                return;
            }
            switch (packet.action()) {
                case "issue_bond" -> engine.getGovernment().issueBond(packet.param(), 0.03, 30);
                case "spend" -> engine.getGovernment().spend(packet.param());
                case "set_rate" -> engine.getCentralBank().setDiscountRate(packet.param());
                case "set_reserve" -> engine.getCentralBank().setReserveRequirement(packet.param());
                default -> reject(context, "未知操作: " + packet.action());
            }
        });
    }

    private static void reject(IPayloadContext context, String reason) {
        if (context.player() instanceof ServerPlayer sp) {
            sp.displayClientMessage(Component.literal("⛔ " + reason), false);
        }
    }
}
