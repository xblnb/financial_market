package com.example.financialmarket.engine.macro.world;

import com.example.financialmarket.engine.macro.world.WorldMacroModel.ProcessKind;
import com.example.financialmarket.engine.macro.world.WorldMacroModel.ProcessProfile;
import com.example.financialmarket.engine.macro.world.WorldMacroModel.ResourceAmount;
import com.example.financialmarket.engine.macro.world.WorldMacroModel.ResourceKey;
import com.example.financialmarket.engine.macro.world.WorldMacroModel.ResourceProfile;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Compiles the modpack's item registry and recipes into resource profiles + a {@link RecipeGraph}.
 *
 * <p>This is the M2 "large modpack" entry point: no mod-specific code is needed for the generic
 * pass. Tech/magic adapters can later refine tiers/energy/process kinds, but every recipe already
 * contributes an input-output edge and every item gets a profile (unknown items fall back to
 * {@code OTHER}).</p>
 */
public final class ProfileCompiler {

    public record CompileStats(int items, int taggedItems, int recipes, int edges, int skipped,
                               long millis) {
    }

    private final ProfileRegistry registry;
    private final RecipeGraph graph;

    public ProfileCompiler(ProfileRegistry registry, RecipeGraph graph) {
        this.registry = registry;
        this.graph = graph;
    }

    public CompileStats compile(RegistryAccess access, RecipeManager manager) {
        long start = System.currentTimeMillis();
        int items = 0;
        int tagged = 0;
        for (Item item : BuiltInRegistries.ITEM) {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
            if (id == null || id.toString().equals("minecraft:air")) {
                continue;
            }
            items++;
            if (registry.contains(id.toString())) {
                continue;
            }
            ResourceProfile profile = derive(id, item);
            if (!profile.tags().isEmpty()) {
                tagged++;
            }
            registry.put(profile, false);
        }

        int recipes = 0;
        int edges = 0;
        int skipped = 0;
        int limit = 120_000;
        for (RecipeHolder<?> holder : manager.getRecipes()) {
            if (recipes >= limit) {
                break;
            }
            Recipe<?> recipe = holder.value();
            ItemStack result;
            try {
                result = recipe.getResultItem(access);
            } catch (RuntimeException ex) {
                skipped++;
                continue;
            }
            if (result == null || result.isEmpty()) {
                skipped++;
                continue;
            }
            String outId = BuiltInRegistries.ITEM.getKey(result.getItem()).toString();
            List<ResourceAmount> outputs = List.of(amount(outId, Math.max(1, result.getCount())));
            List<ResourceAmount> inputs = new ArrayList<>();
            NonNullList<Ingredient> ingredients = recipe.getIngredients();
            for (Ingredient ingredient : ingredients) {
                ItemStack[] options = ingredient.getItems();
                if (options.length == 0 || options[0].isEmpty()) {
                    continue;
                }
                ItemStack stack = options[0];
                String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                inputs.add(amount(id, Math.max(1, stack.getCount())));
            }
            ResourceLocation typeId = BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType());
            String type = typeId == null ? "unknown" : typeId.toString();
            ProcessKind kind = kindOf(type);
            NativeModProfiles.Hint hint = NativeModProfiles.classify(type);
            if (hint != null) {
                kind = hint.kind();
            }
            int tier = 0;
            for (ResourceAmount in : inputs) {
                tier = Math.max(tier, registry.resolve(in.key()).tier());
            }
            if (kind != ProcessKind.CRAFT && kind != ProcessKind.OTHER) {
                tier++;
            }
            if (hint != null) {
                tier = Math.max(tier, hint.tier());
            }
            ResourceProfile outProfile = registry.resolve(outId);
            String sector = hint != null && !"other".equals(hint.sector())
                    ? hint.sector() : outProfile.sector();
            String sourceMod = hint != null ? hint.mod() : idNamespace(outId);
            String processId = type + ":" + holder.id();
            graph.add(new ProcessProfile(processId, kind, sector, tier, inputs, outputs,
                    0, 0, sourceMod));
            recipes++;
            edges += inputs.size();
            if (recipes % 5_000 == 0) {
                // no-op progress hook; keeps the loop deterministic and allocation-light
            }
        }
        return new CompileStats(items, tagged, recipes, edges, skipped,
                System.currentTimeMillis() - start);
    }

    /** Heuristic profile used when no data pack/mod profile exists. Deterministic and cheap. */
    public static ResourceProfile derive(ResourceLocation id, Item item) {
        String ns = id.getNamespace();
        String path = id.getPath().toLowerCase();
        String sector;
        String category;
        int tier;
        double value;
        if (path.contains("_ore") || path.startsWith("raw_") || path.contains("_raw_")) {
            sector = "materials";
            category = "raw";
            tier = 1;
            value = 8.0;
        } else if (path.contains("_ingot") || path.contains("_nugget")) {
            sector = "materials";
            category = "intermediate";
            tier = 1;
            value = 12.0;
        } else if (path.contains("_dust") || path.contains("_gem") || path.contains("_crystal")) {
            sector = "materials";
            category = "intermediate";
            tier = 2;
            value = 18.0;
        } else if (path.contains("_block") || path.contains("_planks") || path.contains("_brick")) {
            sector = "materials";
            category = "intermediate";
            tier = 0;
            value = 4.0;
        } else if (path.contains("_log") || path.contains("_wood") || path.contains("_stem")) {
            sector = "materials";
            category = "raw";
            tier = 0;
            value = 2.0;
        } else if (path.contains("_food") || path.contains("_bread") || path.contains("_wheat")
                || path.contains("_carrot") || path.contains("_apple") || path.contains("_meat")
                || path.contains("_fish") || path.contains("_crop") || path.contains("_seed")) {
            sector = "food";
            category = "consumer";
            tier = 0;
            value = 5.0;
        } else if (path.contains("_sword") || path.contains("_pickaxe") || path.contains("_axe")
                || path.contains("_shovel") || path.contains("_hoe") || path.contains("_armor")
                || path.contains("_helmet") || path.contains("_chestplate") || path.contains("_boots")) {
            sector = "tools";
            category = "capital";
            tier = 2;
            value = 30.0;
        } else if (path.contains("_machine") || path.contains("_factory") || path.contains("_crusher")
                || path.contains("_furnace") || path.contains("_generator") || path.contains("_cable")
                || path.contains("_pipe") || path.contains("_tank")) {
            sector = "tools";
            category = "capital";
            tier = 3;
            value = 100.0;
        } else if (path.contains("mana") || path.contains("rune") || path.contains("spell")
                || path.contains("arcane") || path.contains("source") || path.contains("ritual")) {
            sector = "arcane";
            category = "reagent";
            tier = 3;
            value = 25.0;
        } else if (ns.equals("botania") || ns.equals("ars_nouveau") || ns.equals("bloodmagic")
                || ns.equals("thaumcraft") || ns.equals("astralsorcery")) {
            sector = "arcane";
            category = "reagent";
            tier = 3;
            value = 20.0;
        } else {
            sector = "materials";
            category = "intermediate";
            tier = 1;
            value = 6.0;
        }
        Set<String> tags = item.builtInRegistryHolder().tags()
                .limit(24)
                .map(t -> t.location().toString())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return new ResourceProfile(new ResourceKey(id.toString(), ProfileRegistry.kindOf(id.toString())),
                sector, category, tier, value, 0.6, tags, Set.of());
    }

    private static ResourceAmount amount(String id, double qty) {
        return new ResourceAmount(new ResourceKey(id, ProfileRegistry.kindOf(id)), qty);
    }

    private static ProcessKind kindOf(String type) {
        String s = type.toLowerCase();
        if (s.contains("smelt") || s.contains("furnace")) {
            return ProcessKind.SMELT;
        }
        if (s.contains("crush") || s.contains("macerat") || s.contains("pulveriz")) {
            return ProcessKind.CRUSH;
        }
        if (s.contains("chem") || s.contains("mix") || s.contains("dissol")) {
            return ProcessKind.CHEM;
        }
        if (s.contains("ritual") || s.contains("altar") || s.contains("infus")) {
            return ProcessKind.RITUAL;
        }
        if (s.contains("spell") || s.contains("enchant")) {
            return ProcessKind.SPELL;
        }
        if (s.contains("craft")) {
            return ProcessKind.CRAFT;
        }
        if (s.contains("assembl") || s.contains("mechanic")) {
            return ProcessKind.ASSEMBLY;
        }
        return ProcessKind.OTHER;
    }

    private static String idNamespace(String id) {
        int i = id.indexOf(':');
        return i > 0 ? id.substring(0, i) : "unknown";
    }

    // Kept for callers that need the key type without importing MC classes.
    public static ResourceKey key(String id) {
        return new ResourceKey(id, ProfileRegistry.kindOf(id));
    }
}
