package cn.warriorview.api;

import cn.warriorview.api.manager.AnimationManager;
import cn.warriorview.api.manager.CharReplaceManager;
import cn.warriorview.api.manager.IndicatorManager;
import cn.warriorview.api.manager.NumberFormatManager;
import cn.warriorview.api.manager.ScriptManager;

import java.util.Map;

/**
 * WarriorView 服务提供者接口。
 * <p>
 * Core 模块的主类需要实现此接口，
 * 以便 API 模块可以通过 {@link WarriorViewAPI#getProvider()} 获取到实现。
 */
public interface WarriorView {

    // ── 子系统管理器 ────────────────────────────────────────────────────

    /** 获取动画管理器（播放动画、查询、重载动画定义）。 */
    AnimationManager getAnimationManager();

    /** 获取数字量化格式管理器（管理 {@code number-format.yml}）。 */
    NumberFormatManager getNumberFormatManager();

    /** 获取字符替换规则管理器（管理 {@code char-replace.yml}）。 */
    CharReplaceManager getCharReplaceManager();

    /**
     * 获取脚本管理器。
     *
     * <p>允许第三方插件：</p>
     * <ul>
     *   <li>注册自定义 action 类（与 event-mapping 脚本配合使用）</li>
     *   <li>重载 / 卸载 event-mapping 脚本</li>
     *   <li>查询已加载脚本列表</li>
     * </ul>
     */
    ScriptManager getScriptManager();

    /** 获取指示器配置管理器（管理 {@code indicator/} 目录）。 */
    IndicatorManager getIndicatorManager();

    // ── 全局重载 ────────────────────────────────────────────────────────

    /** 重载全部配置：动画、指示器、格式映射、脚本。 */
    void reloadAll();

    /**
     * 智能重载全部配置：跳过未变更的文件，仅处理实际发生变化的配置。
     *
     * @return 按模块名称到是否发生变更的映射，例如
     *         {@code {animations=true, indicators=false, formatters=false, scripts=true}}
     */
    Map<String, Boolean> smartReloadAll();

    // ── 元信息 ──────────────────────────────────────────────────────────

    /** 获取插件版本。 */
    String getVersion();
}
