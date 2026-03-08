package cn.warriorview.api;

import cn.warriorview.api.manager.AnimationManager;
import cn.warriorview.api.manager.CharReplaceManager;
import cn.warriorview.api.manager.NumberFormatManager;
import cn.warriorview.api.manager.ScriptManager;

/**
 * WarriorView 服务提供者接口。
 * <p>
 * Core 模块的主类需要实现此接口，
 * 以便 API 模块可以通过 {@link WarriorViewAPI#getProvider()} 获取到实现。
 */
public interface WarriorView {

    /** 获取动画管理器（播放动画、显示指示器、重载）。 */
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

    /** 获取插件版本。 */
    String getVersion();
}
