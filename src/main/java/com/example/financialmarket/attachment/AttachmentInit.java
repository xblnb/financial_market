package com.example.financialmarket.attachment;

import com.example.financialmarket.FinancialMarket;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.function.Supplier;

public class AttachmentInit {
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, FinancialMarket.MODID);

    public static final Supplier<AttachmentType<PlayerEconomy>> PLAYER_ECONOMY =
            ATTACHMENT_TYPES.register("player_economy", () ->
                    AttachmentType.<PlayerEconomy>builder(() -> new PlayerEconomy())
                            .serialize(PlayerEconomy.CODEC)
                            .build()
            );

    // 存储 MarketEngine 的 CompoundTag
    public static final Supplier<AttachmentType<CompoundTag>> MARKET =
            ATTACHMENT_TYPES.register("market", () ->
                    AttachmentType.<CompoundTag>builder(CompoundTag::new)
                            .serialize(CompoundTag.CODEC)
                            .build()
            );

    public static void register(IEventBus bus) {
        ATTACHMENT_TYPES.register(bus);
    }
}