package com.example.financialmarket.engine.banking;

/**
 * Luhn 校验（ISO/IEC 7812）：现实银行卡号的校验位算法。
 *
 * <p>纯静态、无状态、无随机。卡号生成用 {@link #appendCheckDigit} 补校验位，
 * 任意给定卡号可用 {@link #verify} 独立验真（BT-3）。
 *
 * <p>标准 Luhn：从右往左，偶位数字×2（若 &gt;9 减 9），累和，校验位 = (10 − 累和%10)%10。
 */
public final class Luhn {

    private Luhn() {
    }

    /**
     * 对 15 位数字前缀计算校验位（末位），返回 16 位完整卡号。
     *
     * @param prefix 15 位数字前缀（可含分隔符，内部剥离）
     * @return 16 位完整卡号（末位为 Luhn 校验位）
     * @throws IllegalArgumentException 当前缀剥离后不足/超过 15 位时
     */
    public static String appendCheckDigit(String prefix) {
        String cleaned = stripNonDigits(prefix);
        if (cleaned.length() != 15) {
            throw new IllegalArgumentException("卡号前缀必须是 15 位数字，实际: " + cleaned.length());
        }
        return cleaned + computeCheckDigit(cleaned);
    }

    /**
     * 校验一个完整卡号是否符合 Luhn（末位校验位正确）。只对数字字符串判定，非数字字符剥离。
     *
     * @param cardNumber 完整卡号（至少 2 位，含校验位）
     * @return 合法返回 {@code true}；篡改任意一位、空串、非数字等返回 {@code false}
     */
    public static boolean verify(String cardNumber) {
        String cleaned = stripNonDigits(cardNumber);
        if (cleaned.length() < 2 || cleaned.length() > 19) return false;
        String payload = cleaned.substring(0, cleaned.length() - 1);
        int check = cleaned.charAt(cleaned.length() - 1) - '0';
        return computeCheckDigit(payload) == check;
    }

    /**
     * 标准 Luhn：对给定的数字串（不含校验位）计算校验位。
     *
     * <p>从右往左遍历载荷数字，最右（紧邻校验位的）数字翻倍，其后逐位交替翻倍；
     * 翻倍结果若 &gt;9 减 9；累和；校验位 = (10 − 累和%10)%10。确定性、无随机。
     */
    private static int computeCheckDigit(String digits) {
        int sum = 0;
        boolean doubleDigit = true; // 从右数第 1 位（紧邻校验位）翻倍
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (doubleDigit) {
                d *= 2;
                if (d > 9) d -= 9;
            }
            sum += d;
            doubleDigit = !doubleDigit;
        }
        return (10 - (sum % 10)) % 10;
    }

    /** 剥离所有非数字字符（允许卡号带空格/短横线）。 */
    private static String stripNonDigits(String s) {
        if (s == null) return "";
        return s.replaceAll("[^0-9]", "");
    }
}
