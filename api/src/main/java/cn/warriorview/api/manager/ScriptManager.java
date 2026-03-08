package cn.warriorview.api.manager;

import java.util.Collection;

/**
 * 脚本系统管理器。
 *
 * <p>提供两类能力：</p>
 * <ol>
 *   <li><b>Action 注册</b>：允许第三方插件向脚本系统注册自定义 action 类，
 *       方法上标注 {@code @ScriptAction} 即可被脚本 YAML 引用。</li>
 *   <li><b>Event-mapping 管理</b>：可查询已加载的映射脚本、重载脚本，
 *       以及在运行时添加或移除映射条目。</li>
 * </ol>
 *
 * <p>通过 {@link cn.warriorview.api.WarriorViewAPI#getProvider()}{@code .getScriptManager()} 获取。</p>
 */
public interface ScriptManager {

    // ── Action 注册 ─────────────────────────────────────────────────────

    /**
     * 扫描并注册 {@code clazz} 中所有标注了 {@code @ScriptAction} 的 public static 方法。
     *
     * <p>多次注册同一个类是幂等操作。</p>
     *
     * @param clazz 包含 {@code @ScriptAction} 方法的类
     */
    void registerActionClass(Class<?> clazz);

    // ── Event-mapping 管理 ─────────────────────────────────────────────

    /**
     * 从磁盘重载所有 {@code event-mapping/} 脚本。
     * 已注册的 action 类不会被清除。
     */
    void reloadMappings();

    /**
     * 卸载所有 event-mapping 脚本（取消 Bukkit 事件监听）。
     */
    void unloadMappings();

    /**
     * 返回当前已加载的所有 event-mapping 脚本 ID（对应 YAML 文件中的 {@code id} 字段）。
     */
    Collection<String> getMappingIds();
}
