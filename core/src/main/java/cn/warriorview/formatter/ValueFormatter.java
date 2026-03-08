package cn.warriorview.formatter;

/**
 * 数值格式化器：将 {@code double} 转为可展示的字符串。
 *
 * <p>实例由 {@link NumberFormatRegistry} 在加载期构建，运行期供
 * {@link cn.warriorview.configFile.IndicatorConfig} 通过 {@code formatValue()} 调用。</p>
 */
@FunctionalInterface
public interface ValueFormatter {

    /**
     * 无操作格式化器（仅十进制格式化）。
     * 用作各配置项的默认值。
     */
    ValueFormatter NONE = (value, decimalPlaces) -> {
        int p = Math.max(0, decimalPlaces);
        return p == 0 ? Long.toString((long) value) : String.format("%." + p + "f", value);
    };

    /**
     * 将 {@code value} 格式化为字符串。
     *
     * @param value         数值（非负）
     * @param decimalPlaces 小数位数（0 = 整数，负数同 0 处理）
     */
    String format(double value, int decimalPlaces);
}
