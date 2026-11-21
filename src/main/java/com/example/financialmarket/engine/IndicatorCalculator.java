package com.example.financialmarket.engine;

import java.util.ArrayList;
import java.util.List;

public class IndicatorCalculator {

    // 计算简单移动平均线
    public static double[] sma(List<Double> prices, int period) {
        double[] result = new double[prices.size()];
        for (int i = 0; i < prices.size(); i++) {
            if (i < period - 1) {
                result[i] = Double.NaN;
                continue;
            }
            double sum = 0;
            for (int j = i - period + 1; j <= i; j++) {
                sum += prices.get(j);
            }
            result[i] = sum / period;
        }
        return result;
    }

    // 计算指数移动平均线
    public static double[] ema(List<Double> prices, int period) {
        double[] result = new double[prices.size()];
        double multiplier = 2.0 / (period + 1);
        // 初始化：前period-1个设为NaN，第period个用SMA
        double sma = 0;
        for (int i = 0; i < period; i++) {
            sma += prices.get(i);
        }
        sma /= period;
        result[period - 1] = sma;
        for (int i = period; i < prices.size(); i++) {
            result[i] = (prices.get(i) - result[i - 1]) * multiplier + result[i - 1];
        }
        // 前period-1个保留为NaN
        for (int i = 0; i < period - 1; i++) {
            result[i] = Double.NaN;
        }
        return result;
    }

    // MACD: 返回 {dif, dea, macdBar}
    public static MacdResult macd(List<Double> prices, int fast, int slow, int signal) {
        double[] emaFast = ema(prices, fast);
        double[] emaSlow = ema(prices, slow);
        double[] dif = new double[prices.size()];
        for (int i = 0; i < prices.size(); i++) {
            if (Double.isNaN(emaFast[i]) || Double.isNaN(emaSlow[i])) {
                dif[i] = Double.NaN;
            } else {
                dif[i] = emaFast[i] - emaSlow[i];
            }
        }
        // 对dif计算signal周期的EMA得到DEA
        List<Double> difList = new ArrayList<>();
        for (double v : dif) difList.add(v);
        double[] dea = ema(difList, signal);
        double[] macdBar = new double[prices.size()];
        for (int i = 0; i < prices.size(); i++) {
            if (Double.isNaN(dif[i]) || Double.isNaN(dea[i])) {
                macdBar[i] = Double.NaN;
            } else {
                macdBar[i] = 2 * (dif[i] - dea[i]); // 通常柱子是(DIF-DEA)*2
            }
        }
        return new MacdResult(dif, dea, macdBar);
    }

    // RSI
    public static double[] rsi(List<Double> prices, int period) {
        double[] rsi = new double[prices.size()];
        if (prices.size() <= period) return rsi;
        double avgGain = 0, avgLoss = 0;
        // 首次计算
        for (int i = 1; i <= period; i++) {
            double diff = prices.get(i) - prices.get(i - 1);
            if (diff > 0) avgGain += diff;
            else avgLoss -= diff;
        }
        avgGain /= period;
        avgLoss /= period;
        rsi[period] = 100 - (100 / (1 + (avgGain / (avgLoss == 0 ? 1e-10 : avgLoss))));

        for (int i = period + 1; i < prices.size(); i++) {
            double diff = prices.get(i) - prices.get(i - 1);
            double gain = diff > 0 ? diff : 0;
            double loss = diff < 0 ? -diff : 0;
            avgGain = (avgGain * (period - 1) + gain) / period;
            avgLoss = (avgLoss * (period - 1) + loss) / period;
            rsi[i] = 100 - (100 / (1 + (avgGain / (avgLoss == 0 ? 1e-10 : avgLoss))));
        }
        return rsi;
    }

    // 布林带：返回 {upper, middle, lower}
    public static BollingerResult bollinger(List<Double> prices, int period, double multiplier) {
        double[] middle = sma(prices, period);
        double[] upper = new double[prices.size()];
        double[] lower = new double[prices.size()];
        for (int i = 0; i < prices.size(); i++) {
            if (i < period - 1 || Double.isNaN(middle[i])) {
                upper[i] = Double.NaN;
                lower[i] = Double.NaN;
                continue;
            }
            double sumSq = 0;
            for (int j = i - period + 1; j <= i; j++) {
                sumSq += Math.pow(prices.get(j) - middle[i], 2);
            }
            double std = Math.sqrt(sumSq / period);
            upper[i] = middle[i] + multiplier * std;
            lower[i] = middle[i] - multiplier * std;
        }
        return new BollingerResult(upper, middle, lower);
    }

    public record MacdResult(double[] dif, double[] dea, double[] macdBar) {}
    public record BollingerResult(double[] upper, double[] middle, double[] lower) {}
}