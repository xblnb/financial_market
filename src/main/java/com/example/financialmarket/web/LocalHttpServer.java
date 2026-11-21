package com.example.financialmarket.web;

import com.example.financialmarket.attachment.Holding;
import com.example.financialmarket.attachment.MarketAttachment;
import com.example.financialmarket.attachment.PlayerEconomy;
import com.example.financialmarket.engine.*;
import com.example.financialmarket.engine.ai.AICompanyData;
import com.example.financialmarket.engine.banking.Bank;
import com.example.financialmarket.engine.banking.BankCard;
import com.example.financialmarket.engine.banking.BankCardService;
import com.example.financialmarket.engine.banking.PaymentService;
import com.example.financialmarket.engine.banking.CreditSystem;
import com.example.financialmarket.engine.banking.GovernmentBond;
import com.example.financialmarket.engine.banking.InterbankMarket;
import com.example.financialmarket.engine.banking.Loan;
import com.example.financialmarket.engine.quant.QuantAPI;
import com.example.financialmarket.engine.quant.QuantScriptEngine;
import com.example.financialmarket.company.CompanyData;
import com.example.financialmarket.engine.futures.FuturesContract;
import com.example.financialmarket.engine.futures.FuturesMarket;
import com.example.financialmarket.engine.futures.FuturesPosition;
import com.example.financialmarket.engine.futures.czce.CzceFuturesMarket;
import com.example.financialmarket.engine.futures.czce.CzceOptionMarket;
import com.example.financialmarket.engine.futures.czce.CzceOptionDesk;
import com.example.financialmarket.engine.futures.czce.SabrModel;
import com.example.financialmarket.engine.gold.GoldPricingSystem;
import com.example.financialmarket.engine.leverage.LeveragedNote;
import com.example.financialmarket.engine.leverage.LeveragedNoteMarket;
import com.example.financialmarket.engine.leverage.LeveragedNotePosition;
import com.example.financialmarket.engine.margin.MarginAccount;
import com.example.financialmarket.engine.margin.MarginManager;
import com.example.financialmarket.engine.macro.world.WorldMacroSnapshot;
import com.example.financialmarket.engine.macro.cross.CrossAssetMacro;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class LocalHttpServer {
    private static HttpServer server;
    private static final int PORT = 8080;
    private static final AtomicBoolean isRunning = new AtomicBoolean(false);
    private static final Object lock = new Object();
    private static final Gson GSON = new GsonBuilder().create();

    // ===== HTTP 交易绑定玩家（AS-3 §5.3）=====
    // HTTP 无 Minecraft 玩家上下文：默认绑定到「web.tradingPlayer」配置（UUID/玩家名）或首个在线真实玩家，
    // 也可经 POST /api/session 动态绑定。交易请求均在绑定玩家身份下执行 A 股校验与记账（对齐 PlaceOrderPacket.handle）。
    private static volatile java.util.UUID boundTradingPlayerId = null;
    private static volatile String boundTradingPlayerName = null;
    // 指数涨跌（changePoint/change 不再恒 0）：缓存上一采样值，滚动增量。确定性、无随机。
    private static volatile double lastIndexValue = 0.0;
    // 关闭标志：游戏内 WebView 的 closeScreen() 经 HTTP 置位，FinancialMCEFScreen 轮询后关闭屏幕。
    private static final AtomicBoolean closeScreenRequested = new AtomicBoolean(false);
    // 商品价格走势历史（展示层滚动缓存，key=商品注册键，cap 30 点，确定性采样）。
    private static final Map<String, List<Double>> commodityHistory = new HashMap<>();

    public static void start() {
        synchronized (lock) {
            if (isRunning.get()) {
                System.out.println("⚠️ HTTP 服务器已在运行");
                return;
            }
            try {
                // F2（连接审计修复 P8）：只绑定回环地址 —— MCEF 浏览器在进程内以 localhost:PORT 加载，
                // 回环绑定不影响本机页面；此前 0.0.0.0 全接口暴露 + /api/session 可绑定任意在线玩家，
                // 局域网内任意主机可冒用玩家身份下单/取款（盗号交易）。
                server = HttpServer.create(new InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), PORT), 0);
                server.setExecutor(Executors.newCachedThreadPool());

                // HTTP 交易绑定玩家默认配置（web.tradingPlayer = UUID 或玩家名，可选）
                String cfgPlayer = System.getProperty("web.tradingPlayer");
                if (cfgPlayer != null && !cfgPlayer.isBlank()) {
                    boundTradingPlayerId = resolveUuidOrNull(cfgPlayer);
                    boundTradingPlayerName = boundTradingPlayerId == null ? cfgPlayer.trim() : null;
                    System.out.println("🔗 [LocalHttpServer] 交易绑定玩家配置: " + cfgPlayer);
                }

                // ===== 静态资源 =====
                server.createContext("/", ex -> {
                    String path = ex.getRequestURI().getPath();
                    if ("/".equals(path) || "/index.html".equals(path)) {
                        sendHtml(ex, loadResource("html/index.html"));
                    } else if ("/legacy".equals(path) || "/index_legacy.html".equals(path)) {
                        sendHtml(ex, loadResource("html/index_legacy.html"));
                    } else if ("/worldmacro.html".equals(path)) {
                        sendHtml(ex, loadResource("html/worldmacro.html"));
                    } else if (path.endsWith(".css")) {
                        sendCss(ex, loadResource("html/" + path.substring(path.lastIndexOf('/') + 1)));
                    } else if (path.endsWith(".js")) {
                        sendJs(ex, loadResource("html/" + path.substring(path.lastIndexOf('/') + 1)));
                    } else {
                        send404(ex);
                    }
                });

                // ===== API =====
                server.createContext("/api/stocks", ex -> sendJson(ex, getStockListJson()));
        server.createContext("/api/worldmacro", ex -> sendJson(ex, getWorldMacroJson()));
        server.createContext("/api/crossmacro", ex -> sendJson(ex, getCrossMacroJson()));
        server.createContext("/api/ak", ex -> sendJson(ex, getAkJson()));
                server.createContext("/api/index", ex -> sendJson(ex, getIndexJson()));
                server.createContext("/api/kline", ex -> {
                    Map<String, String> params = queryToMap(ex.getRequestURI().getQuery());
                    String id = params.get("id");
                    int type = Integer.parseInt(params.getOrDefault("type", "4"));
                    sendJson(ex, getKlineJson(id, type));
                });
                server.createContext("/api/depth", ex -> {
                    String id = queryToMap(ex.getRequestURI().getQuery()).get("id");
                    sendJson(ex, getDepthJson(id));
                });
                server.createContext("/api/tick", ex -> {
                    String id = queryToMap(ex.getRequestURI().getQuery()).get("id");
                    sendJson(ex, getTickJson(id));
                });
                server.createContext("/api/company", ex -> {
                    String id = queryToMap(ex.getRequestURI().getQuery()).get("id");
                    sendJson(ex, getCompanyJson(id));
                });
                // F2（连接审计修复 H1）：任意在线玩家可按公司名认购其 IPO（此前 /fmarket company subscribe
                // 仅创始人可达 → 流通股发行 = 创始人自买，公司链认购环节对非创始人断点）。
                server.createContext("/api/company/subscribe", LocalHttpServer::handleCompanySubscribe);
                server.createContext("/api/fundamentals", ex -> {
                    String id = queryToMap(ex.getRequestURI().getQuery()).get("id");
                    sendJson(ex, getFundamentalsJson(id));
                });
                server.createContext("/api/trades", ex -> {
                    Map<String, String> params = queryToMap(ex.getRequestURI().getQuery());
                    String id = params.get("id");
                    int limit = Integer.parseInt(params.getOrDefault("limit", "50"));
                    sendJson(ex, getTradeHistoryJson(id, limit));
                });
                server.createContext("/api/daily", ex -> {
                    String id = queryToMap(ex.getRequestURI().getQuery()).get("id");
                    sendJson(ex, getDailyDataJson(id));
                });

                // ===== 交易/账户 API（t4：LocalHttpServer 交易接口扩展，AS-3 §5.3）=====
                server.createContext("/api/account", ex -> sendJson(ex, getAccountJson()));
                server.createContext("/api/positions", ex -> sendJson(ex, getPositionsJson()));
                server.createContext("/api/portfolio", ex -> sendJson(ex, getPortfolioJson()));
                server.createContext("/api/orders", ex -> sendJson(ex, getOrdersJson()));
                server.createContext("/api/myTrades", ex -> sendJson(ex, getMyTradesJson()));
                server.createContext("/api/session", LocalHttpServer::handleSession);
                server.createContext("/api/order", LocalHttpServer::handlePlaceOrder);
                server.createContext("/api/cancelOrder", LocalHttpServer::handleCancelOrder);
                // ===== 商品/期货 API（t4，CF-9） =====
                server.createContext("/api/commodities", ex -> sendJson(ex, getCommoditiesJson()));
                server.createContext("/api/commodity/smoke", LocalHttpServer::handleCommoditySmoke);
                server.createContext("/api/futures", ex -> sendJson(ex, getFuturesJson()));
        server.createContext("/api/czce", ex -> sendJson(ex, getCzceJson()));
        server.createContext("/api/options", ex -> sendJson(ex, getOptionsJson(ex)));
                server.createContext("/api/futures/positions", ex -> sendJson(ex, getFuturesPositionsJson()));
                server.createContext("/api/commodity/order", LocalHttpServer::handleCommodityOrder);
                server.createContext("/api/futures/order", LocalHttpServer::handleFuturesOrder);
                // t3：黄金 API（下界合金锭模拟现代黄金；现货价/央行储备/避险状态，NG-4/NG-5）
                server.createContext("/api/gold", ex -> sendJson(ex, getGoldJson()));
                // ===== 保证金/杠杆票据 API（t6，AS-M*/AS-L1，ML-8） =====
                server.createContext("/api/margin", ex -> sendJson(ex, getMarginJson()));
                server.createContext("/api/margin/deposit", LocalHttpServer::handleMarginDeposit);
                server.createContext("/api/leverage/notes", ex -> sendJson(ex, getLeverageNotesJson()));
                server.createContext("/api/leverage/positions", ex -> sendJson(ex, getLeveragePositionsJson()));
                server.createContext("/api/leverage/issue", LocalHttpServer::handleLeverageIssue);
                server.createContext("/api/leverage/order", LocalHttpServer::handleLeverageOrder);
                // ===== 公司列表/银行/借贷/量化/云图 API（t5, FRONTEND_COMPLETE_SPEC §5） =====
                server.createContext("/api/companies", ex -> sendJson(ex, getCompaniesJson()));
                server.createContext("/api/banks", ex -> sendJson(ex, getBanksJson()));
                server.createContext("/api/banking", ex -> sendJson(ex, getBankingJson()));
                server.createContext("/api/loans", ex -> sendJson(ex, getLoansJson()));
                server.createContext("/api/bonds", ex -> sendJson(ex, getBondsJson()));
                server.createContext("/api/macro", ex -> sendJson(ex, getMacroJson()));
                // ===== CB-t5（CENTRAL_BANK_SPEC §9.4）：央行与货币体系只读端点 =====
                // 纪律：①**数值直传原值**（不做 ×100 换算，前端负责展示换算；DATA_GUARD_SPEC §5.1 展示分层）；
                //       ②**只增不改**既有端点字段（R6）；③全部只读，不触任何账本写入。
                server.createContext("/api/money", ex -> sendJson(ex, getMoneyJson()));
                server.createContext("/api/money/identities", ex -> sendJson(ex, getMoneyIdentitiesJson()));
                server.createContext("/api/centralbank", ex -> sendJson(ex, getCentralBankJson()));
                server.createContext("/api/loan/apply", LocalHttpServer::handleLoanApply);
                server.createContext("/api/loan/repay", LocalHttpServer::handleLoanRepay);
                server.createContext("/api/bank/deposit", LocalHttpServer::handleBankDeposit);
                server.createContext("/api/bank/withdraw", LocalHttpServer::handleBankWithdraw);
                // 冒烟阻断项修复：真实玩家发卡入口（服务端权威；与银行平板 CardIssuePacket 同语义）
                server.createContext("/api/bank/card", LocalHttpServer::handleBankCard);
                server.createContext("/api/bank/card/pin", ex -> handleBankCardAction(ex, "pin"));
                server.createContext("/api/bank/card/freeze", ex -> handleBankCardAction(ex, "freeze"));
                server.createContext("/api/bank/card/unfreeze", ex -> handleBankCardAction(ex, "unfreeze"));
                server.createContext("/api/bank/transfer", ex -> handlePayment(ex, false));
                server.createContext("/api/bank/pay", ex -> handlePayment(ex, true));
                server.createContext("/api/bank/statement", LocalHttpServer::handleBankStatement);
                server.createContext("/api/bank/selftest", ex -> sendJson(ex, bankPaymentSelfTest()));
                server.createContext("/api/quant/strategies", ex -> sendJson(ex, getQuantStrategiesJson()));
                server.createContext("/api/quant/script", LocalHttpServer::handleQuantScript);
                server.createContext("/api/quant/status", ex -> sendJson(ex, getQuantStatusJson(ex)));
                server.createContext("/api/quant/strategy", LocalHttpServer::handleQuantStrategy);
                server.createContext("/api/quant/strategy/enable", LocalHttpServer::handleQuantEnable);
                server.createContext("/api/quant/strategy/disable", LocalHttpServer::handleQuantDisable);
                server.createContext("/api/quant/run", LocalHttpServer::handleQuantRun);
                server.createContext("/api/marketmap", ex -> sendJson(ex, getMarketmapJson()));
                server.createContext("/api/closeScreen", ex -> {
                    if ("POST".equalsIgnoreCase(ex.getRequestMethod())) {
                        closeScreenRequested.set(true);
                        sendJson(ex, "{\"success\":true}");
                    } else {
                        sendJson(ex, 400, "{\"success\":false,\"message\":\"仅支持 POST\"}");
                    }
                });

                server.start();
                isRunning.set(true);
                System.out.println("✅ HTTP 服务器启动在端口 " + PORT);
            } catch (IOException e) {
                System.err.println("❌ 端口 " + PORT + " 被占用，请关闭占用程序");
                e.printStackTrace();
            }
        }
    }

    public static void stop() {
        synchronized (lock) {
            if (server != null) {
                server.stop(1);
                server = null;
            }
            isRunning.set(false);
            System.out.println("🛑 HTTP 服务器已停止");
        }
    }

    private static Map<String, String> queryToMap(String query) {
        Map<String, String> map = new HashMap<>();
        if (query == null || query.isEmpty()) return map;
        for (String param : query.split("&")) {
            String[] pair = param.split("=", 2);
            if (pair.length == 2) map.put(pair[0], pair[1]);
        }
        return map;
    }

    private static MarketEngine getEngine() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            System.err.println("❌ [LocalHttpServer] Server is null");
            return null;
        }
        for (ServerLevel level : server.getAllLevels()) {
            if (level.dimension() == Level.OVERWORLD) {
                MarketEngine engine = MarketAttachment.getEngine(level);
                if (engine != null) {
                    System.out.println("✅ [LocalHttpServer] 获取到引擎，资产数: " + engine.getState().getAssetIds().size());
                    return engine;
                }
                // Server-side API can initialize the engine before any player logs in. This keeps
                // smoke/monitoring endpoints usable on a fresh dedicated server.
                MarketEngine created = MarketAttachment.getOrCreateEngine(level);
                if (created != null) {
                    System.out.println("✅ [LocalHttpServer] 初始化引擎，资产数: "
                            + created.getState().getAssetIds().size());
                    return created;
                }
            }
        }
        return null;
    }

    private static double fmt(Number value) {
        if (value == null) return 0.0;
        double d = value.doubleValue();
        if (Double.isNaN(d) || Double.isInfinite(d)) return 0.0;
        return Math.round(d * 100.0) / 100.0;
    }

    private static double fmt(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return 0.0;
        return Math.round(value * 100.0) / 100.0;
    }

    // ===== API 实现 =====

    private static String getStockListJson() {
        MarketEngine eng = getEngine();
        if (eng == null) return "[]";
        List<Map<String, Object>> list = new ArrayList<>();
        for (String id : eng.getState().getAssetIds()) {
            AssetSnapshot snap = eng.getState().getAsset(id);
            if (snap == null) continue;
            AICompanyData company = eng.getAiCompanyManager().getAll().stream()
                    .filter(c -> c.getStockId().equals(id))
                    .findFirst().orElse(null);
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("stockId", id);
            // 显示名：优先 AI 公司中文名（命名系统 CompanyNameGenerator），缺失回退 id
            s.put("name", company != null && company.getName() != null && !company.getName().isBlank()
                    ? company.getName() : id);
            // A 股 6 位代码（AStockCodePool 分配），无则回退 id 供显示
            s.put("code", (company != null && company.getCnCode() != null && !company.getCnCode().isBlank())
                    ? company.getCnCode() : id);
            // F-2（冒烟一致性修复）：列表「现价」取<b>成交锚定</b>的单一价格源
            // （priceEngine.lastPrice = 最近成交价/开仓价，与 sparkline/K线 同一成交序列），
            // 不再读 snap.lastPrice（其被 F3.4 宏观耦合每 tick 拉向 GBM，与成交/K线脱节时会误导用户）。
            double lastTrade = eng.getPriceEngine().getLastPrice(id);
            if (lastTrade <= 0) lastTrade = snap.getLastPrice();
            s.put("price", fmt(lastTrade));
            s.put("change", fmt(snap.getDailyChange()));
            // 成交额/成交量（确定性只读）：volume 取引擎当日成交量，amount = volume × 现价（同一来源）
            s.put("volume", snap.getVolume());
            s.put("amount", fmt(snap.getVolume() * lastTrade));

            if (company != null) {
                s.put("industry", company.getIndustry());
                s.put("roe", fmt(company.getRoe()));
                s.put("eps", fmt(company.getEps()));
                double pe = company.getEps() > 0 ? lastTrade / company.getEps() : 0;
                s.put("pe", fmt(pe));
                s.put("nav", fmt(company.getNetAssetValue()));
                s.put("revenueGrowth", fmt(company.getRevenueGrowth()));
                s.put("dividendYield", fmt(company.getDividendYield()));
            }

            List<Double> closes = eng.getPriceEngine().getRecentCloses(id, 30);
            s.put("sparkline", closes.stream().map(LocalHttpServer::fmt).toList());
            list.add(s);
        }
        return GSON.toJson(list);
    }

    private static String getIndexJson() {
        MarketEngine eng = getEngine();
        if (eng == null) return "{}";
        // FIX-3：指数真源改读 engine.getIndexValueEx()（excellent24Index），绕过 MarketState.getIndexValue() 的 null 桩。
        double value = eng.getIndexValueEx();
        // 滚动增量（相对上一采样）：changePoint 涨跌点数、change 涨跌幅%（向后兼容：旧结构 change 恒 0）
        double changePoint = 0.0;
        double change = 0.0;
        if (lastIndexValue > 0 && value > 0) {
            changePoint = fmt(value - lastIndexValue);
            change = fmt((value - lastIndexValue) / lastIndexValue * 100.0);
        }
        lastIndexValue = value;
        Map<String, Double> data = new LinkedHashMap<>();
        data.put("value", fmt(value));
        data.put("changePoint", changePoint);
        data.put("change", change);
        return GSON.toJson(data);
    }

    private static String getKlineJson(String assetId, int type) {
        MarketEngine eng = getEngine();
        if (eng == null || assetId == null) {
            return "{\"klines\":[],\"tradePoints\":[]}";
        }

        PriceDiscoveryEngine pe = eng.getPriceEngine();

        PriceDiscoveryEngine.KlineType targetType;
        switch (type) {
            case 0:  targetType = PriceDiscoveryEngine.KlineType.TICK; break;
            case 1:  targetType = PriceDiscoveryEngine.KlineType.QUARTER_DAY; break;
            case 2:  targetType = PriceDiscoveryEngine.KlineType.HALF_DAY; break;
            case 4:  targetType = PriceDiscoveryEngine.KlineType.DAILY; break;
            case 5:  targetType = PriceDiscoveryEngine.KlineType.MONTHLY; break;
            default: targetType = PriceDiscoveryEngine.KlineType.DAILY; break;
        }

        List<Map<String, Object>> enhanced = pe.getTechnicalKlines(assetId, targetType, 60);
        if (enhanced == null) enhanced = new ArrayList<>();

        System.out.println("📊 [getKlineJson] assetId=" + assetId + ", type=" + targetType + ", count=" + enhanced.size());

        List<PriceDiscoveryEngine.TradePoint> rawPoints = pe.getTradePoints(assetId);
        List<Map<String, Object>> tradePoints = new ArrayList<>();
        for (int i = 0; i < Math.min(rawPoints.size(), 50); i++) {
            PriceDiscoveryEngine.TradePoint p = rawPoints.get(i);
            Map<String, Object> tp = new LinkedHashMap<>();
            tp.put("time", String.valueOf(i));
            tp.put("price", fmt(p.price()));
            tp.put("isBuy", p.isBuy());
            tradePoints.add(tp);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("klines", enhanced);
        result.put("tradePoints", tradePoints);
        return GSON.toJson(result);
    }

    // ===== 修复：盘口 API 使用字段访问 =====
    private static String getDepthJson(String assetId) {
        MarketEngine eng = getEngine();
        if (eng == null || assetId == null) return "{\"bids\":[],\"asks\":[]}";

        OrderBook orderBook = eng.getOrderBooks().get(assetId);
        if (orderBook == null) {
            System.out.println("⚠️ [getDepthJson] 订单簿不存在: " + assetId);
            return "{\"bids\":[],\"asks\":[]}";
        }

        var snap = orderBook.getSnapshot();
        if (snap == null) return "{\"bids\":[],\"asks\":[]}";

        Map<String, List<List<Double>>> depth = new LinkedHashMap<>();
        List<List<Double>> bids = new ArrayList<>();
        for (var l : snap.bids) {
            bids.add(List.of(fmt(l.price), (double) l.quantity));  // 关键修复：字段访问
        }
        List<List<Double>> asks = new ArrayList<>();
        for (var l : snap.asks) {
            asks.add(List.of(fmt(l.price), (double) l.quantity)); // 关键修复：字段访问
        }
        depth.put("bids", bids);
        depth.put("asks", asks);
        System.out.println("📊 [getDepthJson] assetId=" + assetId + ", bids=" + bids.size() + ", asks=" + asks.size());
        return GSON.toJson(depth);
    }

    private static String getTickJson(String assetId) {
        MarketEngine eng = getEngine();
        if (eng == null || assetId == null) return "{\"ticks\":[]}";
        List<Map<String, Object>> ticks = eng.getPriceEngine().getTradeTicks(assetId, 50);
        for (Map<String, Object> tick : ticks) {
            Object priceObj = tick.get("price");
            double price = (priceObj instanceof Number) ? ((Number) priceObj).doubleValue() : 0.0;
            tick.put("price", fmt(price));
            Object volObj = tick.get("vol");
            long vol = (volObj instanceof Number) ? ((Number) volObj).longValue() : 0L;
            tick.put("vol", vol);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ticks", ticks);
        return GSON.toJson(result);
    }

    private static String getCompanyJson(String assetId) {
        MarketEngine eng = getEngine();
        if (eng == null || assetId == null) return "{}";
        AICompanyData company = eng.getAiCompanyManager().getAll().stream()
                .filter(c -> c.getStockId().equals(assetId))
                .findFirst().orElse(null);
        if (company == null) return "{}";
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("stockId", company.getStockId());
        data.put("name", company.getName());
        data.put("industry", company.getIndustry());
        data.put("netAssetValue", fmt(company.getNetAssetValue()));
        data.put("recentProfit", fmt(company.getRecentProfit()));
        data.put("revenue", fmt(company.getRevenue()));
        data.put("revenueGrowth", fmt(company.getRevenueGrowth()));
        data.put("eps", fmt(company.getEps()));
        data.put("roe", fmt(company.getRoe()));
        data.put("dividendYield", fmt(company.getDividendYield()));
        data.put("debtRatio", fmt(company.getDebtRatio()));
        data.put("bookValuePerShare", fmt(company.getBookValuePerShare()));
        data.put("fairValue", fmt(company.getFairValue()));
        data.put("growthStage", fmt(company.getGrowthStage()));
        data.put("profitabilityScore", fmt(company.getProfitabilityScore()));
        data.put("financialHealthScore", fmt(company.getFinancialHealthScore()));
        data.put("totalShares", company.getTotalShares());
        data.put("ipoPrice", fmt(company.getIpoPrice()));
        AssetSnapshot snap = eng.getState().getAsset(assetId);
        if (snap != null) {
            double price = snap.getLastPrice();
            data.put("currentPrice", fmt(price));
            data.put("peRatio", company.getEps() > 0 ? fmt(price / company.getEps()) : 0);
            data.put("pbRatio", company.getBookValuePerShare() > 0 ? fmt(price / company.getBookValuePerShare()) : 0);
        }
        return GSON.toJson(data);
    }

    private static String getFundamentalsJson(String assetId) {
        MarketEngine eng = getEngine();
        if (eng == null || assetId == null) return "{}";
        AICompanyData company = eng.getAiCompanyManager().getAll().stream()
                .filter(c -> c.getStockId().equals(assetId)).findFirst().orElse(null);
        if (company == null) return "{}";
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("stockId", company.getStockId());
        data.put("name", company.getName());
        data.put("industry", company.getIndustry());
        data.put("netAssetValue", fmt(company.getNetAssetValue()));
        data.put("revenue", fmt(company.getRevenue()));
        data.put("revenueGrowth", fmt(company.getRevenueGrowth()));
        data.put("eps", fmt(company.getEps()));
        data.put("roe", fmt(company.getRoe()));
        data.put("dividendYield", fmt(company.getDividendYield()));
        data.put("debtRatio", fmt(company.getDebtRatio()));
        data.put("bookValuePerShare", fmt(company.getBookValuePerShare()));
        data.put("fairValue", fmt(company.getFairValue()));
        data.put("fundamentalScore", fmt(company.getFundamentalScore()));
        data.put("growthStage", fmt(company.getGrowthStage()));
        return GSON.toJson(data);
    }

    private static String getTradeHistoryJson(String assetId, int limit) {
        MarketEngine eng = getEngine();
        if (eng == null || assetId == null) return "[]";
        List<Trade> trades = eng.getTradeHistory().getOrDefault(assetId, Collections.emptyList());
        int size = trades.size();
        int start = Math.max(0, size - limit);
        List<Map<String, Object>> list = new ArrayList<>();
        for (int i = start; i < size; i++) {
            Trade t = trades.get(i);
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("time", String.valueOf(t.getTimestamp()));
            map.put("price", fmt(t.getPrice()));
            map.put("quantity", t.getQuantity());
            list.add(map);
        }
        return GSON.toJson(list);
    }

    private static String getDailyDataJson(String assetId) {
        MarketEngine eng = getEngine();
        if (eng == null || assetId == null) return "{}";
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("open", fmt(eng.getTodayOpen().getOrDefault(assetId, 0.0)));
        data.put("close", fmt(eng.getTodayClose().getOrDefault(assetId, 0.0)));
        data.put("high", fmt(eng.getState().getAsset(assetId).getHigh()));
        data.put("low", fmt(eng.getState().getAsset(assetId).getLow()));
        return GSON.toJson(data);
    }

    // ===== 交易/账户 API 实现（t4：AS-3 §5.3）=====

    /** 尝试把字符串解析为 UUID；解析失败返回 null（可能为玩家名）。 */
    private static java.util.UUID resolveUuidOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return java.util.UUID.fromString(s.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 当前在线玩家列表（服务端权威）。 */
    private static List<ServerPlayer> onlinePlayers() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return Collections.emptyList();
        return new ArrayList<>(server.getPlayerList().getPlayers());
    }

    /**
     * 解析 HTTP 交易绑定玩家：优先已绑定（config / POST /api/session），否则回落首个在线真实玩家。
     * 无在线玩家返回 null（调用方返回空结构）。确定性、无随机。
     */
    private static ServerPlayer resolveTradingPlayer() {
        List<ServerPlayer> players = onlinePlayers();
        if (players.isEmpty()) return null;
        if (boundTradingPlayerId != null) {
            for (ServerPlayer sp : players) {
                if (sp.getUUID().equals(boundTradingPlayerId)) return sp;
            }
        }
        if (boundTradingPlayerName != null) {
            for (ServerPlayer sp : players) {
                if (boundTradingPlayerName.equalsIgnoreCase(sp.getName().getString())) return sp;
            }
        }
        // 默认：首个在线真实玩家
        return players.get(0);
    }

    /** 资产显示名：优先公司名，否则回落资产 id（与 /api/stocks 的 name 语义一致）。 */
    private static String assetName(MarketEngine eng, String id) {
        if (eng != null) {
            AICompanyData c = eng.getAiCompanyManager().getAll().stream()
                    .filter(x -> x.getStockId().equals(id)).findFirst().orElse(null);
            if (c != null && c.getName() != null && !c.getName().isBlank()) return c.getName();
        }
        return id;
    }

    /** GET /api/account → {playerName, cash, totalMarketValue, totalAssets} */
    private static String getAccountJson() {
        ServerPlayer p = resolveTradingPlayer();
        if (p == null) return "{\"playerName\":\"\",\"cash\":0,\"totalMarketValue\":0,\"totalAssets\":0}";
        MarketEngine eng = getEngine();
        PlayerEconomy eco = PlayerEconomy.get(p);
        double cash = eco != null ? eco.getCash() : 0.0;
        double marketValue = 0.0;
        if (eng != null && eco != null) {
            for (Holding h : eco.getHoldings().values()) {
                AssetSnapshot snap = eng.getState().getAsset(h.getAssetId());
                if (snap != null) marketValue += h.getQuantity() * snap.getLastPrice();
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("playerName", p.getName().getString());
        m.put("cash", fmt(cash));
        m.put("totalMarketValue", fmt(marketValue));
        m.put("totalAssets", fmt(cash + marketValue));
        return GSON.toJson(m);
    }

    /** GET /api/positions → [{assetId, name, quantity, available, frozen, avgCost, lastPrice, marketValue, profit, profitPct}] */
    private static String getPositionsJson() {
        ServerPlayer p = resolveTradingPlayer();
        MarketEngine eng = getEngine();
        if (p == null || eng == null) return "[]";
        PlayerEconomy eco = PlayerEconomy.get(p);
        if (eco == null) return "[]";
        java.util.UUID pid = p.getUUID();
        List<Map<String, Object>> list = new ArrayList<>();
        for (Holding h : eco.getHoldings().values()) {
            String assetId = h.getAssetId();
            AssetSnapshot snap = eng.getState().getAsset(assetId);
            double last = snap != null ? snap.getLastPrice() : h.getAvgCost();
            int available = eng.getSettlement().getAvailable(pid, assetId);
            int frozen = eng.getSettlement().getFrozen(pid, assetId);
            double marketValue = h.getQuantity() * last;
            double cost = h.getAvgCost() * h.getQuantity();
            double profit = marketValue - cost;
            double profitPct = cost > 0 ? profit / cost * 100.0 : 0.0;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("assetId", assetId);
            m.put("name", assetName(eng, assetId));
            m.put("quantity", h.getQuantity());
            m.put("available", available);
            m.put("frozen", frozen);
            m.put("avgCost", fmt(h.getAvgCost()));
            m.put("lastPrice", fmt(last));
            m.put("marketValue", fmt(marketValue));
            m.put("profit", fmt(profit));
            m.put("profitPct", fmt(profitPct));
            list.add(m);
        }
        return GSON.toJson(list);
    }

    /** GET /api/portfolio → {playerName, cash, totalMarketValue, totalAssets, positions:[...]} */
    private static String getPortfolioJson() {
        ServerPlayer p = resolveTradingPlayer();
        if (p == null) return "{\"playerName\":\"\",\"cash\":0,\"totalMarketValue\":0,\"totalAssets\":0,\"positions\":[]}";
        // 复用 account 数值 + positions 列表，聚合为一个响应（前端桥接 requestPortfolio 消费）
        JsonObject acc = JsonParser.parseString(getAccountJson()).getAsJsonObject();
        JsonObject port = new JsonObject();
        port.addProperty("playerName", acc.get("playerName").getAsString());
        port.add("cash", acc.get("cash"));
        port.add("totalMarketValue", acc.get("totalMarketValue"));
        port.add("totalAssets", acc.get("totalAssets"));
        port.add("positions", JsonParser.parseString(getPositionsJson()).getAsJsonArray());
        return GSON.toJson(port);
    }

    /** GET /api/orders → [{orderId, assetId, name, side, type, price, quantity, remaining, status, time}]（绑定玩家 open/resting 委托） */
    private static String getOrdersJson() {
        ServerPlayer p = resolveTradingPlayer();
        MarketEngine eng = getEngine();
        if (p == null || eng == null) return "[]";
        java.util.UUID pid = p.getUUID();
        List<Map<String, Object>> list = new ArrayList<>();
        for (OrderBook book : eng.getOrderBooks().values()) {
            for (Order o : book.getOrdersByPlayer(pid)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("orderId", o.getOrderId().toString());
                m.put("assetId", o.getAssetId());
                m.put("name", assetName(eng, o.getAssetId()));
                m.put("side", o.isBuy() ? "buy" : "sell");
                m.put("type", o.isMarketOrder() ? "market" : "limit");
                m.put("price", fmt(o.getLimitPrice()));
                m.put("quantity", o.getTotalQuantity());
                m.put("remaining", o.getRemainingQuantity());
                m.put("status", o.isFilled() ? "filled"
                        : (o.getRemainingQuantity() < o.getTotalQuantity() ? "partial" : "open"));
                m.put("time", o.getTimestamp());
                list.add(m);
            }
        }
        return GSON.toJson(list);
    }

    /** GET /api/myTrades → [{time, assetId, name, side, price, quantity}]（绑定玩家成交明细） */
    private static String getMyTradesJson() {
        ServerPlayer p = resolveTradingPlayer();
        MarketEngine eng = getEngine();
        if (p == null || eng == null) return "[]";
        java.util.UUID pid = p.getUUID();
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map.Entry<String, List<Trade>> e : eng.getTradeHistory().entrySet()) {
            for (Trade t : e.getValue()) {
                boolean isBuy = pid.equals(t.getBuyerPlayerId());
                if (!isBuy && !pid.equals(t.getSellerPlayerId())) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("time", t.getTimestamp());
                m.put("assetId", e.getKey());
                m.put("name", assetName(eng, e.getKey()));
                m.put("side", isBuy ? "buy" : "sell");
                m.put("price", fmt(t.getPrice()));
                m.put("quantity", t.getQuantity());
                list.add(m);
            }
        }
        return GSON.toJson(list);
    }

    /** GET/POST /api/session：查询或动态绑定 HTTP 交易玩家（AS-3 §5.3）。 */
    private static void handleSession(HttpExchange ex) throws IOException {
        try {
            if ("GET".equalsIgnoreCase(ex.getRequestMethod())) {
                ServerPlayer p = resolveTradingPlayer();
                if (p == null) {
                    sendJson(ex, 200, "{\"playerName\":\"\",\"uuid\":\"\"}");
                    return;
                }
                sendJson(ex, 200, "{\"playerName\":\"" + p.getName().getString() + "\",\"uuid\":\"" + p.getUUID() + "\"}");
                return;
            }
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                sendJson(ex, 400, "{\"success\":false,\"message\":\"仅支持 GET/POST\"}");
                return;
            }
            String body = readBody(ex);
            String target = null;
            try {
                JsonObject obj = JsonParser.parseString(body).getAsJsonObject();
                if (obj.has("uuid")) target = obj.get("uuid").getAsString();
                else if (obj.has("playerName")) target = obj.get("playerName").getAsString();
            } catch (RuntimeException ignored) {
                // 非 JSON：尝试把整个 body 当作 playerName/uuid
                target = body == null ? null : body.trim();
            }
            java.util.UUID targetUuid = resolveUuidOrNull(target);
            ServerPlayer matched = null;
            for (ServerPlayer sp : onlinePlayers()) {
                if (targetUuid != null && sp.getUUID().equals(targetUuid)) { matched = sp; break; }
                if (targetUuid == null && target != null && target.equalsIgnoreCase(sp.getName().getString())) { matched = sp; break; }
            }
            if (matched == null) {
                sendJson(ex, 200, "{\"success\":false,\"message\":\"未找到该在线玩家（playerName 或 uuid）\"}");
                return;
            }
            boundTradingPlayerId = matched.getUUID();
            boundTradingPlayerName = matched.getName().getString();
            sendJson(ex, 200, "{\"success\":true,\"playerName\":\"" + matched.getName().getString()
                    + "\",\"uuid\":\"" + matched.getUUID() + "\"}");
        } catch (Exception e) {
            sendJson(ex, 500, "{\"success\":false,\"message\":\"会话处理异常\"}");
        }
    }

    /**
     * F2（连接审计修复 H1）：POST /api/company/subscribe body {name, shares} → {success, message}。
     * 任意在线玩家按公司名认购其 IPO（复用 engine.subscribeIpo 的现金扣减/认购意向记账，服务端权威）。
     * 修复「仅创始人可达」的认购断点；确定性、无随机。
     */
    private static void handleCompanySubscribe(HttpExchange ex) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                sendJson(ex, 400, "{\"success\":false,\"message\":\"仅支持 POST\"}");
                return;
            }
            ServerPlayer p = resolveTradingPlayer();
            MarketEngine eng = getEngine();
            if (p == null || eng == null) {
                sendJson(ex, 200, "{\"success\":false,\"message\":\"无可用玩家/引擎上下文\"}");
                return;
            }
            JsonObject obj = parseBodyJson(ex);
            String name = obj.has("name") ? obj.get("name").getAsString() : null;
            int shares = obj.has("shares") ? obj.get("shares").getAsInt() : 0;
            com.example.financialmarket.company.CompanyData company =
                    name == null ? null : eng.getCompanyManager().getByName(name);
            if (company == null) {
                sendJson(ex, 200, "{\"success\":false,\"message\":\"公司不存在\"}");
                return;
            }
            String reason = eng.subscribeIpo(p, company, shares);
            if (reason != null) {
                sendJson(ex, 200, "{\"success\":false,\"message\":\"" + reason + "\"}");
                return;
            }
            sendJson(ex, 200, "{\"success\":true,\"message\":\"认购成功\"}");
        } catch (Exception e) {
            sendJson(ex, 500, "{\"success\":false,\"message\":\"认购处理异常\"}");
        }
    }

    /** POST /api/order body {assetId, side, type, quantity, price} → {success, message, orderId?}（AS-3 §5.3）。 */    private static void handlePlaceOrder(HttpExchange ex) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                sendJson(ex, 400, "{\"success\":false,\"message\":\"仅支持 POST\"}");
                return;
            }
            ServerPlayer p = resolveTradingPlayer();
            MarketEngine eng = getEngine();
            if (p == null || eng == null) {
                sendJson(ex, 200, "{\"success\":false,\"message\":\"无可用玩家/引擎上下文\"}");
                return;
            }
            JsonObject obj;
            try {
                obj = JsonParser.parseString(readBody(ex)).getAsJsonObject();
            } catch (RuntimeException e) {
                sendJson(ex, 400, "{\"success\":false,\"message\":\"请求体需为 JSON\"}");
                return;
            }
            String assetId = obj.has("assetId") ? obj.get("assetId").getAsString() : null;
            String side = obj.has("side") ? obj.get("side").getAsString() : "buy";
            boolean isBuy = "buy".equalsIgnoreCase(side);
            int quantity = obj.has("quantity") ? obj.get("quantity").getAsInt() : 0;
            double price = obj.has("price") ? obj.get("price").getAsDouble() : 0.0;

            AssetSnapshot snap = assetId == null ? null : eng.getState().getAsset(assetId);
            if (snap == null) {
                sendJson(ex, 200, "{\"success\":false,\"message\":\"资产不存在\"}");
                return;
            }
            if (quantity <= 0) {
                sendJson(ex, 400, "{\"success\":false,\"message\":\"数量必须为正整数\"}");
                return;
            }
            // 市价（price<=0）回落现价限价（对齐 PlaceOrderPacket：以当前价提交限价单）
            double limitPrice = price > 0 ? price : snap.getLastPrice();
            // 复用 A 股校验语义做即时中文反馈；最终权威仍为 engine.submitOrder（与网络包同路径）
            String vmsg = orderValidationMessage(eng, p, assetId, isBuy, quantity, limitPrice);
            if (vmsg != null) {
                sendJson(ex, 200, "{\"success\":false,\"message\":\"" + vmsg + "\"}");
                return;
            }

            Order.Type orderType = isBuy ? Order.Type.LIMIT_BUY : Order.Type.LIMIT_SELL;
            Order order = new Order(p.getUUID(), assetId, orderType, quantity, limitPrice);
            eng.submitOrder(order);
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("success", true);
            res.put("message", "下单已提交");
            res.put("orderId", order.getOrderId().toString());
            res.put("price", fmt(limitPrice));
            sendJson(ex, 200, GSON.toJson(res));
        } catch (Exception e) {
            sendJson(ex, 500, "{\"success\":false,\"message\":\"下单处理异常\"}");
        }
    }

    /**
     * 服务端下单 A 股预检（HTTP 与网络包路径共用，保证校验一致；最终权威仍为 engine.submitOrder）。
     * 返回 null 表示校验通过，否则返回中文拒绝原因（休市/一手整数倍/T+1 可卖/±10% 涨跌停）。
     * 确定性、无随机。
     */
    public static String orderValidationMessage(MarketEngine eng, ServerPlayer player,
                                                String assetId, boolean isBuy, int quantity, double limitPrice) {
        if (eng == null || player == null) return "无可用玩家/引擎上下文";
        // 冒烟阻断项修复：持卡门控预检（HTTP 与网络包共用路径）——此前 HTTP 下单返回 success:true
        // 但引擎 submitOrder 静默拒单（无卡），前后端不一致导致 SD-3 误判。banking 关闭时 hasBankCard 恒 true。
        if (!eng.hasBankCard(player.getUUID())) return "请先办理储蓄卡（银行平板「办卡」或 POST /api/bank/card）";
        if (eng.getState().getAsset(assetId) == null) return "资产不存在";
        if (eng.isAShareEnabled() && !eng.getCalendar().isMarketOpen()) return "休市中，暂停交易";
        if (eng.isAShareEnabled() && isBuy && quantity % PriceDiscoveryEngine.A_SHARE_BOARD_LOT != 0)
            return "买入数量须为 " + PriceDiscoveryEngine.A_SHARE_BOARD_LOT + " 股整数倍";
        if (eng.isAShareEnabled() && !isBuy && !eng.getSettlement().canSell(player.getUUID(), assetId, quantity))
            return "T+1 冻结：可用数量不足，不可卖出";
        AssetSnapshot snap = eng.getState().getAsset(assetId);
        double prevClose = snap != null ? snap.getPrevClose() : 0.0;
        if (eng.isAShareEnabled() && prevClose > 0) {
            double up = PriceDiscoveryEngine.aShareLimitUp(prevClose);
            double down = PriceDiscoveryEngine.aShareLimitDown(prevClose);
            if (!PriceDiscoveryEngine.aShareWithinBand(limitPrice, up, down)) return "价格超出涨跌停限制";
        }
        // F1（连接审计修复）：买单现金足额预检（与 engine.submitOrder 权威校验一致；HTTP 与网络包共用路径）
        if (isBuy) {
            com.example.financialmarket.attachment.PlayerEconomy eco =
                    com.example.financialmarket.attachment.PlayerEconomy.get(player);
            double unit = limitPrice > 0 ? limitPrice : (snap != null ? snap.getLastPrice() : 0.0);
            if (eco == null || (unit > 0 && eco.getCash() < unit * quantity)) return "现金不足";
        }
        return null;
    }

    /** POST /api/cancelOrder body {orderId} → {success, message}（AS-3 §5.3）。 */
    private static void handleCancelOrder(HttpExchange ex) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                sendJson(ex, 400, "{\"success\":false,\"message\":\"仅支持 POST\"}");
                return;
            }
            ServerPlayer p = resolveTradingPlayer();
            MarketEngine eng = getEngine();
            if (p == null || eng == null) {
                sendJson(ex, 200, "{\"success\":false,\"message\":\"无可用玩家/引擎上下文\"}");
                return;
            }
            JsonObject obj;
            try {
                obj = JsonParser.parseString(readBody(ex)).getAsJsonObject();
            } catch (RuntimeException e) {
                sendJson(ex, 400, "{\"success\":false,\"message\":\"请求体需为 JSON\"}");
                return;
            }
            java.util.UUID orderUuid = obj.has("orderId") ? resolveUuidOrNull(obj.get("orderId").getAsString()) : null;
            if (orderUuid == null) {
                sendJson(ex, 400, "{\"success\":false,\"message\":\"无效的订单号\"}");
                return;
            }
            java.util.UUID pid = p.getUUID();
            boolean found = false;
            boolean cancelled = false;
            for (OrderBook book : eng.getOrderBooks().values()) {
                for (Order o : book.getOrdersByPlayer(pid)) {
                    if (o.getOrderId().equals(orderUuid)) {
                        found = true;
                        if (book.cancelOrder(orderUuid)) cancelled = true;
                        break;
                    }
                }
            }
            if (found && cancelled) {
                sendJson(ex, 200, "{\"success\":true,\"message\":\"撤单成功\"}");
            } else if (!found) {
                sendJson(ex, 200, "{\"success\":false,\"message\":\"未找到该委托（可能已成交或非本人）\"}");
            } else {
                sendJson(ex, 200, "{\"success\":false,\"message\":\"撤单失败\"}");
            }
        } catch (Exception e) {
            sendJson(ex, 500, "{\"success\":false,\"message\":\"撤单处理异常\"}");
        }
    }

    /** 供 FinancialMCEFScreen 轮询：WebView closeScreen() 是否请求关闭屏幕。 */
    public static boolean consumeCloseRequest() {
        return closeScreenRequested.compareAndSet(true, false);
    }

    // ===== 商品/期货 API 实现（t4，CF-9） =====

    /** GET /api/commodity/smoke?days=60 → real registry smoke + 20k synthetic stress. */
    private static void handleCommoditySmoke(HttpExchange ex) throws IOException {
        MarketEngine eng = getEngine();
        if (eng == null) {
            sendJson(ex, 503, "{\"error\":\"engine not ready\"}");
            return;
        }
        int days = 60;
        String query = ex.getRequestURI().getQuery();
        if (query != null) {
            for (String part : query.split("&")) {
                if (part.startsWith("days=")) {
                    try {
                        days = Integer.parseInt(part.substring(5));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("realRegistry", eng.getCommodityMarket().runSmokeDays(days));
        out.put("syntheticStress", syntheticCommodityStress(20_000, days));
        sendJson(ex, GSON.toJson(out));
    }

    private static Map<String, Object> syntheticCommodityStress(int count, int days) {
        int finite = 0;
        int floorViolations = 0;
        int capViolations = 0;
        double volSum = 0;
        long checks = 0;
        int n = Math.max(1, Math.min(365, days));
        for (int i = 0; i < count; i++) {
            String key = "synthetic:item:" + i;
            double cost = 1.0 + (i % 5000) * 0.01;
            double inventory = 500 + (i * 37) % 1500;
            double demand = 500 + (i * 53) % 1500;
            double fair = CommodityPriceMath.fairValue(cost, inventory, demand, 1.0, 0.20);
            double price = cost;
            double momentum = 0;
            double vol = 0.015;
            for (int d = 1; d <= n; d++) {
                var step = CommodityPriceMath.step(price, fair, momentum, vol, key, d,
                        cost * 0.20, cost * 5.0);
                price = step.price();
                momentum = step.momentum();
                vol = step.volatility();
                checks++;
                if (!Double.isFinite(price)) {
                    finite++;
                    break;
                }
                if (price < cost * 0.20 - 0.01) floorViolations++;
                if (price > cost * 5.0 + 0.01) capViolations++;
            }
            volSum += vol;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("syntheticCommodities", count);
        out.put("days", n);
        out.put("pathChecks", checks);
        out.put("nonFinite", finite);
        out.put("floorViolations", floorViolations);
        out.put("capViolations", capViolations);
        out.put("avgFinalVolatility", volSum / Math.max(1, count));
        return out;
    }

    /** GET /api/commodities → [{itemId,name,kind,price,basePrice,costAnchor,supply,demand,change,trend,sparkline,dailyVolume}] */
    private static String getCommoditiesJson() {
        MarketEngine eng = getEngine();
        if (eng == null) return "[]";
        List<Map<String, Object>> list = new ArrayList<>();
        for (CommodityEntry e : eng.getCommodityMarket().getAllCommodities()) {
            ResourceLocation id;
            String kind;
            if ("fluid".equals(e.getKind())) {
                Fluid f = e.getFluid();
                if (f == null) continue;
                id = BuiltInRegistries.FLUID.getKey(f);
                kind = "fluid";
            } else {
                Item it = e.getItem();
                if (it == null) continue;
                id = BuiltInRegistries.ITEM.getKey(it);
                kind = "item";
            }
            String itemId = id.toString();
            double price = e.getPrice();
            double base = e.getBasePrice();
            double change = base > 0 ? (price - base) / base * 100.0 : 0.0;
            double prevSettle = e.getPrevSettlePrice();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("itemId", itemId);
            m.put("name", id.getPath());
            m.put("kind", kind);
            m.put("price", fmt(price));
            m.put("basePrice", fmt(base));
            m.put("costAnchor", fmt(e.getCostAnchor()));
            m.put("fairValue", fmt(e.getFairValue()));
            m.put("momentum", fmt(e.getMomentum()));
            m.put("volatility", fmt(e.getVolatility()));
            m.put("seasonality", fmt(e.getSeasonality()));
            m.put("lastReturn", fmt(e.getLastReturn()));
            m.put("supply", fmt(e.getSupply()));
            m.put("demand", fmt(e.getDemand()));
            m.put("change", fmt(change));
            m.put("dayChange", fmt(prevSettle > 0 ? (price - prevSettle) / prevSettle * 100.0 : 0.0));
            m.put("trend", prevSettle > 0 ? (price >= prevSettle ? 1 : -1) : 0);
            m.put("dailyVolume", e.getDailyVolume());
            List<Double> hist = e.getHistory();
            if (hist.isEmpty()) {
                hist = commodityHistory.computeIfAbsent(itemId, k -> new ArrayList<>());
                hist.add(price);
                if (hist.size() > 30) hist.remove(0);
            }
            m.put("sparkline", hist.size() > 30 ? hist.subList(hist.size() - 30, hist.size()) : hist);
            list.add(m);
        }
        return GSON.toJson(list);
    }

    /** GET /api/options?code=MA2505 → Black-76 option chain (finmath-lib). */
    private static String getOptionsJson(HttpExchange ex) {
        MarketEngine eng = getEngine();
        if (eng == null || eng.getCzceFutures() == null) return "{}";
        CzceFuturesMarket czce = eng.getCzceFutures();
        String code = null;
        String query = ex.getRequestURI().getQuery();
        if (query != null) {
            for (String part : query.split("&")) {
                if (part.startsWith("code=")) {
                    code = part.substring(5).trim().toUpperCase();
                }
            }
        }
        CzceFuturesMarket.Contract c = code == null ? null : czce.contract(code);
        if (c == null) {
            for (CzceFuturesMarket.Contract candidate : czce.contracts().values()) {
                if (candidate.status == CzceFuturesMarket.Status.LISTED) {
                    c = candidate;
                    break;
                }
            }
        }
        if (c == null) return "{}";
        double baseVol = 0.20;
        CzceOptionDesk desk = eng.getCzceOptions();
        List<CzceOptionMarket.Quote> quotes = desk == null
                ? CzceOptionMarket.chainSabr(c.code, c.lastPrice,
                        SabrModel.fromAtmVol(c.lastPrice, baseVol, 60.0 / 365.0, 0.7, -0.3, 0.5),
                        0.02, new double[]{30, 60, 90},
                        new double[]{c.lastPrice * 0.95, c.lastPrice * 0.975, c.lastPrice,
                                c.lastPrice * 1.025, c.lastPrice * 1.05})
                : desk.chain(c.code, new double[]{30, 60, 90});
        List<Map<String, Object>> list = new ArrayList<>();
        for (CzceOptionMarket.Quote q : quotes) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("underlying", q.underlying());
            m.put("type", q.type().name());
            m.put("strike", fmt(q.strike()));
            m.put("expiryDays", q.expiryDays());
            m.put("price", fmt(q.greeks().price()));
            m.put("iv", fmt(q.greeks().impliedVol() * 100));
            m.put("delta", fmt(q.greeks().delta()));
            m.put("gamma", fmt(q.greeks().gamma()));
            m.put("vega", fmt(q.greeks().vega()));
            m.put("theta", fmt(q.greeks().theta()));
            list.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("underlying", c.code);
        out.put("forward", fmt(c.lastPrice));
        out.put("contracts", list);
        return GSON.toJson(out);
    }

    /** GET /api/czce → {tick, session, products, contracts, quantconnect}. */
    private static String getCzceJson() {
        MarketEngine eng = getEngine();
        if (eng == null) return "{}";
        CzceFuturesMarket czce = eng.getCzceFutures();
        if (czce == null) return "{}";
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tick", czce.tick());
        out.put("sessionOpen", czce.sessionOpen());
        out.put("physicalDeliveryLots", czce.physicalDeliveryLots());
        out.put("physicalDeliveryQuantity", czce.physicalDeliveryQuantity());
        out.put("deliveredContracts", czce.deliveredContracts());
        List<Map<String, Object>> contracts = new ArrayList<>();
        for (CzceFuturesMarket.Contract c : czce.contracts().values()) {
            CzceFuturesMarket.ProductSpec spec = czce.products().get(c.productCode);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", c.code);
            m.put("product", c.productCode);
            m.put("name", spec == null ? c.productCode : spec.name());
            m.put("lastPrice", fmt(c.lastPrice));
            m.put("prevSettlePrice", fmt(c.prevSettlePrice));
            m.put("limitUp", fmt(c.limitUp));
            m.put("limitDown", fmt(c.limitDown));
            m.put("marginRatio", fmt(c.marginRatio * 100));
            m.put("multiplier", spec == null ? 0 : spec.multiplier());
            m.put("tickSize", spec == null ? 0 : spec.tickSize());
            m.put("openInterest", c.openInterest);
            m.put("status", c.status.name());
            contracts.add(m);
        }
        out.put("contracts", contracts);
        CzceFuturesMarket.Account qc = czce.account(CzceFuturesMarket.QUANTCONNECT_ACCOUNT);
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("cash", fmt(qc.cash));
        q.put("margin", fmt(qc.margin));
        q.put("equity", fmt(qc.equity()));
        List<Map<String, Object>> positions = new ArrayList<>();
        for (CzceFuturesMarket.Position p : qc.positions.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("contract", p.contractCode);
            m.put("longLots", p.longLots);
            m.put("shortLots", p.shortLots);
            m.put("avgLongPrice", fmt(p.avgLongPrice));
            m.put("avgShortPrice", fmt(p.avgShortPrice));
            positions.add(m);
        }
        q.put("positions", positions);
        out.put("quantconnect", q);
        return GSON.toJson(out);
    }

    /** GET /api/futures → [{code,underlying,itemId,name,lastPrice,prevSettlePrice,change,limitUp,limitDown,marginRatio,multiplier,status,openInterest}] */
    private static String getFuturesJson() {
        MarketEngine eng = getEngine();
        if (eng == null || !eng.isFuturesEnabled()) return "[]";
        if (eng.isCzceFuturesMode()) return getCzceFuturesListJson(eng);
        List<Map<String, Object>> list = new ArrayList<>();
        for (FuturesContract c : eng.getFuturesMarket().getAllContracts()) {
            double change = c.prevSettlePrice > 0 ? (c.lastPrice - c.prevSettlePrice) / c.prevSettlePrice * 100.0 : 0.0;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", c.code);
            m.put("underlying", c.underlying);
            m.put("itemId", c.itemId);
            m.put("name", c.underlying + "_" + c.code.substring(c.code.indexOf('_') + 1));
            m.put("lastPrice", fmt(c.lastPrice));
            m.put("prevSettlePrice", fmt(c.prevSettlePrice));
            m.put("change", fmt(change));
            m.put("limitUp", fmt(c.limitUp));
            m.put("limitDown", fmt(c.limitDown));
            m.put("marginRatio", fmt(c.marginRatio * 100));
            m.put("multiplier", c.multiplier);
            m.put("status", c.status.name());
            m.put("openInterest", c.openInterest);
            list.add(m);
        }
        return GSON.toJson(list);
    }

    /** CZCE contracts mapped to the legacy futures UI shape. */
    private static String getCzceFuturesListJson(MarketEngine eng) {
        CzceFuturesMarket czce = eng.getCzceFutures();
        if (czce == null) return "[]";
        List<Map<String, Object>> list = new ArrayList<>();
        for (CzceFuturesMarket.Contract c : czce.contracts().values()) {
            CzceFuturesMarket.ProductSpec spec = czce.products().get(c.productCode);
            double change = c.prevSettlePrice > 0
                    ? (c.lastPrice - c.prevSettlePrice) / c.prevSettlePrice * 100.0 : 0.0;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", c.code);
            m.put("underlying", c.productCode);
            m.put("itemId", spec == null ? "" : spec.itemId());
            m.put("name", spec == null ? c.productCode : spec.name());
            m.put("lastPrice", fmt(c.lastPrice));
            m.put("prevSettlePrice", fmt(c.prevSettlePrice));
            m.put("change", fmt(change));
            m.put("limitUp", fmt(c.limitUp));
            m.put("limitDown", fmt(c.limitDown));
            m.put("marginRatio", fmt(c.marginRatio * 100));
            m.put("multiplier", spec == null ? 0 : spec.multiplier());
            m.put("status", c.status.name());
            m.put("openInterest", c.openInterest);
            list.add(m);
        }
        return GSON.toJson(list);
    }

    /** CZCE player positions mapped to the legacy futures positions shape. */
    private static String getCzcePositionsJson(MarketEngine eng, ServerPlayer p) {
        CzceFuturesMarket czce = eng.getCzceFutures();
        CzceFuturesMarket.Account acc = czce == null ? null : czce.accountOrNull(p.getUUID());
        if (acc == null) {
            return "{\"positions\":[],\"freeCash\":0,\"frozenMargin\":0,\"usedMargin\":0,\"equity\":0}";
        }
        double used = 0;
        List<Map<String, Object>> list = new ArrayList<>();
        for (CzceFuturesMarket.Position pos : acc.positions.values()) {
            CzceFuturesMarket.Contract c = czce.contract(pos.contractCode);
            CzceFuturesMarket.ProductSpec spec = c == null ? null : czce.products().get(c.productCode);
            if (c == null || spec == null) continue;
            double openPrice = pos.longLots > 0 ? pos.avgLongPrice : pos.avgShortPrice;
            double pnl = pos.longLots > 0
                    ? (c.lastPrice - pos.avgLongPrice) * spec.multiplier() * pos.longLots
                    : (pos.avgShortPrice - c.lastPrice) * spec.multiplier() * pos.shortLots;
            double margin = c.lastPrice * spec.multiplier()
                    * (pos.longLots + pos.shortLots) * c.marginRatio;
            used += margin;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", pos.contractCode);
            m.put("side", pos.longLots > 0 ? "long" : "short");
            m.put("lots", pos.longLots + pos.shortLots);
            m.put("longLots", pos.longLots);
            m.put("shortLots", pos.shortLots);
            m.put("openPrice", fmt(openPrice));
            m.put("lastPrice", fmt(c.lastPrice));
            m.put("floatPnl", fmt(pnl));
            m.put("usedMargin", fmt(margin));
            m.put("marginRatio", fmt(c.marginRatio * 100));
            list.add(m);
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("positions", list);
        res.put("freeCash", fmt(acc.cash));
        res.put("frozenMargin", fmt(acc.margin));
        res.put("usedMargin", fmt(used));
        res.put("equity", fmt(acc.equity()));
        return GSON.toJson(res);
    }

    /** GET /api/futures/positions → {positions:[...], freeCash,frozenMargin,usedMargin,equity} */
    private static String getFuturesPositionsJson() {
        ServerPlayer p = resolveTradingPlayer();
        MarketEngine eng = getEngine();
        if (p == null || eng == null || !eng.isFuturesEnabled()) {
            return "{\"positions\":[],\"freeCash\":0,\"frozenMargin\":0,\"usedMargin\":0,\"equity\":0}";
        }
        if (eng.isCzceFuturesMode()) {
            return getCzcePositionsJson(eng, p);
        }
        FuturesMarket fm = eng.getFuturesMarket();
        FuturesMarket.FuturesAccount acc = fm.getAccount(p.getUUID());
        if (acc == null) {
            return "{\"positions\":[],\"freeCash\":0,\"frozenMargin\":0,\"usedMargin\":0,\"equity\":0}";
        }
        double used = 0;
        List<Map<String, Object>> list = new ArrayList<>();
        for (FuturesPosition pos : acc.positions.values()) {
            FuturesContract c = fm.getContract(pos.contractCode);
            if (c == null) continue;
            double openPrice = pos.longLots > 0 ? pos.avgLongPrice : pos.avgShortPrice;
            double pnl = pos.longLots > 0
                    ? (c.lastPrice - pos.avgLongPrice) * c.multiplier * pos.longLots
                    : (pos.avgShortPrice - c.lastPrice) * c.multiplier * pos.shortLots;
            double margin = c.lastPrice * c.multiplier * pos.netLots() * c.marginRatio;
            used += margin;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", pos.contractCode);
            m.put("side", pos.longLots > 0 ? "long" : "short");
            m.put("lots", pos.netLots());
            m.put("longLots", pos.longLots);
            m.put("shortLots", pos.shortLots);
            m.put("openPrice", fmt(openPrice));
            m.put("lastPrice", fmt(c.lastPrice));
            m.put("floatPnl", fmt(pnl));
            m.put("usedMargin", fmt(margin));
            m.put("marginRatio", fmt(c.marginRatio * 100));
            list.add(m);
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("positions", list);
        res.put("freeCash", fmt(acc.freeCash));
        res.put("frozenMargin", fmt(acc.frozenMargin));
        res.put("usedMargin", fmt(used));
        res.put("equity", fmt(acc.equity()));
        return GSON.toJson(res);
    }

    /**
     * GET /api/gold → {enabled,price,benchmark,multiplier,prevPrice,globalSentiment,inflationRate,crisisLevel,
     * realRate,netCbBuy,goldReserve,goldCash,goldMarketBuyAmount,netheritePrice,safeHavenActive}（只读派生，NG-4/NG-5）。
     * 避险/抗通胀传导：safeHavenActive = 恐慌或危机或高通胀或负实际利率时金价趋涨；网合金锭(netheritePrice)为价值储存现货价。
     */
    private static String getGoldJson() {
        MarketEngine eng = getEngine();
        if (eng == null) {
            return "{\"enabled\":false,\"price\":0,\"benchmark\":60,\"multiplier\":1,\"prevPrice\":60,\"globalSentiment\":0,"
                    + "\"inflationRate\":0,\"crisisLevel\":0,\"realRate\":0,\"netCbBuy\":0,\"goldReserve\":0,\"goldCash\":0,"
                    + "\"goldMarketBuyAmount\":0,\"netheritePrice\":0,\"safeHavenActive\":false}";
        }
        GoldPricingSystem gs = eng.getGoldSystem();
        double netheritePrice = 0;
        CommodityEntry goldEntry = eng.getCommodityMarket().getCommodity(net.minecraft.world.item.Items.NETHERITE_INGOT);
        if (goldEntry != null) netheritePrice = goldEntry.getPrice();
        double gsent = eng.getSentiment() != null ? eng.getSentiment().getGlobalSentiment() : 0.7;
        double infl = gs.getLastInflationRate();
        double crisis = gs.getLastCrisisLevel();
        double realRate = gs.getLastRealRate();
        boolean safeHavenActive = gsent < GoldPricingSystem.PANIC_THRESHOLD
                || crisis > 0 || infl > 0.2 || realRate < 0;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", eng.isGoldEnabled());
        m.put("price", fmt(gs.goldPrice()));
        m.put("benchmark", fmt(gs.goldBenchmark()));
        m.put("multiplier", fmt(gs.goldMultiplier()));
        m.put("prevPrice", fmt(gs.prevGoldPrice()));
        m.put("globalSentiment", fmt(gsent));
        m.put("inflationRate", fmt(infl));
        m.put("crisisLevel", fmt(crisis));
        m.put("realRate", fmt(realRate));
        m.put("netCbBuy", fmt(gs.getLastNetCbBuy()));
        m.put("goldReserve", fmt(eng.getGoldReserve()));
        m.put("goldCash", fmt(eng.getGoldCash()));
        m.put("goldMarketBuyAmount", fmt(eng.getGoldMarketBuyAmount()));
        m.put("netheritePrice", fmt(netheritePrice));
        m.put("safeHavenActive", safeHavenActive);
        return GSON.toJson(m);
    }

    /** POST /api/commodity/order body {itemId, side:buy|sell, quantity, kind:item|fluid} → {success, message} */
    private static void handleCommodityOrder(HttpExchange ex) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                sendJson(ex, 400, "{\"success\":false,\"message\":\"仅支持 POST\"}");
                return;
            }
            ServerPlayer p = resolveTradingPlayer();
            MarketEngine eng = getEngine();
            if (p == null || eng == null) {
                sendJson(ex, 200, "{\"success\":false,\"message\":\"无可用玩家/引擎上下文\"}");
                return;
            }
            JsonObject obj;
            try {
                obj = JsonParser.parseString(readBody(ex)).getAsJsonObject();
            } catch (RuntimeException e) {
                sendJson(ex, 400, "{\"success\":false,\"message\":\"请求体需为 JSON\"}");
                return;
            }
            String itemId = obj.has("itemId") ? obj.get("itemId").getAsString() : null;
            boolean isBuy = "buy".equalsIgnoreCase(obj.has("side") ? obj.get("side").getAsString() : "buy");
            int qty = obj.has("quantity") ? obj.get("quantity").getAsInt() : 0;
            boolean isFluid = obj.has("kind") && "fluid".equalsIgnoreCase(obj.get("kind").getAsString());
            if (itemId == null || qty <= 0) {
                sendJson(ex, 400, "{\"success\":false,\"message\":\"参数无效\"}");
                return;
            }
            boolean ok;
            if (isFluid) {
                Fluid f = BuiltInRegistries.FLUID.get(ResourceLocation.tryParse(itemId));
                if (f == null) { sendJson(ex, 200, "{\"success\":false,\"message\":\"流体不存在\"}"); return; }
                ok = isBuy ? eng.getCommodityMarket().buyFluid(p, f, qty) : eng.getCommodityMarket().sellFluid(p, f, qty);
            } else {
                Item it = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(itemId));
                if (it == null) { sendJson(ex, 200, "{\"success\":false,\"message\":\"物品不存在\"}"); return; }
                ok = isBuy ? eng.getCommodityMarket().buy(p, it, qty) : eng.getCommodityMarket().sell(p, it, qty);
            }
            sendJson(ex, 200, ok
                    ? "{\"success\":true,\"message\":\"" + (isBuy ? "买入成功" : "卖出成功") + "\"}"
                    : "{\"success\":false,\"message\":\"" + (isBuy ? "买入失败（现金不足/休市/无法入袋）" : "卖出失败（库存不足/休市）") + "\"}");
        } catch (Exception e) {
            sendJson(ex, 500, "{\"success\":false,\"message\":\"商品下单处理异常\"}");
        }
    }

    /** POST /api/futures/order body {code, side:long|short, action:open|close, lots} → {success, message} */
    private static void handleFuturesOrder(HttpExchange ex) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                sendJson(ex, 400, "{\"success\":false,\"message\":\"仅支持 POST\"}");
                return;
            }
            ServerPlayer p = resolveTradingPlayer();
            MarketEngine eng = getEngine();
            if (p == null || eng == null || !eng.isFuturesEnabled()) {
                sendJson(ex, 200, "{\"success\":false,\"message\":\"无可用玩家/引擎上下文\"}");
                return;
            }
            JsonObject obj;
            try {
                obj = JsonParser.parseString(readBody(ex)).getAsJsonObject();
            } catch (RuntimeException e) {
                sendJson(ex, 400, "{\"success\":false,\"message\":\"请求体需为 JSON\"}");
                return;
            }
            String code = obj.has("code") ? obj.get("code").getAsString() : null;
            String side = obj.has("side") ? obj.get("side").getAsString() : "long";
            String action = obj.has("action") ? obj.get("action").getAsString() : "open";
            int lots = obj.has("lots") ? obj.get("lots").getAsInt() : 0;
            FuturesMarket fm = eng.getFuturesMarket();
            String msg;
            if (eng.isCzceFuturesMode()) {
                CzceFuturesMarket czce = eng.getCzceFutures();
                msg = "close".equalsIgnoreCase(action)
                        ? czce.close(p, code, side, lots)
                        : czce.open(p, code, side, lots);
            } else if ("close".equalsIgnoreCase(action)) {
                msg = fm.close(p, code, side, lots);
            } else {
                msg = fm.open(p, code, side, lots);
            }
            if (msg == null) {
                sendJson(ex, 200, "{\"success\":true,\"message\":\"" + ("close".equalsIgnoreCase(action) ? "平仓成功" : "开仓成功") + "\"}");
            } else {
                sendJson(ex, 200, "{\"success\":false,\"message\":\"" + msg + "\"}");
            }
        } catch (Exception e) {
            sendJson(ex, 500, "{\"success\":false,\"message\":\"期货下单处理异常\"}");
        }
    }

    /** GET /api/margin → {playerName, collateral, equity, liability, maintenanceRatio, riskBand, marginCallStreak, marginCallDeadline, requiredTopUp, limitUsage, positions:[...]} */
    private static String getMarginJson() {
        ServerPlayer p = resolveTradingPlayer();
        MarketEngine eng = getEngine();
        if (p == null || eng == null) {
            return "{\"playerName\":\"\",\"collateral\":0,\"equity\":0,\"liability\":0,\"maintenanceRatio\":0,\"riskBand\":\"NORMAL\",\"marginCallStreak\":0,\"marginCallDeadline\":0,\"requiredTopUp\":0,\"limitUsage\":0,\"positions\":[]}";
        }
        MarginManager mm = eng.getMarginManager();
        MarginAccount acc = mm.getOrCreateAccount(p.getUUID());
        Map<String, Double> prices = currentPrices(eng);
        double equity = acc.getEquity(prices);
        double liability = acc.getLiability(prices);
        double ratio = liability > 0 ? equity / liability : 0.0;
        double requiredTopUp = liability > 0 ? Math.max(0.0, liability * mm.getMarginCallRatio() - equity) : 0.0;
        double limitUsage = computeMarginLimitUsage(mm, acc, prices, equity, liability);
        List<Map<String, Object>> positions = new ArrayList<>();
        for (MarginAccount.PositionView v : acc.getPositionViews(prices)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("assetId", v.assetId());
            m.put("quantity", v.quantity());
            m.put("entryPrice", fmt(v.entryPrice()));
            m.put("isLong", v.isLong());
            m.put("unrealizedPnl", fmt(v.unrealizedPnl()));
            positions.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("playerName", p.getName().getString());
        out.put("collateral", fmt(acc.getCollateral()));
        out.put("equity", fmt(equity));
        out.put("liability", fmt(liability));
        out.put("maintenanceRatio", fmt(ratio));
        out.put("riskBand", acc.getRiskBand() == null ? "NORMAL" : acc.getRiskBand().name());
        out.put("marginCallStreak", acc.getMarginCallStreak());
        out.put("marginCallDeadline", acc.getMarginCallDeadline());
        out.put("requiredTopUp", fmt(requiredTopUp));
        out.put("limitUsage", fmt(limitUsage));
        out.put("positions", positions);
        return GSON.toJson(out);
    }

    /** POST /api/margin/deposit body {amount} → {success, message}（绑定玩家，扣现金入保证金）。 */
    private static void handleMarginDeposit(HttpExchange ex) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                sendJson(ex, 400, "{\"success\":false,\"message\":\"仅支持 POST\"}");
                return;
            }
            ServerPlayer p = resolveTradingPlayer();
            MarketEngine eng = getEngine();
            if (p == null || eng == null || !eng.isMarginEnabled()) {
                sendJson(ex, 200, "{\"success\":false,\"message\":\"无可用玩家/引擎上下文\"}");
                return;
            }
            JsonObject obj;
            try {
                obj = JsonParser.parseString(readBody(ex)).getAsJsonObject();
            } catch (RuntimeException e) {
                sendJson(ex, 400, "{\"success\":false,\"message\":\"请求体需为 JSON\"}");
                return;
            }
            double amount = obj.has("amount") ? obj.get("amount").getAsDouble() : 0.0;
            if (amount <= 0) {
                sendJson(ex, 400, "{\"success\":false,\"message\":\"金额须为正\"}");
                return;
            }
            PlayerEconomy eco = PlayerEconomy.get(p);
            if (eco == null || eco.getCash() < amount) {
                sendJson(ex, 200, "{\"success\":false,\"message\":\"现金不足\"}");
                return;
            }
            eco.setCash(eco.getCash() - amount);
            eng.getMarginManager().deposit(p.getUUID(), amount);
            sendJson(ex, 200, "{\"success\":true,\"message\":\"入金成功\"}");
        } catch (Exception e) {
            sendJson(ex, 500, "{\"success\":false,\"message\":\"保证金入金处理异常\"}");
        }
    }

    /** GET /api/leverage/notes → [{noteId,name,underlyingType,underlyingKey,leverage,lastPrice,prevSettlePrice,outstanding,status,maturityTicks,unitNotional}] */
    private static String getLeverageNotesJson() {
        MarketEngine eng = getEngine();
        if (eng == null || !eng.isLeverageEnabled()) return "[]";
        LeveragedNoteMarket lm = eng.getLeverageMarket();
        List<Map<String, Object>> list = new ArrayList<>();
        for (LeveragedNote n : lm.getNotes().values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("noteId", n.noteId);
            m.put("name", n.name);
            m.put("underlyingType", n.underlyingType == null ? "STOCK" : n.underlyingType.name());
            m.put("underlyingKey", n.underlyingKey);
            m.put("leverage", n.leverage);
            m.put("lastPrice", fmt(n.lastPrice));
            m.put("prevSettlePrice", fmt(n.prevSettlePrice));
            m.put("outstanding", n.outstanding);
            m.put("status", n.status == null ? "LISTED" : n.status.name());
            m.put("maturityTicks", n.maturityTicks);
            m.put("unitNotional", n.unitNotional);
            list.add(m);
        }
        return GSON.toJson(list);
    }

    /** GET /api/leverage/positions → [{noteId,units,avgEntryPrice,lastPrice,marketValue,pnl}]（绑定玩家持仓） */
    private static String getLeveragePositionsJson() {
        ServerPlayer p = resolveTradingPlayer();
        MarketEngine eng = getEngine();
        if (p == null || eng == null || !eng.isLeverageEnabled()) return "[]";
        LeveragedNoteMarket lm = eng.getLeverageMarket();
        List<Map<String, Object>> list = new ArrayList<>();
        for (LeveragedNotePosition pos : lm.getPositions(p.getUUID())) {
            LeveragedNote note = lm.getNote(pos.noteId);
            double last = note != null ? note.lastPrice : pos.avgEntryPrice;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("noteId", pos.noteId);
            m.put("units", pos.units);
            m.put("avgEntryPrice", fmt(pos.avgEntryPrice));
            m.put("lastPrice", fmt(last));
            m.put("marketValue", fmt(last * pos.units));
            m.put("pnl", fmt((last - pos.avgEntryPrice) * pos.units));
            list.add(m);
        }
        return GSON.toJson(list);
    }

    /** POST /api/leverage/issue body {underlyingType, underlyingKey, leverage, maturityTicks, units} → {success, message} */
    private static void handleLeverageIssue(HttpExchange ex) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                sendJson(ex, 400, "{\"success\":false,\"message\":\"仅支持 POST\"}");
                return;
            }
            ServerPlayer p = resolveTradingPlayer();
            MarketEngine eng = getEngine();
            if (p == null || eng == null || !eng.isLeverageEnabled()) {
                sendJson(ex, 200, "{\"success\":false,\"message\":\"无可用玩家/引擎上下文\"}");
                return;
            }
            JsonObject obj;
            try {
                obj = JsonParser.parseString(readBody(ex)).getAsJsonObject();
            } catch (RuntimeException e) {
                sendJson(ex, 400, "{\"success\":false,\"message\":\"请求体需为 JSON\"}");
                return;
            }
            LeveragedNote.UnderlyingType type;
            try {
                type = LeveragedNote.UnderlyingType.valueOf(obj.has("underlyingType") ? obj.get("underlyingType").getAsString() : "STOCK");
            } catch (IllegalArgumentException e) {
                sendJson(ex, 400, "{\"success\":false,\"message\":\"标的类型无效\"}");
                return;
            }
            String key = obj.has("underlyingKey") ? obj.get("underlyingKey").getAsString() : null;
            double lev = obj.has("leverage") ? obj.get("leverage").getAsDouble() : 0.0;
            long maturity = obj.has("maturityTicks") ? obj.get("maturityTicks").getAsLong() : 0L;
            int units = obj.has("units") ? obj.get("units").getAsInt() : 0;
            if (key == null || key.isEmpty() || units <= 0) {
                sendJson(ex, 400, "{\"success\":false,\"message\":\"参数无效\"}");
                return;
            }
            LeveragedNoteMarket.IssueResult res = eng.getLeverageMarket().issue(p, type, key, lev, maturity, units);
            sendJson(ex, 200, res == LeveragedNoteMarket.IssueResult.OK
                    ? "{\"success\":true,\"message\":\"发行成功\"}"
                    : "{\"success\":false,\"message\":\"发行失败（" + res + "）\"}");
        } catch (Exception e) {
            sendJson(ex, 500, "{\"success\":false,\"message\":\"杠杆票据发行处理异常\"}");
        }
    }

    /** POST /api/leverage/order body {noteId, action:buy|sell, units} → {success, message} */
    private static void handleLeverageOrder(HttpExchange ex) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                sendJson(ex, 400, "{\"success\":false,\"message\":\"仅支持 POST\"}");
                return;
            }
            ServerPlayer p = resolveTradingPlayer();
            MarketEngine eng = getEngine();
            if (p == null || eng == null || !eng.isLeverageEnabled()) {
                sendJson(ex, 200, "{\"success\":false,\"message\":\"无可用玩家/引擎上下文\"}");
                return;
            }
            JsonObject obj;
            try {
                obj = JsonParser.parseString(readBody(ex)).getAsJsonObject();
            } catch (RuntimeException e) {
                sendJson(ex, 400, "{\"success\":false,\"message\":\"请求体需为 JSON\"}");
                return;
            }
            String noteId = obj.has("noteId") ? obj.get("noteId").getAsString() : null;
            String action = obj.has("action") ? obj.get("action").getAsString() : "buy";
            // 兼容 open→buy / close→sell（评审 F1）
            boolean isBuy = "buy".equalsIgnoreCase(action) || "open".equalsIgnoreCase(action);
            int units = obj.has("units") ? obj.get("units").getAsInt() : 0;
            if (noteId == null || noteId.isEmpty() || units <= 0) {
                sendJson(ex, 400, "{\"success\":false,\"message\":\"参数无效\"}");
                return;
            }
            LeveragedNoteMarket lm = eng.getLeverageMarket();
            LeveragedNoteMarket.IssueResult res = isBuy ? lm.buy(p, noteId, units) : lm.sell(p, noteId, units);
            sendJson(ex, 200, res == LeveragedNoteMarket.IssueResult.OK
                    ? "{\"success\":true,\"message\":\"" + (isBuy ? "买入成功" : "卖出成功") + "\"}"
                    : "{\"success\":false,\"message\":\"" + (isBuy ? "买入失败（" + res + "）" : "卖出失败（" + res + "）") + "\"}");
        } catch (Exception e) {
            sendJson(ex, 500, "{\"success\":false,\"message\":\"杠杆票据下单处理异常\"}");
        }
    }

    /** AS-M2：限额用量 = 总负债/单资产名义/单资产融券量/集中度四项用量的最大比例。 */
    private static double computeMarginLimitUsage(MarginManager mm, MarginAccount acc, Map<String, Double> prices,
                                                  double equity, double liability) {
        double max = 0.0;
        double totalAsset = equity + liability;
        if (mm.getMaxLiabilityPerAccount() > 0) max = Math.max(max, liability / mm.getMaxLiabilityPerAccount());
        for (MarginAccount.PositionView v : acc.getPositionViews(prices)) {
            double nominal = v.quantity() * prices.getOrDefault(v.assetId(), v.entryPrice());
            if (mm.getMaxPositionValuePerAsset() > 0) max = Math.max(max, nominal / mm.getMaxPositionValuePerAsset());
            if (!v.isLong() && mm.getMaxShortQtyPerAsset() > 0) max = Math.max(max, (double) v.quantity() / mm.getMaxShortQtyPerAsset());
            if (totalAsset > 0 && mm.getConcentrationLimit() > 0) max = Math.max(max, (nominal / totalAsset) / mm.getConcentrationLimit());
        }
        return max;
    }

    private static Map<String, Double> currentPrices(MarketEngine eng) {
        Map<String, Double> map = new HashMap<>();
        for (String id : eng.getState().getAssetIds()) {
            AssetSnapshot snap = eng.getState().getAsset(id);
            if (snap != null) map.put(id, snap.getLastPrice());
        }
        return map;
    }

    private static String readBody(HttpExchange ex) throws IOException {
        try (InputStream is = ex.getRequestBody()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    // ===== 公司列表/银行/借贷/量化/云图 API 实现（FRONTEND_COMPLETE_SPEC §5） =====

    /** 供 fiat 玩家现金读取：返回绑定玩家 PlayerEconomy（无则 null）。 */
    private static PlayerEconomy boundPlayerEconomy(ServerPlayer p) {
        return p == null ? null : PlayerEconomy.get(p);
    }

    /**
     * GET /api/companies → [{stockId,name,industry,price,change,pe,pb,eps,roe,nav,revenueGrowth,
     * dividendYield,marketCap,growthStage}]（全部 AI 公司 + 已上市玩家公司，只读聚合）。
     */
    private static String getCompaniesJson() {
        MarketEngine eng = getEngine();
        if (eng == null) return "[]";
        List<Map<String, Object>> list = new ArrayList<>();
        for (String id : eng.getState().getAssetIds()) {
            AssetSnapshot snap = eng.getState().getAsset(id);
            if (snap == null) continue;
            AICompanyData company = eng.getAiCompanyManager().getAll().stream()
                    .filter(c -> c.getStockId().equals(id)).findFirst().orElse(null);
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("stockId", id);
            s.put("name", company != null && company.getName() != null ? company.getName() : id);
            double price = snap.getLastPrice();
            double change = snap.getDailyChange();
            if (company != null) {
                s.put("industry", company.getIndustry());
                s.put("roe", fmt(company.getRoe()));
                s.put("eps", fmt(company.getEps()));
                s.put("pe", fmt(company.getEps() > 0 ? price / company.getEps() : 0));
                s.put("pb", fmt(company.getBookValuePerShare() > 0 ? price / company.getBookValuePerShare() : 0));
                s.put("nav", fmt(company.getNetAssetValue()));
                s.put("revenueGrowth", fmt(company.getRevenueGrowth()));
                s.put("dividendYield", fmt(company.getDividendYield()));
                s.put("marketCap", fmt(price * company.getTotalShares()));
                s.put("growthStage", fmt(company.getGrowthStage()));
            } else {
                CompanyData pc = eng.getCompanyManager().getByStockId(id);
                s.put("industry", pc != null ? "PLAYER" : "");
                s.put("roe", 0.0);
                s.put("eps", 0.0);
                s.put("pe", 0.0);
                s.put("pb", 0.0);
                s.put("nav", pc != null ? fmt(pc.getNetAssetValue()) : 0.0);
                s.put("revenueGrowth", 0.0);
                s.put("dividendYield", 0.0);
                s.put("marketCap", pc != null ? fmt(price * pc.getTotalShares()) : 0.0);
                s.put("growthStage", 0.0);
                s.put("inventoryValue", pc != null ? fmt(pc.getInventoryValue()) : 0.0);
                s.put("appraisedAssetValue", pc != null ? fmt(pc.getAppraisedAssetValue()) : 0.0);
                s.put("replacementCost", pc != null ? fmt(pc.getReplacementCost()) : 0.0);
                s.put("machineCount", pc != null ? pc.getMachineCount() : 0);
                s.put("multiblockCount", pc != null ? pc.getMultiblockCount() : 0);
                s.put("assetUtilization", pc != null ? fmt(pc.getAssetUtilization()) : 0.0);
                s.put("assetRisk", pc != null ? fmt(pc.getAssetRisk()) : 0.0);
                s.put("assetQuality", pc != null ? fmt(pc.getAssetQuality()) : 0.0);
                s.put("productionOutputValue", pc != null ? fmt(pc.getProductionOutputValue()) : 0.0);
                s.put("productionInputValue", pc != null ? fmt(pc.getProductionInputValue()) : 0.0);
                s.put("productionUtilization", pc != null ? fmt(pc.getProductionUtilization()) : 0.0);
                s.put("inventoryTurnover", pc != null ? fmt(pc.getInventoryTurnover()) : 0.0);
                s.put("operatingProfit", pc != null ? fmt(pc.getOperatingProfit()) : 0.0);
                s.put("akCapital", pc != null ? fmt(pc.getAkCapital()) : 0.0);
                s.put("akOutput", pc != null ? fmt(pc.getAkOutput()) : 0.0);
                s.put("akGrowth", pc != null ? fmt(pc.getAkGrowth()) : 0.0);
                s.put("akReturn", pc != null ? fmt(pc.getAkReturn()) : 0.0);
                s.put("akFairValue", pc != null ? fmt(pc.getAkFairValue()) : 0.0);
                s.put("cdOutput", pc != null ? fmt(pc.getCdOutput()) : 0.0);
                s.put("cdTfp", pc != null ? fmt(pc.getCdTfp()) : 0.0);
                s.put("cdMpk", pc != null ? fmt(pc.getCdMpk()) : 0.0);
                s.put("cdMpl", pc != null ? fmt(pc.getCdMpl()) : 0.0);
                s.put("cdOptimalCapital", pc != null ? fmt(pc.getCdOptimalCapital()) : 0.0);
                s.put("cdCapitalGap", pc != null ? fmt(pc.getCdCapitalGap()) : 0.0);
            }
            s.put("price", fmt(price));
            s.put("change", fmt(change));
            list.add(s);
        }
        return GSON.toJson(list);
    }

    /** GET /api/banks → [{bankId,name,depositRate,loanRate,primeRate,reserveRequirement,totalDeposits,totalLoans,equity,vaultCash,reserves}] */
    private static String getBanksJson() {
        MarketEngine eng = getEngine();
        if (eng == null) return "[]";
        List<Map<String, Object>> list = new ArrayList<>();
        for (Bank bank : eng.getBankManager().getAllBanks()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("bankId", bank.getBankId());
            m.put("name", bank.getName());
            m.put("depositRate", fmt(bank.getDepositRate()));
            m.put("loanRate", fmt(bank.getPrimeRate()));
            m.put("primeRate", fmt(bank.getPrimeRate()));
            m.put("reserveRequirement", fmt(bank.getReserveRequirement() * 100));
            m.put("totalDeposits", fmt(bank.getTotalDepositsAll()));
            m.put("totalLoans", fmt(bank.getTotalLoans()));
            m.put("equity", fmt(bank.getEquity()));
            m.put("vaultCash", fmt(bank.getVaultCash()));
            m.put("reserves", fmt(bank.getReserves()));
            list.add(m);
        }
        return GSON.toJson(list);
    }

    /** GET /api/banking → {playerName,cash,demandDeposits,timeDeposits,moneyMarketDeposits,totalDebt,creditScore,nplRatio,creditGrowth,collateralValue,interbankRate} */
    private static String getBankingJson() {
        ServerPlayer p = resolveTradingPlayer();
        MarketEngine eng = getEngine();
        if (p == null || eng == null) {
            return "{\"playerName\":\"\",\"cash\":0,\"demandDeposits\":0,\"timeDeposits\":0,\"moneyMarketDeposits\":0,\"totalDebt\":0,\"creditScore\":0,\"nplRatio\":0,\"creditGrowth\":0,\"collateralValue\":0,\"interbankRate\":0}";
        }
        PlayerEconomy eco = boundPlayerEconomy(p);
        double cash = eco != null ? eco.getCash() : 0.0;
        double totalDebt = 0;
        double collateral = 0;
        for (Bank bank : eng.getBankManager().getAllBanks()) {
            for (Loan loan : bank.getLoans()) {
                if (p.getUUID().equals(loan.getBorrowerId()) && loan.getStatus() == Loan.LoanStatus.ACTIVE) {
                    totalDebt += loan.getRemaining();
                    collateral += loan.getCollateralValue();
                }
            }
        }
        // 信用分：确定性派生（基准 750，扣减负债/抵押杠杆影响；只读展示，非引擎权威）。
        double creditScore = Math.max(0, 750 - totalDebt / 1000.0 - collateral / 2000.0);
        CreditSystem cs = eng.getCreditSystem();
        InterbankMarket im = eng.getBankManager().getInterbankMarket();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("playerName", p.getName().getString());
        m.put("cash", fmt(cash));
        m.put("demandDeposits", 0.0);
        m.put("timeDeposits", 0.0);
        m.put("moneyMarketDeposits", 0.0);
        m.put("totalDebt", fmt(totalDebt));
        m.put("creditScore", fmt(creditScore));
        m.put("nplRatio", fmt(cs != null ? cs.getNplRatio() : 0));
        m.put("creditGrowth", fmt(cs != null ? cs.getCreditGrowth() : 0));
        m.put("collateralValue", fmt(collateral));
        m.put("interbankRate", fmt(im != null ? im.getInterbankRate() : 0));
        return GSON.toJson(m);
    }

    /** GET /api/loans → [{loanId,bankId,borrowerType,principal,remaining,annualRate,termDays,status,collateralValue,startDay}] */
    private static String getLoansJson() {
        ServerPlayer p = resolveTradingPlayer();
        MarketEngine eng = getEngine();
        if (p == null || eng == null) return "[]";
        List<Map<String, Object>> list = new ArrayList<>();
        for (Bank bank : eng.getBankManager().getAllBanks()) {
            for (Loan loan : bank.getLoans()) {
                if (!p.getUUID().equals(loan.getBorrowerId())) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("loanId", loan.getLoanId().toString());
                m.put("bankId", loan.getBankId());
                m.put("borrowerType", loan.getBorrowerType());
                m.put("principal", fmt(loan.getPrincipal()));
                m.put("remaining", fmt(loan.getRemaining()));
                m.put("annualRate", fmt(loan.getAnnualRate()));
                m.put("termDays", loan.getTermDays());
                m.put("status", loan.getStatus().name());
                m.put("collateralValue", fmt(loan.getCollateralValue()));
                m.put("startDay", loan.getStartDay());
                list.add(m);
            }
        }
        return GSON.toJson(list);
    }

    /** GET /api/bonds → [{bondId,faceValue,couponRate,maturityDays,currentValue,status}]（政府债券，只读）。 */
    private static String getBondsJson() {
        MarketEngine eng = getEngine();
        if (eng == null) return "[]";
        double marketRate = eng.getCentralBank().getDiscountRate();
        long currentDay = eng.getTickCounter();
        List<Map<String, Object>> list = new ArrayList<>();
        for (GovernmentBond bond : eng.getGovernment().getIssuedBonds()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("bondId", bond.getBondId().toString());
            m.put("faceValue", fmt(bond.getFaceValue()));
            m.put("couponRate", fmt(bond.getCouponRate()));
            m.put("maturityDays", bond.getMaturityDays());
            m.put("currentValue", fmt(bond.calculateCurrentValue(currentDay, marketRate)));
            m.put("status", bond.isMatured() ? "MATURED" : "ACTIVE");
            list.add(m);
        }
        return GSON.toJson(list);
    }

    /**
     * GET /api/macro → {discountRate,reserveRequirement,m0,m1,m2,interbankRate,totalPrivateDebt,nplRatio,creditGrowth}
     * （只读派生）。
     *
     * <p><b>★CB-t5 P1 改名落地（§1.9(a) 第 4 项：本文件是 `computeM0` 的唯一跨包调用点）</b>：
     * 三个取值语句改走**冻结后的新命名入口**（数值与旧义逐位一致，R6 字段名与量纲均不变）：
     * <ul>
     *   <li>{@code m0}：旧 {@code computeM0(playerCash)}（= 流通现金 C）⇒ 新 {@code computeM0(playerCash)}
     *       （同名，语义已一义化为「流通现金」；基础货币不再借用该名）。</li>
     *   <li>{@code m1}/{@code m2}：{@code computeM1}/{@code computeM2}(playerCash)（签名不变，内部按开关委托聚合器）。</li>
     * </ul>
     * ⚠ 本端点**有意不暴露**基础货币 B：B 的新权威出口是 {@code /api/money} 的 {@code b}
     * （{@code getBaseMoneyB()}）、记账侧旧口径是同一端点的 {@code ledgerVsComponentsGap} 诊断；
     * 在此新增同名字段会重新制造「一名两义」，故按 §1.9(a) 的改名口径**只改取值入口、不改字段语义**。
     * {@code reserveRequirement} 的 ×100 展示换算**保持不变**（既有端点契约，R6）。
     */
    private static String getMacroJson() {
        MarketEngine eng = getEngine();
        if (eng == null) return "{}";
        ServerPlayer p = resolveTradingPlayer();
        PlayerEconomy eco = p != null ? boundPlayerEconomy(p) : null;
        double playerCash = eco != null ? eco.getCash() : 0.0;
        // ★P1 改名落地：以下三行即 §1.9(a) 的第 4 项跨包调用点（改名后入口，非新方法）
        double m0 = eng.getBankManager().computeM0(playerCash);
        double m1 = eng.getBankManager().computeM1(playerCash);
        double m2 = eng.getBankManager().computeM2(playerCash);
        CreditSystem cs = eng.getCreditSystem();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("discountRate", fmt(eng.getCentralBank().getDiscountRate()));
        m.put("reserveRequirement", fmt(eng.getCentralBank().getReserveRequirement() * 100));
        m.put("m0", fmt(m0));
        m.put("m1", fmt(m1));
        m.put("m2", fmt(m2));
        m.put("interbankRate", fmt(eng.getBankManager().getInterbankMarket().getInterbankRate()));
        m.put("totalPrivateDebt", fmt(cs != null ? cs.getTotalPrivateDebt() : 0));
        m.put("nplRatio", fmt(cs != null ? cs.getNplRatio() : 0));
        m.put("creditGrowth", fmt(cs != null ? cs.getCreditGrowth() : 0));
        return GSON.toJson(m);
    }

    // ===================== CB-t5：央行与货币体系只读端点（CENTRAL_BANK_SPEC §9.4） =====================

    /**
     * GET /api/money → 货币分层与基础货币分解（**数值直传原值**，不做 ×100 换算）。
     *
     * <p>字段：{@code m0, m1, m1Legacy1994, m2, qm, b, multiplierM, multiplierMLegacy, c, e, rrEffective,
     * cbdc, cash, vaultCash, requiredReserves, excessReserves, paymentInstitutions, noteCash,
     * ledgerVsComponentsGap, divergenceAccount, refreshTick}。
     *
     * <p><b>★P1 一义化</b>：本端点的 {@code m0} = **流通中现金 C**（{@code MonetaryAggregator.getM0()}，
     * §3.1 权威口径），{@code b} = **I1 右侧之和**（{@code MonetaryAggregator.getBaseMoneyB()}，
     * ★**权威判据**，day-0 = 4,500,000），
     * {@code ledgerVsComponentsGap} = 记账侧 B 与 I1 右侧和之差（**只读诊断，非判据**，明文不作判据）。
     *
     * <p><b>★CB-T19 键名消歧（与同组端点 {@code /api/centralbank} 的关系）</b>：
     * 本端点的 {@code b} 是**唯一权威**的基础货币口径；
     * {@code /api/centralbank} 的 {@code reserveMoneyMirror}（原键名 {@code baseMoneyB}）是**账本镜像**
     * （{@code CentralBank.getBaseMoneyB()} = {@code balanceSheet.getReserveMoney()}），
     * **仅 I10 成立后**与 {@code b} 相等；两者不等时差额 = day-0 具名例外「历史发行封装」。
     * ⇒ 验证方**不得**把 {@code reserveMoneyMirror} 当作 CB-3/§3.1 的基础货币判据。
     *
     * <p>只读：{@code refreshMonetary(...)} 仅以**当日已注入**的流通现金重算聚合（同一输入 ⇒ 结果幂等），
     * 不写任何账本、不消费随机源。
     */
    private static String getMoneyJson() {
        MarketEngine eng = getEngine();
        if (eng == null) return "{}";
        com.example.financialmarket.engine.banking.BankManager bm = eng.getBankManager();
        refreshMonetary(eng, bm);
        com.example.financialmarket.engine.monetary.MonetaryAggregator agg = bm.getMonetaryAggregator();
        Map<String, Object> m = new LinkedHashMap<>();
        // ---- §3.1 分层：M0/M1/M2/QM（raw） ----
        m.put("m0", agg.getM0());
        m.put("m1", agg.getM1());
        m.put("m1Legacy1994", agg.getM1Legacy1994());
        m.put("m2", agg.getM2());
        m.put("qm", agg.getQuasiMoneyQM());
        // ---- I1 基础货币分解 ----
        m.put("b", agg.getBaseMoneyB());
        m.put("cash", agg.getCirculationCash());
        m.put("noteCash", agg.getNoteCash());
        m.put("cbdc", agg.getCbdcOutstanding());
        m.put("vaultCash", agg.getVaultCashTotal());
        m.put("requiredReserves", agg.getRequiredReservesTotal());
        m.put("excessReserves", agg.getExcessReservesTotal());
        m.put("paymentInstitutions", agg.getPaymentInstitutionDeposits());
        // ---- I6 乘数（新口径 m = M2/B；兼容口径分母 = C，§1.7 D3 同 run 对照用） ----
        m.put("multiplierM", agg.getMoneyMultiplierM());
        m.put("multiplierMLegacy", bm.getCashMultiplierLegacy(agg.getCirculationCash()));
        m.put("multiplierZeroDenominatorHits", agg.getMultiplierZeroDenominatorHits());
        // ---- 口径比率 ----
        m.put("c", agg.getCashLeakageRatioC());
        m.put("e", agg.getExcessReserveRatioE());
        m.put("rrEffective", eng.getCentralBank().getEffectiveReserveRequirement());
        m.put("rrParam", eng.getCentralBank().getReserveRequirement());
        // ---- I10 诊断（只读：不作判据；大数相减口径） ----
        m.put("ledgerVsComponentsGap", agg.getOutstandingBookedB() - agg.getBaseMoneyB());
        m.put("divergenceAccount", eng.getCentralBank().getDivergenceAccount());
        m.put("refreshTick", agg.getAggregateRefreshTick());
        m.put("aggregationArmed", agg.isArmed());
        m.put("aggregationDegraded", agg.isAggregationDegraded());
        return GSON.toJson(m);
    }

    /**
     * GET /api/money/identities → {@code {i1..i10: {ok, evaluable, maxError, name}, ...}}（**验证专用**）。
     *
     * <p>口径（§8.4 调用契约①）：**先 refresh 再校验**；未注入快照的 I7/I8 由聚合器自报
     * {@code evaluable=false} ⇒ 绝不假通过（§5.1）。只读。
     */
    private static String getMoneyIdentitiesJson() {
        MarketEngine eng = getEngine();
        if (eng == null) return "{}";
        com.example.financialmarket.engine.banking.BankManager bm = eng.getBankManager();
        refreshMonetary(eng, bm);
        com.example.financialmarket.engine.monetary.MonetaryAggregator agg = bm.getMonetaryAggregator();
        Map<String, Object> root = new LinkedHashMap<>();
        for (int i = 1; i <= com.example.financialmarket.engine.monetary.MonetaryAggregator.IDENTITY_COUNT; i++) {
            com.example.financialmarket.engine.monetary.MonetaryAggregator.IdentitySpec spec =
                    com.example.financialmarket.engine.monetary.MonetaryAggregator.specOf(i);
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("ok", agg.isIdentityOk(i));
            one.put("evaluable", agg.isIdentityEvaluable(i));
            double err = agg.getIdentityMaxError(i);
            one.put("maxError", Double.isNaN(err) ? null : err);
            if (spec != null) {
                one.put("name", spec.name());
                one.put("expression", spec.expression());
            }
            root.put("i" + i, one);
        }
        root.put("lastFailedIdentity", agg.getLastFailedIdentity());
        root.put("divergenceHits", agg.getDivergenceHits());
        root.put("evaluableCount", agg.getEvaluableIdentityCount());
        return GSON.toJson(root);
    }

    /**
     * GET /api/centralbank → 利率走廊 + 政策态 + 央行资产负债表全科目（**数值直传原值**）。
     *
     * <p>字段（§9.4 逐字口径）：{@code policyRate, slf, ioer, dr007, corridorUpper, corridorLower,
     * lpr1y, govBondYield, totalAssets, totalLiabilitiesAndCapital, foreignAssets, monetaryGold,
     * fxReserves, claimsOnGovernment, claimsOnOtherDepositoryCorps, claimsOnOtherFinancialCorps,
     * otherAssets, reserveMoney, financialCorpDepositsNonReserve, govDeposits, centralBankBills,
     * otherLiabilities, ownCapital, reserveMoneyMirror, omoOutstanding, mlfOutstanding, reloanOutstanding,
     * rediscountOutstanding, slfOutstanding, lastPolicyAction, lastPolicyActionTick, lastPolicyActionDelta,
     * policyDecisionSource, fiscalDominanceCount, erpFiscalDominancePremium, lastResortLendingCount,
     * lastResortLendingOutstanding, manualTakeoverActive, takeoverTransitionCount, goldReserve, goldCash,
     * meanFairValueDelta, divergenceAccount, balanceSheetOk, lastFailedIdentity, policyEventCount, …}
     *
     * <p><b>★CB-T19 键名消歧</b>：{@code reserveMoneyMirror}（原键名 {@code baseMoneyB}）是
     * **账本储备货币镜像**（{@code CentralBank.getBaseMoneyB()} = {@code balanceSheet.getReserveMoney()}），
     * 与 {@code reserveMoney} 同源；**它不是 I1 右侧和**。I1 右侧和（权威判据）由 `/api/money` 的 {@code b}
     * 给出（{@code MonetaryAggregator.getBaseMoneyB()}）。两者**仅 I10 成立后**相等，
     * 不等时差额 = day-0 具名例外「历史发行封装」⇒ 本键**明文不作 CB-3/§3.1 的基础货币判据**。
     */
    private static String getCentralBankJson() {
        MarketEngine eng = getEngine();
        if (eng == null) return "{}";
        com.example.financialmarket.engine.banking.CentralBank cb = eng.getCentralBank();
        com.example.financialmarket.engine.banking.InterbankMarket im = eng.getInterbankMarket();
        Map<String, Object> m = new LinkedHashMap<>();
        // ---- 利率走廊（IOER ≤ DR007 ≤ SLF；raw） ----
        m.put("policyRate", cb.getPolicyRate());
        m.put("slf", cb.getStandingLendingFacilityRate());
        m.put("ioer", cb.getInterestOnExcessReservesRate());
        m.put("dr007", im != null ? im.getInterbankRate() : 0.0);
        m.put("corridorUpper", cb.getCorridorUpperRate());
        m.put("corridorLower", cb.getCorridorLowerRate());
        m.put("corridorUpperMarket", im != null ? im.getCorridorUpper() : 0.0);
        m.put("corridorLowerMarket", im != null ? im.getCorridorLower() : 0.0);
        m.put("interbankVolume", im != null ? im.getInterbankRateVolume() : 0.0);
        m.put("interbankParticipants", im != null ? im.getInterbankRateParticipants() : 0);
        m.put("interbankTotalDemand", im != null ? im.getInterbankTotalDemand() : 0.0);
        m.put("interbankTotalSupply", im != null ? im.getInterbankTotalSupply() : 0.0);
        m.put("zlbException", im != null && im.isZlbException());
        m.put("lpr1y", cb.getLprOneYear());
        m.put("govBondYield", cb.getGovernmentBondYield());
        m.put("discountRate", cb.getDiscountRate());
        // ---- 资产负债表（全科目 raw；I8：Σ资产 == Σ负债+自有资金） ----
        m.put("totalAssets", cb.getTotalAssets());
        m.put("totalLiabilitiesAndCapital", cb.getTotalLiabilitiesAndCapital());
        m.put("foreignAssets", cb.getForeignAssets());
        m.put("monetaryGold", cb.getMonetaryGold());
        m.put("fxReserves", cb.getFxReserves());
        m.put("claimsOnGovernment", cb.getClaimsOnGovernment());
        m.put("claimsOnOtherDepositoryCorps", cb.getClaimsOnOtherDepositoryCorps());
        m.put("claimsOnOtherFinancialCorps", cb.getClaimsOnOtherFinancialCorps());
        m.put("otherAssets", cb.getOtherAssets());
        m.put("reserveMoney", cb.getReserveMoney());
        m.put("financialCorpDepositsNonReserve", cb.getFinancialCorpDepositsNonReserve());
        m.put("govDeposits", cb.getGovernmentDeposits());
        m.put("centralBankBills", cb.getCentralBankBills());
        m.put("otherLiabilities", cb.getOtherLiabilities());
        m.put("ownCapital", cb.getOwnCapital());
        // ★CB-T19 键名消歧（防 t8 假 FAIL）：本键**不是** I1 右侧和，而是**账本储备货币镜像**
        // （`CentralBank.getBaseMoneyB()` = `balanceSheet.getReserveMoney()`，day-0 = 10,000,000）。
        // I1 右侧和（权威判据，day-0 = 4,500,000）由 `/api/money` 的 `b` 给出（`MonetaryAggregator.getBaseMoneyB()`）。
        // 两者**仅在 I10 成立后**相等；不等的差额 = day-0 具名例外「历史发行封装」。
        // 旧键名 `baseMoneyB` 会在同一端点语义下与 `b` 互相矛盾 ⇒ 改名 `reserveMoneyMirror`（**值/调用点不变**）。
        m.put("reserveMoneyMirror", cb.getBaseMoneyB());
        m.put("balanceSheetOk", cb.validateBalanceSheet());
        m.put("balanceSheetError", cb.getBalanceSheetError());
        // ---- 工具箱余额 ----
        m.put("omoOutstanding", cb.getOmoOutstanding());
        m.put("mlfOutstanding", cb.getMlfOutstanding());
        m.put("reloanOutstanding", cb.getReloanOutstanding());
        m.put("rediscountOutstanding", cb.getRediscountOutstanding());
        m.put("slfOutstanding", cb.getSlfOutstanding());
        // ---- 政策态 / 事件通道 ----
        m.put("lastPolicyAction", cb.getLastPolicyAction());
        m.put("lastPolicyActionTick", cb.getLastPolicyActionTick());
        m.put("lastPolicyActionDelta", cb.getLastPolicyActionDelta());
        m.put("policyDecisionSource", cb.getPolicyDecisionSource());
        m.put("policyEventCount", cb.getPolicyEventCount());
        m.put("taylorSuggestedRate", cb.getTaylorSuggestedRate());
        // ---- Q6/Q7 代价链条 ----
        m.put("fiscalDominanceCount", cb.getFiscalDominanceCount());
        m.put("erpFiscalDominancePremium", cb.getErpFiscalDominancePremium());
        m.put("lastResortLendingCount", cb.getLastResortLendingCount());
        m.put("lastResortLendingOutstanding", cb.getLastResortLendingOutstanding());
        m.put("troubledStateTransitions", cb.getTroubledStateTransitions());
        m.put("manualTakeoverActive", cb.isManualTakeoverActive());
        m.put("takeoverTransitionCount", cb.getTakeoverTransitionCount());
        m.put("meanFairValueDelta", cb.getMeanFairValueDelta());
        // ---- 黄金储备（N7：不进 M2；仅作资产侧科目披露） ----
        m.put("goldReserve", cb.getGoldReserve());
        m.put("goldCash", cb.getGoldCash());
        // ---- I10 收口 + 开关快照 ----
        m.put("divergenceAccount", cb.getDivergenceAccount());
        m.put("divergenceHits", cb.getDivergenceHits());
        m.put("lastFailedIdentity", cb.getLastFailedIdentity());
        m.put("centralBankEnabled", eng.isCentralBankEnabled());
        m.put("rfPolicyRate", eng.isCentralBankRfPolicyRateEnabled());
        return GSON.toJson(m);
    }

    /**
     * CB-t5（§8.3⑩ / §8.4 调用契约①）：以**当日已注入**的流通现金重算分层聚合（供 HTTP 只读消费）。
     *
     * <p>只读纪律：本方法不写账本、不消费随机；输入与引擎日级刷新同源（{@code BankManager.getLastCirculationCash()}）
     * ⇒ 重复调用结果**幂等**（同输入同输出），不会改变任何账本或偏差账户。
     */
    private static void refreshMonetary(MarketEngine eng, com.example.financialmarket.engine.banking.BankManager bm) {
        if (eng == null || bm == null) return;
        double cash = Math.max(0.0, bm.getLastCirculationCash());
        bm.getMonetaryAggregator().refresh(eng.getCentralBank(), bm, () -> cash);
    }

    /** POST /api/loan/apply body {bankId, amount, termDays, collateralValue} → {success, message, loanId?}。走 Bank.issueLoan 确定性路径。 */
    private static void handleLoanApply(HttpExchange ex) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                sendJson(ex, 400, "{\"success\":false,\"message\":\"仅支持 POST\"}");
                return;
            }
            ServerPlayer p = resolveTradingPlayer();
            MarketEngine eng = getEngine();
            if (p == null || eng == null) {
                sendJson(ex, 200, "{\"success\":false,\"message\":\"无可用玩家/引擎上下文\"}");
                return;
            }
            JsonObject obj = parseBodyJson(ex);
            String bankId = obj.has("bankId") ? obj.get("bankId").getAsString() : null;
            double amount = obj.has("amount") ? obj.get("amount").getAsDouble() : 0.0;
            int termDays = obj.has("termDays") ? obj.get("termDays").getAsInt() : 0;
            double collateral = obj.has("collateralValue") ? obj.get("collateralValue").getAsDouble() : 0.0;
            Bank bank = bankId == null ? null : eng.getBankManager().getBank(bankId);
            if (bank == null) {
                sendJson(ex, 200, "{\"success\":false,\"message\":\"银行不存在\"}");
                return;
            }
            if (amount <= 0 || termDays <= 0) {
                sendJson(ex, 200, "{\"success\":false,\"message\":\"金额/期限须为正\"}");
                return;
            }
            double rate = bank.getPrimeRate();
            Loan loan = bank.issueLoan(p.getUUID(), "PLAYER", amount, rate, termDays, collateral);
            if (loan == null) {
                sendJson(ex, 200, "{\"success\":false,\"message\":\"贷款申请被拒（可用额度/准备金不足）\"}");
                return;
            }
            // F2（连接审计修复 #5/#7）：不再用引擎 tick（与违约判定用的交易日序不一致）覆盖起算日——
            // Bank.issueLoan 已按最近交易日序打 startDay，各入口（平板/网络包/HTTP）语义一致。
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("success", true);
            res.put("message", "贷款发放成功");
            res.put("loanId", loan.getLoanId().toString());
            sendJson(ex, 200, GSON.toJson(res));
        } catch (Exception e) {
            sendJson(ex, 500, "{\"success\":false,\"message\":\"贷款申请处理异常\"}");
        }
    }

    /** POST /api/loan/repay body {loanId, amount} → {success, message}。走 Bank.repayLoan 确定性路径。 */
    private static void handleLoanRepay(HttpExchange ex) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                sendJson(ex, 400, "{\"success\":false,\"message\":\"仅支持 POST\"}");
                return;
            }
            ServerPlayer p = resolveTradingPlayer();
            MarketEngine eng = getEngine();
            if (p == null || eng == null) {
                sendJson(ex, 200, "{\"success\":false,\"message\":\"无可用玩家/引擎上下文\"}");
                return;
            }
            JsonObject obj = parseBodyJson(ex);
            String loanIdStr = obj.has("loanId") ? obj.get("loanId").getAsString() : null;
            double amount = obj.has("amount") ? obj.get("amount").getAsDouble() : 0.0;
            java.util.UUID loanUuid = resolveUuidOrNull(loanIdStr);
            if (loanUuid == null) {
                sendJson(ex, 400, "{\"success\":false,\"message\":\"贷款号/金额无效\"}");
                return;
            }
            boolean found = false;
            double repaid = 0;
            for (Bank bank : eng.getBankManager().getAllBanks()) {
                for (Loan loan : bank.getLoans()) {
                    if (loan.getLoanId().equals(loanUuid) && p.getUUID().equals(loan.getBorrowerId())
                            && loan.getStatus() == Loan.LoanStatus.ACTIVE) {
                        found = true;
                        // amount<=0 视为全额/提前还款：取该贷款未还余额(remaining)，与前端一致
                        double effective = amount > 0 ? amount : loan.getRemaining();
                        repaid = bank.repayLoan(loanUuid, effective);
                        break;
                    }
                }
                if (found) break;
            }
            if (!found) {
                sendJson(ex, 200, "{\"success\":false,\"message\":\"未找到该贷款（可能已还清或非本人）\"}");
                return;
            }
            sendJson(ex, 200, repaid > 0
                    ? "{\"success\":true,\"message\":\"还款成功（已还 " + fmt(repaid) + "）\"}"
                    : "{\"success\":false,\"message\":\"还款失败（余额/现金不足）\"}");
        } catch (Exception e) {
            sendJson(ex, 500, "{\"success\":false,\"message\":\"还款处理异常\"}");
        }
    }

    /** POST /api/bank/deposit body {bankId, amount} → {success, message}（扣玩家现金入银行活期）。 */
    private static void handleBankDeposit(HttpExchange ex) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                sendJson(ex, 400, "{\"success\":false,\"message\":\"仅支持 POST\"}");
                return;
            }
            ServerPlayer p = resolveTradingPlayer();
            MarketEngine eng = getEngine();
            if (p == null || eng == null) {
                sendJson(ex, 200, "{\"success\":false,\"message\":\"无可用玩家/引擎上下文\"}");
                return;
            }
            JsonObject obj = parseBodyJson(ex);
            String bankId = obj.has("bankId") ? obj.get("bankId").getAsString() : null;
            double amount = obj.has("amount") ? obj.get("amount").getAsDouble() : 0.0;
            Bank bank = bankId == null ? null : eng.getBankManager().getBank(bankId);
            if (bank == null) {
                sendJson(ex, 200, "{\"success\":false,\"message\":\"银行不存在\"}");
                return;
            }
            if (amount <= 0) {
                sendJson(ex, 400, "{\"success\":false,\"message\":\"金额须为正\"}");
                return;
            }
            PlayerEconomy eco = boundPlayerEconomy(p);
            if (eco == null || eco.getCash() < amount) {
                sendJson(ex, 200, "{\"success\":false,\"message\":\"现金不足\"}");
                return;
            }
            eco.setCash(eco.getCash() - amount);
            bank.deposit(p.getUUID(), amount);
            sendJson(ex, 200, "{\"success\":true,\"message\":\"存款成功\"}");
        } catch (Exception e) {
            sendJson(ex, 500, "{\"success\":false,\"message\":\"存款处理异常\"}");
        }
    }

    /** POST /api/bank/withdraw body {bankId, amount} → {success, message}（银行活期划转回玩家现金）。 */
    private static void handleBankWithdraw(HttpExchange ex) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                sendJson(ex, 400, "{\"success\":false,\"message\":\"仅支持 POST\"}");
                return;
            }
            ServerPlayer p = resolveTradingPlayer();
            MarketEngine eng = getEngine();
            if (p == null || eng == null) {
                sendJson(ex, 200, "{\"success\":false,\"message\":\"无可用玩家/引擎上下文\"}");
                return;
            }
            JsonObject obj = parseBodyJson(ex);
            String bankId = obj.has("bankId") ? obj.get("bankId").getAsString() : null;
            double amount = obj.has("amount") ? obj.get("amount").getAsDouble() : 0.0;
            Bank bank = bankId == null ? null : eng.getBankManager().getBank(bankId);
            if (bank == null) {
                sendJson(ex, 200, "{\"success\":false,\"message\":\"银行不存在\"}");
                return;
            }
            if (amount <= 0) {
                sendJson(ex, 400, "{\"success\":false,\"message\":\"金额须为正\"}");
                return;
            }
            if (!bank.withdraw(p.getUUID(), amount)) {
                sendJson(ex, 200, "{\"success\":false,\"message\":\"取款失败（活期余额/现金不足）\"}");
                return;
            }
            PlayerEconomy eco = boundPlayerEconomy(p);
            if (eco != null) eco.addCash(amount);
            sendJson(ex, 200, "{\"success\":true,\"message\":\"取款成功\"}");
        } catch (Exception e) {
            sendJson(ex, 500, "{\"success\":false,\"message\":\"取款处理异常\"}");
        }
    }

    /**
     * 冒烟阻断项修复：POST /api/bank/card body {bankId} → {success, message, cardNumber?}。
     * 真实玩家发卡入口（此前 issueCard 全仓库零调用方 → 全玩法被「请先办理储蓄卡」门控卡死）。
     * 服务端权威：banking 开关 / 已持卡（每玩家 1 张）/ 未知银行校验后复用
     * {@code BankCardService.issueCard}（确定性、Luhn 合法、无随机偏差），成功返回 16 位卡号。
     * 与银行平板 {@code CardIssuePacket} 双入口语义一致。确定性、无随机。
     */
    private static void handleBankCard(HttpExchange ex) throws IOException {
        try {
            if ("GET".equalsIgnoreCase(ex.getRequestMethod())) {
                ServerPlayer p = resolveTradingPlayer();
                MarketEngine eng = getEngine();
                if (p == null || eng == null) {
                    sendJson(ex, 200, "{\"hasCard\":false,\"message\":\"无可用玩家/引擎上下文\"}");
                    return;
                }
                sendJson(ex, 200, GSON.toJson(cardInfoMap(eng, p)));
                return;
            }
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                sendJson(ex, 400, "{\"success\":false,\"message\":\"仅支持 GET/POST\"}");
                return;
            }
            ServerPlayer p = resolveTradingPlayer();
            MarketEngine eng = getEngine();
            if (p == null || eng == null) {
                sendJson(ex, 200, "{\"success\":false,\"message\":\"无可用玩家/引擎上下文\"}");
                return;
            }
            if (!eng.isBankingEnabled()) {
                sendJson(ex, 200, "{\"success\":false,\"message\":\"银行系统未开启，无法办卡\"}");
                return;
            }
            JsonObject obj = parseBodyJson(ex);
            String bankId = obj.has("bankId") ? obj.get("bankId").getAsString() : null;
            if (bankId == null || !eng.getBankCardService().isKnownBank(bankId)) {
                sendJson(ex, 200, "{\"success\":false,\"message\":\"未知银行（bankId 须为默认银行）\"}");
                return;
            }
            if (eng.hasBankCard(p.getUUID())) {
                sendJson(ex, 200, "{\"success\":false,\"message\":\"您已持有储蓄卡（每玩家限 1 张）\"}");
                return;
            }
            String number = eng.getBankCardService().issueCard(p.getUUID(), bankId, eng.getTickCounter());
            if (number == null) {
                sendJson(ex, 200, "{\"success\":false,\"message\":\"开卡失败（卡号碰撞重试耗尽）\"}");
                return;
            }
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("success", true);
            res.put("message", "开卡成功");
            res.put("cardNumber", number);
            res.put("bankId", bankId);
            sendJson(ex, 200, GSON.toJson(res));
        } catch (Exception e) {
            sendJson(ex, 500, "{\"success\":false,\"message\":\"开卡处理异常\"}");
        }
    }

    private static Map<String, Object> cardInfoMap(MarketEngine eng, ServerPlayer p) {
        Map<String, Object> out = new LinkedHashMap<>();
        BankCard card = eng.getBankCardService().getCardOf(p.getUUID());
        out.put("hasCard", card != null);
        out.put("cash", fmt(PlayerEconomy.get(p).getCash()));
        if (card == null) {
            out.put("message", "尚未办理储蓄卡");
            return out;
        }
        out.put("cardMasked", BankCardService.mask(card.getCardNumber()));
        out.put("last4", card.getCardNumber().substring(card.getCardNumber().length() - 4));
        out.put("bankId", card.getBankId());
        out.put("active", card.isActive());
        out.put("frozen", card.isFrozen());
        out.put("pinSet", card.hasPin());
        out.put("dailyLimit", fmt(card.getDailyLimit()));
        out.put("spentToday", fmt(card.getSpentToday()));
        out.put("depositBalance", fmt(
                eng.getBankManager().getBank(card.getBankId()) == null ? 0
                        : eng.getBankManager().getBank(card.getBankId())
                        .getDepositBalance(p.getUUID())));
        return out;
    }

    private static void handleBankCardAction(HttpExchange ex, String action) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                sendJson(ex, 400, "{\"success\":false,\"message\":\"仅支持 POST\"}");
                return;
            }
            ServerPlayer p = resolveTradingPlayer();
            MarketEngine eng = getEngine();
            if (p == null || eng == null) {
                sendJson(ex, 200, "{\"success\":false,\"message\":\"无可用玩家/引擎上下文\"}");
                return;
            }
            PaymentService svc = eng.getPaymentService();
            PaymentService.Receipt r;
            if ("pin".equals(action)) {
                JsonObject obj = parseBodyJson(ex);
                String pin = obj.has("pin") ? obj.get("pin").getAsString() : "";
                r = svc.setPin(p, pin);
            } else if ("freeze".equals(action)) {
                r = svc.freeze(p, true);
            } else {
                r = svc.freeze(p, false);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("success", r.success());
            out.put("message", r.message());
            sendJson(ex, 200, GSON.toJson(out));
        } catch (Exception e) {
            sendJson(ex, 500, "{\"success\":false,\"message\":\"卡片操作异常\"}");
        }
    }

    private static void handlePayment(HttpExchange ex, boolean merchant) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                sendJson(ex, 400, "{\"success\":false,\"message\":\"仅支持 POST\"}");
                return;
            }
            ServerPlayer p = resolveTradingPlayer();
            MarketEngine eng = getEngine();
            if (p == null || eng == null) {
                sendJson(ex, 200, "{\"success\":false,\"message\":\"无可用玩家/引擎上下文\"}");
                return;
            }
            JsonObject obj = parseBodyJson(ex);
            String toCard = obj.has("toCard") ? obj.get("toCard").getAsString() : "";
            double amount = obj.has("amount") ? obj.get("amount").getAsDouble() : 0;
            String memo = obj.has("memo") ? obj.get("memo").getAsString() : "";
            String pin = obj.has("pin") ? obj.get("pin").getAsString() : "";
            PaymentService.Receipt r = merchant
                    ? eng.getPaymentService().pay(p, toCard, amount, memo, pin, eng.getTickCounter())
                    : eng.getPaymentService().transfer(p, toCard, amount, memo, pin,
                    eng.getTickCounter(), false);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("success", r.success());
            out.put("message", r.message());
            out.put("transactionId", r.transactionId());
            out.put("amount", fmt(r.amount()));
            out.put("fee", fmt(r.fee()));
            out.put("balance", fmt(r.payerBalance()));
            sendJson(ex, 200, GSON.toJson(out));
        } catch (Exception e) {
            sendJson(ex, 500, "{\"success\":false,\"message\":\"支付处理异常\"}");
        }
    }

    private static void handleBankStatement(HttpExchange ex) throws IOException {
        try {
            ServerPlayer p = resolveTradingPlayer();
            MarketEngine eng = getEngine();
            if (p == null || eng == null) {
                sendJson(ex, 200, "[]");
                return;
            }
            int limit = 50;
            String q = ex.getRequestURI().getQuery();
            if (q != null) {
                for (String part : q.split("&")) {
                    if (part.startsWith("limit=")) {
                        try { limit = Math.max(1, Math.min(200, Integer.parseInt(part.substring(6)))); }
                        catch (NumberFormatException ignored) { }
                    }
                }
            }
            List<Map<String, Object>> list = new ArrayList<>();
            for (PaymentService.PaymentTransaction tx
                    : eng.getPaymentService().statement(p.getUUID(), limit)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", tx.id());
                m.put("tick", tx.tick());
                m.put("day", tx.day());
                boolean incoming = p.getUUID().equals(tx.to());
                m.put("direction", incoming ? "IN" : "OUT");
                m.put("amount", fmt(tx.amount()));
                m.put("fee", fmt(tx.fee()));
                m.put("channel", tx.channel());
                m.put("memo", tx.memo());
                m.put("status", tx.status());
                m.put("counterparty", incoming ? tx.fromCard() : tx.toCard());
                list.add(m);
            }
            sendJson(ex, 200, GSON.toJson(list));
        } catch (Exception e) {
            sendJson(ex, 500, "[]");
        }
    }

    private static String bankPaymentSelfTest() {
        try {
            var banks = new com.example.financialmarket.engine.banking.BankManager();
            var cards = new com.example.financialmarket.engine.banking.BankCardService(42L);
            cards.setValidBankIds(java.util.List.of("BANK_A", "BANK_B", "BANK_C", "BANK_D"));
            UUID u1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
            UUID u2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
            String a = cards.issueCard(u1, "BANK_A", 1);
            String b = cards.issueCard(u2, "BANK_B", 1);
            cards.setPin(u1, "1234");
            cards.setPin(u2, "5678");
            banks.getBank("BANK_A").deposit(u1, 10_000);
            var payments = new PaymentService(banks, cards);
            var wrong = payments.transfer(u1, b, 1000, "wrong pin", "0000", 24000, false);
            var ok = payments.transfer(u1, b, 1000, "selftest", "1234", 24000, false);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("success", a != null && b != null && !wrong.success() && ok.success()
                    && Math.abs(banks.getBank("BANK_A").getDepositBalance(u1) - 8999.0) < 1e-6
                    && Math.abs(banks.getBank("BANK_B").getDepositBalance(u2) - 1000.0) < 1e-6
                    && payments.statement(u1, 10).size() == 1);
            out.put("cardA", BankCardService.mask(a));
            out.put("cardB", BankCardService.mask(b));
            out.put("wrongPinRejected", !wrong.success());
            out.put("transferSuccess", ok.success());
            out.put("fee", fmt(ok.fee()));
            out.put("payerBalance", fmt(banks.getBank("BANK_A").getDepositBalance(u1)));
            out.put("payeeBalance", fmt(banks.getBank("BANK_B").getDepositBalance(u2)));
            out.put("ledgerSize", payments.statement(u1, 10).size());
            return GSON.toJson(out);
        } catch (Exception e) {
            return "{\"success\":false,\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    /** GET /api/quant/strategies → [{strategyId,playerId,name,kind,fast,slow,enabled,scriptId}]（只读快照）。 */
    private static String getQuantStrategiesJson() {
        MarketEngine eng = getEngine();
        if (eng == null) return "[]";
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map.Entry<java.util.UUID, com.example.financialmarket.engine.quant.Strategy> e
                : eng.getQuantAPI().snapshot().entrySet()) {
            com.example.financialmarket.engine.quant.Strategy st = e.getValue();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("strategyId", e.getKey().toString());
            m.put("playerId", e.getKey().toString());
            m.put("name", st.getStrategyName());
            m.put("kind", st.getKind() == null ? "EMA_CROSS" : st.getKind().name());
            m.put("fast", st.getFastPeriod());
            m.put("slow", st.getSlowPeriod());
            m.put("enabled", st.isEnabled());
            m.put("scriptId", st.getScriptId());
            list.add(m);
        }
        return GSON.toJson(list);
    }

    /** GET /api/quant/script?scriptId → {scriptId,fileName,code}；POST /api/quant/script body {scriptId,code} 编译保存。 */
    private static void handleQuantScript(HttpExchange ex) throws IOException {
        try {
            if ("GET".equalsIgnoreCase(ex.getRequestMethod())) {
                String scriptId = queryToMap(ex.getRequestURI().getQuery()).get("scriptId");
                if (scriptId == null) {
                    sendJson(ex, 200, "{\"scriptId\":\"\",\"fileName\":\"\",\"code\":\"\"}");
                    return;
                }
                String code = com.example.financialmarket.storage.db.H2DatabaseManager.loadQuantScript(scriptId);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("scriptId", scriptId);
                m.put("fileName", scriptId);
                m.put("code", code == null ? "" : code);
                sendJson(ex, 200, GSON.toJson(m));
                return;
            }
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                sendJson(ex, 400, "{\"success\":false,\"message\":\"仅支持 GET/POST\"}");
                return;
            }
            MarketEngine eng = getEngine();
            if (eng == null) {
                sendJson(ex, 200, "{\"success\":false,\"message\":\"无可用引擎上下文\"}");
                return;
            }
            JsonObject obj = parseBodyJson(ex);
            String scriptId = obj.has("scriptId") ? obj.get("scriptId").getAsString() : null;
            String code = obj.has("code") ? obj.get("code").getAsString() : "";
            if (scriptId == null || scriptId.isEmpty()) {
                sendJson(ex, 400, "{\"success\":false,\"message\":\"scriptId 无效\"}");
                return;
            }
            boolean saved = com.example.financialmarket.storage.db.H2DatabaseManager.saveQuantScript(scriptId, code);
            boolean compiled = eng.getQuantScriptEngine().compile(scriptId, code);
            if (saved && compiled) {
                sendJson(ex, 200, "{\"success\":true,\"message\":\"脚本已保存并编译\"}");
            } else {
                sendJson(ex, 200, "{\"success\":false,\"message\":\"脚本编译失败（指令无法解析）\"}");
            }
        } catch (Exception e) {
            sendJson(ex, 500, "{\"success\":false,\"message\":\"量化脚本处理异常\"}");
        }
    }

    /** GET /api/quant/status?strategyId → {enabled,lastRunTick,generatedOrders,pnl,pnlPct,equityCurve}。 */
    private static String getQuantStatusJson(HttpExchange ex) {
        MarketEngine eng = getEngine();
        if (eng == null) return "{}";
        Map<String, String> params = queryToMap(ex.getRequestURI().getQuery());
        String strategyId = params.get("strategyId");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", strategyId != null && resolveUuidOrNull(strategyId) != null
                && eng.getQuantAPI().isEnabled(resolveUuidOrNull(strategyId)));
        m.put("lastRunTick", eng.getTickCounter());
        m.put("generatedOrders", new ArrayList<>());
        m.put("pnl", 0.0);
        m.put("pnlPct", 0.0);
        m.put("equityCurve", new ArrayList<>());
        return GSON.toJson(m);
    }

    /** POST /api/quant/strategy body {name,kind,fast,slow} → {success,message,strategyId?}（绑定玩家），走 QuantAPI.enableStrategy。 */
    private static void handleQuantStrategy(HttpExchange ex) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                sendJson(ex, 400, "{\"success\":false,\"message\":\"仅支持 POST\"}");
                return;
            }
            ServerPlayer p = resolveTradingPlayer();
            MarketEngine eng = getEngine();
            if (p == null || eng == null) {
                sendJson(ex, 200, "{\"success\":false,\"message\":\"无可用玩家/引擎上下文\"}");
                return;
            }
            JsonObject obj = parseBodyJson(ex);
            String name = obj.has("name") ? obj.get("name").getAsString() : "ema_cross";
            String kind = obj.has("kind") ? obj.get("kind").getAsString() : "EMA_CROSS";
            int fast = obj.has("fast") ? obj.get("fast").getAsInt() : 5;
            int slow = obj.has("slow") ? obj.get("slow").getAsInt() : 20;
            eng.getQuantAPI().enableStrategy(p.getUUID(), name, fast, slow);
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("success", true);
            res.put("message", "策略已启用");
            res.put("strategyId", p.getUUID().toString());
            sendJson(ex, 200, GSON.toJson(res));
        } catch (Exception e) {
            sendJson(ex, 500, "{\"success\":false,\"message\":\"策略创建处理异常\"}");
        }
    }

    /** POST /api/quant/strategy/enable|disable body {strategyId} → {success,message}（绑定玩家）。 */
    private static void handleQuantEnable(HttpExchange ex) throws IOException {
        quantToggle(ex, true);
    }

    private static void handleQuantDisable(HttpExchange ex) throws IOException {
        quantToggle(ex, false);
    }

    private static void quantToggle(HttpExchange ex, boolean enable) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                sendJson(ex, 400, "{\"success\":false,\"message\":\"仅支持 POST\"}");
                return;
            }
            ServerPlayer p = resolveTradingPlayer();
            MarketEngine eng = getEngine();
            if (p == null || eng == null) {
                sendJson(ex, 200, "{\"success\":false,\"message\":\"无可用玩家/引擎上下文\"}");
                return;
            }
            if (enable) {
                com.example.financialmarket.engine.quant.Strategy st = eng.getQuantAPI().getStrategy(p.getUUID());
                if (st == null) {
                    sendJson(ex, 200, "{\"success\":false,\"message\":\"请先创建策略\"}");
                    return;
                }
                eng.getQuantAPI().enableStrategy(p.getUUID(), st.getStrategyName(), st.getFastPeriod(), st.getSlowPeriod());
                sendJson(ex, 200, "{\"success\":true,\"message\":\"策略已启用\"}");
            } else {
                eng.getQuantAPI().disableStrategy(p.getUUID());
                sendJson(ex, 200, "{\"success\":true,\"message\":\"策略已停用\"}");
            }
        } catch (Exception e) {
            sendJson(ex, 500, "{\"success\":false,\"message\":\"策略启停处理异常\"}");
        }
    }

    /** POST /api/quant/run body {strategyId} → {success,message,orders,pnl}。手动运行一次（走 QuantAPI.executeStrategies 确定性路径）。 */
    private static void handleQuantRun(HttpExchange ex) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                sendJson(ex, 400, "{\"success\":false,\"message\":\"仅支持 POST\"}");
                return;
            }
            ServerPlayer p = resolveTradingPlayer();
            MarketEngine eng = getEngine();
            if (p == null || eng == null) {
                sendJson(ex, 200, "{\"success\":false,\"message\":\"无可用玩家/引擎上下文\"}");
                return;
            }
            JsonObject obj = parseBodyJson(ex);
            String strategyId = obj.has("strategyId") ? obj.get("strategyId").getAsString() : null;
            if (strategyId == null || !strategyId.equals(p.getUUID().toString())) {
                sendJson(ex, 200, "{\"success\":false,\"message\":\"策略不存在或非本人\"}");
                return;
            }
            if (!eng.getQuantAPI().isEnabled(p.getUUID())) {
                sendJson(ex, 200, "{\"success\":false,\"message\":\"策略未启用\"}");
                return;
            }
            List<Map<String, Object>> orders = new ArrayList<>();
            for (String assetId : eng.getState().getAssetIds()) {
                List<Order> generated = eng.getQuantAPI().executeStrategies(eng.getState(), p.getUUID(), assetId);
                if (generated == null) continue;
                for (Order o : generated) {
                    eng.submitOrder(o);
                    Map<String, Object> mo = new LinkedHashMap<>();
                    mo.put("assetId", o.getAssetId());
                    mo.put("side", o.isBuy() ? "buy" : "sell");
                    mo.put("price", fmt(o.getLimitPrice()));
                    mo.put("quantity", o.getTotalQuantity());
                    mo.put("time", eng.getTickCounter());
                    orders.add(mo);
                }
            }
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("success", true);
            res.put("message", orders.isEmpty() ? "运行完成（未产生订单）" : "运行完成，产生 " + orders.size() + " 笔订单");
            res.put("orders", orders);
            res.put("pnl", 0.0);
            sendJson(ex, 200, GSON.toJson(res));
        } catch (Exception e) {
            sendJson(ex, 500, "{\"success\":false,\"message\":\"量化运行处理异常\"}");
        }
    }

    /** GET /api/marketmap → {industries:[{name,avgChange,count,upCount,downCount,flatCount,totalVol,totalAmount}], breadth:{...}, indices:[...]}（纯只读聚合，可选）。 */
    /** GET /api/worldmacro → {tick,day,productionIndex,ppi,cpi,capacityUtil,eventRisk,novelty,sectors,companies,eventsProcessed,unmappedResources}. */
    /** GET /api/crossmacro → world↔stock↔futures↔options↔player transmission snapshot. */
    private static String getCrossMacroJson() {
        MarketEngine eng = getEngine();
        if (eng == null) return "{}";
        CrossAssetMacro macro = eng.getCrossAssetMacro();
        if (macro == null) return "{}";
        CrossAssetMacro.Snapshot s = macro.snapshot();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tick", s.tick());
        out.put("day", s.day());
        out.put("stockReturn", fmt(s.stockReturn()));
        out.put("commodityReturn", fmt(s.commodityReturn()));
        out.put("riskAppetite", fmt(s.riskAppetite()));
        out.put("growth", fmt(s.growth()));
        out.put("inflation", fmt(s.inflation()));
        out.put("eventRisk", fmt(s.eventRisk()));
        out.put("playerStockFlow", fmt(s.playerStockFlow()));
        out.put("playerFuturesFlow", fmt(s.playerFuturesFlow()));
        out.put("leverageStress", fmt(s.leverageStress()));
        out.put("futuresSpotMultiplier", fmt(s.futuresSpotMultiplier()));
        out.put("optionVolMultiplier", fmt(s.optionVolMultiplier()));
        out.put("optionSkewShift", fmt(s.optionSkewShift()));
        out.put("stockGlobalTilt", fmt(s.stockGlobalTilt()));
        out.put("sectorTilts", s.sectorTilts());
        return GSON.toJson(out);
    }

    /** GET /api/ak → aggregate AK growth + per-company capital/output/investment. */
    private static String getAkJson() {
        MarketEngine eng = getEngine();
        if (eng == null) return "{}";
        var a = eng.getAkGrowthEngine().aggregate();
        var cd = eng.getAkGrowthEngine().cobbDouglas();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("capital", fmt(a.capital()));
        out.put("output", fmt(a.output()));
        out.put("investment", fmt(a.investment()));
        out.put("consumption", fmt(a.consumption()));
        out.put("growth", fmt(a.growth()));
        out.put("returnRate", fmt(a.returnRate()));
        out.put("tfp", fmt(a.tfp()));
        out.put("savingsRate", fmt(a.savingsRate()));
        Map<String, Object> cdm = new LinkedHashMap<>();
        cdm.put("output", fmt(cd.output()));
        cdm.put("tfp", fmt(cd.tfp()));
        cdm.put("mpk", fmt(cd.mpk()));
        cdm.put("mpl", fmt(cd.mpl()));
        cdm.put("capitalShare", fmt(cd.capitalShare()));
        cdm.put("laborShare", fmt(cd.laborShare()));
        cdm.put("optimalCapital", fmt(cd.optimalCapital()));
        cdm.put("capitalGap", fmt(cd.capitalGap()));
        cdm.put("returnRate", fmt(cd.returnRate()));
        out.put("cobbDouglas", cdm);
        List<Map<String, Object>> companies = new ArrayList<>();
        for (CompanyData c : eng.getCompanyManager().getAllCompanies()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("stockId", c.getStockId());
            m.put("name", c.getName());
            m.put("capital", fmt(c.getAkCapital()));
            m.put("output", fmt(c.getAkOutput()));
            m.put("investment", fmt(c.getAkInvestment()));
            m.put("growth", fmt(c.getAkGrowth()));
            m.put("returnRate", fmt(c.getAkReturn()));
            m.put("tfp", fmt(c.getAkTfp()));
            m.put("fairValue", fmt(c.getAkFairValue()));
            m.put("cdOutput", fmt(c.getCdOutput()));
            m.put("cdTfp", fmt(c.getCdTfp()));
            m.put("cdMpk", fmt(c.getCdMpk()));
            m.put("cdMpl", fmt(c.getCdMpl()));
            m.put("cdOptimalCapital", fmt(c.getCdOptimalCapital()));
            m.put("cdCapitalGap", fmt(c.getCdCapitalGap()));
            companies.add(m);
        }
        out.put("companies", companies);
        return GSON.toJson(out);
    }

    private static String getWorldMacroJson() {
        MarketEngine eng = getEngine();
        if (eng == null) return "{}";
        WorldMacroSnapshot s = eng.getWorldMacro().snapshot();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tick", s.tick());
        out.put("day", s.day());
        out.put("productionIndex", fmt(s.productionIndex()));
        out.put("ppi", fmt(s.ppi()));
        out.put("cpi", fmt(s.cpi()));
        out.put("capacityUtil", fmt(s.capacityUtil()));
        out.put("eventRisk", fmt(s.eventRisk()));
        out.put("novelty", fmt(s.novelty()));
        out.put("eventsProcessed", s.eventsProcessed());
        out.put("eventsDropped", s.eventsDropped());
        out.put("unmappedResources", s.unmappedResources());
        out.put("resourceProfiles", s.resourceProfiles());
        List<Map<String, Object>> sectors = new ArrayList<>();
        for (var e : s.sectors().entrySet()) {
            var v = e.getValue();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.getKey());
            m.put("outputIndex", fmt(v.outputIndex()));
            m.put("output", fmt(v.output()));
            m.put("capex", fmt(v.capex()));
            m.put("rnd", fmt(v.rnd()));
            m.put("risk", fmt(v.risk()));
            m.put("margin", fmt(v.margin()));
            m.put("events", v.events());
            sectors.add(m);
        }
        out.put("sectors", sectors);
        List<Map<String, Object>> companies = new ArrayList<>();
        for (Map.Entry<String, WorldMacroSnapshot.CompanyState> e : s.companies().entrySet()) {
            WorldMacroSnapshot.CompanyState v = e.getValue();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.getKey());
            m.put("sector", v.sector());
            m.put("production", fmt(v.production()));
            m.put("capital", fmt(v.capital()));
            m.put("capex", fmt(v.capex()));
            m.put("rnd", fmt(v.rnd()));
            m.put("risk", fmt(v.risk()));
            m.put("techTier", fmt(v.techTier()));
            m.put("cashFlow", fmt(v.cashFlow()));
            m.put("lastSurprise", fmt(v.lastSurprise()));
            companies.add(m);
        }
        out.put("companies", companies);
        return GSON.toJson(out);
    }

    private static String getMarketmapJson() {
        MarketEngine eng = getEngine();
        if (eng == null) return "{\"industries\":[],\"breadth\":{},\"indices\":[]}";
        // 行业聚合（由引擎股票数据按 industry 分组，只读）
        Map<String, List<Double>> changeByIndustry = new LinkedHashMap<>();
        int up = 0, down = 0, flat = 0, limitUp = 0, limitDown = 0;
        for (String id : eng.getState().getAssetIds()) {
            AssetSnapshot snap = eng.getState().getAsset(id);
            if (snap == null) continue;
            AICompanyData company = eng.getAiCompanyManager().getAll().stream()
                    .filter(c -> c.getStockId().equals(id)).findFirst().orElse(null);
            String industry = company != null ? company.getIndustry() : "PLAYER";
            double chg = snap.getDailyChange();
            changeByIndustry.computeIfAbsent(industry, k -> new ArrayList<>()).add(chg);
            if (chg > 0) { up++; if (chg >= 10) limitUp++; }
            else if (chg < 0) { down++; if (chg <= -10) limitDown++; }
            else { flat++; }
        }
        List<Map<String, Object>> industries = new ArrayList<>();
        for (Map.Entry<String, List<Double>> e : changeByIndustry.entrySet()) {
            List<Double> group = e.getValue();
            double sum = 0;
            int gUp = 0, gDown = 0, gFlat = 0;
            for (double chg : group) {
                sum += chg;
                if (chg > 0) gUp++;
                else if (chg < 0) gDown++;
                else gFlat++;
            }
            Map<String, Object> im = new LinkedHashMap<>();
            im.put("name", e.getKey());
            im.put("avgChange", fmt(sum / group.size()));
            im.put("count", group.size());
            im.put("upCount", gUp);
            im.put("downCount", gDown);
            im.put("flatCount", gFlat);
            industries.add(im);
        }
        Map<String, Object> breadth = new LinkedHashMap<>();
        breadth.put("up", up);
        breadth.put("down", down);
        breadth.put("flat", flat);
        breadth.put("limitUp", limitUp);
        breadth.put("limitDown", limitDown);
        breadth.put("totalVol", 0.0);
        breadth.put("totalAmount", 0.0);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("industries", industries);
        result.put("breadth", breadth);
        result.put("indices", JsonParser.parseString(getIndexJson()).getAsJsonObject());
        return GSON.toJson(result);
    }

    /** 解析请求体为 JsonObject（非法则抛出）。 */
    private static JsonObject parseBodyJson(HttpExchange ex) throws IOException {
        String body = readBody(ex);
        if (body == null || body.isBlank()) return new JsonObject();
        try {
            return JsonParser.parseString(body).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("请求体需为 JSON", e);
        }
    }

    // ===== 静态资源加载 =====
    private static String loadResource(String path) {
        try (InputStream is = LocalHttpServer.class.getClassLoader().getResourceAsStream("assets/financialmarket/" + path)) {
            if (is == null) return "";
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    // ===== 响应发送 =====
    private static void sendHtml(HttpExchange ex, String content) throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    private static void sendCss(HttpExchange ex, String content) throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/css; charset=UTF-8");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    private static void sendJs(HttpExchange ex, String content) throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/javascript; charset=UTF-8");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    private static void sendJson(HttpExchange ex, String json) throws IOException {
        sendJson(ex, 200, json);
    }

    private static void sendJson(HttpExchange ex, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    private static void send404(HttpExchange ex) throws IOException {
        String msg = "404 Not Found";
        ex.sendResponseHeaders(404, msg.length());
        try (OutputStream os = ex.getResponseBody()) { os.write(msg.getBytes()); }
    }
}