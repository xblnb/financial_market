package com.example.financialmarket.network.packets;

import com.example.financialmarket.attachment.MarketAttachment;
import com.example.financialmarket.company.AssetScanner;
import com.example.financialmarket.company.CompanyData;
import com.example.financialmarket.company.CompanyLevel;
import com.example.financialmarket.company.DelistingMonitor;
import com.example.financialmarket.engine.MarketEngine;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 玩家公司动作包（COMPANY_SPEC §9）。action 扩展：create / levelup / leveldown / list / ipo /
 * subscribe / transfer / shareholders。服务端主线程 enqueueWork 内确定性分派。
 */
public record CompanyActionPacket(String action, String param) implements CustomPacketPayload {
    public static final Type<CompanyActionPacket> ID = new Type<>(ResourceLocation.fromNamespaceAndPath("financialmarket", "company_action"));
    @Override public Type<? extends CustomPacketPayload> type() { return ID; }

    public static final StreamCodec<FriendlyByteBuf, CompanyActionPacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, CompanyActionPacket::action,
            ByteBufCodecs.STRING_UTF8, CompanyActionPacket::param,
            CompanyActionPacket::new
    );

    public void send() { PacketDistributor.sendToServer(this); }

    public static void handle(CompanyActionPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer sp) {
                MarketEngine engine = MarketAttachment.getEngine(sp.serverLevel());
                if (engine == null) return;
                var cm = engine.getCompanyManager();
                CompanyData company = cm.getByOwner(sp.getUUID());
                if (company == null) {
                    if ("create".equals(packet.action())) {
                        cm.createCompany(sp.getUUID(), packet.param());
                        sp.displayClientMessage(Component.literal("公司 '" + packet.param() + "' 已创建"), false);
                    }
                    return;
                }
                String feedback = null;
                boolean syncBack = false;
                switch (packet.action()) {
                    case "levelup" -> {
                        // §3.4：升级走确定性门槛（净资产 >= 目标 minMarketCap、非退市）
                        if (cm.tryLevelUp(company)) {
                            feedback = "公司升级成功 → " + company.getLevel().getDisplayName()
                                    + " (Lv." + company.getLevel().getLevel() + ")";
                        } else {
                            feedback = "升级失败：净资产未达门槛或状态不允许";
                        }
                    }
                    case "leveldown" -> {
                        if (sp.isCreative() && company.getLevel().getLevel() > 1) {
                            company.setLevel(CompanyLevel.fromLevel(company.getLevel().getLevel() - 1));
                            feedback = "公司等级已降低 → " + company.getLevel().getDisplayName();
                        } else {
                            feedback = "降级仅管理（创造模式）可用";
                        }
                    }
                    case "list", "ipo" -> {
                        // §9：list 等价 ipo（走 IPO 状态机）
                        feedback = engine.startIpo(company);
                        if (feedback == null) {
                            feedback = "IPO 已发起，发行 " + company.getFloatShares() + " 股 @ "
                                    + String.format("%.2f", company.getIpoPrice()) + "，请认购";
                            syncBack = true;
                        } else {
                            feedback = "发起 IPO 失败：" + feedback;
                        }
                    }
                    case "subscribe" -> {
                        int shares = parseInt(packet.param());
                        if (shares <= 0) {
                            feedback = "认购数量无效";
                        } else {
                            feedback = engine.subscribeIpo(sp, company, shares);
                            if (feedback == null) {
                                feedback = "认购 " + shares + " 股成功";
                                syncBack = true;
                            } else {
                                feedback = "认购失败：" + feedback;
                            }
                        }
                    }
                    case "transfer" -> {
                        String[] parts = packet.param().trim().split("\\s+");
                        if (parts.length < 2) {
                            feedback = "转让格式：<接收方UUID> <股数>";
                        } else {
                            try {
                                UUID target = UUID.fromString(parts[0]);
                                int qty = Integer.parseInt(parts[1]);
                                feedback = engine.transferShares(company, sp.getUUID(), target, qty, sp.serverLevel());
                                if (feedback == null) {
                                    feedback = "转让 " + qty + " 股成功";
                                    syncBack = true;
                                } else {
                                    feedback = "转让失败：" + feedback;
                                }
                            } catch (IllegalArgumentException e) {
                                feedback = "转让参数无效";
                            }
                        }
                    }
                    case "shareholders" -> {
                        feedback = shareholdersMessage(engine, company);
                    }
                    case "report" -> {
                        CompanyData.CompanyFinancialReport r = company.getLatestReport();
                        feedback = r == null ? "暂无财报（尚未到财报周期）"
                                : ("最近财报(期 " + r.periodEnd() + "): 净资产 " + fmt(r.netAssetValue())
                                + " | 净利 " + fmt(r.recentProfit()) + " | EPS " + fmt(r.eps())
                                + " | ROE " + fmt(r.roe()) + " | 市值 " + fmt(r.marketCap()));
                    }
                    case "dividend" -> {
                        if (!sp.isCreative()) {
                            feedback = "分红仅管理可用";
                        } else {
                            engine.payDividend(company, sp.serverLevel());
                            feedback = "分红完成：留存 " + fmt(company.getRetainedEarnings())
                                    + "，累计分红 " + fmt(company.getDividendPaidTotal());
                            syncBack = true;
                        }
                    }
                    case "delist" -> {
                        if (!sp.isCreative()) {
                            feedback = "退市仅管理可用";
                        } else {
                            boolean triggered = DelistingMonitor.evaluate(company, engine, sp.serverLevel());
                            feedback = triggered ? "公司已退市（股东已回购清偿）" : "未触发退市条件";
                            syncBack = true;
                        }
                    }
                    case "scan" -> {
                        AssetScanner.scanCompany(sp.serverLevel(), company, engine.getCommodityMarket());
                        feedback = "扫描完成：净资产 " + fmt(company.getNetAssetValue())
                                + "，利润 " + fmt(company.getRecentProfit());
                    }
                    default -> feedback = "未知公司动作：" + packet.action();
                }
                if (feedback != null) {
                    sp.displayClientMessage(Component.literal(feedback), false);
                }
                if (syncBack) {
                    sp.connection.send(new CompanyDataResponsePacket(company));
                }
            }
        });
    }

    private static String shareholdersMessage(MarketEngine engine, CompanyData company) {
        List<Map.Entry<UUID, Integer>> list = engine.getCompanyManager().getShareholdersOf(company);
        StringBuilder sb = new StringBuilder("股权结构（" + company.getName() + "）: 总股本 " + company.getTotalShares());
        if (list.isEmpty()) {
            sb.append("，无股东记录");
        } else {
            for (Map.Entry<UUID, Integer> e : list) {
                sb.append(" [").append(e.getKey()).append(" 持股 ").append(e.getValue()).append("]");
            }
        }
        return sb.toString();
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return -1;
        }
    }

    private static String fmt(double v) {
        return String.format("%.2f", v);
    }
}
