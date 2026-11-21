package com.example.financialmarket.company;

public enum CompanyLevel {
    STARTUP(1, 9, "初创企业", 0),
    SMALL(2, 27, "小型企业", 5000),
    MEDIUM(3, 64, "中型企业", 20000),
    LARGE(4, 125, "大型企业", 100000),
    CORPORATION(5, 200, "集团公司", 500000);

    private final int level;
    private final int maxContainers;    // 最大可绑定容器数
    private final String displayName;
    private final double minMarketCap;  // 升级所需最低市值

    CompanyLevel(int level, int maxContainers, String displayName, double minMarketCap) {
        this.level = level;
        this.maxContainers = maxContainers;
        this.displayName = displayName;
        this.minMarketCap = minMarketCap;
    }

    public int getLevel() { return level; }
    public int getMaxContainers() { return maxContainers; }
    public String getDisplayName() { return displayName; }
    public double getMinMarketCap() { return minMarketCap; }

    public static CompanyLevel fromLevel(int level) {
        for (CompanyLevel l : values()) {
            if (l.level == level) return l;
        }
        return STARTUP;
    }
}