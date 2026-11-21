package com.example.financialmarket.engine.ai;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * Optional JQuantLib bridge for fundamental DCF analysis.
 *
 * <p>JQuantLib is loaded reflectively so the mod compiles and runs without the jar. When
 * {@code libs/jquantlib-core-0.3.0.jar} is on the runtime classpath, its {@code InterestRate}
 * discount factors drive the DCF; otherwise an equivalent internal discount curve is used.</p>
 */
public final class JQuantLibBridge {

    public record Analysis(double dcfPerShare, double npv, double irr, String source) {
    }

    private static volatile Boolean available;
    private static Method discountFactor;
    private static Object interestRate;

    private JQuantLibBridge() {
    }

    public static boolean available() {
        Boolean a = available;
        if (a != null) {
            return a;
        }
        synchronized (JQuantLibBridge.class) {
            if (available == null) {
                try {
                    Class<?> ir = Class.forName("org.jquantlib.termstructures.InterestRate");
                    Class<?> dayCounter = Class.forName("org.jquantlib.daycounters.DayCounter");
                    Class<?> actual = Class.forName("org.jquantlib.daycounters.Actual365Fixed");
                    Object dc = actual.getDeclaredConstructor().newInstance();
                    Constructor<?> ctor = ir.getConstructor(double.class, dayCounter);
                    // Build per call (rate varies), but cache the method/class resolution.
                    discountFactor = ir.getMethod("discountFactor", double.class);
                    interestRate = ctor;
                    available = true;
                } catch (Throwable t) {
                    available = false;
                }
            }
        }
        return available;
    }

    /** DCF per share: 5-year explicit FCF + Gordon terminal value, discounted with JQuantLib. */
    public static double dcfPerShare(double fcf, double growth, double discount,
                                     double terminalGrowth, double shares, int years) {
        if (shares <= 0 || !Double.isFinite(fcf)) {
            return 0.0;
        }
        double r = Math.max(discount, terminalGrowth + 0.02);
        double g = Math.max(-0.5, Math.min(r - 0.005, growth));
        double tg = Math.max(-0.5, Math.min(r - 0.005, terminalGrowth));
        double pv = 0.0;
        for (int t = 1; t <= years; t++) {
            double cf = fcf * Math.pow(1.0 + g, t);
            pv += cf * discountFactor(r, t);
        }
        double terminal = fcf * Math.pow(1.0 + g, years) * (1.0 + tg) / (r - tg);
        pv += terminal * discountFactor(r, years);
        return pv / shares;
    }

    public static Analysis analyze(double fcf, double growth, double discount,
                                   double terminalGrowth, double shares, int years) {
        double dcf = dcfPerShare(fcf, growth, discount, terminalGrowth, shares, years);
        double pv = 0.0;
        for (int t = 1; t <= years; t++) {
            pv += fcf * Math.pow(1.0 + growth, t) * discountFactor(discount, t);
        }
        // A simple IRR proxy: the discount rate that makes the same cash-flow stream break even.
        double irr = irr(new double[]{-fcf, fcf * (1 + growth)}, growth);
        return new Analysis(dcf, pv, irr, available() ? "jquantlib" : "internal");
    }

    public static double discountFactor(double rate, double years) {
        if (available()) {
            try {
                Class<?> ir = Class.forName("org.jquantlib.termstructures.InterestRate");
                Class<?> dayCounter = Class.forName("org.jquantlib.daycounters.DayCounter");
                Class<?> actual = Class.forName("org.jquantlib.daycounters.Actual365Fixed");
                Object dc = actual.getDeclaredConstructor().newInstance();
                Object obj = ir.getConstructor(double.class, dayCounter).newInstance(rate, dc);
                return (double) discountFactor.invoke(obj, years);
            } catch (Throwable ignored) {
                // fall through to internal
            }
        }
        return Math.exp(-rate * years);
    }

    private static double irr(double[] flows, double fallback) {
        double lo = -0.99, hi = 1.0;
        for (int i = 0; i < 80; i++) {
            double mid = (lo + hi) / 2.0;
            double npv = 0;
            for (int t = 0; t < flows.length; t++) {
                npv += flows[t] / Math.pow(1 + mid, t);
            }
            if (npv > 0) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        double v = (lo + hi) / 2.0;
        return Double.isFinite(v) ? v : fallback;
    }
}
