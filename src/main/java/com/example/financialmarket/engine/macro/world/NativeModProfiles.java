package com.example.financialmarket.engine.macro.world;

import com.example.financialmarket.engine.macro.world.WorldMacroModel.ProcessKind;

import java.util.Locale;

/**
 * Soft native-mod profiles: recipe-type -> process kind/tier/sector. No mod classes are referenced,
 * so this works for any modpack and degrades gracefully when a mod is absent.
 *
 * <p>This is the M2/M3 "native semantics" layer: it gives Mekanism/Create/GregTech/Botania/Ars/
 * Blood Magic recipes the right economic process and tier before the generic tag/name fallback.</p>
 */
public final class NativeModProfiles {

    public record Hint(ProcessKind kind, int tier, String sector, String mod) {
    }

    private NativeModProfiles() {
    }

    public static Hint classify(String recipeType) {
        if (recipeType == null || recipeType.isBlank()) {
            return null;
        }
        String t = recipeType.toLowerCase(Locale.ROOT);
        String mod = namespace(t);
        boolean mek = mod.equals("mekanism") || t.contains("mekanism");
        boolean create = mod.equals("create") || t.contains("create:");
        boolean gt = mod.equals("gregtech") || t.contains("gregtech") || t.contains("gtceu");
        boolean thermal = mod.equals("thermal") || t.contains("thermal");
        boolean ae2 = mod.equals("ae2") || t.contains("ae2");
        boolean ie = mod.equals("immersiveengineering") || t.contains("immersiveengineering");
        boolean botania = mod.equals("botania") || t.contains("botania");
        boolean ars = mod.equals("ars_nouveau") || t.contains("ars_nouveau");
        boolean blood = mod.equals("bloodmagic") || t.contains("bloodmagic");
        boolean thaum = mod.equals("thaumcraft") || t.contains("thaumcraft");

        if (mek) {
            if (contains(t, "enrich", "crush", "purif", "dissol", "wash")) {
                return new Hint(ProcessKind.CRUSH, 3, "materials", "mekanism");
            }
            if (contains(t, "inject", "infus", "chem", "oxidiz", "reaction")) {
                return new Hint(ProcessKind.CHEM, 3, "materials", "mekanism");
            }
            if (contains(t, "smelt", "energized")) {
                return new Hint(ProcessKind.SMELT, 2, "materials", "mekanism");
            }
            if (contains(t, "combine", "compress", "saw", "assembl")) {
                return new Hint(ProcessKind.ASSEMBLY, 2, "tools", "mekanism");
            }
        }
        if (create) {
            if (contains(t, "crush", "mill", "splash", "fan")) {
                return new Hint(ProcessKind.CRUSH, 2, "materials", "create");
            }
            if (contains(t, "mix", "press", "compact", "deploy", "sequenced", "mechanical")) {
                return new Hint(ProcessKind.ASSEMBLY, 2, "tools", "create");
            }
        }
        if (gt) {
            if (contains(t, "macerat", "orewash", "crush")) {
                return new Hint(ProcessKind.CRUSH, 3, "materials", "gregtech");
            }
            if (contains(t, "centrifug", "chemical", "electrolyz", "distill")) {
                return new Hint(ProcessKind.CHEM, 4, "materials", "gregtech");
            }
            if (contains(t, "assembl", "forming", "wiremill")) {
                return new Hint(ProcessKind.ASSEMBLY, 4, "tools", "gregtech");
            }
            if (contains(t, "blast", "smelt")) {
                return new Hint(ProcessKind.SMELT, 3, "materials", "gregtech");
            }
        }
        if (thermal) {
            if (contains(t, "pulveriz", "saw", "crucible")) {
                return new Hint(ProcessKind.CRUSH, 2, "materials", "thermal");
            }
            if (contains(t, "smelt", "furnace")) {
                return new Hint(ProcessKind.SMELT, 2, "materials", "thermal");
            }
        }
        if (ae2) {
            if (contains(t, "inscriber", "charger", "transform")) {
                return new Hint(ProcessKind.ASSEMBLY, 2, "tools", "ae2");
            }
        }
        if (ie) {
            if (contains(t, "crusher", "arc")) {
                return new Hint(ProcessKind.CRUSH, 2, "materials", "immersiveengineering");
            }
            if (contains(t, "fermenter", "refinery", "mixer")) {
                return new Hint(ProcessKind.CHEM, 2, "materials", "immersiveengineering");
            }
        }
        if (botania) {
            if (contains(t, "mana", "runic", "petal", "terra", "elven", "infusion")) {
                return new Hint(ProcessKind.RITUAL, 3, "arcane", "botania");
            }
        }
        if (ars) {
            if (contains(t, "enchant", "imbue", "glyph", "spell")) {
                return new Hint(ProcessKind.SPELL, 3, "arcane", "ars_nouveau");
            }
            if (contains(t, "ritual", "altar")) {
                return new Hint(ProcessKind.RITUAL, 3, "arcane", "ars_nouveau");
            }
        }
        if (blood) {
            if (contains(t, "altar", "ritual", "sacrific", "bound")) {
                return new Hint(ProcessKind.RITUAL, 4, "arcane", "bloodmagic");
            }
        }
        if (thaum) {
            if (contains(t, "infus", "crucible", "arcane", "research")) {
                return new Hint(ProcessKind.RITUAL, 3, "arcane", "thaumcraft");
            }
        }
        return null;
    }

    public static boolean isMagicResource(String id) {
        if (id == null) {
            return false;
        }
        String s = id.toLowerCase(Locale.ROOT);
        return s.startsWith("botania:") || s.startsWith("ars_nouveau:")
                || s.startsWith("bloodmagic:") || s.startsWith("thaumcraft:")
                || s.startsWith("astralsorcery:") || s.startsWith("occultism:")
                || s.contains("mana") || s.contains("rune") || s.contains("spell")
                || s.contains("ritual") || s.contains("arcane") || s.contains("source_");
    }

    public static boolean isTechResource(String id) {
        if (id == null) {
            return false;
        }
        String s = id.toLowerCase(Locale.ROOT);
        return s.startsWith("mekanism:") || s.startsWith("create:")
                || s.startsWith("gregtech:") || s.startsWith("thermal:")
                || s.startsWith("ae2:") || s.startsWith("immersiveengineering:")
                || s.contains("machine") || s.contains("factory") || s.contains("crusher");
    }

    private static boolean contains(String haystack, String... needles) {
        for (String n : needles) {
            if (haystack.contains(n)) {
                return true;
            }
        }
        return false;
    }

    private static String namespace(String id) {
        int i = id.indexOf(':');
        return i > 0 ? id.substring(0, i) : id;
    }
}
