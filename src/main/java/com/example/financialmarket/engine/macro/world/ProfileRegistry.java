package com.example.financialmarket.engine.macro.world;

import com.example.financialmarket.engine.macro.world.WorldMacroModel.ResourceKey;
import com.example.financialmarket.engine.macro.world.WorldMacroModel.ResourceKind;
import com.example.financialmarket.engine.macro.world.WorldMacroModel.ResourceProfile;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Canonical resource profiles and aliases.
 *
 * <p>Lookup order is represented by how callers register: pack overrides should be registered last
 * (or with {@link #put(ResourceProfile, boolean)}), then mod data, then auto-derived profiles.
 * Unknown resources resolve to an {@code OTHER} profile with a low impact weight instead of being
 * silently dropped.</p>
 */
public final class ProfileRegistry {

    private final Map<String, ResourceProfile> byId = new LinkedHashMap<>();
    private final Map<String, String> aliasToId = new LinkedHashMap<>();
    private final Set<String> unmapped = new LinkedHashSet<>();
    private final Set<String> explicit = new LinkedHashSet<>();

    public void put(ResourceProfile profile) {
        put(profile, false);
    }

    /** @param override true for pack/mod overrides; they replace auto-derived profiles. */
    public void put(ResourceProfile profile, boolean override) {
        if (profile == null || profile.key() == null) {
            return;
        }
        String id = profile.key().id();
        if (!override && explicit.contains(id)) {
            return;
        }
        byId.put(id, profile);
        aliasToId.put(id, id);
        for (String tag : profile.tags()) {
            aliasToId.putIfAbsent(tag, id);
        }
        if (override) {
            explicit.add(id);
        }
    }

    public void alias(String alias, String canonicalId) {
        if (alias == null || canonicalId == null) {
            return;
        }
        if (byId.containsKey(canonicalId)) {
            aliasToId.put(alias, canonicalId);
        }
    }

    public ResourceProfile resolve(String id) {
        if (id == null || id.isBlank()) {
            return ResourceProfile.other(new ResourceKey("other:unknown", ResourceKind.ITEM));
        }
        String canonical = aliasToId.getOrDefault(id, id);
        ResourceProfile p = byId.get(canonical);
        if (p != null) {
            return p;
        }
        unmapped.add(id);
        return ResourceProfile.other(new ResourceKey(id, kindOf(id)));
    }

    public ResourceProfile resolve(ResourceKey key) {
        return resolve(key.id());
    }

    public boolean contains(String id) {
        return byId.containsKey(aliasToId.getOrDefault(id, id));
    }

    public Collection<ResourceProfile> all() {
        return Collections.unmodifiableCollection(byId.values());
    }

    public Set<String> unmapped() {
        return Collections.unmodifiableSet(unmapped);
    }

    public int size() {
        return byId.size();
    }

    public void clearUnmapped() {
        unmapped.clear();
    }

    public static ResourceKind kindOf(String id) {
        if (id == null) {
            return ResourceKind.ITEM;
        }
        String s = id.toLowerCase();
        if (s.startsWith("fluid:") || s.contains("fluid")) {
            return ResourceKind.FLUID;
        }
        if (s.startsWith("energy:") || s.contains("fe") || s.contains("rf") || s.contains("joule")) {
            return ResourceKind.ENERGY;
        }
        if (s.startsWith("mana:") || s.contains("mana") || s.contains("source")) {
            return ResourceKind.MANA;
        }
        if (s.startsWith("service:") || s.contains("spell")) {
            return ResourceKind.SERVICE;
        }
        if (s.startsWith("knowledge:") || s.contains("research")) {
            return ResourceKind.KNOWLEDGE;
        }
        return ResourceKind.ITEM;
    }

    /** Small set of vanilla defaults so M1 works without any data pack. */
    public static ProfileRegistry vanillaDefaults() {
        ProfileRegistry r = new ProfileRegistry();
        add(r, "minecraft:iron_ore", "materials", "raw", 1, 8.0, Set.of("c:ores", "c:iron_ores"));
        add(r, "minecraft:raw_iron", "materials", "raw", 1, 6.0, Set.of("c:raw_materials"));
        add(r, "minecraft:iron_ingot", "materials", "intermediate", 1, 12.0, Set.of("c:ingots"));
        add(r, "minecraft:coal", "energy", "raw", 1, 4.0, Set.of("c:coal", "c:fuels"));
        add(r, "minecraft:oak_log", "materials", "raw", 1, 2.0, Set.of("c:logs"));
        add(r, "minecraft:oak_planks", "materials", "intermediate", 1, 3.0, Set.of("c:planks"));
        add(r, "minecraft:wheat", "food", "raw", 1, 3.0, Set.of("c:crops", "c:foods"));
        add(r, "minecraft:bread", "food", "consumer", 1, 6.0, Set.of("c:foods"));
        add(r, "minecraft:cobblestone", "materials", "raw", 0, 1.0, Set.of("c:cobblestone"));
        add(r, "minecraft:stone", "materials", "intermediate", 0, 1.5, Set.of("c:stone"));
        add(r, "energy:fe", "energy", "energy", 1, 0.05, Set.of("c:energy"));
        add(r, "mana:generic", "arcane", "energy", 2, 0.10, Set.of("c:mana"));
        add(r, "service:spell", "arcane", "service", 2, 20.0, Set.of("c:spells"));
        add(r, "service:loss", "services", "loss", 1, 10.0, Set.of("c:loss"));
        add(r, "knowledge:research", "services", "knowledge", 2, 50.0, Set.of("c:research"));
        r.alias("c:ingots/iron", "minecraft:iron_ingot");
        r.alias("c:ores/iron", "minecraft:iron_ore");
        r.alias("c:foods/bread", "minecraft:bread");
        return r;
    }

    private static void add(ProfileRegistry r, String id, String sector, String category, int tier,
                            double value, Set<String> tags) {
        r.put(new ResourceProfile(new ResourceKey(id, kindOf(id)), sector, category, tier, value,
                0.6, tags, Set.of()), true);
    }

    /** Diagnostics for /fm macro status. */
    public List<String> describe(int limit) {
        List<String> out = new ArrayList<>();
        int n = 0;
        for (ResourceProfile p : byId.values()) {
            if (n++ >= limit) {
                break;
            }
            out.add(p.key().id() + " sector=" + p.sector() + " category=" + p.category()
                    + " tier=" + p.tier() + " value=" + p.unitValue());
        }
        return out;
    }
}
