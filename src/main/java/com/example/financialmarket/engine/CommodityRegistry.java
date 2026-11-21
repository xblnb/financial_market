package com.example.financialmarket.engine;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.*;

/**
 * 商品动态注册（CF-2）与经济学成本基价（CF-3）。
 *
 * <p><b>动态注册</b>：遍历 {@code BuiltInRegistries.ITEM}（全部非 AIR/禁用物品）+ {@code BuiltInRegistries.FLUID}
 * （可获源流体），运行期枚举注册表 → 天然覆盖任意模组物品/流体，无需白名单。
 * 注册幂等（已存在 key 保持既有条目，不破坏读档恢复的状态）。
 *
 * <p><b>经济学成本基价</b>：{@link #basePriceOf(Item)} 为确定性纯函数——最小配方生产成本
 * （递归 Σ 原料基价 × 份数 / 产出数，带环防护）优先，无配方或成本为 0 时落<b>稀有度档</b>
 * 保底；可合成工具/盔甲成品叠加工艺溢价。全部常量，无随机。
 */
public class CommodityRegistry {
    private static final Gson GSON = new Gson();

    /** 配方成本递归深度上限（防环，P0 可复现）。 */
    public static final int MAX_RECIPE_DEPTH = 4;
    /** 工艺溢价：工具/盔甲成品 = Σ原料 × (1 + CRAFT_MARKUP)。 */
    public static final double CRAFT_MARKUP = 0.20;

    /** 禁用注册的物品路径片段（沿用既有排除：命令方块/屏障/结构/光源）。 */
    public static boolean isExcludedItem(String path) {
        if (path == null) return true;
        return path.contains("command_block") || path.contains("barrier")
                || path.contains("structure") || path.contains("light");
    }

    /** 动态注册全部可获物品（非 AIR/禁用）+ 任意模组物品。幂等。 */
    public static void autoRegister(CommodityMarket market) {
        for (Item item : BuiltInRegistries.ITEM) {
            if (item == Items.AIR) continue;
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
            if (id == null || isExcludedItem(id.getPath())) continue;
            double basePrice = basePriceOf(item);
            String[] industries = inferIndustries(id);
            market.register(item, basePrice, industries);
        }
    }

    /** 动态注册全部可获源流体（水/岩浆 + 任意模组源流体；跳过 empty）。幂等。 */
    public static void registerFluids(CommodityMarket market) {
        for (Fluid fluid : BuiltInRegistries.FLUID) {
            if (fluid == Fluids.EMPTY) continue;
            if (!fluid.isSource(fluid.defaultFluidState())) continue; // 只注册源流体（可获取）
            ResourceLocation id = BuiltInRegistries.FLUID.getKey(fluid);
            if (id == null) continue;
            double basePrice = fluidBasePrice(id);
            market.registerFluid(fluid, basePrice, new String[]{"FLUID"});
        }
    }

    /**
     * 配方图可用后重算各物品的<b>经济学成本基价</b>（CF-3）。
     * 配方图在引擎首个 tick 构建（服务端 RecipeManager 可用）；构建一次后本方法为确定性纯函数，
     * 结果与读档恢复的基价一致（配方固定 → 成本固定）。无配方物品保持稀有度档。
     */
    public static void refreshBasePrices(CommodityMarket market) {
        for (CommodityEntry entry : market.getAllCommodities()) {
            if (!"item".equals(entry.getKind()) || entry.getItem() == null) continue;
            if (entry.isManualAnchor()) continue;
            double cost = basePriceOf(entry.getItem());
            if (cost > 0) {
                if (entry.isPristine()) {
                    entry.resetInitialPrice(cost);
                } else {
                    entry.applyCostBase(cost);
                }
            }
        }
    }

    /**
     * 经济学成本基价（确定性，无随机）：
     * 取所有配方中最小单件生产成本（Σ 原料基价 × 份数 / 产出数，递归、环防护），
     * 再叠加工艺溢价；与稀有度档取最大保底。
     */
    public static double basePriceOf(Item item) {
        return computeBasePrice(item, new HashMap<>(), new HashSet<>(), 0);
    }

    private static double computeBasePrice(Item item, Map<Item, Double> memo, Set<Item> computing, int depth) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
        String path = id != null ? id.getPath() : "";
        double rarityFloor = rarityTier(path);
        if (depth >= MAX_RECIPE_DEPTH) return rarityFloor;

        Double cached = memo.get(item);
        if (cached != null) return cached;
        if (computing.contains(item)) return rarityFloor; // 环防护：返回稀有度档

        computing.add(item);
        double minCost = 0;
        List<RecipeAnalyzer.RecipeCost> recipes = RecipeAnalyzer.getRecipesProducing(item);
        if (!recipes.isEmpty()) {
            double best = Double.POSITIVE_INFINITY;
            for (RecipeAnalyzer.RecipeCost r : recipes) {
                double ing = 0;
                boolean ok = true;
                for (Map.Entry<Item, Integer> e : r.ingredients.entrySet()) {
                    double c = computeBasePrice(e.getKey(), memo, computing, depth + 1);
                    if (c <= 0) { ok = false; break; }
                    ing += c * e.getValue();
                }
                if (ok && ing > 0) {
                    double unit = ing / Math.max(1, r.outputCount);
                    if (unit < best) best = unit;
                }
            }
            if (best < Double.POSITIVE_INFINITY) minCost = best * (1.0 + craftMarkup(path));
        }
        computing.remove(item);

        double base = Math.max(rarityFloor, minCost);
        base = Math.max(base, intrinsicFloor(item, path, rarityFloor));
        base = Math.round(base * 100.0) / 100.0;
        memo.put(item, base);
        return base;
    }

    /**
     * Auto initial-value floor for mod items that have no recipe or tag:
     * stackability, durability, food value, rarity and mod namespace.
     */
    private static double intrinsicFloor(Item item, String path, double rarityFloor) {
        double floor = rarityFloor;
        ItemStack stack = item.getDefaultInstance();
        if (stack.getMaxStackSize() == 1) floor *= 1.40;
        int damage = stack.getMaxDamage();
        if (damage > 0) floor *= 1.0 + Math.min(1.0, damage / 1000.0);
        var food = stack.get(net.minecraft.core.component.DataComponents.FOOD);
        if (food != null && food.nutrition() > 0) {
            floor = Math.max(floor, food.nutrition() * 0.25);
        }
        try {
            var rarity = stack.getRarity();
            floor *= switch (rarity) {
                case UNCOMMON -> 1.10;
                case RARE -> 1.35;
                case EPIC -> 1.75;
                default -> 1.0;
            };
        } catch (RuntimeException ignored) {
            // Some modded items cannot build a default stack; keep the path floor.
        }
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
        String ns = id == null ? "" : id.getNamespace();
        if (ns.contains("gregtech") || ns.contains("gtceu")) floor *= 1.35;
        else if (ns.contains("mekanism")) floor *= 1.20;
        else if (ns.contains("thermal") || ns.contains("immersiveengineering")) floor *= 1.15;
        else if (ns.contains("create")) floor *= 1.12;
        floor = Math.max(floor, namespaceFloor(ns));
        return floor;
    }

    /** Conservative mod-item floor so unlabeled machine parts are not priced at 1 coin. */
    private static double namespaceFloor(String ns) {
        if (ns.contains("gregtech") || ns.contains("gtceu")) return 12.0;
        if (ns.contains("mekanism")) return 6.0;
        if (ns.contains("appliedenergistics") || ns.contains("ae2")) return 5.0;
        if (ns.contains("create") || ns.contains("thermal")
                || ns.contains("immersiveengineering")) return 4.0;
        if (ns.contains("botania") || ns.contains("ars_nouveau")
                || ns.contains("occultism") || ns.contains("bloodmagic")) return 4.0;
        if (!ns.isEmpty() && !ns.equals("minecraft") && !ns.equals("neoforge")) return 1.5;
        return 0.0;
    }

    /** 工艺溢价：可合成工具/盔甲成品 = Σ原料 + 20% 工艺溢价（CF-3）。 */
    private static double craftMarkup(String path) {
        return isToolOrArmor(path) ? CRAFT_MARKUP : 0.0;
    }

    private static boolean isToolOrArmor(String path) {
        return path.contains("sword") || path.contains("pickaxe") || path.contains("axe")
                || path.contains("shovel") || path.contains("hoe") || path.contains("helmet")
                || path.contains("chestplate") || path.contains("leggings") || path.contains("boots");
    }

    /**
     * 稀有度档（确定性常量表，按物品资源路径归类）：仅作<b>无配方/天然矿产物</b>的基价保底。
     * 天然稀缺性：下界合金>钻石/绿宝石>金>铁/铜>石英/青金石>矿石>块>锭>…，满足
     * 铁锭类 > 铁矿 > 石头、钻石 > 铁锭（CF-3）。
     */
    private static double rarityTier(String path) {
        if (path.contains("netherite")) return 60.0;
        if (path.contains("diamond") || path.contains("emerald")) return 40.0;
        if (path.contains("gold")) return 20.0;
        if (path.contains("amethyst") || path.contains("lapis") || path.contains("quartz")) return 12.0;
        if (path.contains("iron") || path.contains("copper")) return 10.0;
        if (path.contains("ingot")) return 10.0;
        if (path.contains("gem")) return 25.0;
        if (path.contains("ore") || path.contains("raw_")) return 8.0;
        if (path.contains("block")) return 60.0;
        if (path.contains("dust")) return 3.0;
        if (path.contains("nugget")) return 1.5;
        if (path.contains("log")) return 2.0;
        if (path.contains("planks")) return 0.5;
        if (path.contains("stick")) return 0.2;
        if (path.contains("wheat") || path.contains("carrot") || path.contains("potato")
                || path.contains("apple") || path.contains("berry") || path.contains("beetroot")
                || path.contains("melon")) return 1.0;
        if (path.contains("seed")) return 0.8;
        if (path.contains("stone") || path.contains("cobble") || path.contains("dirt")
                || path.contains("sand") || path.contains("gravel") || path.contains("andesite")
                || path.contains("diorite") || path.contains("granite") || path.contains("deepslate")
                || path.contains("tuff") || path.contains("mud")) return 0.5;
        if (path.contains("helmet") || path.contains("chestplate") || path.contains("leggings")
                || path.contains("boots")) return 50.0;
        if (isToolOrArmor(path)) return 15.0;
        return 1.0;
    }

    /** 流体基价（确定性常量表）：水低值、岩浆较高、模组流体默认中值。 */
    private static double fluidBasePrice(ResourceLocation id) {
        String path = id.getPath();
        if (path.contains("water")) return 0.5;
        if (path.contains("lava")) return 1.5;
        return 2.0;
    }

    public static void loadFromJson(CommodityMarket market) {
        String path = "/data/financialmarket/commodity_prices.json";
        try (InputStream in = CommodityRegistry.class.getResourceAsStream(path)) {
            if (in == null) return;
            JsonObject root = GSON.fromJson(new InputStreamReader(in), JsonObject.class);
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                String itemId = entry.getKey();
                Item item = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(itemId));
                if (item == Items.AIR) continue;
                JsonObject data = entry.getValue().getAsJsonObject();
                double basePrice = data.get("base_price").getAsDouble();
                String[] industries = GSON.fromJson(data.get("industries"), String[].class);
                market.overrideManual(item, basePrice, industries);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static String[] inferIndustries(ResourceLocation id) {
        String path = id.getPath();
        List<String> industries = new ArrayList<>();
        if (path.contains("iron") || path.contains("gold") || path.contains("diamond") ||
                path.contains("copper") || path.contains("emerald") || path.contains("netherite"))
            industries.add("MINING");
        if (path.contains("redstone") || path.contains("glowstone") || path.contains("quartz"))
            industries.add("TECH");
        if (path.contains("wheat") || path.contains("carrot") || path.contains("potato") ||
                path.contains("apple") || path.contains("crop") || path.contains("seed"))
            industries.add("AGRICULTURE");
        if (path.contains("log") || path.contains("planks") || path.contains("stone") ||
                path.contains("brick") || path.contains("concrete"))
            industries.add("CONSTRUCTION");
        if (path.contains("sword") || path.contains("bow") || path.contains("arrow") ||
                path.contains("tnt") || path.contains("gunpowder"))
            industries.add("WEAPON");
        if (path.contains("ender") || path.contains("pearl") || path.contains("blaze") ||
                path.contains("potion") || path.contains("enchant"))
            industries.add("MAGIC");
        if (industries.isEmpty()) industries.add("GENERAL");
        return industries.toArray(new String[0]);
    }
}
