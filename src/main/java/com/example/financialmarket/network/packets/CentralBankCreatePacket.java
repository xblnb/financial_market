package com.example.financialmarket.network.packets;

import com.example.financialmarket.attachment.MarketAttachment;
import com.example.financialmarket.engine.banking.CentralBank;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import static com.example.financialmarket.network.packets.BankDepositPacket.accept;
import static com.example.financialmarket.network.packets.BankDepositPacket.reject;
import static com.example.financialmarket.network.packets.BankDepositPacket.sendBankData;

/**
 * 玩家开设央行交互包（AS-3/BT-3，服务端权威）。
 * <p>客户端仅提交「我要开设央行」意图；服务端校验当前世界央行是否已属玩家
 * （{@link CentralBank#isPlayerOwned()}）：<b>首次</b>写入归属（playerOwner/createdBy/createdTick，
 * 确定性 tick 计数器），<b>重复</b>则原子拒绝且不改状态。纯标记，不修改任何货币公式。
 */
public record CentralBankCreatePacket() implements CustomPacketPayload {
    public static final Type<CentralBankCreatePacket> ID = new Type<>(ResourceLocation.fromNamespaceAndPath("financialmarket", "central_bank_create"));
    @Override public Type<? extends CustomPacketPayload> type() { return ID; }

    public static final StreamCodec<FriendlyByteBuf, CentralBankCreatePacket> STREAM_CODEC = StreamCodec.unit(new CentralBankCreatePacket());

    public void send() { PacketDistributor.sendToServer(this); }

    public static void handle(CentralBankCreatePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer sp)) return;
            var engine = MarketAttachment.getEngine(sp.serverLevel());
            if (engine == null) {
                reject(sp, "引擎未就绪");
                return;
            }
            CentralBank cb = engine.getBankManager().getCentralBank();
            if (cb.isPlayerOwned()) {
                reject(sp, "央行已存在（归属玩家 " + cb.getCreatedBy() + "）");
                return;
            }
            // 首次开设：写入确定性归属（引擎 tick 计数器为时间权威；无随机）
            cb.setPlayerOwner(sp.getUUID(), sp.getName().getString(), engine.getTickCounter());
            accept(sp, "央行已由您开设");
            sendBankData(sp);
        });
    }
}
