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

    /**
     * 使用指定的替换规则对字符串进行字符替换。
     *
     * <p>示例：规则 {@code double-struck} 定义 {@code "0"→"𝟘"}，则
     * {@code replaceChars("double-struck", "100")} 返回 {@code "𝟙𝟘𝟘"}。</p>
     *
     * @param ruleName 替换规则名称（对应 {@code char-replace.yml} 顶层键）
     * @param input    待替换的原始字符串
     * @return 替换后的字符串；规则不存在时返回原始字符串
     */
    String replaceChars(String ruleName, String input);

    /** 从磁盘重载 {@code char-replace.yml}。 */
    void reload();
}
