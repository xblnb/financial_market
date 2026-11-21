package com.example.financialmarket.screen;

import com.example.financialmarket.network.packets.FinancialWarPacket;
import com.example.financialmarket.network.packets.GovernmentActionPacket;
import com.example.financialmarket.network.packets.RequestGovernmentDataPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class GovernmentTabletScreen extends Screen {
    private double treasury, totalDebt, inflation, baseMoney, discountRate, reserveReq;
    private String inputText = "0";

    public GovernmentTabletScreen() {
        super(Component.literal("金融战控制台"));
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partial) {
        g.fill(0, 0, width, height, 0xFF1E1E1E);
        g.drawString(font, "金融战控制台", 10, 10, 0xFFCCCCCC);
        g.drawString(font, "国库: " + treasury, 10, 30, 0xFFCCCCCC);
        g.drawString(font, "国债: " + totalDebt, 10, 44, 0xFFCCCCCC);
        g.drawString(font, "通胀: " + String.format("%.1f%%", inflation*100), 10, 58, 0xFFCCCCCC);
        g.drawString(font, "基础货币: " + baseMoney, 10, 72, 0xFFCCCCCC);
        g.drawString(font, "贴现率: " + String.format("%.2f%%", discountRate*100), 10, 86, 0xFFCCCCCC);
        g.drawString(font, "准备金率: " + String.format("%.1f%%", reserveReq*100), 10, 100, 0xFFCCCCCC);

        int y = 130;
        // 货币政策
        g.drawString(font, "--- 货币政策 ---", 10, y, 0xFFFFA500); y += 14;
        g.drawString(font, "利率调整:", 10, y, 0xFFCCCCCC);
        drawButton(g, 80, y-2, 40, 14, "+0.25", mx, my, () -> new FinancialWarPacket("interest", "", 0.25).send());
        drawButton(g, 125, y-2, 40, 14, "-0.25", mx, my, () -> new FinancialWarPacket("interest", "", -0.25).send());
        y += 18;
        g.drawString(font, "准备金率:", 10, y, 0xFFCCCCCC);
        drawButton(g, 80, y-2, 40, 14, "+0.01", mx, my, () -> new FinancialWarPacket("reserve", "", 0.01).send());
        drawButton(g, 125, y-2, 40, 14, "-0.01", mx, my, () -> new FinancialWarPacket("reserve", "", -0.01).send());
        y += 22;

        // 市场干预
        g.drawString(font, "--- 市场干预 ---", 10, y, 0xFFFFA500); y += 14;
        g.drawString(font, "目标资产:", 10, y, 0xFFCCCCCC);
        y += 14;
        g.drawString(font, "金额:", 10, y, 0xFFCCCCCC);
        drawButton(g, 50, y-2, 40, 14, "买入", mx, my, () -> new FinancialWarPacket("buy", "EXCELLENT24", parseInput()).send());
        drawButton(g, 95, y-2, 40, 14, "卖出", mx, my, () -> new FinancialWarPacket("sell", "EXCELLENT24", parseInput()).send());
        y += 20;
        g.drawString(font, "情绪干预:", 10, y, 0xFFCCCCCC);
        drawButton(g, 80, y-2, 40, 14, "+0.1", mx, my, () -> new FinancialWarPacket("sentiment", "", 0.1).send());
        drawButton(g, 125, y-2, 40, 14, "-0.1", mx, my, () -> new FinancialWarPacket("sentiment", "", -0.1).send());
        y += 22;

        // 国债
        g.drawString(font, "--- 国债管理 ---", 10, y, 0xFFFFA500); y += 14;
        g.drawString(font, "发行国债(额):", 10, y, 0xFFCCCCCC);
        drawButton(g, 100, y-2, 40, 14, "发行", mx, my, () -> new GovernmentActionPacket("issue_bond", parseInput()).send());
        y += 20;

        // 制裁
        g.drawString(font, "--- 金融制裁 ---", 10, y, 0xFFFFA500); y += 14;
        g.drawString(font, "制裁银行:", 10, y, 0xFFCCCCCC);
        drawButton(g, 80, y-2, 40, 14, "BANK_A", mx, my, () -> new FinancialWarPacket("sanction", "BANK_A", 0).send());
        drawButton(g, 125, y-2, 40, 14, "BANK_B", mx, my, () -> new FinancialWarPacket("sanction", "BANK_B", 0).send());
        y += 18;
        drawButton(g, 80, y-2, 40, 14, "BANK_C", mx, my, () -> new FinancialWarPacket("sanction", "BANK_C", 0).send());
    }

    private void drawButton(GuiGraphics g, int x, int y, int w, int h, String text, int mx, int my, Runnable action) {
        boolean hover = mx >= x && mx <= x + w && my >= y && my <= y + h;
        int bg = hover ? 0xFF5A5A5A : 0xFF3F3F3F;
        g.fill(x, y, x + w, y + h, bg);
        g.drawString(font, text, x + 4, y + 2, 0xFFCCCCCC);
        // 点击处理在 mouseClicked 中
    }

    private double parseInput() {
        try { return Double.parseDouble(inputText); } catch (NumberFormatException e) { return 0; }
    }

    public void updateGovernmentData(double treasury, double totalDebt, double inflation, double baseMoney, double discountRate, double reserveReq) {
        this.treasury = treasury;
        this.totalDebt = totalDebt;
        this.inflation = inflation;
        this.baseMoney = baseMoney;
        this.discountRate = discountRate;
        this.reserveReq = reserveReq;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        // 简化：所有按钮在渲染时通过 drawButton 的 lambda 触发，需要手动绑定
        return super.mouseClicked(mx, my, button);
    }
}