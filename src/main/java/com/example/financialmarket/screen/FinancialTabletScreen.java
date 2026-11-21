package com.example.financialmarket.screen;

import com.example.financialmarket.attachment.Holding;
import com.example.financialmarket.company.CompanyData;
import com.example.financialmarket.company.CompanyLevel;
import com.example.financialmarket.company.CompanyStatus;
import com.example.financialmarket.engine.OHLCData;
import com.example.financialmarket.engine.OrderBook;
import com.example.financialmarket.engine.PriceDiscoveryEngine;
import com.example.financialmarket.engine.banking.Loan;
import com.example.financialmarket.engine.banking.CurrencySystem;
import com.example.financialmarket.network.packets.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import org.lwjgl.glfw.GLFW;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public class FinancialTabletScreen extends Screen {
    public void updateBankData(Map<String, BankDataResponsePacket.BankInfo> banks, double cash, List<BankDataResponsePacket.LoanInfo> loans) {
    }

    public void updateGovernmentData(double treasury, double v, double inflation, double v1, double v2) {
    }

    private enum Page { STOCKS, COMMODITIES, PORTFOLIO, COMPANY, LENDING, QUANT, MARKET_MAP }
    private Page currentPage = Page.STOCKS;
    public enum MarketStyle { US, CN }
    private MarketStyle currentStyle = MarketStyle.CN;
    private int COLOR_RISE, COLOR_FALL;

    private double cash = 0.0;
    private final Map<String, Holding> holdings = new LinkedHashMap<>();
    private final List<StockEntry> marketStocks = new ArrayList<>();
    private String selectedStockId = "";
    private double indexValue = 1000.0;
    private CompanyData playerCompany = null;
    private double marginBalance = 0.0;
    private double marginRisk = 0.0;
    private final List<Loan> activeLoans = new ArrayList<>();
    private boolean showKlineOverlay = true;
    private boolean logScale = false;
    private List<PriceDiscoveryEngine.TradePoint> tradePoints = new ArrayList<>();

    private final List<OHLCData> klines = new ArrayList<>();
    private int currentKlineType = 0;
    private double[] ma5, ma10, ma20, ma60, ma120, ma250;
    private double[] volMa5, volMa10;
    private double[] rsi, macdLine, signalLine, macdHistogram;
    private double[] kValue, dValue, jValue;
    private double[] bollUpper, bollMid, bollLower;

    private boolean showRSI = true, showMACD = true, showKDJ = true, showBOLL = true;
    private boolean showKline = true;

    private final Map<Item, Double> commodityPrices = new LinkedHashMap<>();
    private Item selectedCommodity = null;
    private final Map<Item, LinkedList<Double>> commodityPriceHistory = new LinkedHashMap<>();
    private static final int COMMODITY_HISTORY_MAX = 50;
    private OrderBook.OrderBookSnapshot currentOrderBook = new OrderBook.OrderBookSnapshot();

    private static final int LEFT_W = 280;
    private static final int RIGHT_W = 240;
    private static final int TOP_H = 32;
    private static final int BOTTOM_H = 28;
    private static final int VOL_H = 80;
    private static final int INDICATOR_H = 80;

    private int chartX, chartY, chartW, chartH;
    private int volY, volH;
    private int indicatorY;
    private int rightX, rightY, rightH;

    private int leftScrollOffset = 0;
    private int leftRowHeight = 14;
    private int leftVisibleRows = 0;
    private int commodityScrollOffset = 0;
    private int commodityRowHeight = 12;
    private int commodityVisibleRows = 0;
    private int rightScrollOffset = 0;
    private int chartScrollOffset = 0;
    private boolean chartNeedScroll = false;

    private int visibleCandles = 60;
    private double verticalScale = 1.0, verticalOffset = 0.0;
    private int scrollOffsetX = 0;
    private boolean dragging = false;
    private int dragStartX, dragStartY, dragStartOffsetX;
    private double dragStartOffsetY;

    private boolean qtyFocused = false, priceFocused = false;
    private String qtyText = "100", priceText = "";
    private boolean commodityQtyFocused = false;
    private String commodityQtyText = "1";
    private boolean companyNameFocused = false;
    private String companyNameText = "";
    private boolean useLots = true;
    private int lotSize = 100;
    private int leverage = 1;
    private boolean isShort = false;

    private boolean selectingArea = false;
    private BlockPos corner1 = null, corner2 = null;
    private boolean draggingCorner = false;
    private int dragCornerStartX, dragCornerStartY;

    private boolean depositFocused = false;
    private String depositQtyText = "1";
    private boolean quantCodeFocused = false;
    private String quantCodeText = "";
    private String quantFileName = "strategy1.js";

    private static final int COLOR_BG = 0xFF1E1E1E;
    private static final int COLOR_PANEL = 0xCC2D2D2D;
    private static final int COLOR_CHART_BG = 0xFF0A0A0A;
    private static final int COLOR_TEXT = 0xFFF5F5F5;
    private static final int COLOR_HEADER = 0xFFD0D0D0;
    private static final int COLOR_ACCENT = 0xFF8A8A8A;
    private static final int COLOR_CROSSHAIR = 0xFFFFFF00;
    private static final int COLOR_BTN = 0xFF3F3F3F;
    private static final int COLOR_BTN_HOVER = 0xFF5A5A5A;
    private static final int COLOR_TAB_ACTIVE = 0xFF6C6C6C;
    private static final int COLOR_TAB_INACTIVE = 0xFF3A3A3A;
    private static final int COLOR_TRENDLINE = 0xFFFFB347;
    private static final int COLOR_BID = 0xFF00AA00;
    private static final int COLOR_ASK = 0xFFAA0000;
    private static final int COLOR_LINE = 0xFF444444;
    private static final int COLOR_MA5  = 0xFFFFF000;
    private static final int COLOR_MA10 = 0xFFFFA500;
    private static final int COLOR_MA20 = 0xFFE040FB;
    private static final int COLOR_MA60 = 0xFF448AFF;
    private static final int COLOR_MA120 = 0xFF69F0AE;
    private static final int COLOR_MA250 = 0xFFBDBDBD;

    private int refreshTimer = 0;
    private int cursorBlink = 0;

    public FinancialTabletScreen() {
        super(Component.literal("Financial Market"));
        applyStyleColors();
    }

    private void applyStyleColors() {
        if (currentStyle == MarketStyle.US) { COLOR_RISE = 0xFF00FFA3; COLOR_FALL = 0xFFFF5252; }
        else { COLOR_RISE = 0xFFFF5252; COLOR_FALL = 0xFF00FFA3; }
    }

    private void recalcLayout() {
        rightX = width - RIGHT_W;
        rightY = TOP_H;
        rightH = height - TOP_H - BOTTOM_H;
        chartX = LEFT_W;
        chartY = TOP_H + 40;
        chartW = width - LEFT_W - RIGHT_W;
        chartH = Math.max(100, (height - TOP_H - BOTTOM_H - chartY - VOL_H - 20) / 2);
        volY = chartY + chartH + 10;
        volH = VOL_H;
        indicatorY = volY + volH + 5;
        int leftTop = TOP_H + 20 + 14;
        leftVisibleRows = Math.max(1, (height - BOTTOM_H - leftTop) / leftRowHeight);
        commodityVisibleRows = Math.max(1, (height - BOTTOM_H - (TOP_H + 22)) / commodityRowHeight);
    }

    @Override
    protected void init() {
        super.init();
        recalcLayout();
        new RequestMarketListPacket().send();
        new RequestPortfolioPacket().send();
        new RequestCommodityPricePacket().send();
        new RequestCompanyDataPacket().send();
        new RequestMarginDataPacket().send();
        new RequestLoanDataPacket().send();
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partial) {
        recalcLayout();
        g.fill(0, 0, width, height, COLOR_BG);
        drawTopBar(g, mx, my);
        switch (currentPage) {
            case STOCKS -> { drawLeftPanel(g, mx, my); drawChartArea(g, mx, my); drawRightPanel(g, mx, my); }
            case COMMODITIES -> drawCommoditiesPage(g, mx, my);
            case PORTFOLIO -> drawPortfolioPage(g, mx, my);
            case COMPANY -> drawCompanyPage(g, mx, my);
            case LENDING -> drawLendingPage(g, mx, my);
            case QUANT -> drawQuantPage(g, mx, my);
            case MARKET_MAP -> drawMarketMapPage(g, mx, my);
        }
        drawBottomBar(g);
        cursorBlink++;
        if (cursorBlink > 20) cursorBlink = 0;
        if (!selectedStockId.isEmpty() && currentPage == Page.STOCKS) {
            refreshTimer++;
            if (refreshTimer > 100) {
                refreshTimer = 0;
                new RequestKlinePacket(selectedStockId, currentKlineType).send();
            }
        }
    }

    private void drawTopBar(GuiGraphics g, int mx, int my) {
        g.fill(0, 0, width, TOP_H, 0xFF2B2B2B);
        String[] tabNames = {"股票", "商品", "持仓", "公司", "借贷", "量化", "云图"};
        Page[] tabPages = {Page.STOCKS, Page.COMMODITIES, Page.PORTFOLIO, Page.COMPANY, Page.LENDING, Page.QUANT, Page.MARKET_MAP};
        int tabW = width / 7;
        for (int i = 0; i < 7; i++) {
            drawTab(g, i * tabW, 4, tabW - 2, 22, tabNames[i], tabPages[i], mx, my);
        }
        g.drawString(font, String.format("现金: %.2f", cash), width - 150, 8, COLOR_TEXT);
    }

    private void drawTab(GuiGraphics g, int x, int y, int w, int h, String text, Page page, int mx, int my) {
        boolean active = currentPage == page;
        boolean hover = mx >= x && mx <= x + w && my >= y && my <= y + h;
        int bg = active ? COLOR_TAB_ACTIVE : (hover ? COLOR_BTN_HOVER : COLOR_TAB_INACTIVE);
        g.fill(x, y, x + w, y + h, bg);
        g.drawString(font, text, x + (w - font.width(text)) / 2, y + 5, active ? COLOR_TEXT : COLOR_HEADER);
    }

    private void drawBottomBar(GuiGraphics g) {
        g.fill(0, height - BOTTOM_H, width, height, 0xFF2B2B2B);
        String info = "优经24: " + String.format("%.2f", indexValue);
        if (marginBalance > 0) info += "  保证金: " + String.format("%.2f", marginBalance);
        g.drawString(font, info, 5, height - 18, COLOR_HEADER);
    }

    // ===================== 左侧自选列表 =====================
    private void drawLeftPanel(GuiGraphics g, int mx, int my) {
        int y = TOP_H, panelH = height - TOP_H - BOTTOM_H;
        g.fill(0, y, LEFT_W, y + panelH, COLOR_PANEL);
        g.drawString(font, "自选", 6, y + 5, COLOR_ACCENT);
        int headY = y + 20;
        g.fill(0, headY, LEFT_W, headY + 1, COLOR_LINE);
        g.drawString(font, "代码", 5, headY + 3, COLOR_HEADER);
        g.drawString(font, "名称", 45, headY + 3, COLOR_HEADER);
        g.drawString(font, "涨幅%", 105, headY + 3, COLOR_HEADER);
        g.drawString(font, "现价", 155, headY + 3, COLOR_HEADER);
        g.drawString(font, "最高", 195, headY + 3, COLOR_HEADER);
        g.drawString(font, "最低", 230, headY + 3, COLOR_HEADER);
        g.fill(0, headY + 14, LEFT_W, headY + 15, COLOR_LINE);
        int listTop = headY + 16, listBottom = y + panelH - 8, listHeight = listBottom - listTop;
        int totalRows = marketStocks.size();
        int maxScroll = Math.max(0, totalRows - leftVisibleRows);
        leftScrollOffset = Mth.clamp(leftScrollOffset, 0, maxScroll);
        g.enableScissor(0, listTop, LEFT_W, listBottom);
        int lineY = listTop - leftScrollOffset * leftRowHeight;
        for (int i = 0; i < totalRows; i++) {
            StockEntry stock = marketStocks.get(i);
            if (lineY + leftRowHeight < listTop || lineY > listBottom) { lineY += leftRowHeight; continue; }
            boolean sel = stock.stockId.equals(selectedStockId);
            if (sel) g.fill(2, lineY - 1, LEFT_W - 2, lineY + 13, 0xAA8A8A8A);
            String code = currentStyle == MarketStyle.US ? stock.usCode : stock.cnCode;
            g.drawString(font, code, 5, lineY, sel ? COLOR_TEXT : COLOR_HEADER);
            g.drawString(font, stock.name, 45, lineY, COLOR_TEXT);
            String chStr = String.format("%+.2f%%", stock.change);
            int chColor = stock.change >= 0 ? COLOR_RISE : COLOR_FALL;
            g.drawString(font, chStr, 105, lineY, chColor);
            g.drawString(font, String.format("%.2f", stock.price), 155, lineY, COLOR_TEXT);
            g.drawString(font, String.format("%.2f", stock.high), 195, lineY, COLOR_TEXT);
            g.drawString(font, String.format("%.2f", stock.low), 230, lineY, COLOR_TEXT);
            lineY += leftRowHeight;
        }
        g.disableScissor();
        if (maxScroll > 0) {
            int scrollBarX = LEFT_W - 8, scrollBarH = listHeight;
            int thumbH = Math.max(20, scrollBarH * leftVisibleRows / totalRows);
            int thumbY = listTop + (int)((float)leftScrollOffset / maxScroll * (scrollBarH - thumbH));
            g.fill(scrollBarX, listTop, scrollBarX + 6, listTop + scrollBarH, 0x44000000);
            g.fill(scrollBarX, thumbY, scrollBarX + 6, thumbY + thumbH, 0xFF888888);
        }
    }

    // ===================== 图表区域 =====================
    private void drawChartArea(GuiGraphics g, int mx, int my) {
        int infoY = chartY;
        g.fill(chartX, infoY, chartX + chartW, infoY + 20, 0xFF1E1E1E);
        if (!selectedStockId.isEmpty() && !klines.isEmpty()) {
            g.drawString(font, selectedStockId + "  " + String.format("%.2f", getLastPrice()), chartX + 6, infoY + 2, COLOR_TEXT);
        }

        // K线类型切换按钮
        String[] kTypeNames = {"分时","1/4日","1/2日","日K","月K"};
        int btnW = 40;
        int btnX = chartX + chartW - (kTypeNames.length * (btnW + 2)) + 2;
        for (int i = 0; i < kTypeNames.length; i++) {
            int bx = btnX + i * (btnW + 2);
            int finalI = i;
            drawCustomButton(g, bx, infoY + 2, btnW, 16, kTypeNames[i], mx, my,
                    () -> { currentKlineType = finalI; new RequestKlinePacket(selectedStockId, finalI).send(); },
                    currentKlineType == i);
        }
        // 对数/线性切换
        drawCustomButton(g, chartX + chartW - 22, infoY + 20, 22, 16, logScale ? "Log" : "Lin", mx, my,
                () -> logScale = !logScale, false);

        // 布局：折线图上方，K线图下方
        int lineH = (chartH - 20) / 2;
        int klineH = (chartH - 20) - lineH;
        int lineY = infoY + 38;
        int klineY = lineY + lineH;

        // ===== 折线图区域 =====
        g.fill(chartX, lineY, chartX + chartW, lineY + lineH, COLOR_CHART_BG);
        if (!klines.isEmpty()) {
            g.enableScissor(chartX, lineY, chartX + chartW, lineY + lineH);
            drawAxes(g, lineY, lineH);
            drawTrendlineChartEnhanced(g, mx, my, lineY, lineH);
            g.disableScissor();
        }

        // ===== K线图区域 =====
        g.fill(chartX, klineY, chartX + chartW, klineY + klineH, COLOR_CHART_BG);
        if (!klines.isEmpty()) {
            g.enableScissor(chartX, klineY, chartX + chartW, klineY + klineH);
            drawKLineChart(g, mx, my, klineY, klineH);
            g.disableScissor();
        }

        // ===== 成交量副图 =====
        g.fill(chartX, volY, chartX + chartW, volY + volH, COLOR_CHART_BG);
        if (!klines.isEmpty()) {
            g.enableScissor(chartX, volY, chartX + chartW, volY + volH);
            drawVolumeChart(g, volY, volH);
            g.disableScissor();
        }

        // ===== 技术指标区域 =====
        int yOff = indicatorY;
        if (showRSI && rsi != null && rsi.length > 0) {
            g.enableScissor(chartX, yOff, chartX + chartW, yOff + INDICATOR_H);
            drawSingleIndicator(g, yOff, INDICATOR_H, "RSI(14)", rsi, 0, 100, 0xFFFF00FF);
            g.disableScissor();
            yOff += INDICATOR_H + 2;
        }
        if (showMACD && macdLine != null && macdLine.length > 0) {
            g.enableScissor(chartX, yOff, chartX + chartW, yOff + INDICATOR_H);
            drawMACDIndicator(g, yOff, INDICATOR_H);
            g.disableScissor();
            yOff += INDICATOR_H + 2;
        }
        if (showKDJ && kValue != null && kValue.length > 0) {
            g.enableScissor(chartX, yOff, chartX + chartW, yOff + INDICATOR_H);
            drawKDJIndicator(g, yOff, INDICATOR_H);
            g.disableScissor();
        }
    }

    // ===================== 坐标轴绘制 =====================
    private void drawAxes(GuiGraphics g, int startY, int h) {
        if (klines.isEmpty()) return;
        int total = klines.size();
        int start = Mth.clamp(total - visibleCandles - scrollOffsetX, 0, total - 1);
        int end = Mth.clamp(start + visibleCandles, 0, total - 1);
        int count = end - start + 1;
        if (count < 2) return;

        double minPrice = Double.MAX_VALUE, maxPrice = -Double.MAX_VALUE;
        for (int i = start; i <= end; i++) {
            OHLCData bar = klines.get(i);
            minPrice = Math.min(minPrice, bar.getLow());
            maxPrice = Math.max(maxPrice, bar.getHigh());
        }
        if (minPrice <= 0) minPrice = 0.01;
        if (maxPrice - minPrice < 0.01) maxPrice = minPrice + 0.01;

        double plotMin = logScale ? Math.log(minPrice) : minPrice;
        double plotMax = logScale ? Math.log(maxPrice) : maxPrice;
        double range = plotMax - plotMin;
        int axisLeft = chartX + 60;
        int plotWidth = chartW - 62;

        for (int i = 0; i <= 5; i++) {
            double value = plotMin + range * i / 5.0;
            double displayValue = logScale ? Math.exp(value) : value;
            int y = startY + h - (int)((value - plotMin) / range * h);
            g.fill(axisLeft, y, chartX + chartW, y + 1, 0x33FFFFFF);
            g.drawString(font, String.format("%.2f", displayValue), chartX + 2, y - 4, COLOR_HEADER);
        }

        int step = Math.max(1, count / 5);
        for (int i = start; i <= end; i += step) {
            int x = axisLeft + (i - start) * plotWidth / count;
            g.drawString(font, "#" + i, x, startY + h + 2, COLOR_HEADER);
        }
    }

    // ===================== 白色折线图 + 买卖点标记 =====================
    private void drawTrendlineChartEnhanced(GuiGraphics g, int mx, int my, int startY, int h) {
        int total = klines.size();
        int start = Mth.clamp(total - visibleCandles - scrollOffsetX, 0, total - 1);
        int end = Mth.clamp(start + visibleCandles, 0, total - 1);
        int count = end - start + 1;
        if (count < 2) return;

        double[] closes = new double[count];
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            double c = klines.get(start + i).getClose();
            closes[i] = c;
            min = Math.min(min, c); max = Math.max(max, c);
        }
        if (logScale) { min = Math.log(min); max = Math.log(max); }
        if (max - min < 0.01) max = min + 0.01;
        double range = max - min;
        int plotLeft = chartX + 62;
        int plotWidth = chartW - 62;

        // 白色折线
        for (int i = 0; i < count - 1; i++) {
            double v1 = logScale ? Math.log(closes[i]) : closes[i];
            double v2 = logScale ? Math.log(closes[i+1]) : closes[i+1];
            int x1 = plotLeft + i * plotWidth / count;
            int x2 = plotLeft + (i+1) * plotWidth / count;
            int y1 = startY + h - (int)((v1 - min) / range * h);
            int y2 = startY + h - (int)((v2 - min) / range * h);
            drawLineBresenham(g, x1, y1, x2, y2, 0xFFFFFFFF);
        }

        // 买卖点
        for (PriceDiscoveryEngine.TradePoint tp : tradePoints) {
            int idx = -1;
            for (int i = start; i <= end; i++) {
                if (Math.abs(klines.get(i).getClose() - tp.price()) < 0.01) { idx = i; break; }
            }
            if (idx != -1) {
                int x = plotLeft + (idx - start) * plotWidth / count;
                double v = logScale ? Math.log(tp.price()) : tp.price();
                int y = startY + h - (int)((v - min) / range * h);
                int color = tp.isBuy() ? COLOR_BID : COLOR_ASK;
                g.fill(x - 2, y - 2, x + 3, y + 3, color);
                g.drawString(font, tp.isBuy() ? "B" : "S", x + 4, y - 4, color);
            }
        }

        // 十字线
        if (mx >= plotLeft && mx <= chartX + chartW && my >= startY && my <= startY + h) {
            g.fill(plotLeft, my, chartX + chartW, my + 1, COLOR_CROSSHAIR);
            g.fill(mx, startY, mx + 1, startY + h, COLOR_CROSSHAIR);
            int idx = start + (mx - plotLeft) * count / plotWidth;
            if (idx >= 0 && idx < klines.size()) {
                OHLCData bar = klines.get(idx);
                String[] lines = {"开:"+String.format("%.2f",bar.getOpen()),"高:"+String.format("%.2f",bar.getHigh()),
                        "低:"+String.format("%.2f",bar.getLow()),"收:"+String.format("%.2f",bar.getClose()),"量:"+bar.getVolume()};
                int bw = 0; for (String l : lines) bw = Math.max(bw, font.width(l));
                bw += 8; int bh = 10 * lines.length + 4;
                int bx = mx + 12, by = my - bh / 2;
                if (bx + bw > width) bx = mx - bw - 12;
                if (by < 0) by = 5; if (by + bh > height) by = height - bh - 5;
                g.fill(bx - 1, by - 1, bx + bw + 1, by + bh + 1, 0xCC000000);
                for (int i = 0; i < lines.length; i++) g.drawString(font, lines[i], bx + 3, by + 2 + i * 10, COLOR_TEXT);
            }
        }
    }

    // ===================== K线图（不透明） =====================
    private void drawKLineChart(GuiGraphics g, int mx, int my, int startY, int h) {
        int total = klines.size();
        int start = Mth.clamp(total - visibleCandles - scrollOffsetX, 0, total - 1);
        int end = Mth.clamp(start + visibleCandles, 0, total - 1);
        int count = end - start + 1;
        if (count < 2) return;

        double minP = Double.MAX_VALUE, maxP = -Double.MAX_VALUE;
        for (int i = start; i <= end; i++) {
            OHLCData bar = klines.get(i);
            minP = Math.min(minP, bar.getLow()); maxP = Math.max(maxP, bar.getHigh());
        }
        if (maxP - minP < 0.01) maxP = minP + 0.01;
        double mid = (maxP + minP) / 2.0, half = (maxP - minP) / 2.0 * verticalScale;
        double aMin = mid - half + verticalOffset * half, aMax = mid + half + verticalOffset * half;
        int candleW = Math.max(2, chartW / count - 1);

        // 均线
        drawMA(g, start, end, count, candleW, startY, h, aMin, aMax, ma5, COLOR_MA5);
        drawMA(g, start, end, count, candleW, startY, h, aMin, aMax, ma10, COLOR_MA10);
        drawMA(g, start, end, count, candleW, startY, h, aMin, aMax, ma20, COLOR_MA20);
        drawMA(g, start, end, count, candleW, startY, h, aMin, aMax, ma60, COLOR_MA60);

        // 布林带
        if (showBOLL && bollUpper != null && bollUpper.length > end) {
            drawLineSeries(g, start, end, count, candleW, startY, h, aMin, aMax, bollUpper, 0x55FFFF00);
            drawLineSeries(g, start, end, count, candleW, startY, h, aMin, aMax, bollMid, 0x5500FFFF);
            drawLineSeries(g, start, end, count, candleW, startY, h, aMin, aMax, bollLower, 0x55FFFF00);
        }

        // 蜡烛
        for (int i = start; i <= end; i++) {
            OHLCData bar = klines.get(i);
            int barX = chartX + (i - start) * (chartW / count) + 1;
            int openY  = priceToY(bar.getOpen(), aMin, aMax, startY, h);
            int closeY = priceToY(bar.getClose(), aMin, aMax, startY, h);
            int highY  = priceToY(bar.getHigh(), aMin, aMax, startY, h);
            int lowY   = priceToY(bar.getLow(), aMin, aMax, startY, h);
            int color = bar.getClose() >= bar.getOpen() ? COLOR_RISE : COLOR_FALL;
            g.fill(barX + candleW/2, highY, barX + candleW/2 + 1, lowY, color);
            int top = Math.min(openY, closeY), bot = Math.max(openY, closeY);
            g.fill(barX, top, barX + candleW, bot, color);
        }
    }

    private void drawVolumeChart(GuiGraphics g, int startY, int h) {
        int total = klines.size();
        int start = Mth.clamp(total - visibleCandles - scrollOffsetX, 0, total - 1);
        int end = Mth.clamp(start + visibleCandles, 0, total - 1);
        int count = end - start + 1;
        if (count < 2) return;
        double maxVol = 0;
        for (int i = start; i <= end; i++) maxVol = Math.max(maxVol, klines.get(i).getVolume());
        if (maxVol < 1) maxVol = 1;
        int candleW = Math.max(2, chartW / count - 1);
        for (int i = start; i <= end; i++) {
            OHLCData bar = klines.get(i);
            int barX = chartX + (i - start) * (chartW / count) + 1;
            int volH = (int)((bar.getVolume() / maxVol) * (h - 10));
            int color = bar.getClose() >= bar.getOpen() ? COLOR_RISE : COLOR_FALL;
            g.fill(barX, startY + h - 5 - volH, barX + candleW, startY + h - 5, color);
        }
        drawMA(g, start, end, count, candleW, startY + 5, h - 10, 0, maxVol, volMa5, COLOR_MA5);
        drawMA(g, start, end, count, candleW, startY + 5, h - 10, 0, maxVol, volMa10, COLOR_MA10);
    }

    private void drawSingleIndicator(GuiGraphics g, int y, int h, String name, double[] vals, double min, double max, int color) {
        g.fill(chartX, y, chartX + chartW, y + h, COLOR_CHART_BG);
        g.drawString(font, name, chartX + 4, y + 2, COLOR_HEADER);
        int total = klines.size();
        int start = Mth.clamp(total - visibleCandles - scrollOffsetX, 0, total - 1);
        int end = Mth.clamp(start + visibleCandles, 0, total - 1);
        int count = end - start + 1;
        if (count < 2 || end >= vals.length) return;
        int candleW = Math.max(2, chartW / count - 1);
        int plotTop = y + 14, plotH = h - 18;
        double range = max - min;
        for (int i = start; i < end; i++) {
            int x1 = chartX + (i - start) * (chartW / count) + candleW/2;
            int x2 = chartX + (i + 1 - start) * (chartW / count) + candleW/2;
            int y1 = plotTop + plotH - (int)((vals[i] - min) / range * plotH);
            int y2 = plotTop + plotH - (int)((vals[i+1] - min) / range * plotH);
            drawLineBresenham(g, x1, y1, x2, y2, color);
        }
    }

    private void drawMACDIndicator(GuiGraphics g, int y, int h) {
        g.fill(chartX, y, chartX + chartW, y + h, COLOR_CHART_BG);
        g.drawString(font, "MACD(12,26,9)", chartX + 4, y + 2, COLOR_HEADER);
        int total = klines.size();
        int start = Mth.clamp(total - visibleCandles - scrollOffsetX, 0, total - 1);
        int end = Mth.clamp(start + visibleCandles, 0, total - 1);
        int count = end - start + 1;
        if (count < 2 || end >= macdLine.length) return;
        int candleW = Math.max(2, chartW / count - 1);
        int plotTop = y + 14, plotH = h - 18;
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        for (int i = start; i <= end; i++) {
            min = Math.min(min, Math.min(macdLine[i], Math.min(signalLine[i], macdHistogram[i])));
            max = Math.max(max, Math.max(macdLine[i], Math.max(signalLine[i], macdHistogram[i])));
        }
        double range = Math.max(max - min, 0.001);
        for (int i = start; i < end; i++) {
            int x1 = chartX + (i - start) * (chartW / count) + candleW/2, x2 = chartX + (i + 1 - start) * (chartW / count) + candleW/2;
            int y1 = plotTop + plotH - (int)((macdLine[i] - min) / range * plotH), y2 = plotTop + plotH - (int)((macdLine[i+1] - min) / range * plotH);
            drawLineBresenham(g, x1, y1, x2, y2, 0xFFFFFFFF);
            y1 = plotTop + plotH - (int)((signalLine[i] - min) / range * plotH); y2 = plotTop + plotH - (int)((signalLine[i+1] - min) / range * plotH);
            drawLineBresenham(g, x1, y1, x2, y2, 0xFFFFFF00);
        }
        int barW = Math.max(2, chartW / count - 1);
        int zeroY = plotTop + plotH - (int)((0 - min) / range * plotH);
        for (int i = start; i <= end; i++) {
            int bx = chartX + (i - start) * (chartW / count);
            int by = plotTop + plotH - (int)((macdHistogram[i] - min) / range * plotH);
            int top = Math.min(zeroY, by), bot = Math.max(zeroY, by);
            g.fill(bx, top, bx + barW, bot, macdHistogram[i] >= 0 ? COLOR_RISE : COLOR_FALL);
        }
    }

    private void drawKDJIndicator(GuiGraphics g, int y, int h) {
        g.fill(chartX, y, chartX + chartW, y + h, COLOR_CHART_BG);
        g.drawString(font, "KDJ(9,3,3)", chartX + 4, y + 2, COLOR_HEADER);
        int total = klines.size();
        int start = Mth.clamp(total - visibleCandles - scrollOffsetX, 0, total - 1);
        int end = Mth.clamp(start + visibleCandles, 0, total - 1);
        int count = end - start + 1;
        if (count < 2 || end >= kValue.length) return;
        int candleW = Math.max(2, chartW / count - 1);
        int plotTop = y + 14, plotH = h - 18;
        double min = 0, max = 100;
        drawLineSeries(g, start, end, count, candleW, plotTop, plotH, min, max, kValue, 0xFFFFFFFF);
        drawLineSeries(g, start, end, count, candleW, plotTop, plotH, min, max, dValue, 0xFFFFFF00);
        drawLineSeries(g, start, end, count, candleW, plotTop, plotH, min, max, jValue, 0xFFFF00FF);
    }

    private void drawLineSeries(GuiGraphics g, int start, int end, int count, int candleW, int plotTop, int plotH,
                                double min, double max, double[] data, int color) {
        if (data == null || data.length <= end) return;
        for (int i = start; i < end; i++) {
            int x1 = chartX + (i - start) * (chartW / count) + candleW/2;
            int x2 = chartX + (i + 1 - start) * (chartW / count) + candleW/2;
            int y1 = plotTop + plotH - (int)((data[i] - min) / (max - min) * plotH);
            int y2 = plotTop + plotH - (int)((data[i+1] - min) / (max - min) * plotH);
            drawLineBresenham(g, x1, y1, x2, y2, color);
        }
    }

    private void drawMA(GuiGraphics g, int start, int end, int count, int candleW, int startY, int h,
                        double min, double max, double[] ma, int color) {
        if (ma == null || ma.length <= end) return;
        for (int i = start; i < end; i++) {
            if (ma[i] == 0 || ma[i+1] == 0) continue;
            int x1 = chartX + (i - start) * (chartW / count) + candleW/2;
            int x2 = chartX + (i + 1 - start) * (chartW / count) + candleW/2;
            int y1 = priceToY(ma[i], min, max, startY, h);
            int y2 = priceToY(ma[i+1], min, max, startY, h);
            drawLineBresenham(g, x1, y1, x2, y2, color);
        }
    }

    private void drawLineBresenham(GuiGraphics g, int x1, int y1, int x2, int y2, int color) {
        int dx = Math.abs(x2 - x1), dy = -Math.abs(y2 - y1);
        int sx = x1 < x2 ? 1 : -1, sy = y1 < y2 ? 1 : -1;
        int err = dx + dy;
        while (true) {
            g.fill(x1, y1, x1 + 1, y1 + 1, color);
            if (x1 == x2 && y1 == y2) break;
            int e2 = 2 * err;
            if (e2 >= dy) { err += dy; x1 += sx; }
            if (e2 <= dx) { err += dx; y1 += sy; }
        }
    }

    private int priceToY(double price, double min, double max, int top, int height) {
        return (int) (top + height - (price - min) / (max - min) * height);
    }

    // ===================== 右侧面板 =====================
    private void drawRightPanel(GuiGraphics g, int mx, int my) {
        g.fill(rightX, rightY, rightX + RIGHT_W, rightY + rightH, COLOR_PANEL);
        int contentTop = rightY + 5;
        int totalContentH = 540;
        int maxScroll = Math.max(0, totalContentH - (rightH - 10));
        rightScrollOffset = Mth.clamp(rightScrollOffset, 0, maxScroll);
        g.enableScissor(rightX, rightY, rightX + RIGHT_W, rightY + rightH);
        int yOff = contentTop - rightScrollOffset;

        if (!selectedStockId.isEmpty()) {
            g.drawString(font, selectedStockId, rightX + 6, yOff, COLOR_TEXT);
            yOff += 12;
            if (!klines.isEmpty())
                g.drawString(font, "最新: " + String.format("%.2f", getLastPrice()), rightX + 6, yOff, 0xFFFFA500);
            yOff += 16;
        }

        g.drawString(font, "买盘", rightX + 6, yOff, COLOR_BID);
        g.drawString(font, "卖盘", rightX + RIGHT_W - 60, yOff, COLOR_ASK);
        yOff += 12;
        for (int i = 0; i < 5; i++) {
            if (i < currentOrderBook.bids.size()) {
                OrderBook.OrderBookLevel bid = currentOrderBook.bids.get(i);
                g.drawString(font, String.format("%.2f  %d", bid.price, bid.quantity), rightX + 6, yOff, COLOR_BID);
            }
            if (i < currentOrderBook.asks.size()) {
                OrderBook.OrderBookLevel ask = currentOrderBook.asks.get(i);
                g.drawString(font, String.format("%.2f  %d", ask.price, ask.quantity), rightX + RIGHT_W - 100, yOff, COLOR_ASK);
            }
            yOff += 12;
        }
        yOff += 8;

        g.drawString(font, "数量:", rightX + 6, yOff, COLOR_TEXT);
        drawInputBox(g, rightX + 50, yOff - 2, 60, 14, qtyText, qtyFocused, mx, my);
        drawCustomButton(g, rightX + 120, yOff - 2, 40, 14, useLots ? "手" : "股", mx, my, () -> useLots = !useLots, false);
        yOff += 18;
        g.drawString(font, "限价:", rightX + 6, yOff, COLOR_TEXT);
        drawInputBox(g, rightX + 50, yOff - 2, 60, 14, priceText, priceFocused, mx, my);
        yOff += 20;
        drawCustomButton(g, rightX + 10, yOff, 80, 24, "买入", mx, my, () -> executeTrade(true), false);
        drawCustomButton(g, rightX + 110, yOff, 80, 24, "卖出", mx, my, () -> executeTrade(false), false);
        yOff += 30;

        g.drawString(font, "杠杆:", rightX + 6, yOff, COLOR_TEXT);
        drawInputBox(g, rightX + 50, yOff - 2, 40, 14, String.valueOf(leverage), false, mx, my);
        drawCustomButton(g, rightX + 100, yOff - 2, 50, 14, isShort ? "做空" : "做多", mx, my, () -> isShort = !isShort, false);
        yOff += 20;
        drawCustomButton(g, rightX + 10, yOff, 80, 24, "融资买入", mx, my, () -> executeMarginTrade(true, false), false);
        drawCustomButton(g, rightX + 110, yOff, 80, 24, "融券卖出", mx, my, () -> executeMarginTrade(false, true), false);
        yOff += 28;
        g.drawString(font, "保证金: " + String.format("%.2f", marginBalance), rightX + 6, yOff, COLOR_TEXT);
        yOff += 14;
        g.drawString(font, "风险率: " + String.format("%.1f%%", marginRisk), rightX + 6, yOff, marginRisk > 80 ? COLOR_ASK : COLOR_BID);
        yOff += 4;
        // t7：融资融券入金入口（从玩家现金扣减转入保证金；现金不足由服务端拒绝）
        drawCustomButton(g, rightX + 10, yOff, 70, 18, "入金1000", mx, my, () -> new MarginDepositPacket(1000).send(), false);
        yOff += 22;

        drawCustomButton(g, rightX + 10, yOff, 60, 18, currentStyle == MarketStyle.US ? "美股" : "A股", mx, my, () -> {
            currentStyle = (currentStyle == MarketStyle.US) ? MarketStyle.CN : MarketStyle.US; applyStyleColors(); }, false);
        drawCustomButton(g, rightX + 80, yOff, 50, 18, showKline ? "K线" : "趋势", mx, my, () -> showKline = !showKline, false);
        yOff += 22;
        drawCustomButton(g, rightX + 10, yOff, 40, 16, "RSI", mx, my, () -> showRSI = !showRSI, showRSI);
        drawCustomButton(g, rightX + 55, yOff, 45, 16, "MACD", mx, my, () -> showMACD = !showMACD, showMACD);
        drawCustomButton(g, rightX + 105, yOff, 40, 16, "KDJ", mx, my, () -> showKDJ = !showKDJ, showKDJ);
        drawCustomButton(g, rightX + 150, yOff, 40, 16, "BOLL", mx, my, () -> showBOLL = !showBOLL, showBOLL);

        g.disableScissor();
        if (maxScroll > 0) {
            int scrollBarX = rightX + RIGHT_W - 8;
            int thumbH = Math.max(20, rightH * rightH / totalContentH);
            int thumbY = rightY + (int)((float)rightScrollOffset / maxScroll * (rightH - thumbH));
            g.fill(scrollBarX, rightY, scrollBarX + 6, rightY + rightH, 0x44000000);
            g.fill(scrollBarX, thumbY, scrollBarX + 6, thumbY + thumbH, 0xFF888888);
        }
    }

    private void drawCustomButton(GuiGraphics g, int x, int y, int w, int h, String text, int mx, int my, Runnable action, boolean active) {
        boolean hover = mx >= x && mx <= x + w && my >= y && my <= y + h;
        int bg = active ? COLOR_ACCENT : (hover ? COLOR_BTN_HOVER : COLOR_BTN);
        g.fill(x, y, x + w, y + h, bg);
        int textX = x + (w - font.width(text)) / 2, textY = y + (h - 8) / 2;
        g.drawString(font, text, textX, textY, COLOR_TEXT);
    }

    private void drawInputBox(GuiGraphics g, int x, int y, int w, int h, String text, boolean focused, int mx, int my) {
        g.fill(x, y, x + w, y + h, 0xFF0F1012);
        int border = focused ? COLOR_ACCENT : COLOR_LINE;
        g.fill(x, y, x + w, y + 1, border);
        g.fill(x, y + h - 1, x + w, y + h, border);
        g.fill(x, y, x + 1, y + h, border);
        g.fill(x + w - 1, y, x + w, y + h, border);
        g.drawString(font, text, x + 4, y + 2, COLOR_TEXT);
        if (focused && (cursorBlink / 10 % 2 == 0)) {
            int cx = x + 4 + font.width(text);
            g.fill(cx, y + 2, cx + 1, y + h - 3, COLOR_TEXT);
        }
    }

    // ===================== 商品页面 =====================
    private void drawCommoditiesPage(GuiGraphics g, int mx, int my) {
        int listW = 200, panelH = height - TOP_H - BOTTOM_H;
        g.fill(0, TOP_H, listW, height - BOTTOM_H, COLOR_PANEL);
        g.drawString(font, "商品", 6, TOP_H + 5, COLOR_ACCENT);
        int listTop = TOP_H + 22, listBottom = height - BOTTOM_H - 8, listHeight = listBottom - listTop;
        int totalRows = commodityPrices.size();
        int maxScroll = Math.max(0, totalRows - commodityVisibleRows);
        commodityScrollOffset = Mth.clamp(commodityScrollOffset, 0, maxScroll);
        g.enableScissor(0, listTop, listW, listBottom);
        int lineY = listTop - commodityScrollOffset * commodityRowHeight;
        for (Map.Entry<Item, Double> entry : commodityPrices.entrySet()) {
            Item item = entry.getKey();
            double price = entry.getValue();
            if (lineY + commodityRowHeight < listTop || lineY > listBottom) { lineY += commodityRowHeight; continue; }
            boolean sel = item.equals(selectedCommodity);
            if (sel) g.fill(2, lineY - 1, listW - 2, lineY + 9, COLOR_ACCENT);
            g.renderItem(new ItemStack(item), 5, lineY - 2);
            g.drawString(font, item.getDescription().getString() + " " + String.format("%.2f", price), 22, lineY, sel ? COLOR_TEXT : COLOR_HEADER);
            lineY += commodityRowHeight;
        }
        g.disableScissor();
        if (maxScroll > 0) {
            int scrollBarX = listW - 8, thumbH = Math.max(20, listHeight * commodityVisibleRows / totalRows);
            int thumbY = listTop + (int)((float)commodityScrollOffset / maxScroll * (listHeight - thumbH));
            g.fill(scrollBarX, listTop, scrollBarX + 6, listTop + listHeight, 0x44000000);
            g.fill(scrollBarX, thumbY, scrollBarX + 6, thumbY + thumbH, 0xFF888888);
        }
        int tradeX = width - RIGHT_W;
        g.fill(tradeX, TOP_H, tradeX + RIGHT_W, height - BOTTOM_H, COLOR_PANEL);
        g.drawString(font, "交易", tradeX + 6, TOP_H + 6, COLOR_TEXT);
        if (selectedCommodity != null) {
            double price = commodityPrices.getOrDefault(selectedCommodity, 0.0);
            g.drawString(font, "单价: " + String.format("%.2f", price), tradeX + 10, TOP_H + 30, COLOR_TEXT);
            g.drawString(font, "数量:", tradeX + 10, TOP_H + 55, COLOR_TEXT);
            drawInputBox(g, tradeX + 55, TOP_H + 50, 80, 20, commodityQtyText, commodityQtyFocused, mx, my);
            drawCustomButton(g, tradeX + 10, TOP_H + 80, 80, 24, "买入", mx, my, () -> executeCommodityTrade(true), false);
            drawCustomButton(g, tradeX + 110, TOP_H + 80, 80, 24, "卖出", mx, my, () -> executeCommodityTrade(false), false);
        }
    }

    private void executeCommodityTrade(boolean buy) {
        if (selectedCommodity == null) return;
        int qty = 0;
        try { qty = Integer.parseInt(commodityQtyText); } catch (NumberFormatException ignored) {}
        if (qty <= 0) return;
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(selectedCommodity);
        if (itemId == null) return;
        if (buy) new CommodityBuyPacket(itemId.toString(), qty).send();
        else new CommoditySellPacket(itemId.toString(), qty).send();
    }

    // ===================== 持仓页面 =====================
    private void drawPortfolioPage(GuiGraphics g, int mx, int my) {
        g.fill(0, TOP_H, width, height - BOTTOM_H, COLOR_PANEL);
        g.drawString(font, "我的持仓", 10, TOP_H + 10, COLOR_ACCENT);
        double totalValue = holdings.values().stream().mapToDouble(h -> getLastPrice(h.getAssetId()) * h.getQuantity()).sum();
        g.drawString(font, "总市值: " + String.format("%.2f", totalValue) + "  现金: " + String.format("%.2f", cash), 10, TOP_H + 30, COLOR_TEXT);
        int lineY = TOP_H + 55;
        for (Holding h : holdings.values()) {
            String info = h.getAssetId() + "  x" + h.getQuantity() + "  成本:" + String.format("%.2f", h.getAvgCost());
            g.drawString(font, info, 10, lineY, COLOR_TEXT);
            lineY += 14;
        }
        lineY += 10;
        g.drawString(font, "下界合金兑换 (1锭 = " + CurrencySystem.EXCHANGE_RATE + "元)", 10, lineY, COLOR_HEADER);
        lineY += 14;
        g.drawString(font, "数量:", 10, lineY, COLOR_TEXT);
        drawInputBox(g, 50, lineY - 2, 60, 14, depositQtyText, depositFocused, mx, my);
        drawCustomButton(g, 120, lineY - 2, 50, 14, "充值", mx, my, () -> {
            int q = parseInt(depositQtyText);
            if (q > 0) new DepositPacket(q).send();
        }, false);
        drawCustomButton(g, 180, lineY - 2, 50, 14, "提现", mx, my, () -> {
            int q = parseInt(depositQtyText);
            if (q > 0) new WithdrawPacket(q).send();
        }, false);
    }

    // ===================== 公司页面 =====================
    private void drawCompanyPage(GuiGraphics g, int mx, int my) {
        g.fill(0, TOP_H, width, height - BOTTOM_H, COLOR_PANEL);
        if (playerCompany == null) {
            g.drawString(font, "创建公司", 10, TOP_H + 10, COLOR_ACCENT);
            g.drawString(font, "公司名称:", 10, TOP_H + 35, COLOR_TEXT);
            drawInputBox(g, 80, TOP_H + 30, 150, 20, companyNameText, companyNameFocused, mx, my);
            drawCustomButton(g, 240, TOP_H + 30, 60, 20, "创建", mx, my, () -> {
                if (!companyNameText.isEmpty()) new CompanyActionPacket("create", companyNameText).send();
            }, false);
            return;
        }

        g.drawString(font, playerCompany.getName(), 10, TOP_H + 10, COLOR_ACCENT);
        g.drawString(font, "等级: " + playerCompany.getLevel().getDisplayName(), 10, TOP_H + 30, COLOR_TEXT);
        g.drawString(font, "状态: " + playerCompany.getStatus().name(), 10, TOP_H + 45, COLOR_TEXT);
        g.drawString(font, "净资产: " + String.format("%.2f", playerCompany.getNetAssetValue()), 10, TOP_H + 60, COLOR_TEXT);
        g.drawString(font, "利润: " + String.format("%.2f", playerCompany.getRecentProfit()), 10, TOP_H + 75, COLOR_TEXT);
        g.drawString(font, "总股本: " + playerCompany.getTotalShares(), 10, TOP_H + 90, COLOR_TEXT);
        if (playerCompany.getStockId() != null) g.drawString(font, "股票代码: " + playerCompany.getStockId(), 10, TOP_H + 105, COLOR_TEXT);

        int btnY = TOP_H + 130;
        drawCustomButton(g, 10, btnY, 80, 20, "升级", mx, my, () -> new CompanyActionPacket("levelup", "").send(), false);
        drawCustomButton(g, 100, btnY, 80, 20, "降级", mx, my, () -> new CompanyActionPacket("leveldown", "").send(), false);
        if (playerCompany.getStatus() == CompanyStatus.PRIVATE && playerCompany.getLevel().getLevel() >= 2) {
            drawCustomButton(g, 10, btnY + 30, 80, 20, "上市", mx, my, () -> new CompanyActionPacket("list", "").send(), false);
        }
        drawCustomButton(g, 10, btnY + 60, 100, 20, selectingArea ? "完成划定" : "划定区域", mx, my, () -> {
            selectingArea = !selectingArea;
            if (!selectingArea && corner1 != null && corner2 != null) new CompanyAreaPacket(corner1, corner2).send();
            if (!selectingArea) { corner1 = null; corner2 = null; }
        }, selectingArea);
        if (selectingArea) drawMiniMap(g, mx, my);
    }

    private void drawMiniMap(GuiGraphics g, int mx, int my) {
        int mapX = 10, mapY = TOP_H + 200, mapW = 300, mapH = 200;
        g.fill(mapX, mapY, mapX + mapW, mapY + mapH, 0xAA000000);
        g.drawString(font, "在地图上拖拽划定区域", mapX + 5, mapY + 5, COLOR_HEADER);
        var player = Minecraft.getInstance().player;
        if (player == null) return;
        int centerX = player.blockPosition().getX(), centerZ = player.blockPosition().getZ();
        double scale = 1.0;
        int originX = mapX + mapW / 2, originY = mapY + mapH / 2;
        if (draggingCorner) {
            int worldX1 = centerX + (int)((dragCornerStartX - originX) * scale);
            int worldZ1 = centerZ + (int)((dragCornerStartY - originY) * scale);
            int worldX2 = centerX + (int)((mx - originX) * scale);
            int worldZ2 = centerZ + (int)((my - originY) * scale);
            corner1 = new BlockPos(Math.min(worldX1, worldX2), player.blockPosition().getY(), Math.min(worldZ1, worldZ2));
            corner2 = new BlockPos(Math.max(worldX1, worldX2), player.blockPosition().getY(), Math.max(worldZ1, worldZ2));
            int sx = Math.min(dragCornerStartX, mx), sy = Math.min(dragCornerStartY, my);
            g.fill(sx, sy, sx + Math.abs(mx - dragCornerStartX), sy + Math.abs(my - dragCornerStartY), 0x44FFFFFF);
        }
        if (corner1 != null && corner2 != null) {
            int c1x = originX + (int)((corner1.getX() - centerX) / scale);
            int c1y = originY + (int)((corner1.getZ() - centerZ) / scale);
            int c2x = originX + (int)((corner2.getX() - centerX) / scale);
            int c2y = originY + (int)((corner2.getZ() - centerZ) / scale);
            g.fill(Math.min(c1x, c2x), Math.min(c1y, c2y), Math.abs(c2x - c1x), Math.abs(c2y - c1y), 0x44FFFF00);
        }
        g.fill(originX - 2, originY - 2, originX + 3, originY + 3, 0xFFFF0000);
    }

    // ===================== 借贷页面 =====================
    private void drawLendingPage(GuiGraphics g, int mx, int my) {
        g.fill(0, TOP_H, width, height - BOTTOM_H, COLOR_PANEL);
        g.drawString(font, "银行贷款", 10, TOP_H + 10, COLOR_ACCENT);
        g.drawString(font, "下界皇家银行  利率:5%  额度:100000", 10, TOP_H + 35, COLOR_TEXT);
        drawCustomButton(g, 200, TOP_H + 30, 60, 18, "申请贷款", mx, my, () -> new LoanRequestPacket("BANK_A", 5000).send(), false);
        g.drawString(font, "末地商业银行  利率:6%  额度:80000", 10, TOP_H + 55, COLOR_TEXT);
        drawCustomButton(g, 200, TOP_H + 50, 60, 18, "申请贷款", mx, my, () -> new LoanRequestPacket("BANK_B", 5000).send(), false);
        g.drawString(font, "主世界发展银行 利率:4.5% 额度:120000", 10, TOP_H + 75, COLOR_TEXT);
        drawCustomButton(g, 200, TOP_H + 70, 60, 18, "申请贷款", mx, my, () -> new LoanRequestPacket("BANK_C", 5000).send(), false);

        g.drawString(font, "我的贷款", 10, TOP_H + 110, COLOR_ACCENT);
        int y = TOP_H + 130;
        for (Loan loan : activeLoans) {
            String info = loan.getBankId() + " 本金:" + String.format("%.2f", loan.getPrincipal()) + " 剩余:" + String.format("%.2f", loan.getRemaining());
            g.drawString(font, info, 10, y, COLOR_TEXT);
            y += 14;
            drawCustomButton(g, 200, y - 14, 60, 16, "还款", mx, my, () -> new RepayLoanPacket(loan.getLoanId(), loan.getRemaining()).send(), false);
        }
    }

    // ===================== 量化交易页面 =====================
    private void drawQuantPage(GuiGraphics g, int mx, int my) {
        g.fill(0, TOP_H, width, height - BOTTOM_H, COLOR_PANEL);
        int leftW = 220;
        int rightW = width - leftW - 10;

        g.fill(5, TOP_H + 5, leftW, height - BOTTOM_H - 5, 0xCC1E1E1E);
        g.drawString(font, "策略列表", 15, TOP_H + 10, COLOR_ACCENT);
        g.drawString(font, "双均线策略", 15, TOP_H + 30, COLOR_TEXT);
        g.drawString(font, "网格策略", 15, TOP_H + 46, COLOR_TEXT);
        g.drawString(font, "马丁格尔策略", 15, TOP_H + 62, COLOR_TEXT);

        g.fill(leftW + 5, TOP_H + 5, rightW, height - BOTTOM_H - 5, 0xCC0A0A0A);
        g.drawString(font, "代码编辑器 - " + quantFileName, leftW + 15, TOP_H + 10, COLOR_ACCENT);

        int editorX = leftW + 10;
        int editorY = TOP_H + 30;
        int editorW = rightW - leftW - 20;
        int editorH = height - BOTTOM_H - TOP_H - 80;
        g.fill(editorX, editorY, editorX + editorW, editorY + editorH, 0xFF000000);
        g.enableScissor(editorX, editorY, editorX + editorW, editorY + editorH);
        int textY = editorY + 4;
        for (String line : quantCodeText.split("\n")) {
            if (textY > editorY + editorH - 10) break;
            g.drawString(font, line, editorX + 4, textY, COLOR_TEXT);
            textY += 10;
        }
        if (quantCodeFocused && (cursorBlink / 10 % 2 == 0)) {
            int cx = editorX + 4 + font.width(quantCodeText.contains("\n") ?
                    quantCodeText.substring(quantCodeText.lastIndexOf('\n') + 1) : quantCodeText);
            g.fill(cx, textY - 10, cx + 1, textY, COLOR_TEXT);
        }
        g.disableScissor();

        int btnY = editorY + editorH + 5;
        drawCustomButton(g, leftW + 10, btnY, 60, 18, "保存", mx, my, () -> saveQuantFile(), false);
        drawCustomButton(g, leftW + 80, btnY, 60, 18, "加载", mx, my, () -> loadQuantFile(), false);
        drawCustomButton(g, leftW + 150, btnY, 60, 18, "启用", mx, my, () -> {
            if (!selectedStockId.isEmpty()) new EnableQuantStrategyPacket("custom", 0, 0).send();
        }, false);
        drawCustomButton(g, leftW + 220, btnY, 60, 18, "停止", mx, my, () -> new DisableQuantStrategyPacket().send(), false);
        g.drawString(font, "策略目标: " + (selectedStockId.isEmpty() ? "请先选择股票" : selectedStockId), leftW + 10, btnY + 25, COLOR_HEADER);
    }

    /**
     * 保存量化脚本：从本地文件改为发送 {@link SaveQuantScriptPacket}，由服务端写入 H2
     * {@code quant_script} 表（不再依赖工作目录文件系统，见 H2_MIGRATION.md §7.2.3）。
     */
    private void saveQuantFile() {
        new SaveQuantScriptPacket(quantFileName, quantCodeText).send();
    }

    /**
     * 加载量化脚本：发送 {@link RequestQuantScriptPacket}，由服务端从 H2 读取后经
     * {@link QuantScriptDataPacket} 回传，客户端在 {@link #updateQuantScript} 中填充编辑器。
     */
    private void loadQuantFile() {
        new RequestQuantScriptPacket(quantFileName).send();
    }

    /** 由 {@code ModMessages} 在收到服务端 {@link QuantScriptDataPacket} 时调用，回填代码编辑器。 */
    public void updateQuantScript(String fileName, String code) {
        if (fileName != null && !fileName.isEmpty()) quantFileName = fileName;
        if (code != null) quantCodeText = code;
    }

    // ===================== 大盘云图页面 =====================
    private void drawMarketMapPage(GuiGraphics g, int mx, int my) {
        g.fill(0, TOP_H, width, height - BOTTOM_H, COLOR_PANEL);
        g.drawString(font, "大盘云图", 10, TOP_H + 10, COLOR_ACCENT);

        int gridX = 10, gridY = TOP_H + 30, gridW = width - 20, gridH = height - TOP_H - BOTTOM_H - 40;
        int cols = 10;
        int rows = 8;
        int cellW = gridW / cols;
        int cellH = gridH / rows;

        List<StockEntry> displayList = marketStocks.isEmpty() ? generateMockStocks() : marketStocks;
        int index = 0;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                if (index >= displayList.size()) break;
                StockEntry stock = displayList.get(index++);
                int cellX = gridX + c * cellW + 2;
                int cellY = gridY + r * cellH + 2;
                int color = stock.change >= 0 ? COLOR_RISE : COLOR_FALL;
                double intensity = Math.min(1.0, Math.abs(stock.change) / 10.0);
                int alpha = (int)(0xAA * intensity) + 0x55;
                int bgColor = (alpha << 24) | (color & 0x00FFFFFF);
                g.fill(cellX, cellY, cellX + cellW - 4, cellY + cellH - 4, bgColor);
                String code = currentStyle == MarketStyle.US ? stock.usCode : stock.cnCode;
                g.drawString(font, code, cellX + 4, cellY + 4, COLOR_TEXT);
                String chStr = String.format("%+.1f%%", stock.change);
                g.drawString(font, chStr, cellX + 4, cellY + cellH - 12, COLOR_TEXT);
            }
        }
    }

    private List<StockEntry> generateMockStocks() {
        List<StockEntry> list = new ArrayList<>();
        for (int i = 0; i < 80; i++) {
            double price = 10 + Math.random() * 100;
            double change = (Math.random() - 0.5) * 10;
            list.add(new StockEntry("MOCK" + i, "MC" + i, "60000" + i, "模拟" + i, price, change, 0,
                    price / (1 + change/100), price * 1.05, price * 0.95));
        }
        return list;
    }

    // ===================== 交互事件 =====================
    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            // 顶部导航标签
            int tabW = width / 7;
            for (int i = 0; i < 7; i++) {
                if (mx >= i * tabW && mx <= (i + 1) * tabW && my >= 4 && my <= 26) {
                    currentPage = Page.values()[i];
                    return true;
                }
            }
            if (currentPage == Page.STOCKS) {
                // 优先检查 K 线类型按钮
                if (!selectedStockId.isEmpty()) {
                    int infoY = chartY;
                    String[] kTypeNames = {"分时","1/4日","1/2日","日K","月K"};
                    int btnW = 40;
                    int btnX = chartX + chartW - (kTypeNames.length * (btnW + 2)) + 2;
                    for (int i = 0; i < kTypeNames.length; i++) {
                        int bx = btnX + i * (btnW + 2);
                        if (mx >= bx && mx <= bx + btnW && my >= infoY + 2 && my <= infoY + 18) {
                            currentKlineType = i;
                            new RequestKlinePacket(selectedStockId, i).send();
                            return true;
                        }
                    }
                    // 对数/线性按钮
                    if (mx >= chartX + chartW - 22 && mx <= chartX + chartW && my >= infoY + 20 && my <= infoY + 36) {
                        logScale = !logScale;
                        return true;
                    }
                }
                // 右侧面板按钮
                if (mx >= rightX && mx <= rightX + RIGHT_W && my >= rightY && my <= rightY + rightH) {
                    handleRightPanelClick(mx, my);
                    return true;
                }
                // 左侧列表点击
                if (handleLeftPanelClick(mx, my)) return true;
                // 图表拖动（K线区域）
                if (!klines.isEmpty() && mx >= chartX && mx <= chartX + chartW - 8 && my >= chartY + 38 && my <= height - BOTTOM_H - 10) {
                    dragging = true;
                    dragStartX = (int) mx; dragStartY = (int) my;
                    dragStartOffsetX = scrollOffsetX;
                    dragStartOffsetY = verticalOffset;
                    return true;
                }
                // 图表滚动条点击
                if (chartNeedScroll && mx >= chartX + chartW - 8 && mx <= chartX + chartW) {
                    int maxScroll = Math.max(0, getTotalChartHeight() - (height - BOTTOM_H - chartY - 10));
                    if (maxScroll > 0) {
                        int scrollBarH = height - BOTTOM_H - chartY - 10;
                        int thumbH = Math.max(20, scrollBarH * (height - BOTTOM_H - chartY - 10) / getTotalChartHeight());
                        double ratio = (my - chartY) / (double) scrollBarH;
                        chartScrollOffset = (int)(ratio * maxScroll);
                        chartScrollOffset = Mth.clamp(chartScrollOffset, 0, maxScroll);
                    }
                    return true;
                }
            } else if (currentPage == Page.COMMODITIES) {
                int listW = 200;
                if (mx >= 0 && mx <= listW && my >= TOP_H + 22) {
                    int idx = (int)((my - TOP_H - 22 + commodityScrollOffset * commodityRowHeight) / commodityRowHeight);
                    int i = 0;
                    for (Item item : commodityPrices.keySet()) { if (i == idx) { selectedCommodity = item; return true; } i++; }
                }
                int tradeX = width - RIGHT_W;
                if (checkClick(mx, my, tradeX + 10, TOP_H + 80, 80, 24)) { executeCommodityTrade(true); return true; }
                if (checkClick(mx, my, tradeX + 110, TOP_H + 80, 80, 24)) { executeCommodityTrade(false); return true; }
                if (mx >= tradeX + 55 && mx <= tradeX + 135 && my >= TOP_H + 50 && my <= TOP_H + 70) {
                    commodityQtyFocused = true; return true;
                } else { commodityQtyFocused = false; }
            } else if (currentPage == Page.PORTFOLIO) {
                if (checkClick(mx, my, 50, height - BOTTOM_H - 70, 60, 14)) depositFocused = true;
                else depositFocused = false;
                if (checkClick(mx, my, 120, height - BOTTOM_H - 70, 50, 14)) { int q = parseInt(depositQtyText); if (q > 0) new DepositPacket(q).send(); return true; }
                if (checkClick(mx, my, 180, height - BOTTOM_H - 70, 50, 14)) { int q = parseInt(depositQtyText); if (q > 0) new WithdrawPacket(q).send(); return true; }
            } else if (currentPage == Page.COMPANY) {
                if (selectingArea && mx >= 10 && mx <= 310 && my >= TOP_H + 200 && my <= TOP_H + 400) {
                    draggingCorner = true;
                    dragCornerStartX = (int) mx;
                    dragCornerStartY = (int) my;
                    return true;
                }
                if (handleCompanyClick(mx, my)) return true;
            } else if (currentPage == Page.LENDING) {
                handleLendingClick(mx, my);
            } else if (currentPage == Page.QUANT) {
                handleQuantClick(mx, my);
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    private void handleRightPanelClick(double mx, double my) {
        int btnY = rightY + 50;
        if (checkClick(mx, my, rightX + 10, btnY, 60, 18)) return;
        if (checkClick(mx, my, rightX + 80, btnY, 60, 18)) return;
        if (checkClick(mx, my, rightX + 150, btnY, 60, 18)) { currentPage = Page.PORTFOLIO; return; }
        int tradeY = btnY + 30;
        if (mx >= rightX + 50 && mx <= rightX + 130 && my >= tradeY + 6 && my <= tradeY + 26) { qtyFocused = true; priceFocused = false; return; }
        if (mx >= rightX + 50 && mx <= rightX + 130 && my >= tradeY + 36 && my <= tradeY + 56) { priceFocused = true; qtyFocused = false; return; }
        if (checkClick(mx, my, rightX + 10, tradeY + 70, 60, 18)) { currentStyle = currentStyle == MarketStyle.US ? MarketStyle.CN : MarketStyle.US; applyStyleColors(); return; }
        if (checkClick(mx, my, rightX + 10, tradeY + 100, 80, 24)) { executeTrade(true); return; }
        if (checkClick(mx, my, rightX + 110, tradeY + 100, 80, 24)) { executeTrade(false); return; }
        int indY = tradeY + 140;
        if (checkClick(mx, my, rightX + 10, indY, 40, 16)) { showRSI = !showRSI; return; }
        if (checkClick(mx, my, rightX + 55, indY, 45, 16)) { showMACD = !showMACD; return; }
        if (checkClick(mx, my, rightX + 105, indY, 40, 16)) { showKDJ = !showKDJ; return; }
        if (checkClick(mx, my, rightX + 150, indY, 40, 16)) { showBOLL = !showBOLL; return; }
        if (checkClick(mx, my, rightX + 10, indY + 20, 80, 16)) { showKline = !showKline; return; }
        qtyFocused = false; priceFocused = false;
    }

    private boolean handleLeftPanelClick(double mx, double my) {
        int listTop = TOP_H + 20 + 16;
        int lineY = listTop - leftScrollOffset * leftRowHeight;
        for (int i = 0; i < marketStocks.size(); i++) {
            if (mx >= 0 && mx <= LEFT_W - 8 && my >= lineY && my <= lineY + leftRowHeight) {
                selectedStockId = marketStocks.get(i).stockId;
                new RequestKlinePacket(selectedStockId, currentKlineType).send();
                refreshTimer = 0; currentPage = Page.STOCKS; return true;
            }
            lineY += leftRowHeight;
        }
        return false;
    }

    private boolean handleCompanyClick(double mx, double my) {
        if (playerCompany == null) {
            if (checkClick(mx, my, 240, TOP_H + 30, 60, 20)) { new CompanyActionPacket("create", companyNameText).send(); return true; }
            if (mx >= 80 && mx <= 230 && my >= TOP_H + 30 && my <= TOP_H + 50) { companyNameFocused = true; return true; }
            else companyNameFocused = false;
        } else {
            int btnY = TOP_H + 130;
            if (checkClick(mx, my, 10, btnY, 80, 20)) { new CompanyActionPacket("levelup", "").send(); return true; }
            if (checkClick(mx, my, 100, btnY, 80, 20)) { new CompanyActionPacket("leveldown", "").send(); return true; }
            if (playerCompany.getStatus() == CompanyStatus.PRIVATE && playerCompany.getLevel().getLevel() >= 2) {
                if (checkClick(mx, my, 10, btnY + 30, 80, 20)) { new CompanyActionPacket("list", "").send(); return true; }
            }
        }
        return false;
    }

    private void handleLendingClick(double mx, double my) {
        if (checkClick(mx, my, 200, TOP_H + 30, 60, 18)) { new LoanRequestPacket("BANK_A", 5000).send(); }
        if (checkClick(mx, my, 200, TOP_H + 50, 60, 18)) { new LoanRequestPacket("BANK_B", 5000).send(); }
        if (checkClick(mx, my, 200, TOP_H + 70, 60, 18)) { new LoanRequestPacket("BANK_C", 5000).send(); }
    }

    private void handleQuantClick(double mx, double my) {
        int leftW = 220;
        if (checkClick(mx, my, leftW + 10, TOP_H + (height - BOTTOM_H - TOP_H - 80) + 35, 60, 18)) { saveQuantFile(); }
        if (checkClick(mx, my, leftW + 80, TOP_H + (height - BOTTOM_H - TOP_H - 80) + 35, 60, 18)) { loadQuantFile(); }
        if (checkClick(mx, my, leftW + 150, TOP_H + (height - BOTTOM_H - TOP_H - 80) + 35, 60, 18)) {
            if (!selectedStockId.isEmpty()) new EnableQuantStrategyPacket("custom", 0, 0).send();
        }
        if (checkClick(mx, my, leftW + 220, TOP_H + (height - BOTTOM_H - TOP_H - 80) + 35, 60, 18)) { new DisableQuantStrategyPacket().send(); }
        if (mx >= leftW + 10 && mx <= width - 20 && my >= TOP_H + 30 && my <= TOP_H + 30 + (height - BOTTOM_H - TOP_H - 80)) {
            quantCodeFocused = true;
        } else { quantCodeFocused = false; }
    }

    private boolean checkClick(double mx, double my, int bx, int by, int bw, int bh) {
        return mx >= bx && mx <= bx + bw && my >= by && my <= by + bh;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (draggingCorner) draggingCorner = false;
        if (dragging) dragging = false;
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (dragging && !klines.isEmpty()) {
            int deltaOffset = (int) ((dragStartX - mx) * visibleCandles / chartW);
            scrollOffsetX = Mth.clamp(dragStartOffsetX + deltaOffset, 0, Math.max(0, klines.size() - visibleCandles));
            double priceRange = getVisiblePriceRange();
            if (priceRange > 0) {
                double deltaPrice = (dragStartY - my) * priceRange / chartH;
                verticalOffset = Mth.clamp(dragStartOffsetY + deltaPrice / (priceRange / 2.0), -2.0, 2.0);
            }
            return true;
        }
        if (currentPage == Page.STOCKS && mx >= LEFT_W - 8 && mx <= LEFT_W && my >= TOP_H + 20 + 16 && my <= height - BOTTOM_H) {
            int maxScroll = Math.max(0, marketStocks.size() - leftVisibleRows);
            if (maxScroll > 0) {
                int listHeight = height - BOTTOM_H - (TOP_H + 20 + 16);
                int thumbH = Math.max(20, listHeight * leftVisibleRows / marketStocks.size());
                double ratio = (my - (TOP_H + 20 + 16)) / (double) listHeight;
                leftScrollOffset = (int)(ratio * maxScroll);
                leftScrollOffset = Mth.clamp(leftScrollOffset, 0, maxScroll);
            }
            return true;
        }
        if (currentPage == Page.COMMODITIES && mx >= 192 && mx <= 200 && my >= TOP_H + 22 && my <= height - BOTTOM_H) {
            int maxScroll = Math.max(0, commodityPrices.size() - commodityVisibleRows);
            if (maxScroll > 0) {
                int listHeight = height - BOTTOM_H - (TOP_H + 22);
                int thumbH = Math.max(20, listHeight * commodityVisibleRows / commodityPrices.size());
                double ratio = (my - (TOP_H + 22)) / (double) listHeight;
                commodityScrollOffset = (int)(ratio * maxScroll);
                commodityScrollOffset = Mth.clamp(commodityScrollOffset, 0, maxScroll);
            }
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
        // 图表缩放（K线区域）
        if (currentPage == Page.STOCKS && !klines.isEmpty() && mx >= chartX && mx <= chartX + chartW - 8 && my >= chartY + 38 && my <= height - BOTTOM_H - 10) {
            double factor = (scrollY > 0) ? 1.2 : 0.8;
            int oldVisible = visibleCandles;
            visibleCandles = (int) Mth.clamp(visibleCandles * factor, 5, 200);
            double relX = (mx - chartX) / (double) chartW;
            int newOffset = scrollOffsetX + (int) (relX * (oldVisible - visibleCandles));
            scrollOffsetX = Mth.clamp(newOffset, 0, Math.max(0, klines.size() - visibleCandles));

            double oldVScale = verticalScale;
            verticalScale = Mth.clamp(verticalScale * factor, 0.3, 5.0);
            // 纵向缩放保持鼠标所指价格不变
            return true;
        }
        if (currentPage == Page.STOCKS && mx >= 0 && mx <= LEFT_W && my >= TOP_H + 20 && my <= height - BOTTOM_H) {
            leftScrollOffset -= (int) scrollY;
            leftScrollOffset = Mth.clamp(leftScrollOffset, 0, Math.max(0, marketStocks.size() - leftVisibleRows));
            return true;
        }
        if (currentPage == Page.COMMODITIES && mx >= 0 && mx <= 200 && my >= TOP_H + 22 && my <= height - BOTTOM_H) {
            commodityScrollOffset -= (int) scrollY;
            commodityScrollOffset = Mth.clamp(commodityScrollOffset, 0, Math.max(0, commodityPrices.size() - commodityVisibleRows));
            return true;
        }
        if (currentPage == Page.STOCKS && mx >= rightX && mx <= rightX + RIGHT_W && my >= rightY && my <= rightY + rightH) {
            rightScrollOffset -= (int) scrollY;
            int maxScroll = Math.max(0, 540 - rightH);
            rightScrollOffset = Mth.clamp(rightScrollOffset, 0, maxScroll);
            return true;
        }
        if (currentPage == Page.STOCKS && mx >= chartX && mx <= chartX + chartW - 8 && my >= chartY && my <= height - BOTTOM_H - 10) {
            if (!klines.isEmpty()) {
                double factor = (scrollY > 0) ? 1.2 : 0.8;
                int oldVisible = visibleCandles;
                visibleCandles = (int) Mth.clamp(visibleCandles * factor, 5, 200);
                double relX = (mx - chartX) / (double) chartW;
                int newOffset = scrollOffsetX + (int) (relX * (oldVisible - visibleCandles));
                scrollOffsetX = Mth.clamp(newOffset, 0, Math.max(0, klines.size() - visibleCandles));
                double oldVScale = verticalScale;
                verticalScale = Mth.clamp(verticalScale * factor, 0.3, 5.0);
                double relY = 1.0 - (my - (chartY - chartScrollOffset)) / (double) chartH;
                double mid = (getVisiblePriceMax() + getVisiblePriceMin()) / 2.0;
                double half = (getVisiblePriceMax() - getVisiblePriceMin()) / 2.0;
                double oldPrice = mid + (relY - 0.5) * 2 * half / oldVScale;
                verticalOffset = (oldPrice - mid) / (half * verticalScale) - (relY - 0.5) * 2;
                return true;
            }
        }
        if (currentPage == Page.STOCKS && chartNeedScroll && mx >= chartX && mx <= chartX + chartW && my >= chartY && my <= height - BOTTOM_H - 10) {
            int maxScroll = Math.max(0, getTotalChartHeight() - (height - BOTTOM_H - chartY - 10));
            chartScrollOffset -= (int) scrollY * 20;
            chartScrollOffset = Mth.clamp(chartScrollOffset, 0, maxScroll);
            return true;
        }
        return super.mouseScrolled(mx, my, scrollX, scrollY);
    }

    private int getTotalChartHeight() {
        int total = chartH + volH + 20;
        if (showRSI || showMACD || showKDJ) total += INDICATOR_H * 3 + 20;
        return total;
    }

    // ===================== 键盘输入 =====================
    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (qtyFocused) { if (Character.isDigit(codePoint) || codePoint == '.') { qtyText += codePoint; return true; } }
        else if (priceFocused) { if (Character.isDigit(codePoint) || codePoint == '.') { priceText += codePoint; return true; } }
        else if (commodityQtyFocused) { if (Character.isDigit(codePoint)) { commodityQtyText += codePoint; return true; } }
        else if (companyNameFocused) { if (Character.isLetterOrDigit(codePoint) || codePoint == '_') { companyNameText += codePoint; return true; } }
        else if (depositFocused) { if (Character.isDigit(codePoint)) { depositQtyText += codePoint; return true; } }
        else if (quantCodeFocused) { quantCodeText += codePoint; return true; }
        return super.charTyped(codePoint, modifiers);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) { onClose(); return true; }
        if (qtyFocused && keyCode == GLFW.GLFW_KEY_BACKSPACE && !qtyText.isEmpty()) { qtyText = qtyText.substring(0, qtyText.length() - 1); return true; }
        if (priceFocused && keyCode == GLFW.GLFW_KEY_BACKSPACE && !priceText.isEmpty()) { priceText = priceText.substring(0, priceText.length() - 1); return true; }
        if (commodityQtyFocused && keyCode == GLFW.GLFW_KEY_BACKSPACE && !commodityQtyText.isEmpty()) { commodityQtyText = commodityQtyText.substring(0, commodityQtyText.length() - 1); return true; }
        if (companyNameFocused && keyCode == GLFW.GLFW_KEY_BACKSPACE && !companyNameText.isEmpty()) { companyNameText = companyNameText.substring(0, companyNameText.length() - 1); return true; }
        if (depositFocused && keyCode == GLFW.GLFW_KEY_BACKSPACE && !depositQtyText.isEmpty()) { depositQtyText = depositQtyText.substring(0, depositQtyText.length() - 1); return true; }
        if (quantCodeFocused && keyCode == GLFW.GLFW_KEY_BACKSPACE && !quantCodeText.isEmpty()) {
            if (quantCodeText.endsWith("\n")) quantCodeText = quantCodeText.substring(0, quantCodeText.length() - 1);
            else if (!quantCodeText.isEmpty()) quantCodeText = quantCodeText.substring(0, quantCodeText.length() - 1);
            return true;
        }
        if (quantCodeFocused && keyCode == GLFW.GLFW_KEY_ENTER) { quantCodeText += "\n"; return true; }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    // ===================== 网络回调 =====================
    public void updatePortfolio(Map<String, Holding> newHoldings, double newCash) { holdings.clear(); holdings.putAll(newHoldings); cash = newCash; }
    public void updateMarketList(MarketListPacket packet) {
        marketStocks.clear();
        for (var info : packet.stocks()) {
            double price = info.price(), change = info.change();
            double open = price / (1 + change / 100.0);
            double high = Math.max(price, open) * 1.005;
            double low = Math.min(price, open) * 0.995;
            marketStocks.add(new StockEntry(info.stockId(), info.usCode(), info.cnCode(), info.name(), price, change, info.volume(), open, high, low));
        }
    }
    public void updateStockData(StockDataResponsePacket packet) {
        selectedStockId = packet.assetId();
        /*klines.clear(); klines.addAll(packet.klines());
        tradePoints.clear(); tradePoints.addAll(packet.tradePoints());*/
        if (!klines.isEmpty()) calcIndicators();
        scrollOffsetX = 0; verticalScale = 1.0; verticalOffset = 0.0; visibleCandles = Math.min(60, klines.size());
    }

    public void updateCommodityPrices(Map<Item, Double> prices) { commodityPrices.clear(); commodityPrices.putAll(prices); }
    public void updateCompanyData(CompanyData data) { this.playerCompany = data; }
    public void updateOrderBook(OrderBookSnapshotPacket packet) {
        if (packet.assetId().equals(selectedStockId)) {
            currentOrderBook.bids.clear();
            currentOrderBook.asks.clear();
            if (packet.bids() != null) {
                for (var l : packet.bids()) currentOrderBook.bids.add(new OrderBook.OrderBookLevel(l.price(), l.quantity()));
            }
            if (packet.asks() != null) {
                for (var l : packet.asks()) currentOrderBook.asks.add(new OrderBook.OrderBookLevel(l.price(), l.quantity()));
            }
        }
    }
    public void updateIndexValue(double value) { this.indexValue = value; }
    public void updateMarginData(double balance, double risk) { this.marginBalance = balance; this.marginRisk = risk; }
    public void updateLoanData(List<Loan> loans) { this.activeLoans.clear(); this.activeLoans.addAll(loans); }

    // ===================== 交易执行 =====================
    private void executeTrade(boolean buy) {
        int qty = parseInt(qtyText);
        if (useLots) qty *= 100;
        if (qty <= 0 || selectedStockId.isEmpty()) return;
        /*new PlaceOrderPacket(selectedStockId, buy ? PlaceOrderPacket.OrderType.MARKET_BUY : PlaceOrderPacket.OrderType.MARKET_SELL, qty).send();*/
    }
    private void executeMarginTrade(boolean isLong, boolean isShort) {
        int qty = parseInt(qtyText);
        if (useLots) qty *= 100;
        if (qty <= 0 || selectedStockId.isEmpty()) return;
        new MarginOrderPacket(selectedStockId, qty, leverage, isShort).send();
    }

    // ===================== 指标计算 =====================
    private void calcIndicators() {
        int size = klines.size(); if (size == 0) return;
        double[] closes = klines.stream().mapToDouble(OHLCData::getClose).toArray();
        double[] highs = klines.stream().mapToDouble(OHLCData::getHigh).toArray();
        double[] lows = klines.stream().mapToDouble(OHLCData::getLow).toArray();
        double[] volumes = klines.stream().mapToDouble(OHLCData::getVolume).toArray();
        ma5 = calcMA(closes, 5, size); ma10 = calcMA(closes, 10, size); ma20 = calcMA(closes, 20, size);
        ma60 = calcMA(closes, 60, size); ma120 = calcMA(closes, 120, size); ma250 = calcMA(closes, 250, size);
        volMa5 = calcMA(volumes, 5, size); volMa10 = calcMA(volumes, 10, size);
        rsi = calcRSI(closes, 14, size);
        macdLine = new double[size]; signalLine = new double[size]; macdHistogram = new double[size];
        calcMACD(closes, macdLine, signalLine, macdHistogram, size);
        kValue = new double[size]; dValue = new double[size]; jValue = new double[size];
        calcKDJ(highs, lows, closes, kValue, dValue, jValue, size);
        bollMid = ma20; bollUpper = new double[size]; bollLower = new double[size];
        calcBollinger(closes, ma20, bollUpper, bollLower, size);
    }

    private double[] calcMA(double[] d, int p, int s) {
        double[] m = new double[s];
        for (int i = 0; i < s; i++) { if (i < p-1) { m[i]=0; continue; } double sum=0; for (int j=0;j<p;j++) sum+=d[i-j]; m[i]=sum/p; }
        return m;
    }
    private double[] calcRSI(double[] c, int p, int s) {
        double[] r = new double[s]; if (s<=p) return r;
        double ag=0, al=0;
        for (int i=1;i<=p;i++) { double diff=c[i]-c[i-1]; if(diff>0)ag+=diff; else al-=diff; }
        ag/=p; al/=p; r[p]=100-(100/(1+ag/(al==0?1:al)));
        for (int i=p+1;i<s;i++) { double diff=c[i]-c[i-1]; double gain=diff>0?diff:0, loss=diff<0?-diff:0;
            ag=(ag*(p-1)+gain)/p; al=(al*(p-1)+loss)/p; r[i]=100-(100/(1+ag/(al==0?1:al))); }
        return r;
    }
    private void calcMACD(double[] c, double[] dif, double[] dea, double[] hist, int s) {
        double[] e12=calcEMA(c,12,s), e26=calcEMA(c,26,s);
        for (int i=0;i<s;i++) { if(i<26){dif[i]=0;dea[i]=0;hist[i]=0;continue;} dif[i]=e12[i]-e26[i]; }
        double[] sig=calcEMA(dif,9,s);
        for (int i=0;i<s;i++) { if(i<34){dea[i]=0;hist[i]=0;continue;} dea[i]=sig[i]; hist[i]=(dif[i]-dea[i])*2; }
        System.arraycopy(dif,0,macdLine,0,s); System.arraycopy(dea,0,signalLine,0,s); System.arraycopy(hist,0,macdHistogram,0,s);
    }
    private double[] calcEMA(double[] d, int p, int s) { double[] e=new double[s]; e[0]=d[0]; double m=2.0/(p+1); for(int i=1;i<s;i++) e[i]=(d[i]-e[i-1])*m+e[i-1]; return e; }
    private void calcKDJ(double[] h, double[] l, double[] c, double[] k, double[] d, double[] j, int s) {
        k[0]=50; d[0]=50;
        for (int i=8;i<s;i++) { double ll=l[i], hh=h[i]; for(int t=i-8;t<=i;t++){ if(l[t]<ll)ll=l[t]; if(h[t]>hh)hh=h[t]; }
            double rsv=(c[i]-ll)/(hh-ll+0.001)*100; k[i]=(2.0/3)*k[i-1]+(1.0/3)*rsv; d[i]=(2.0/3)*d[i-1]+(1.0/3)*k[i]; j[i]=3*k[i]-2*d[i]; }
    }
    private void calcBollinger(double[] c, double[] ma, double[] u, double[] l, int s) {
        for(int i=19;i<s;i++){ double sum=0; for(int t=i-19;t<=i;t++) sum+=Math.pow(c[t]-ma[i],2); double std=Math.sqrt(sum/20); u[i]=ma[i]+2*std; l[i]=ma[i]-2*std; }
    }

    private double getVisiblePriceMin() {
        if (klines.isEmpty()) return 0;
        int start = Mth.clamp(klines.size() - visibleCandles - scrollOffsetX, 0, klines.size() - 1);
        int end = Mth.clamp(start + visibleCandles, 0, klines.size() - 1);
        double min = Double.MAX_VALUE;
        for (int i = start; i <= end; i++) min = Math.min(min, klines.get(i).getLow());
        return min;
    }
    private double getVisiblePriceMax() {
        if (klines.isEmpty()) return 0;
        int start = Mth.clamp(klines.size() - visibleCandles - scrollOffsetX, 0, klines.size() - 1);
        int end = Mth.clamp(start + visibleCandles, 0, klines.size() - 1);
        double max = -Double.MAX_VALUE;
        for (int i = start; i <= end; i++) max = Math.max(max, klines.get(i).getHigh());
        return max;
    }
    private double getVisiblePriceRange() { return getVisiblePriceMax() - getVisiblePriceMin(); }
    private double getLastPrice() { return klines.isEmpty() ? 0 : klines.get(klines.size() - 1).getClose(); }
    private double getLastPrice(String stockId) { return getLastPrice(); }

    private int parseInt(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
    }

    // ===================== 内部数据类 =====================
    public static class StockEntry {
        public String stockId, usCode, cnCode, name;
        public double price, change, open, high, low;
        public long volume;
        public StockEntry(String id, String us, String cn, String name, double price, double change, long volume, double open, double high, double low) {
            this.stockId = id; this.usCode = us; this.cnCode = cn; this.name = name;
            this.price = price; this.change = change; this.volume = volume;
            this.open = open; this.high = high; this.low = low;
        }
    }
}