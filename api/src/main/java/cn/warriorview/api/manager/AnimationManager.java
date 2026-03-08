package cn.warriorview.api.manager;

import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.Collection;

/**
 * 动画系统的公共 API 门面。
 *
 * <p>通过 {@link cn.warriorview.api.WarriorViewAPI#getProvider()} 获取实现。</p>
 */
public interface AnimationManager {

    // ── 动画播放 ──────────────────────────────────────────────────────────────

    /**
     * 播放一个已注册的命名动画。
     *
     * @param name        动画定义名称（对应 YAML 中的 key）
     * @param anchor      生成位置（会叠加动画自身的 offset 表达式）
     * @param text        TextDisplay 初始文字
     * @param viewers     可见玩家数组
     * @param viewerCount {@code viewers} 中有效条目数
     */
    void playAnimation(String name, Location anchor, Component text,
                       Player[] viewers, int viewerCount);

    /**
     * 向一组玩家播放命名动画（便捷重载）。
     */
    default void playAnimation(String name, Location anchor, Component text,
                               Collection<? extends Player> viewers) {
        Player[] arr = viewers.toArray(new Player[0]);
        playAnimation(name, anchor, text, arr, arr.length);
    }

    /**
     * 向单个玩家播放命名动画（便捷重载）。
     */
    default void playAnimation(String name, Location anchor, Component text, Player viewer) {
        playAnimation(name, anchor, text, new Player[]{viewer}, 1);
    }

    /**
     * 返回 {@code true} 当且仅当指定名称的动画已加载。
     */
    boolean hasAnimation(String name);

    /**
     * 获取所有已注册动画的名称（只读）。
     */
    Collection<String> getAnimationNames();

    // ── 伤害指示器 ────────────────────────────────────────────────────────────

    /**
     * 处理一次伤害事件并生成伤害指示器动画。
     *
     * @param victim      受击实体
     * @param attacker    攻击者（触发者）
     * @param finalDamage 最终伤害值
     * @param tag         配置标记，用于匹配 {@code indicator/} 配置文件中的条目；
     *                    传入 {@code null} 或空串时回退到 {@code default} 配置
     * @return 是否至少有一名玩家在范围内看到了指示器
     */
    boolean showDamageIndicator(Entity victim, Entity attacker, double finalDamage, String tag);

    /**
     * 返回所有已注册的指示器 tag 名称（只读）。
     *
     * <p>tag 来自 {@code indicator/} 目录下的 YAML 文件顶层键，可通过
     * {@link #showDamageIndicator} 直接引用。</p>
     */
    Collection<String> getIndicatorTags();

    // ── 重载 ─────────────────────────────────────────────────────────────────

    /** 从磁盘重载所有动画定义。 */
    void reloadAnimations();

    /** 从磁盘重载指示器配置（{@code indicator/} 目录）。 */
    void reloadIndicatorConfigs();

    /** 从磁盘重载数字格式映射（{@code replacement.yml}）。 */
    void reloadFormatters();

    /** 从磁盘重载所有事件脚本（可用 {@link ScriptManager} 实现相同操作）。 */
    void reloadScripts();

    /** 重载全部配置：动画、指示器、格式映射、脚本。 */
    default void reloadAll() {
        reloadAnimations();
        reloadIndicatorConfigs();
        reloadFormatters();
        reloadScripts();
    }

    // ── 智能重载（仅重新加载磁盘上有变更的文件）──────────────────────────────

    /**
     * 智能重载全部配置：跳过未变更的文件，仅处理实际发生变化的配置。
     *
     * @return 按模块名称到是否发生变更的映射，例如
     *         {@code {animations=true, indicators=false, formatters=false, scripts=true}}
     */
    java.util.Map<String, Boolean> smartReloadAll();
}

