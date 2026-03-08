package cn.warriorview.formatter;

/**
 * 数值格式化器：将 {@code double} 转为可展示的字符串。
 *
 * <p>实例由 {@link NumberFormatRegistry} 在加载期构建，{@code decimalPlaces}、{@code scale}
 * 等配置参数在这时绑定到闭包中，运行期 {@link #format(double)} 不接受额外参数，
 * 避免重复计算和分支判断。</p>
 */
@FunctionalInterface
public interface ValueFormatter {

    /** 整数格式化器（零小数位），无运行期分支。 */
    ValueFormatter NONE = value -> Long.toString((long) value);

    /**
     * 将 {@code value} 格式化为字符串。配置已在加载期绑定，无额外参数。
     *
     * @param value 数值（非负）
     */
    String format(double value);

    /**
     * 创建定点小数格式化器，{@code scale}（10^dp）在加载期预计算。
     *
     * @param decimalPlaces 小数位数（≤0 回退到 {@link #NONE}）
     */
    static ValueFormatter decimal(int decimalPlaces) {
        if (decimalPlaces <= 0) return NONE;
        long scale = 1;
        for (int i = 0; i < decimalPlaces; i++) scale *= 10;
        final long s = scale;
        final int dp = decimalPlaces;
        return value -> formatDecimal(value, s, dp);
    }

    /**
     * 无中间 String 分配的定点小数格式化：直接写入 {@code char[]}，
     * 利用 Java 9+ Compact Strings 以 Latin-1 编码存储。
     */
    static String formatDecimal(double value, long scale, int dp) {
        long scaled = Math.round(value * scale);
        long intPart = scaled / scale;
        long fracPart = scaled % scale;
        String intStr = Long.toString(intPart);
        int len = intStr.length();
        char[] buf = new char[len + 1 + dp];
        intStr.getChars(0, len, buf, 0);
        buf[len] = '.';
        for (int i = len + dp; i > len; i--) {
            buf[i] = (char) ('0' + (fracPart % 10));
            fracPart /= 10;
        }
        return new String(buf);
    }
}
