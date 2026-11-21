package com.example.financialmarket.init;

import com.example.financialmarket.FinancialMarket;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * 开市/休市钟声音效注册（规范 §8）。
 *
 * <p>两个 {@link SoundEvent}：{@code exchange_open_bell}（开市）/ {@code exchange_close_bell}（休市）。
 * 音频文件路径为 {@code assets/financialmarket/sounds/block/exchange_open_bell.ogg} 与
 * {@code exchange_close_bell.ogg}（<b>由用户提供</b>，本实现预留注册与路径）。若用户只提供一个音频文件，
 * 可把同一 {@code .ogg} 拷贝到两个路径复用（提示：提供第二个可区分音色更佳）。
 *
 * <p>{@code sounds.json} 位于 {@code assets/financialmarket/sounds.json}（注意：不在 sounds 子目录内），
 * 键必须与 {@code SoundEvent} 注册路径一致（{@code exchange_open_bell}/{@code exchange_close_bell}）。
 *
 * <p>注册方式与 {@link BlockInit} 一致：{@code @EventBusSubscriber(Bus.MOD)} 自注册到 MOD 总线，用
 * {@link RegisterEvent} 在 {@code Registries.SOUND_EVENT} 注册，无需改动 {@code FinancialMarket} 构造接线。
 */
@EventBusSubscriber(modid = FinancialMarket.MODID, bus = EventBusSubscriber.Bus.MOD)
public class SoundInit {
    private static final String MODID = FinancialMarket.MODID;

    public static final ResourceLocation EXCHANGE_OPEN_BELL_ID =
            ResourceLocation.fromNamespaceAndPath(MODID, "exchange_open_bell");
    public static final ResourceLocation EXCHANGE_CLOSE_BELL_ID =
            ResourceLocation.fromNamespaceAndPath(MODID, "exchange_close_bell");

    /** 开市钟声（注册时赋值，供方块实体播放）。 */
    public static SoundEvent EXCHANGE_OPEN_BELL;
    /** 休市钟声（注册时赋值，供方块实体播放）。 */
    public static SoundEvent EXCHANGE_CLOSE_BELL;

    /** MOD 总线：注册音效事件。 */
    @SubscribeEvent
    public static void onRegisterSounds(RegisterEvent event) {
        if (event.getRegistryKey().equals(Registries.SOUND_EVENT)) {
            event.register(Registries.SOUND_EVENT, EXCHANGE_OPEN_BELL_ID, () ->
                    EXCHANGE_OPEN_BELL = SoundEvent.createVariableRangeEvent(EXCHANGE_OPEN_BELL_ID));
            event.register(Registries.SOUND_EVENT, EXCHANGE_CLOSE_BELL_ID, () ->
                    EXCHANGE_CLOSE_BELL = SoundEvent.createVariableRangeEvent(EXCHANGE_CLOSE_BELL_ID));
        }
    }
}
