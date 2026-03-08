package cn.warriorview.api.manager;

import java.util.Collection;

/**
 * 字符替换规则管理器。
 *
 * <p>管理 {@code char-replace.yml} 中定义的字符替换规则，例如：
 * <pre>
 * double-struck:
 *   "0": "𝟘"
 *   "1": "𝟙"
 *   ".": "·"
 * </pre>
 *
 * <p>规则名称通过 {@code indicator/} 配置文件中的 {@code char-replace} 字段引用。</p>
 */
public interface CharReplaceManager {

    /** 返回所有已加载的字符替换规则名称（只读）。 */
    Collection<String> getRuleNames();

    /** 从磁盘重载 {@code char-replace.yml}。 */
    void reload();
}
