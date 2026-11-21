package com.example.financialmarket.screen;

import com.example.financialmarket.network.packets.BankCreatePacket;
import com.example.financialmarket.network.packets.BankDataResponsePacket;
import com.example.financialmarket.network.packets.BankDepositPacket;
import com.example.financialmarket.network.packets.BankLoanPacket;
import com.example.financialmarket.network.packets.BankRatePacket;
import com.example.financialmarket.network.packets.CardIssuePacket;
import com.example.financialmarket.network.packets.CentralBankCreatePacket;
import com.example.financialmarket.network.packets.CentralBankPolicyPacket;
import com.example.financialmarket.network.packets.RequestBankDataPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.*;

/**
 * 银行平板（BankTabletScreen）完善——完整分页银行管理界面（AS-7）。
 * <p>客户端<b>纯展示 + 请求</b>：只发包（{@link BankCreatePacket}/{@link BankDepositPacket}/
 * {@link BankLoanPacket}/{@link CentralBankCreatePacket}/{@link CentralBankPolicyPacket}）
 * 与读服务端对账数据（{@link BankDataResponsePacket}），<b>不做任何权威状态变更</b>，服务端唯一权威。
 * 分页：银行列表 / 我的存贷款 / 我的银行 / 央行 / 操作结果。所有控件（按钮 + {@link EditBox}）只发包，
 * 客户端不改本地状态；非拥有者只读；操作后底部提示栏显示中文结果。
 */
public class BankTabletScreen extends Screen {
    private static final String[] TABS = {"银行列表", "我的存贷款", "我的银行", "央行", "操作结果"};

    private final Map<String, BankDataResponsePacket.BankInfo> banks = new LinkedHashMap<>();
    private double playerCash;
    private List<BankDataResponsePacket.LoanInfo> playerLoans = new ArrayList<>();
    private BankDataResponsePacket.CentralBankInfo centralBank;
    private int currentTab = 0;
    private String selectedBankId = "BANK_A";
    private String resultMessage = "";
    /** 是否启用交互控件；false 时回落只读看板基线（不注册任何交互 widget，仅展示数据）。 */
    private boolean interactive = true;
    private final List<AbstractWidget> myWidgets = new ArrayList<>();

    private EditBox bankNameField, equityField, amountField, loanAmountField, loanTermField, loanRateField;
    private EditBox repayLoanIdField, repayAmountField, cbRateField, cbReserveField, cbBaseField;

    public BankTabletScreen() {
        super(Component.literal("银行平板"));
        new RequestBankDataPacket().send();
    }

    @Override
    protected void init() {
        if (interactive) {
            int x = 10;
            for (int i = 0; i < TABS.length; i++) {
                final int idx = i;
                add(buildButton(x, 8, 70, 18, TABS[i], () -> { currentTab = idx; rebuild(); }));
                x += 72;
            }
        }
        if (!interactive) return; // 回落只读看板基线：不注册任何交互控件
        switch (currentTab) {
            case 0 -> buildBankListTab();
            case 1 -> buildDepositLoanTab();
            case 2 -> buildMyBankTab();
            case 3 -> buildCentralBankTab();
            case 4 -> buildResultTab();
            default -> { }
        }
    }

    // ===================== 各页签控件 =====================

    private void buildBankListTab() {
        int row = 0;
        for (BankDataResponsePacket.BankInfo info : banks.values()) {
            final BankDataResponsePacket.BankInfo bi = info;
            int rowY = 34 + row * 16;
            add(buildButton(width - 220, rowY, 60, 14, "选择",
                    () -> { selectedBankId = bi.bankId(); resultMessage = "已选择银行：" + bi.name(); rebuild(); }));
            // 冒烟阻断项修复：真实玩家发卡入口——选中该行银行后点「办卡」，
            // 客户端仅发包（CardIssuePacket），服务端权威发卡（Luhn 卡号、每玩家 1 张）。
            add(buildButton(width - 150, rowY, 60, 14, "办卡",
                    () -> {
                        new CardIssuePacket(bi.bankId()).send();
                        resultMessage = "已向 " + bi.name() + " 发起办卡请求 → 服务端处理（结果见聊天）";
                    }));
            row++;
        }
        if (row == 0) {
            gFieldLabel(34, "暂无银行数据（请先开设商业银行或等待数据加载）");
        }
    }

    private void buildDepositLoanTab() {
        amountField = add(buildEdit(120, 44, 90, 16, "0"));
        add(buildButton(220, 44, 60, 16, "存款", () -> {
            if (selectedBankId == null) { resultMessage = "请先选择银行"; rebuild(); return; }
            new BankDepositPacket(selectedBankId, "deposit", parse(amountField)).send();
            resultMessage = "已发起存款请求 → 服务端处理（结果见聊天/刷新）";
        }));
        add(buildButton(285, 44, 60, 16, "取款", () -> {
            if (selectedBankId == null) { resultMessage = "请先选择银行"; rebuild(); return; }
            new BankDepositPacket(selectedBankId, "withdraw", parse(amountField)).send();
            resultMessage = "已发起取款请求 → 服务端处理（结果见聊天/刷新）";
        }));

        loanAmountField = add(buildEdit(120, 70, 90, 16, "0"));
        loanTermField = add(buildEdit(230, 70, 50, 16, "30"));
        loanRateField = add(buildEdit(300, 70, 50, 16, "0"));
        add(buildButton(120, 94, 90, 16, "申请贷款", () -> {
            if (selectedBankId == null) { resultMessage = "请先选择银行"; rebuild(); return; }
            int term = parseTerm(loanTermField);
            double rate = parse(loanRateField);
            new BankLoanPacket(selectedBankId, "issue", parse(loanAmountField), term, rate, null).send();
            resultMessage = "已发起贷款申请 → 服务端处理";
        }));

        repayLoanIdField = add(buildEdit(120, 122, 120, 16, ""));
        repayAmountField = add(buildEdit(250, 122, 60, 16, "0"));
        add(buildButton(120, 146, 80, 16, "还款", () -> {
            java.util.UUID loanId = uuidOf(repayLoanIdField);
            if (loanId == null) { resultMessage = "请填写有效贷款ID"; rebuild(); return; }
            new BankLoanPacket(null, "repay", parse(repayAmountField), 0, 0, loanId).send();
            resultMessage = "已发起还款请求 → 服务端处理";
        }));
    }

    private void buildMyBankTab() {
        bankNameField = add(buildEdit(90, 44, 120, 16, "我的银行"));
        equityField = add(buildEdit(240, 44, 90, 16, "100000"));
        add(buildButton(90, 68, 110, 16, "开设商业银行", () -> {
            String name = bankNameField.getValue().trim();
            if (name.isEmpty()) name = "我的银行";
            new BankCreatePacket(name, parse(equityField)).send();
            resultMessage = "已发起开设银行请求 → 服务端处理";
            new RequestBankDataPacket().send();
        }));

        int row = 0;
        for (BankDataResponsePacket.BankInfo info : banks.values()) {
            if (!info.isMine()) continue;
            final BankDataResponsePacket.BankInfo bi = info;
            int rowY = 100 + row * 18;
            final EditBox prime = add(buildEdit(200, rowY, 70, 14, fmtRate(bi.primeRate())));
            final EditBox dep = add(buildEdit(285, rowY, 70, 14, fmtRate(bi.depositRate())));
            add(buildButton(365, rowY - 2, 70, 16, "设定利率", () -> {
                new BankRatePacket(bi.bankId(), "prime", parse(prime)).send();
                new BankRatePacket(bi.bankId(), "deposit", parse(dep)).send();
                resultMessage = "已发起设定 " + bi.name() + " 利率请求 → 服务端处理";
            }));
            row++;
        }
    }

    private void buildCentralBankTab() {
        boolean ownedByMe = isMyCentralBank();
        if (centralBank == null || !centralBank.playerOwned()) {
            add(buildButton(120, 44, 120, 16, "开设央行", () -> {
                new CentralBankCreatePacket().send();
                resultMessage = "已发起开设央行请求 → 服务端处理";
                new RequestBankDataPacket().send();
            }));
        } else if (ownedByMe) {
            int y = 44;
            cbRateField = add(buildEdit(150, y, 80, 16, "0.03"));
            gFieldLabel(y, "贴现率");
            add(buildButton(240, y, 60, 16, "设定", () -> {
                new CentralBankPolicyPacket("set_discount", parse(cbRateField)).send();
                resultMessage = "已发起设定贴现率请求";
            }));
            y += 22;
            cbReserveField = add(buildEdit(150, y, 80, 16, "0.10"));
            gFieldLabel(y, "准备金率");
            add(buildButton(240, y, 60, 16, "设定", () -> {
                new CentralBankPolicyPacket("set_reserve", parse(cbReserveField)).send();
                resultMessage = "已发起设定准备金率请求";
            }));
            y += 22;
            cbBaseField = add(buildEdit(150, y, 80, 16, "0"));
            gFieldLabel(y, "发钞/注入M0");
            add(buildButton(240, y, 60, 16, "发钞", () -> {
                new CentralBankPolicyPacket("inject_base", parse(cbBaseField)).send();
                resultMessage = "已发起发钞请求";
            }));
            add(buildButton(240, y + 24, 60, 16, "设基数", () -> {
                new CentralBankPolicyPacket("set_base", parse(cbBaseField)).send();
                resultMessage = "已发起设定基础货币基数请求";
            }));
        }
    }

    private void buildResultTab() {
        // 操作结果提示（渲染层读取 resultMessage）
    }

    private void gFieldLabel(int y, String label) {
        // 文字标签由 render() 绘制
    }

    // ===================== 渲染 =====================

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float partial) {
        // 背景在 render() 中绘制，避免 renderBackground 覆盖自绘内容
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partial) {
        g.fill(0, 0, width, height, 0xFF1E1E1E);
        g.drawString(font, "银行系统", 10, 10, 0xFFCCCCCC);
        g.drawString(font, "现金: " + fmt(playerCash), width - 150, 10, 0xFFCCCCCC);
        if (interactive) {
            g.drawString(font, "当前页签: " + TABS[Math.min(currentTab, TABS.length - 1)], 10, 34, 0xFFFFA500);
        } else {
            g.drawString(font, "只读看板（banking 关闭）", 10, 34, 0xFF888888);
        }

        switch (currentTab) {
            case 0 -> renderBankList(g);
            case 1 -> renderDepositLoan(g);
            case 2 -> renderMyBank(g);
            case 3 -> renderCentralBank(g);
            case 4 -> renderResult(g);
            default -> { }
        }

        g.fill(0, height - 24, width, height, 0xFF2A2A2A);
        g.drawString(font, resultMessage.isEmpty() ? "操作结果：等待操作…" : "操作结果：" + resultMessage, 10, height - 18, 0xFF88FF88);

        super.render(g, mx, my, partial);
    }

    private void renderBankList(GuiGraphics g) {
        int y = 68;
        for (BankDataResponsePacket.BankInfo info : banks.values()) {
            boolean sel = info.bankId().equals(selectedBankId);
            String line = (info.isMine() ? "【我的】" : "") + info.name()
                    + "  贷款率 " + pct(info.primeRate()) + "  存款率 " + pct(info.depositRate())
                    + "  权益 " + fmt(info.equity()) + "  存款 " + fmt(info.totalDeposits())
                    + "  贷款 " + fmt(info.totalLoans()) + "  我的存款 " + fmt(info.depositBalance());
            g.drawString(font, line, 10, y, sel ? 0xFFAAAAAA : 0xFF777777);
            y += 16;
        }
        if (y == 68) g.drawString(font, "暂无银行数据", 10, 68, 0xFF888888);
    }

    private void renderDepositLoan(GuiGraphics g) {
        g.drawString(font, "选中银行: " + (selectedBankId == null ? "无" : selectedBankId), 10, 116, 0xFFCCCCCC);
        g.drawString(font, "存取金额:", 10, 46, 0xFFCCCCCC);
        g.drawString(font, "贷款金额:", 10, 72, 0xFFCCCCCC);
        g.drawString(font, "期限(日):", 210, 72, 0xFFCCCCCC);
        g.drawString(font, "利率:", 285, 72, 0xFFCCCCCC);
        g.drawString(font, "还款贷款ID:", 10, 124, 0xFFCCCCCC);
        g.drawString(font, "还款金额:", 240, 124, 0xFFCCCCCC);

        g.drawString(font, "我的贷款:", 10, 174, 0xFFFFA500);
        int y = 190;
        for (BankDataResponsePacket.LoanInfo loan : playerLoans) {
            g.drawString(font, loan.bankId() + " 本金 " + fmt(loan.principal()) + " 剩余 " + fmt(loan.remaining()), 10, y, 0xFFCCCCCC);
            y += 12;
        }
    }

    private void renderMyBank(GuiGraphics g) {
        g.drawString(font, "银行名:", 10, 46, 0xFFCCCCCC);
        g.drawString(font, "初始权益:", 220, 46, 0xFFCCCCCC);
        g.drawString(font, "名下银行（仅拥有者可编辑利率，AI 银行只读）:", 10, 92, 0xFFFFA500);
        int y = 100;
        for (BankDataResponsePacket.BankInfo info : banks.values()) {
            if (!info.isMine()) continue;
            g.drawString(font, info.name(), 10, y + 1, 0xFFCCCCCC);
            g.drawString(font, "贷", 188, y + 1, 0xFFCCCCCC);
            g.drawString(font, "存", 273, y + 1, 0xFFCCCCCC);
            y += 18;
        }
        if (y == 100) g.drawString(font, "您还没有商业银行，可在上方开设", 10, 100, 0xFF888888);
    }

    private void renderCentralBank(GuiGraphics g) {
        BankDataResponsePacket.CentralBankInfo cb = centralBank;
        if (cb == null) {
            g.drawString(font, "央行数据加载中…（如已开设，可下方开设）", 10, 72, 0xFF888888);
            return;
        }
        g.drawString(font, "央行: " + (cb.playerOwned() ? "玩家央行(" + cb.ownerUuid() + ")" : "系统央行(AI)"), 10, 72, 0xFFCCCCCC);
        g.drawString(font, "贴现率 " + pct(cb.discountRate()) + "  准备金率 " + pct(cb.reserveRequirement()), 10, 86, 0xFFCCCCCC);
        g.drawString(font, "基础货币(M0) " + fmt(cb.baseMoney()) + "  理论M2上限 " + fmt(cb.theoreticalM2Ceiling()), 10, 100, 0xFFCCCCCC);
        if (interactive && cb.playerOwned() && isMyCentralBank()) {
            g.drawString(font, "您可设定政策（下方控件）:", 10, 124, 0xFFFFA500);
        } else if (interactive && cb.playerOwned()) {
            g.drawString(font, "央行已由其他玩家开设，您只读", 10, 124, 0xFF888888);
        } else if (interactive) {
            g.drawString(font, "当前为系统央行，可开设玩家央行:", 10, 124, 0xFFFFA500);
        }
    }

    private void renderResult(GuiGraphics g) {
        g.drawString(font, "最近操作:", 10, 68, 0xFFFFA500);
        g.drawString(font, resultMessage.isEmpty() ? "暂无操作" : resultMessage, 10, 84, 0xFFCCCCCC);
        g.drawString(font, "提示：每次操作仅发包，服务端权威校验后回传结果（聊天/下方提示）。", 10, 104, 0xFF888888);
    }

    // ===================== 数据刷新 =====================

    public void updateBankData(Map<String, BankDataResponsePacket.BankInfo> bankMap, double cash,
                               List<BankDataResponsePacket.LoanInfo> loans) {
        this.banks.clear();
        this.banks.putAll(bankMap);
        this.playerCash = cash;
        this.playerLoans = loans;
        if (selectedBankId == null || !this.banks.containsKey(selectedBankId)) {
            if (!this.banks.isEmpty()) this.selectedBankId = this.banks.values().iterator().next().bankId();
        }
    }

    /** 供 s2c 回调把 {@link BankDataResponsePacket#centralBank()} 转发进来以展示央行政策（AS-7/AS-4）。 */
    public void updateCentralBankData(BankDataResponsePacket.CentralBankInfo cb) {
        this.centralBank = cb;
    }

    /** BT-9/BT-10：banking.enabled 信号。false 时回落只读看板（不注册任何交互控件），true 恢复交互。 */
    public void setBankingEnabled(boolean enabled) {
        if (this.interactive == enabled) return;
        this.interactive = enabled;
        if (this.minecraft != null) this.rebuild();
    }

    private boolean isMyCentralBank() {
        if (centralBank == null || !centralBank.playerOwned()) return false;
        var p = Minecraft.getInstance().player;
        return p != null && p.getUUID().toString().equals(centralBank.ownerUuid());
    }

    // ===================== 控件与工具 =====================

    private <W extends AbstractWidget> W add(W w) {
        myWidgets.add(w);
        addRenderableWidget(w);
        return w;
    }

    private void rebuild() {
        for (AbstractWidget w : myWidgets) removeWidget(w);
        myWidgets.clear();
        init();
    }

    private Button buildButton(int x, int y, int w, int h, String label, Runnable action) {
        return Button.builder(Component.literal(label), btn -> action.run()).bounds(x, y, w, h).build();
    }

    private EditBox buildEdit(int x, int y, int w, int h, String val) {
        EditBox e = new EditBox(this.font, x, y, w, h, Component.empty());
        e.setMaxLength(64);
        e.setValue(val);
        return e;
    }

    private double parse(EditBox e) {
        if (e == null) return 0;
        try { return Double.parseDouble(e.getValue().trim()); } catch (NumberFormatException ex) { return 0; }
    }

    private int parseTerm(EditBox e) {
        if (e == null) return 30;
        try { return Integer.parseInt(e.getValue().trim()); } catch (NumberFormatException ex) { return 30; }
    }

    private java.util.UUID uuidOf(EditBox e) {
        if (e == null) return null;
        String s = e.getValue().trim();
        if (s.isEmpty()) return null;
        try { return java.util.UUID.fromString(s); } catch (IllegalArgumentException ex) { return null; }
    }

    private static String fmt(double v) { return String.format(java.util.Locale.ROOT, "%.2f", v); }
    private static String pct(double v) { return String.format(java.util.Locale.ROOT, "%.1f%%", v * 100); }
    private static String fmtRate(double v) { return String.format(java.util.Locale.ROOT, "%.3f", v); }
}
