package cn.warriorView.Object.Format.Number;

import cn.warriorView.Util.CharUtils;

import java.util.Map;

public final class NumberQuantize implements INumberFormat {
    private static final char[] DIGITS = "0123456789".toCharArray();
    private static final long[] POW10_CACHE = new long[18];

    static {
        long value = 1;
        for (int i = 0; i < POW10_CACHE.length; i++) {
            POW10_CACHE[i] = value;
            value *= 10;
        }
    }

    private final double[] thresholds;
    private final char[][] units;
    private final int maxUnitIndex;
    private final ThreadLocal<StringBuilder> buffer;

    private NumberQuantize(double[] thresholds, String[] units) {
        this.thresholds = thresholds;
        this.units = new char[units.length][];
        for (int i = 0; i < units.length; i++) {
            this.units[i] = units[i].toCharArray();
        }
        this.maxUnitIndex = thresholds.length - 1;
        this.buffer = ThreadLocal.withInitial(() -> new StringBuilder(32));
    }

    public static NumberQuantize create(Map<Integer, String> unitMap) {
        if (unitMap.isEmpty()) {
            throw new IllegalArgumentException("Unit map cannot be empty");
        }

        var sortedEntries = unitMap.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .toList();

        double[] thresholds = new double[sortedEntries.size()];
        String[] units = new String[sortedEntries.size()];

        for (int i = 0; i < sortedEntries.size(); i++) {
            int exp = sortedEntries.get(i).getKey();
            thresholds[i] = (exp >= 0 && exp < POW10_CACHE.length) ?
                    POW10_CACHE[exp] : Math.pow(10, exp);
            units[i] = CharUtils.unescapeUnicode(sortedEntries.get(i).getValue());
        }

        return new NumberQuantize(thresholds, units);
    }

    private static void reverseSegment(StringBuilder sb, int start, int end) {
        while (start < end) {
            char temp = sb.charAt(start);
            sb.setCharAt(start++, sb.charAt(end));
            sb.setCharAt(end--, temp);
        }
    }

    @Override
    public String format(String prefix, String suffix, int precision, double value) {
        final long scale = POW10_CACHE[precision];
        StringBuilder buf = buffer.get();
        buf.setLength(0);

        buf.append(prefix);
        int unitIdx = findOptimalUnit(value);
        double scaledValue = unitIdx != -1 ? value / thresholds[unitIdx] : value;
        long fixed = Math.round(scaledValue * scale);

        writeNumber(fixed, buf, scale, precision);
        if (unitIdx != -1) buf.append(units[unitIdx]);
        return buf.append(suffix).toString();
    }

    private int findOptimalUnit(double value) {
        if (maxUnitIndex < 0 || value < thresholds[0]) return -1;

        int low = 0, high = maxUnitIndex;
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

    private void writeNumber(long num, StringBuilder buf, long scale, int precision) {
        long integer = num / scale;
        long decimal = num % scale;

        if (integer == 0) {
            buf.append('0');
        } else {
            int start = buf.length();
            while (integer > 0) {
                buf.append(DIGITS[(int) (integer % 10)]);
                integer /= 10;
            }
            reverseSegment(buf, start, buf.length() - 1);
        }

        if (precision > 0) {
            buf.append('.');
            long divisor = POW10_CACHE[precision - 1];
            for (int i = 0; i < precision; i++) {
                int digit = (int) (decimal / divisor);
                buf.append(DIGITS[digit]);
                decimal %= divisor;
                divisor /= 10;
            }
        }
    }
}