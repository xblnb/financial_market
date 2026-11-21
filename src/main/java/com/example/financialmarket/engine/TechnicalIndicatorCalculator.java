package com.example.financialmarket.engine;

import java.util.*;

public class TechnicalIndicatorCalculator {
    public static List<Map<String, Double>> calculateIndicators(List<OHLCData> klines) {
        if (klines == null || klines.size() < 2) {
            // 返回空列表，由调用方填充 0
            return Collections.emptyList();
        }
        int n = klines.size();
        double[] closes = new double[n];
        double[] highs = new double[n];
        double[] lows = new double[n];
        for (int i = 0; i < n; i++) {
            closes[i] = klines.get(i).getClose();
            highs[i] = klines.get(i).getHigh();
            lows[i] = klines.get(i).getLow();
        }

        // 计算 MACD
        double[] macdLine = macd(closes, 12, 26);
        double[] macdSignal = ema(macdLine, 9);
        double[] macdHist = new double[n];
        for (int i = 0; i < n; i++) {
            if (i < 26 + 9 - 1) macdHist[i] = Double.NaN;
            else macdHist[i] = macdLine[i] - macdSignal[i];
        }

        // RSI
        double[] rsi = rsi(closes, 14);

        // KDJ
        double[] kdjK = new double[n], kdjD = new double[n], kdjJ = new double[n];
        calculateKDJ(highs, lows, closes, 9, 3, 3, kdjK, kdjD, kdjJ);

        // BOLL
        double[] bollUpper = new double[n], bollMiddle = new double[n], bollLower = new double[n];
        bollingerBands(closes, 20, 2, bollUpper, bollMiddle, bollLower);

        List<Map<String, Double>> result = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Map<String, Double> map = new LinkedHashMap<>();
            // 用 sanitize 将 NaN 转为 0
            map.put("macd", sanitize(macdLine[i]));
            map.put("macdSignal", sanitize(macdSignal[i]));
            map.put("macdHist", sanitize(macdHist[i]));
            map.put("rsi", sanitize(rsi[i]));
            map.put("kdjK", sanitize(kdjK[i]));
            map.put("kdjD", sanitize(kdjD[i]));
            map.put("kdjJ", sanitize(kdjJ[i]));
            map.put("bollUpper", sanitize(bollUpper[i]));
            map.put("bollMiddle", sanitize(bollMiddle[i]));
            map.put("bollLower", sanitize(bollLower[i]));
            result.add(map);
        }
        return result;
    }

    private static double sanitize(double value) {
        return Double.isNaN(value) || Double.isInfinite(value) ? 0.0 : value;
    }

    // ---------- 以下为指标计算函数（保持不变） ----------
    private static double[] ema(double[] data, int period) {
        int n = data.length;
        double[] result = new double[n];
        if (n == 0) return result;
        double multiplier = 2.0 / (period + 1);
        double sma = 0;
        int count = Math.min(period, n);
        for (int i = 0; i < count; i++) sma += data[i];
        sma /= count;
        for (int i = 0; i < n; i++) {
            if (i < period - 1) result[i] = Double.NaN;
            else if (i == period - 1) result[i] = sma;
            else result[i] = (data[i] - result[i-1]) * multiplier + result[i-1];
        }
        return result;
    }

    private static double[] macd(double[] close, int fast, int slow) {
        int n = close.length;
        double[] fastEma = ema(close, fast);
        double[] slowEma = ema(close, slow);
        double[] dif = new double[n];
        for (int i = 0; i < n; i++) {
            if (Double.isNaN(fastEma[i]) || Double.isNaN(slowEma[i])) dif[i] = Double.NaN;
            else dif[i] = fastEma[i] - slowEma[i];
        }
        return dif;
    }

    private static double[] rsi(double[] close, int period) {
        int n = close.length;
        double[] rsi = new double[n];
        if (n <= period) return rsi;
        double gain = 0, loss = 0;
        for (int i = 1; i <= period; i++) {
            double diff = close[i] - close[i-1];
            if (diff > 0) gain += diff;
            else loss -= diff;
        }
        gain /= period; loss /= period;
        rsi[period] = 100 - (100 / (1 + (gain / (loss == 0 ? 1e-10 : loss))));
        for (int i = period + 1; i < n; i++) {
            double diff = close[i] - close[i-1];
            double g = diff > 0 ? diff : 0;
            double l = diff < 0 ? -diff : 0;
            gain = (gain * (period - 1) + g) / period;
            loss = (loss * (period - 1) + l) / period;
            rsi[i] = 100 - (100 / (1 + (gain / (loss == 0 ? 1e-10 : loss))));
        }
        for (int i = 0; i < period; i++) rsi[i] = Double.NaN;
        return rsi;
    }

    private static void calculateKDJ(double[] high, double[] low, double[] close, int n, int m1, int m2,
                                     double[] k, double[] d, double[] j) {
        int len = high.length;
        double prevK = 50, prevD = 50;
        for (int i = 0; i < len; i++) {
            if (i < n - 1) {
                k[i] = Double.NaN; d[i] = Double.NaN; j[i] = Double.NaN;
                continue;
            }
            double hhv = high[i], llv = low[i];
            for (int t = i - n + 1; t <= i; t++) {
                if (high[t] > hhv) hhv = high[t];
                if (low[t] < llv) llv = low[t];
            }
            double rsv = (close[i] - llv) / (hhv - llv + 1e-10) * 100;
            double curK = (prevK * (m1 - 1) + rsv) / m1;
            double curD = (prevD * (m2 - 1) + curK) / m2;
            double curJ = 3 * curK - 2 * curD;
            k[i] = curK; d[i] = curD; j[i] = curJ;
            prevK = curK; prevD = curD;
        }
    }

    private static void bollingerBands(double[] close, int period, double multiplier,
                                       double[] upper, double[] middle, double[] lower) {
        int n = close.length;
        for (int i = 0; i < n; i++) {
            if (i < period - 1) {
                upper[i] = Double.NaN; middle[i] = Double.NaN; lower[i] = Double.NaN;
                continue;
            }
            double sum = 0;
            for (int j = i - period + 1; j <= i; j++) sum += close[j];
            double ma = sum / period;
            double sumSq = 0;
            for (int j = i - period + 1; j <= i; j++) {
                double diff = close[j] - ma;
                sumSq += diff * diff;
            }
            double std = Math.sqrt(sumSq / period);
            upper[i] = ma + multiplier * std;
            middle[i] = ma;
            lower[i] = ma - multiplier * std;
        }
    }
}