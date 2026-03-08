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

    /** 从磁盘重载 {@code number-format.yml}。 */
    void reload();
}
