package com.example.financialmarket.screen;

import com.cinemamod.mcef.MCEF;
import com.cinemamod.mcef.MCEFBrowser;
import com.example.financialmarket.web.LocalHttpServer;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefLoadHandler;
import org.cef.network.CefRequest;

public class FinancialMCEFScreen extends Screen {
    private MCEFBrowser browser;
    private static final String URL = "http://localhost:8080";

    /**
     * t4：javaBridge 桥接（AS-3 §5.2）。MCEF 2.1.6 无 JS→Java 对象绑定，故桥接统一走与页面同源的
     * LocalHttpServer HTTP API（同一后端、双轨数据源复用），在页面加载完成后注入：
     * request* / placeOrder / cancelOrder / closeScreen 方法。update* 由前端页面定义（window.*），
     * 桥接缺失时静默占位不报错（兼容旧占位防御）。
     */
    private static final String BRIDGE_JS = """
            (function(){
              function getJSON(url, cb){ fetch(url).then(function(r){return r.json();}).then(function(d){ cb&&cb(d); }).catch(function(){ cb&&cb(null); }); }
              function post(url, body, cb){ fetch(url, {method:'POST', headers:{'Content-Type':'application/json'}, body: body?JSON.stringify(body):'{}'}).then(function(r){return r.json();}).then(function(d){ cb&&cb(d); }).catch(function(){ cb&&cb({success:false,message:'请求失败'}); }); }
              function push(fn, args){ if(window[fn] && typeof window[fn]==='function'){ window[fn].apply(null, args||[]); return; } if(window.javaBridge && window.javaBridge[fn] && typeof window.javaBridge[fn]==='function'){ window.javaBridge[fn].apply(null, args||[]); } }
              var existing = (window.javaBridge && typeof window.javaBridge==='object') ? window.javaBridge : {};
              var bridge = Object.assign({}, existing);
              bridge.__financialMarketBridge = true;
              bridge.requestKline = function(id,type){ getJSON('/api/kline?id='+encodeURIComponent(id)+'&type='+(type||4), function(d){ push('updateStockData',[id,(d&&d.klines)||[],(d&&d.tradePoints)||[]]); }); };
              bridge.requestDepth = function(id){ getJSON('/api/depth?id='+encodeURIComponent(id), function(d){ push('updateDepth',[id,(d&&d.bids)||[],(d&&d.asks)||[]]); }); };
              bridge.requestTick = function(id){ getJSON('/api/tick?id='+encodeURIComponent(id), function(d){ push('updateTick',[id,(d&&d.ticks)||[]]); }); };
              bridge.requestCompany = function(id){ getJSON('/api/company?id='+encodeURIComponent(id), function(d){ push('updateCompany',[d||{}]); }); };
              bridge.requestMarketList = function(){ getJSON('/api/stocks', function(d){ push('updateMarketList',[d||[]]); }); };
              bridge.requestIndex = function(){ getJSON('/api/index', function(d){ push('updateIndex',[(d&&d.value)||0,(d&&d.changePoint)||0,(d&&d.change)||0]); }); };
              bridge.requestOrders = function(){ getJSON('/api/orders', function(d){ push('updateOrders',[d||[]]); }); };
              bridge.requestPositions = function(){ getJSON('/api/positions', function(d){ push('updatePositions',[d||[]]); }); };
              bridge.requestTrades = function(){ getJSON('/api/myTrades', function(d){ push('updateTrades',[d||[]]); }); };
              bridge.requestPortfolio = function(){ getJSON('/api/portfolio', function(d){ push('updatePortfolio',[(d&&d.positions)||[],(d&&d.cash)||0]); }); };
              bridge.placeOrder = function(assetId,isBuy,quantity,price){
                var body={assetId:assetId, side:isBuy?'buy':'sell', type:((price>0)?'limit':'market'), quantity:quantity, price:price};
                post('/api/order', body, function(d){ push('updateTradingResult',[!!(d&&d.success),(d&&d.message)||'',(d&&d.orderId)||null]); if(d&&d.success){ setTimeout(function(){ bridge.requestOrders(); bridge.requestPositions(); bridge.requestPortfolio(); },120); } });
              };
              bridge.cancelOrder = function(orderId){
                post('/api/cancelOrder', {orderId:orderId}, function(d){ push('updateTradingResult',[!!(d&&d.success),(d&&d.message)||'',null]); if(d&&d.success){ setTimeout(function(){ bridge.requestOrders(); bridge.requestPositions(); },120); } });
              };
              bridge.closeScreen = function(){ post('/api/closeScreen', {}, function(){}); };
              window.javaBridge = bridge;
            })();
            """;

    public FinancialMCEFScreen() {
        super(Component.literal("Financial Market"));
    }

    @Override
    protected void init() {
        super.init();
        // 如果已有浏览器，先关闭（无论其状态如何）
        if (browser != null) {
            browser.close();
            browser = null;
        }
        // 创建新浏览器
        browser = MCEF.createBrowser(URL, true);
        resizeBrowser();
        // 页面加载完成后注入 javaBridge 桥接（t4）
        browser.getClient().addLoadHandler(new CefLoadHandler() {
            @Override public void onLoadingStateChange(CefBrowser b, boolean isLoading, boolean canGoBack, boolean canGoForward) {}
            @Override public void onLoadStart(CefBrowser b, CefFrame f, CefRequest.TransitionType transitionType) {}
            @Override public void onLoadEnd(CefBrowser b, CefFrame f, int httpStatusCode) {
                if (b != null) b.executeJavaScript(BRIDGE_JS, URL, 0);
            }
            @Override public void onLoadError(CefBrowser b, CefFrame f, ErrorCode errorCode, String errorText, String failedUrl) {}
        });
        System.out.println("📱 MCEF 屏幕已创建，加载 URL: " + URL);
    }

    private int toBrowserX(double x) { return (int)(x * minecraft.getWindow().getGuiScale()); }
    private int toBrowserY(double y) { return (int)(y * minecraft.getWindow().getGuiScale()); }

    private void resizeBrowser() {
        if (browser != null && width > 0 && height > 0) {
            browser.resize(toBrowserX(width), toBrowserY(height));
        }
    }

    @Override
    public void resize(Minecraft mc, int w, int h) {
        super.resize(mc, w, h);
        resizeBrowser();
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partial) {
        super.render(guiGraphics, mouseX, mouseY, partial);
        // t4：WebView 经 javaBridge.closeScreen()（POST /api/closeScreen）请求关闭屏幕
        if (LocalHttpServer.consumeCloseRequest()) {
            minecraft.setScreen(null);
            return;
        }
        if (browser == null) return;
        RenderSystem.disableDepthTest();
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, browser.getRenderer().getTextureID());

        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder buffer = tesselator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        buffer.addVertex(0, height, 0).setUv(0.0f, 1.0f).setColor(255,255,255,255);
        buffer.addVertex(width, height, 0).setUv(1.0f, 1.0f).setColor(255,255,255,255);
        buffer.addVertex(width, 0, 0).setUv(1.0f, 0.0f).setColor(255,255,255,255);
        buffer.addVertex(0, 0, 0).setUv(0.0f, 0.0f).setColor(255,255,255,255);
        BufferUploader.drawWithShader(buffer.build());
        RenderSystem.setShaderTexture(0, 0);
        RenderSystem.enableDepthTest();
    }

    @Override public boolean mouseClicked(double mx, double my, int btn) {
        if (browser != null) { browser.sendMousePress(toBrowserX(mx), toBrowserY(my), btn); browser.setFocus(true); }
        return super.mouseClicked(mx, my, btn);
    }
    @Override public boolean mouseReleased(double mx, double my, int btn) {
        if (browser != null) browser.sendMouseRelease(toBrowserX(mx), toBrowserY(my), btn);
        return super.mouseReleased(mx, my, btn);
    }
    @Override public void mouseMoved(double mx, double my) {
        if (browser != null) browser.sendMouseMove(toBrowserX(mx), toBrowserY(my));
        super.mouseMoved(mx, my);
    }
    @Override public boolean mouseDragged(double mx, double my, int btn, double dx, double dy) {
        if (browser != null) browser.sendMouseMove(toBrowserX(mx), toBrowserY(my));
        return super.mouseDragged(mx, my, btn, dx, dy);
    }
    @Override public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
        if (browser != null) browser.sendMouseWheel(toBrowserX(mx), toBrowserY(my), scrollY, 0);
        return super.mouseScrolled(mx, my, scrollX, scrollY);
    }
    @Override public boolean keyPressed(int key, int scan, int mod) {
        if (browser != null) { browser.sendKeyPress(key, scan, mod); browser.setFocus(true); }
        return super.keyPressed(key, scan, mod);
    }
    @Override public boolean keyReleased(int key, int scan, int mod) {
        if (browser != null) browser.sendKeyRelease(key, scan, mod);
        return super.keyReleased(key, scan, mod);
    }
    @Override public boolean charTyped(char ch, int mod) {
        if (ch == 0) return false;
        if (browser != null) browser.sendKeyTyped(ch, mod);
        return super.charTyped(ch, mod);
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() {
        if (browser != null) {
            browser.close();
            browser = null;
        }
        super.onClose();
    }
}