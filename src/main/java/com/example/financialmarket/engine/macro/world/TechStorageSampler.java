package com.example.financialmarket.engine.macro.world;

import com.example.financialmarket.company.CompanyData;
import com.example.financialmarket.engine.CommodityEntry;
import com.example.financialmarket.engine.CommodityMarket;
import com.example.financialmarket.engine.MarketEngine;
import com.example.financialmarket.engine.macro.world.WorldMacroModel.ActionType;
import com.example.financialmarket.engine.macro.world.WorldMacroModel.ResourceAmount;
import com.example.financialmarket.engine.macro.world.WorldMacroModel.ResourceKey;
import com.example.financialmarket.engine.macro.world.WorldMacroModel.WorldAction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * M2 generic tech/magic production sampler.
 *
 * <p>Many large mods do not expose a stable recipe-completion event. Their output still lands in a
 * container (machine output, AE2 storage, magic pool, etc.). Once per game day we diff each
 * company's bound containers: positive deltas become PRODUCTION/MACHINE_RUN, negative deltas become
 * CONSUMPTION. This is deliberately mod-agnostic and covers tech and magic storage alike.</p>
 */
public final class TechStorageSampler {

    private static final int MAX_CONTAINERS = 128;
    private static final int MAX_STACKS = 4096;

    private final Map<String, Map<String, Integer>> lastCounts = new LinkedHashMap<>();
    private final Map<String, Integer> lastEnergy = new LinkedHashMap<>();
    private final Map<String, Integer> lastMana = new LinkedHashMap<>();
    private final java.util.Set<String> seenCompanies = new java.util.HashSet<>();
    private long lastDay = Long.MIN_VALUE;

    public void sample(WorldMacroEngine macro, MarketEngine engine, ServerLevel level, long day) {
        if (macro == null || engine == null || level == null || day == lastDay) {
            return;
        }
        lastDay = day;
        for (CompanyData company : engine.getCompanyManager().getAllCompanies()) {
            String companyId = companyId(company);
            Map<String, Integer> now = scan(level, company);
            Map<String, Integer> before = lastCounts.getOrDefault(companyId, Map.of());
            boolean first = seenCompanies.add(companyId) && before.isEmpty();
            int outputItems = 0;
            int inputItems = 0;
            double outputValue = 0.0;
            double inputValue = 0.0;
            if (!first) {
                for (Map.Entry<String, Integer> e : now.entrySet()) {
                    int delta = e.getValue() - before.getOrDefault(e.getKey(), 0);
                    if (delta == 0) continue;
                    double unit = unitValue(engine.getCommodityMarket(), e.getKey());
                    if (delta > 0) {
                        outputItems += delta;
                        outputValue += unit * delta;
                        submit(macro, company, companyId, ActionType.MACHINE_RUN, e.getKey(), delta, true);
                    } else {
                        inputItems += -delta;
                        inputValue += unit * -delta;
                        submit(macro, company, companyId, ActionType.MACHINE_RUN, e.getKey(), -delta, false);
                    }
                }
            }
            lastCounts.put(companyId, now);
            int energyNow = scanEnergy(level, company);
            int energyBefore = lastEnergy.getOrDefault(companyId, energyNow);
            int energyConsumed = Math.max(0, energyBefore - energyNow);
            if (!first && energyConsumed > 0) {
                submitEnergy(macro, company, companyId, energyConsumed);
            }
            lastEnergy.put(companyId, energyNow);
            int manaNow = scanMana(level, company);
            int manaBefore = lastMana.getOrDefault(companyId, manaNow);
            if (!first && manaNow < manaBefore) {
                submitMana(macro, company, companyId, manaBefore - manaNow);
            }
            lastMana.put(companyId, manaNow);

            int endItems = now.values().stream().mapToInt(Integer::intValue).sum();
            int machines = Math.max(1, company.getBoundProductionBlocks().size());
            double capacity = Math.max(64.0, machines * 64.0);
            double energyUtil = Math.min(1.0, energyConsumed / Math.max(1.0, machines * 2000.0));
            double throughputUtil = Math.min(1.0, outputItems / capacity);
            double utilization = Math.max(throughputUtil, energyUtil * 0.6);
            company.setProductionOutputValue(outputValue);
            company.setProductionInputValue(inputValue);
            company.setProductionThroughput(outputItems);
            company.setProductionUtilization(utilization);
            company.setInventoryTurnover(outputItems / Math.max(1.0, endItems));
            company.setOperatingProfit(outputValue - inputValue);
            company.setEnergyConsumed(energyConsumed);
        }
    }

    private static double unitValue(CommodityMarket market, String itemId) {
        if (market != null && itemId != null) {
            ResourceLocation rl = ResourceLocation.tryParse(itemId);
            if (rl != null) {
                var item = BuiltInRegistries.ITEM.get(rl);
                CommodityEntry entry = item == null ? null : market.getCommodity(item);
                if (entry != null && entry.getBasePrice() > 0) {
                    return entry.getBasePrice();
                }
            }
        }
        return 1.0;
    }

    private static void submit(WorldMacroEngine macro, CompanyData company, String companyId,
                               ActionType action, String itemId, int qty, boolean output) {
        UUID actor = company.getOwner() == null
                ? UUID.nameUUIDFromBytes(companyId.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                : company.getOwner();
        List<ResourceAmount> inputs = output
                ? macro.recipeGraph().inputsForOutput(itemId) : List.of();
        List<ResourceAmount> outputs = output
                ? List.of(amount(itemId, qty))
                : List.of(amount(itemId, qty));
        if (!output) {
            inputs = List.of(amount(itemId, qty));
            outputs = List.of();
        }
        String sourceMod = namespace(itemId);
        macro.submit(new WorldAction(macro.tick(), macro.bus().nextSeq(), actor, companyId,
                action, sourceMod, itemId, "world", inputs, outputs, 0.55));
    }

    /** Containers + production blocks, deduplicated; many machines expose output inventories. */
    private static List<BlockPos> positions(CompanyData company) {
        List<BlockPos> out = new java.util.ArrayList<>(company.getBoundContainers());
        for (BlockPos pos : company.getBoundProductionBlocks()) {
            if (pos != null && !out.contains(pos)) out.add(pos);
        }
        return out;
    }

    private static Map<String, Integer> scan(ServerLevel level, CompanyData company) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        int containers = 0;
        int stacks = 0;
        for (BlockPos pos : positions(company)) {
            if (containers++ >= MAX_CONTAINERS || stacks >= MAX_STACKS) {
                break;
            }
            BlockEntity be = level.getBlockEntity(pos);
            if (!(be instanceof BaseContainerBlockEntity container)) {
                continue;
            }
            for (int i = 0; i < container.getContainerSize() && stacks < MAX_STACKS; i++) {
                ItemStack stack = container.getItem(i);
                if (stack.isEmpty()) {
                    continue;
                }
                String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                counts.merge(id, stack.getCount(), Integer::sum);
                stacks++;
            }
        }
        return counts;
    }

    private static int scanEnergy(ServerLevel level, CompanyData company) {
        int total = 0;
        int containers = 0;
        for (BlockPos pos : positions(company)) {
            if (containers++ >= MAX_CONTAINERS) {
                break;
            }
            IEnergyStorage storage = level.getCapability(Capabilities.EnergyStorage.BLOCK, pos, null);
            if (storage != null) {
                total += Math.max(0, storage.getEnergyStored());
            }
        }
        return total;
    }

    private static void submitEnergy(WorldMacroEngine macro, CompanyData company, String companyId,
                                     int consumed) {
        if (consumed <= 0) {
            return;
        }
        UUID actor = company.getOwner() == null
                ? UUID.nameUUIDFromBytes(companyId.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                : company.getOwner();
        macro.submit(new WorldAction(macro.tick(), macro.bus().nextSeq(), actor, companyId,
                ActionType.MACHINE_RUN, "energy", "energy:fe", "world",
                List.of(amount("energy:fe", consumed)), List.of(), 0.5));
    }

    private static int scanMana(ServerLevel level, CompanyData company) {
        int total = 0;
        int containers = 0;
        int stacks = 0;
        for (BlockPos pos : positions(company)) {
            if (containers++ >= MAX_CONTAINERS || stacks >= MAX_STACKS) {
                break;
            }
            BlockEntity be = level.getBlockEntity(pos);
            if (!(be instanceof BaseContainerBlockEntity container)) {
                continue;
            }
            for (int i = 0; i < container.getContainerSize() && stacks < MAX_STACKS; i++) {
                ItemStack stack = container.getItem(i);
                if (stack.isEmpty()) {
                    continue;
                }
                CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY)
                        .copyTag();
                if (tag.isEmpty()) {
                    continue;
                }
                int value = 0;
                for (String key : new String[]{"mana", "source", "stored_mana", "lp"}) {
                    if (tag.contains(key)) {
                        value = Math.max(value, tag.getInt(key));
                    }
                }
                total += value;
                stacks++;
            }
        }
        return total;
    }

    private static void submitMana(WorldMacroEngine macro, CompanyData company, String companyId,
                                   int consumed) {
        if (consumed <= 0) {
            return;
        }
        UUID actor = company.getOwner() == null
                ? UUID.nameUUIDFromBytes(companyId.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                : company.getOwner();
        macro.submit(new WorldAction(macro.tick(), macro.bus().nextSeq(), actor, companyId,
                ActionType.MACHINE_RUN, "mana", "mana:generic", "world",
                List.of(amount("mana:generic", consumed)), List.of(), 0.5));
    }

    private static ResourceAmount amount(String id, double qty) {
        return new ResourceAmount(new ResourceKey(id, ProfileRegistry.kindOf(id)), qty);
    }

    private static String companyId(CompanyData company) {
        if (company.getStockId() != null && !company.getStockId().isBlank()) {
            return "company:" + company.getStockId();
        }
        return "company:" + company.getName();
    }

    private static String namespace(String id) {
        int i = id == null ? -1 : id.indexOf(':');
        return i > 0 ? id.substring(0, i) : "unknown";
    }
}
