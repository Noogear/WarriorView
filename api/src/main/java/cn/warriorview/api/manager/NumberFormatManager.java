package cn.warriorview.api.manager;

import java.util.Collection;

/**
 * 数字量化格式管理器。
 *
 * <p>管理 {@code number-format.yml} 中定义的量化缩写规则，例如：
 * <pre>
 * compact:
 *   1000:       "K"
 *   1000000:    "M"
 *   1000000000: "B"
 * </pre>
 *
 * <p>规则名称通过 {@code indicator/} 配置文件中的 {@code number-format} 字段引用。</p>
 */
public interface NumberFormatManager {

    /** 返回所有已加载的量化规则名称（只读）。 */
    Collection<String> getRuleNames();

    /**
     * 使用指定的量化规则格式化数值。
     *
     * <p>示例：规则 {@code compact} 定义 {@code 1000→K}，则
     * {@code formatNumber("compact", 1200, 1)} 返回 {@code "1.2K"}。</p>
     *
     * @param ruleName  量化规则名称（对应 {@code number-format.yml} 顶层键）
     * @param value     要格式化的数值
     * @param precision 小数位数（低于阈值时使用标准小数格式）
     * @return 格式化后的字符串；规则不存在时退化为标准小数格式
     */
    String formatNumber(String ruleName, double value, int precision);

    /** 从磁盘重载 {@code number-format.yml}。 */
    void reload();
}
