package cn.warriorView.Object.Text;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TextQuantize {

    private static final Pattern FORMAT_REGEX = Pattern.compile("^(.*?)%(\\d+)f(.*)$");

    private static final char[] DIGIT_TABLE = new char[10];

    static {
        for (int i = 0; i < 10; i++) {
            DIGIT_TABLE[i] = (char) ('0' + i);
        }
    }

    private final char[] prefix;
    private final char[] suffix;
    private final int precision;
    private final double[] thresholds;
    private final char[][] units;
    private final long scaleFactor;
    private final int[] decimalFactors;

    private final ThreadLocal<StringBuilder> buffer =
            ThreadLocal.withInitial(() -> new StringBuilder(32));

    protected TextQuantize(String prefix, String suffix, int precision,
                           double[] thresholds, String[] units) {
        this.prefix = prefix.toCharArray();
        this.suffix = suffix.toCharArray();
        this.precision = precision;
        this.thresholds = thresholds;
        this.units = Arrays.stream(units)
                .map(String::toCharArray)
                .toArray(char[][]::new);

        this.scaleFactor = computeScale(precision);
        this.decimalFactors = computeDecimalFactors(precision);
    }

    public static TextQuantize build(String format, Map<Integer, String> unitMap) {
        Matcher matcher = FORMAT_REGEX.matcher(format);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid format: " + format);
        }

        return new TextQuantize(
                matcher.group(1),
                matcher.group(3),
                Integer.parseInt(matcher.group(2)),
                unitMap.keySet().stream()
                        .sorted()
                        .mapToDouble(e -> Math.pow(10, e))
                        .toArray(),
                unitMap.values().toArray(String[]::new)
        );
    }

    private static long computeScale(int precision) {
        try {
            return BigInteger.TEN.pow(precision).longValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Precision exceeds limit: " + precision);
        }
    }

    private static int[] computeDecimalFactors(int precision) {
        int[] factors = new int[precision];
        BigInteger current = BigInteger.TEN.pow(precision - 1);
        for (int i = 0; i < precision; i++) {
            factors[i] = current.intValueExact();
            current = current.divide(BigInteger.TEN);
        }
        return factors;
    }

    private static void reverseSegment(StringBuilder sb, int start, int end) {
        while (start < end) {
            char temp = sb.charAt(start);
            sb.setCharAt(start++, sb.charAt(end));
            sb.setCharAt(end--, temp);
        }
    }

    public String format(double value) {
        StringBuilder buf = buffer.get();
        buf.setLength(0);
        buf.append(prefix);

        int unitIndex = findUnitIndex(value);
        double scaledValue = (unitIndex != -1) ? value / thresholds[unitIndex] : value;

        long fixedPoint = Math.round(scaledValue * scaleFactor);
        writeFixedNumber(fixedPoint, buf);

        if (unitIndex != -1) buf.append(units[unitIndex]);
        buf.append(suffix);

        return buf.toString();
    }

    private int findUnitIndex(double value) {
        int low = 0, high = thresholds.length - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (value >= thresholds[mid]) {
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return high;
    }

    private void writeFixedNumber(long num, StringBuilder buf) {
        long integerPart = num / scaleFactor;
        long decimalPart = num % scaleFactor;

        writeDigits(integerPart, buf);

        if (precision > 0) {
            buf.append('.');
            writePaddedDecimal(decimalPart, buf);
        }
    }

    private void writeDigits(long num, StringBuilder buf) {
        if (num == 0) {
            buf.append('0');
            return;
        }

        int start = buf.length();
        while (num > 0) {
            buf.append(DIGIT_TABLE[(int) (num % 10)]);
            num /= 10;
        }
        reverseSegment(buf, start, buf.length() - 1);
    }

    private void writePaddedDecimal(long dec, StringBuilder buf) {
        for (int factor : decimalFactors) {
            buf.append(DIGIT_TABLE[(int) (dec / factor)]);
            dec %= factor;
        }
    }
}