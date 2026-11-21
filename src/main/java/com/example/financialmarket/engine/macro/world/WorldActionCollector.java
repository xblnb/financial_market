package com.example.financialmarket.engine.macro.world;

import com.example.financialmarket.engine.MarketEngine;
import com.example.financialmarket.engine.macro.world.WorldMacroModel.ActionType;
import com.example.financialmarket.engine.macro.world.WorldMacroModel.ResourceAmount;
import com.example.financialmarket.engine.macro.world.WorldMacroModel.ResourceKey;
import com.example.financialmarket.engine.macro.world.WorldMacroModel.WorldAction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * M1 generic capture: vanilla NeoForge actions become {@link WorldAction}s. No mod-specific code.
 * Tech/magic adapters can submit native events through the same engine.
 */
public final class WorldActionCollector {

    private WorldActionCollector() {
    }

    public static void crafted(MarketEngine engine, ServerPlayer player, ItemStack result,
                               String sourceId) {
        if (engine == null || player == null || result == null || result.isEmpty()) {
            return;
        }
        WorldMacroEngine macro = engine.getWorldMacro();
        if (macro == null) {
            return;
        }
        String outId = itemId(result.getItem());
        List<ResourceAmount> inputs = new ArrayList<>(macro.recipeGraph().inputsForOutput(outId));
        List<ResourceAmount> outputs = List.of(amount(outId, Math.max(1, result.getCount())));
        submit(engine, player, ActionType.CRAFT, sourceId, inputs, outputs, 0.85);
    }

    public static void blockBroken(MarketEngine engine, ServerPlayer player, BlockState state) {
        if (engine == null || player == null || state == null) {
            return;
        }
        String id = blockItemId(state);
        if (id == null) {
            return;
        }
        submit(engine, player, ActionType.BREAK_BLOCK, id, List.of(),
                List.of(amount(id, 1)), 0.75);
    }

    public static void blockPlaced(MarketEngine engine, LivingEntity entity, BlockState state) {
        if (engine == null || entity == null || state == null) {
            return;
        }
        String id = blockItemId(state);
        if (id == null) {
            return;
        }
        submit(engine, entity, ActionType.PLACE_BLOCK, id, List.of(),
                List.of(amount(id, 1)), 0.75);
    }

    public static void death(MarketEngine engine, LivingEntity entity) {
        if (engine == null || entity == null) {
            return;
        }
        String id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
        submit(engine, entity, ActionType.DEATH, id, List.of(), List.of(), 0.7);
    }

    public static void advancement(MarketEngine engine, ServerPlayer player, String advancementId) {
        if (engine == null || player == null || advancementId == null) {
            return;
        }
        submit(engine, player, ActionType.ADVANCEMENT, advancementId, List.of(), List.of(), 0.9);
    }

    /** M3 soft magic adapter: only ids/namespaces that look magical are translated. */
    public static void spell(MarketEngine engine, ServerPlayer player, ItemStack stack) {
        if (engine == null || player == null || stack == null || stack.isEmpty()) {
            return;
        }
        String id = itemId(stack.getItem());
        if (!isMagic(id)) {
            return;
        }
        submit(engine, player, ActionType.CAST_SPELL, id,
                List.of(amount("mana:generic", 5)),
                List.of(amount("service:spell", 1)), 0.65);
    }

    public static void ritual(MarketEngine engine, ServerPlayer player, BlockState state) {
        if (engine == null || player == null || state == null) {
            return;
        }
        String id = blockItemId(state);
        if (id == null || !isMagic(id)) {
            return;
        }
        submit(engine, player, ActionType.RITUAL, id,
                List.of(amount("mana:generic", 20)),
                List.of(amount("service:spell", 1)), 0.7);
    }

    private static boolean isMagic(String id) {
        if (id == null) {
            return false;
        }
        String s = id.toLowerCase();
        return s.startsWith("botania:") || s.startsWith("ars_nouveau:")
                || s.startsWith("bloodmagic:") || s.startsWith("thaumcraft:")
                || s.startsWith("astralsorcery:") || s.startsWith("occultism:")
                || s.contains("mana") || s.contains("rune") || s.contains("spell")
                || s.contains("ritual") || s.contains("arcane") || s.contains("source_");
    }

    private static void submit(MarketEngine engine, LivingEntity entity, ActionType action,
                               String sourceId, List<ResourceAmount> inputs,
                               List<ResourceAmount> outputs, double confidence) {
        WorldMacroEngine macro = engine.getWorldMacro();
        if (macro == null) {
            return;
        }
        UUID actor = entity.getUUID();
        String company = "player:" + actor;
        var ownerCompany = engine.getCompanyManager().getByOwner(actor);
        if (ownerCompany != null) {
            company = ownerCompany.getStockId() != null && !ownerCompany.getStockId().isBlank()
                    ? "company:" + ownerCompany.getStockId()
                    : "company:" + ownerCompany.getName();
        }
        String sourceMod = namespace(sourceId);
        long tick = entity.level().getGameTime();
        String region = entity.level().dimension().location().toString();
        macro.submit(new WorldAction(tick, macro.bus().nextSeq(), actor, company, action,
                sourceMod, sourceId, region, inputs, outputs, confidence));
    }

    private static ResourceAmount amount(String id, double qty) {
        return new ResourceAmount(new ResourceKey(id, ProfileRegistry.kindOf(id)), qty);
    }

    private static String itemId(Item item) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
        return id == null ? "minecraft:air" : id.toString();
    }

    private static String blockItemId(BlockState state) {
        Item item = state.getBlock().asItem();
        if (item != null && item != net.minecraft.world.item.Items.AIR) {
            return itemId(item);
        }
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return id == null ? null : id.toString();
    }

    private static String namespace(String id) {
        if (id == null) {
            return "unknown";
        }
        int i = id.indexOf(':');
        return i > 0 ? id.substring(0, i) : "minecraft";
    }
}
