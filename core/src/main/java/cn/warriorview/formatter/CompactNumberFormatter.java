package cn.warriorview.formatter;

import java.util.Map;
import java.util.TreeMap;

/**
 * 紧凑型数字格式化器，支持将大数字格式化为带尾缀的短字符串（如 1.2K, 3.5M）。
 * 支持自定小数精度，并针对虚拟线程（Virtual Threads）进行了缓存优化。
 *
 * <p>内部实现细节，不对外暴露。由 {@link ValueFormatterRegistry} 在加载期构建。</p>
 */
class CompactNumberFormatter {
    private static final long[] POW10_CACHE = new long[18];
    private static final char[] DIGITS = "0123456789".toCharArray();

    static {
        long value = 1;
        for (int i = 0; i < POW10_CACHE.length; i++) {
            POW10_CACHE[i] = value;
            value *= 10;
        }
    }

    private final double[] thresholds;
    private final double[] multipliers;
    private final char[][] units;
    private final int maxUnitIndex;

    private final ThreadLocal<StringBuilder> threadLocalBuffer =
            ThreadLocal.withInitial(() -> new StringBuilder(32));

    private CompactNumberFormatter(double[] thresholds, double[] multipliers, char[][] units) {
        this.thresholds = thresholds;
        this.multipliers = multipliers;
        this.units = units;
        this.maxUnitIndex = thresholds.length - 1;
    }

    static CompactNumberFormatter of(Map<Double, String> configuration) {
        if (configuration == null || configuration.isEmpty()) {
            return new CompactNumberFormatter(new double[0], new double[0], new char[0][]);
        }
        Builder builder = new Builder();
        configuration.forEach(builder::add);
        return builder.build();
    }

    String format(double value, int precision) {
        if (Double.isNaN(value)) {
            return "NaN";
        }
        if (Double.isInfinite(value)) {
            return value > 0 ? "Infinity" : "-Infinity";
        }
        if (precision < 0 || precision >= POW10_CACHE.length) {
            throw new IllegalArgumentException("Precision must be between 0 and " + (POW10_CACHE.length - 1) + ".");
        }

        final StringBuilder buf = threadLocalBuffer.get();
        buf.setLength(0);

        boolean isNegative = value < 0;
        double absValue = isNegative ? -value : value;

        final int unitIndex = findOptimalUnit(absValue);

        double scaledValue = absValue;
        char[] unitSymbol = null;

        if (unitIndex != -1) {
            scaledValue = absValue * multipliers[unitIndex];
            unitSymbol = units[unitIndex];
        }

        final long scale = POW10_CACHE[precision];
        final long fixed = (long) Math.fma(scaledValue, scale, 0.5);

        if (isNegative) {
            buf.append('-');
        }

        writeFormattedNumber(buf, fixed, scale, precision);

        if (unitSymbol != null) {
            buf.append(unitSymbol);
        }

        return buf.toString();
    }

    private int findOptimalUnit(double value) {
        if (maxUnitIndex < 0 || value < thresholds[0]) {
            return -1;
        }
        int low = 0;
        int high = maxUnitIndex;
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (value >= thresholds[mid]) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        return low;
    }

    private void writeFormattedNumber(StringBuilder buf, long num, long scale, int precision) {
        final long integerPart = num / scale;
        buf.append(integerPart);

        if (precision > 0) {
            final long decimalPart = num % scale;
            buf.append('.');
            var decimalChars = new char[precision];
            long temp = decimalPart;
            for (int i = precision - 1; i >= 0; i--) {
                decimalChars[i] = DIGITS[(int) (temp % 10)];
                temp /= 10;
            }
            buf.append(decimalChars);
        }
    }

    static class Builder {
        private final TreeMap<Double, String> config = new TreeMap<>();

        Builder add(double threshold, String symbol) {
            if (threshold > 0 && symbol != null) {
                config.put(threshold, symbol);
            }
            return this;
        }

        CompactNumberFormatter build() {
            int size = config.size();
            var thresholds = new double[size];
            var multipliers = new double[size];
            var units = new char[size][];
            int index = 0;

            for (var entry : config.entrySet()) {
                double val = entry.getKey();
                thresholds[index] = val;
                multipliers[index] = 1.0d / val;
                units[index] = entry.getValue().toCharArray();
                index++;
            }
            return new CompactNumberFormatter(thresholds, multipliers, units);
        }
    }
}
