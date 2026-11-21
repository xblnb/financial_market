package com.example.financialmarket.company;

/**
 * A-3/§4.3：上市前置校验（ListingValidator 从占位完善为完整确定性门槛）。
 *
 * <p>所有条件为确定性阈值常量；任一不满足 → {@code canList=false}，
 * {@link #rejectionReason(CompanyData)} 返回首个未满足项的中文原因（供命令/前端展示）。
 */
public class ListingValidator {
    /** 上市净资产门槛（= SMALL 门槛）。 */
    public static final double LISTING_MIN_NAV = 5000;
    /** 是否要求绑定至少一个有效经营区域/容器（软门槛，默认开启）。 */
    public static final boolean REQUIRE_BOUND_AREA = true;
    /** 一手股数（复用 A 股 board lot）。 */
    public static final int A_SHARE_BOARD_LOT = 100;

    private ListingValidator() {
    }

    /** 是否满足全部上市条件。 */
    public static boolean canList(CompanyData company) {
        return rejectionReason(company) == null;
    }

    /** 返回首个未满足的上市条件原因；全部满足返回 null。 */
    public static String rejectionReason(CompanyData company) {
        if (company == null) return "公司不存在";
        if (company.getStatus() != CompanyStatus.PRIVATE) return "仅未上市（PRIVATE）公司可发起 IPO";
        CompanyIpoState st = company.getIpoState();
        if (st != CompanyIpoState.NONE && st != CompanyIpoState.FAILED) {
            return "当前 IPO 状态不允许发起（" + st.name() + "）";
        }
        if (company.getLevel().getLevel() < 2) return "等级不足：需 SMALL（Lv.2）及以上";
        if (company.getNetAssetValue() < LISTING_MIN_NAV) return "净资产不足：需 ≥ " + LISTING_MIN_NAV;
        if (company.getRetainedEarnings() < 0) return "留存收益为负，财务不健康";
        if (company.getTotalShares() < A_SHARE_BOARD_LOT) return "总股本不足 " + A_SHARE_BOARD_LOT + " 股";
        if (REQUIRE_BOUND_AREA && company.getBoundContainers().isEmpty()) return "未绑定任何经营区域/容器";
        return null;
    }
}
