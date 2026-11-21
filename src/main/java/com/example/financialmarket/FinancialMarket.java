/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.mojang.brigadier.CommandDispatcher
 *  net.minecraft.commands.CommandSourceStack
 *  net.minecraft.network.protocol.common.custom.CustomPacketPayload
 *  net.minecraft.server.level.ServerLevel
 *  net.minecraft.server.level.ServerPlayer
 *  net.minecraft.world.entity.player.Player
 *  net.minecraft.world.level.Level
 *  net.neoforged.bus.api.IEventBus
 *  net.neoforged.bus.api.SubscribeEvent
 *  net.neoforged.fml.common.EventBusSubscriber
 *  net.neoforged.fml.common.Mod
 *  net.neoforged.neoforge.event.RegisterCommandsEvent
 *  net.neoforged.neoforge.event.entity.player.PlayerEvent$PlayerLoggedInEvent
 *  net.neoforged.neoforge.event.server.ServerStartedEvent
 *  net.neoforged.neoforge.event.server.ServerStoppingEvent
 *  net.neoforged.neoforge.event.tick.LevelTickEvent$Post
 */
package com.example.financialmarket;

import com.example.financialmarket.attachment.AttachmentInit;
import com.example.financialmarket.attachment.MarketAttachment;
import com.example.financialmarket.attachment.PlayerEconomy;
import com.example.financialmarket.command.BankCommand;
import com.example.financialmarket.command.CompanyCommand;
import com.example.financialmarket.command.MoneyCommand;
import com.example.financialmarket.command.P2Command;
import com.example.financialmarket.engine.MarketEngine;
import com.example.financialmarket.engine.OHLCData;
import com.example.financialmarket.engine.PriceDiscoveryEngine;
import com.example.financialmarket.engine.macro.world.WorldActionCollector;
import com.example.financialmarket.engine.macro.world.WorldMacroCommand;
import com.example.financialmarket.engine.futures.czce.CzceCommand;
import com.example.financialmarket.engine.futures.czce.CzceOptionCommand;
import com.example.financialmarket.init.ItemInit;
import com.example.financialmarket.network.packets.MarketListPacket;
import com.example.financialmarket.network.packets.PortfolioSyncPacket;
import com.example.financialmarket.network.packets.StockDataResponsePacket;
import com.example.financialmarket.storage.db.H2DatabaseManager;
import com.example.financialmarket.web.LocalHttpServer;
import com.mojang.brigadier.CommandDispatcher;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.AdvancementEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

@Mod(value="financialmarket")
public class FinancialMarket {
    public static final String MODID = "financialmarket";

    public FinancialMarket(IEventBus modEventBus) {
        ItemInit.ITEMS.register(modEventBus);
        AttachmentInit.register(modEventBus);
    }

    @EventBusSubscriber(modid="financialmarket")
    public static class Events {
        private static boolean httpStarted = false;

        @SubscribeEvent
        public static void onServerStarted(ServerStartedEvent event) {
            H2DatabaseManager.open(event.getServer());
            if (!httpStarted) {
                LocalHttpServer.start();
                httpStarted = true;
                System.out.println("\u2705 HTTP \u670d\u52a1\u5668\u5df2\u542f\u52a8");
            }
        }

        @SubscribeEvent
        public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
            ServerPlayer player = (ServerPlayer)event.getEntity();
            ServerLevel level = player.serverLevel();
            MarketEngine engine = MarketAttachment.getEngine(level);
            if (engine == null) {
                engine = MarketAttachment.getOrCreateEngine(level);
            }
            if (engine != null) {
                String firstAssetId;
                MarketListPacket listPacket = MarketListPacket.create(engine);
                player.connection.send((CustomPacketPayload)listPacket);
                System.out.println("\ud83d\udce4 \u53d1\u9001\u5e02\u573a\u5217\u8868\u7ed9 " + player.getName().getString());
                PlayerEconomy eco = PlayerEconomy.get((Player)player);
                player.connection.send((CustomPacketPayload)new PortfolioSyncPacket(eco.getHoldings(), eco.getCash()));
                System.out.println("\ud83d\udce4 \u53d1\u9001\u6301\u4ed3\u7ed9 " + player.getName().getString());
                Set<String> assetIds = engine.getState().getAssetIds();
                if (!assetIds.isEmpty() && (firstAssetId = assetIds.iterator().next()) != null && !firstAssetId.isEmpty()) {
                    PriceDiscoveryEngine pe = engine.getPriceEngine();
                    java.util.List<OHLCData> klines = pe.getAggregatedKlines(firstAssetId, PriceDiscoveryEngine.KlineType.DAILY, 60);
                    java.util.List<PriceDiscoveryEngine.TradePoint> points = pe.getTradePoints(firstAssetId);
                    java.util.List<OHLCData> klinesList = klines != null ? klines : new ArrayList<>();
                    java.util.List<Map<String, Object>> klinesMap = new ArrayList<>();
                    for (OHLCData k : klinesList) {
                        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
                        map.put("open", k.getOpen());
                        map.put("high", k.getHigh());
                        map.put("low", k.getLow());
                        map.put("close", k.getClose());
                        map.put("volume", k.getVolume());
                        klinesMap.add(map);
                    }
                    java.util.List<PriceDiscoveryEngine.TradePoint> pointsList = points != null ? points : new ArrayList<>();
                    java.util.List<Map<String, Object>> pointsMap = new ArrayList<>();
                    for (PriceDiscoveryEngine.TradePoint p : pointsList) {
                        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
                        map.put("price", p.price());
                        map.put("quantity", p.quantity());
                        map.put("isBuy", p.isBuy());
                        pointsMap.add(map);
                    }
                    try {
                        player.connection.send((CustomPacketPayload)new StockDataResponsePacket(firstAssetId, klinesMap, pointsMap));
                        System.out.println("\ud83d\udce4 \u53d1\u9001 K \u7ebf\u6570\u636e\u7ed9 " + player.getName().getString() + "\uff0c\u8d44\u4ea7: " + firstAssetId);
                    }
                    catch (Exception e) {
                        System.err.println("\u274c \u53d1\u9001 K \u7ebf\u6570\u636e\u5931\u8d25: " + e.getMessage());
                        e.printStackTrace();
                    }
                }
            }
        }

        @SubscribeEvent
        public static void onItemCrafted(PlayerEvent.ItemCraftedEvent event) {
            if (event.getEntity() instanceof ServerPlayer player) {
                MarketEngine engine = MarketAttachment.getEngine(player.serverLevel());
                if (engine != null) {
                    WorldActionCollector.crafted(engine, player, event.getCrafting(), "crafting");
                }
            }
        }

        @SubscribeEvent
        public static void onBlockBroken(BlockEvent.BreakEvent event) {
            if (event.getPlayer() instanceof ServerPlayer player) {
                MarketEngine engine = MarketAttachment.getEngine(player.serverLevel());
                if (engine != null) {
                    WorldActionCollector.blockBroken(engine, player, event.getState());
                }
            }
        }

        @SubscribeEvent
        public static void onBlockPlaced(BlockEvent.EntityPlaceEvent event) {
            if (event.getEntity() instanceof LivingEntity living
                    && living.level() instanceof ServerLevel level) {
                MarketEngine engine = MarketAttachment.getEngine(level);
                if (engine != null) {
                    WorldActionCollector.blockPlaced(engine, living, event.getPlacedBlock());
                }
            }
        }

        @SubscribeEvent
        public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
            if (event.getEntity() instanceof ServerPlayer player) {
                MarketEngine engine = MarketAttachment.getEngine(player.serverLevel());
                if (engine != null) {
                    WorldActionCollector.spell(engine, player, event.getItemStack());
                }
            }
        }

        @SubscribeEvent
        public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
            if (event.getEntity() instanceof ServerPlayer player
                    && event.getLevel() instanceof ServerLevel level) {
                MarketEngine engine = MarketAttachment.getEngine(level);
                if (engine != null) {
                    WorldActionCollector.ritual(engine, player, level.getBlockState(event.getPos()));
                }
            }
        }

        @SubscribeEvent
        public static void onLivingDeath(LivingDeathEvent event) {
            if (event.getEntity() instanceof LivingEntity living
                    && living.level() instanceof ServerLevel level) {
                MarketEngine engine = MarketAttachment.getEngine(level);
                if (engine != null) {
                    WorldActionCollector.death(engine, living);
                }
            }
        }

        @SubscribeEvent
        public static void onAdvancement(AdvancementEvent.AdvancementEarnEvent event) {
            if (event.getEntity() instanceof ServerPlayer player) {
                MarketEngine engine = MarketAttachment.getEngine(player.serverLevel());
                if (engine != null) {
                    WorldActionCollector.advancement(engine, player,
                            event.getAdvancement().id().toString());
                }
            }
        }

        @SubscribeEvent
        public static void onLevelTick(LevelTickEvent.Post event) {
            ServerLevel serverLevel;
            MarketEngine engine;
            Level level = event.getLevel();
            if (level instanceof ServerLevel && (engine = MarketAttachment.getEngine(serverLevel = (ServerLevel)level)) != null) {
                engine.tick(serverLevel);
            }
        }

        @SubscribeEvent
        public static void onRegisterCommands(RegisterCommandsEvent event) {
            CompanyCommand.register((CommandDispatcher<CommandSourceStack>)event.getDispatcher());
            WorldMacroCommand.register((CommandDispatcher<CommandSourceStack>)event.getDispatcher());
            CzceCommand.register((CommandDispatcher<CommandSourceStack>)event.getDispatcher());
            CzceOptionCommand.register((CommandDispatcher<CommandSourceStack>)event.getDispatcher());
            P2Command.register((CommandDispatcher<CommandSourceStack>)event.getDispatcher());
            MoneyCommand.register((CommandDispatcher<CommandSourceStack>)event.getDispatcher());
            BankCommand.register((CommandDispatcher<CommandSourceStack>)event.getDispatcher());
        }

        @SubscribeEvent
        public static void onServerStopping(ServerStoppingEvent event) {
            LocalHttpServer.stop();
            httpStarted = false;
            for (ServerLevel level : event.getServer().getAllLevels()) {
                MarketAttachment.saveEngine(level);
            }
            System.out.println("\u2705 \u6240\u6709\u5f15\u64ce\u6570\u636e\u5df2\u4fdd\u5b58");
            H2DatabaseManager.close();
        }
    }
}
