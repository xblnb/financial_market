package com.example.financialmarket.engine;

import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.*;
import java.util.*;

/**
 * 配方图分析器。
 *
 * <p>在既有上游/下游传播图基础上，扩展记录<b>配方生产成本</b>信息（CF-3）：
 * 对每个配方，记录其原料（物品 → 份数，按配料格子计数）与产出数，供
 * {@link CommodityRegistry#basePriceOf(Item)} 计算经济学成本基价（合成成本 / 产出）。
 *
 * <p>配方图在引擎首个 tick（服务端 {@code RecipeManager} 可用时）构建一次，之后只读。
 * 构建是确定性的（同世界 → 同配方 → 同成本基价），无任何随机。
 */
public class RecipeAnalyzer {
    private static final Map<Item, Set<Item>> upstream = new HashMap<>();
    private static final Map<Item, Set<Item>> downstream = new HashMap<>();
    private static final Map<Item, List<RecipeCost>> recipeCosts = new HashMap<>();
    private static boolean graphBuilt = false;

    /** 一个配方的成本信息：原料（物品→份数）与产出数。 */
    public static class RecipeCost {
        public final Map<Item, Integer> ingredients = new LinkedHashMap<>();
        public final int outputCount;
        public RecipeCost(int outputCount) { this.outputCount = Math.max(1, outputCount); }
        public void addIngredient(Item item) {
            if (item != null) ingredients.merge(item, 1, Integer::sum);
        }
    }

    public static void buildGraph(RecipeManager recipeManager, RegistryAccess registryAccess) {
        if (graphBuilt || recipeManager == null) return;
        upstream.clear();
        downstream.clear();
        recipeCosts.clear();
        for (RecipeHolder<?> holder : recipeManager.getRecipes()) {
            try {
                Recipe<?> recipe = holder.value();
                ItemStack result = recipe.getResultItem(registryAccess);
                if (result.isEmpty()) continue;
                Item product = result.getItem();
                List<Ingredient> ingredients = recipe.getIngredients();

                // 配方成本：按配料格子统计各原料份数（同物品多格累加），产出数用于单件成本折算。
                RecipeCost cost = new RecipeCost(result.getCount());
                for (Ingredient ing : ingredients) {
                    if (ing == null || ing.isEmpty()) continue;
                    ItemStack[] stacks = ing.getItems();
                    if (stacks.length == 0) continue;
                    Item mat = stacks[0].getItem();
                    if (mat == null || mat == product) continue;
                    cost.addIngredient(mat);
                    addLink(mat, product);
                }
                if (!cost.ingredients.isEmpty()) {
                    recipeCosts.computeIfAbsent(product, k -> new ArrayList<>()).add(cost);
                }
            } catch (Exception e) { continue; }
        }
        graphBuilt = true;
    }

    private static void addLink(Item ingredient, Item product) {
        downstream.computeIfAbsent(ingredient, k -> new HashSet<>()).add(product);
        upstream.computeIfAbsent(product, k -> new HashSet<>()).add(ingredient);
    }

    public static Set<Item> getUpstream(Item item) { return upstream.getOrDefault(item, Collections.emptySet()); }
    public static Set<Item> getDownstream(Item item) { return downstream.getOrDefault(item, Collections.emptySet()); }

    /** 返回产出该物品的所有配方成本信息（无配方时为空列表）。 */
    public static List<RecipeCost> getRecipesProducing(Item item) {
        return recipeCosts.getOrDefault(item, Collections.emptyList());
    }

    public static boolean isGraphBuilt() { return graphBuilt; }

    /** 测试/重建用：清空图（正常路径仅构建一次）。 */
    public static void resetForReplay() {
        graphBuilt = false;
        upstream.clear();
        downstream.clear();
        recipeCosts.clear();
    }
}
