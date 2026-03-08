package cn.warriorview.api.manager;

import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.Collection;

/**
 * 动画系统的公共 API 门面。
 *
 * <p>职责：动画播放、动画查询、指示器触发。</p>
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

    // ── 指示器 ──────────────────────────────────────────────────────────────

    /**
     * 处理一次事件并生成指示器动画（伤害、治愈等）。
     *
     * @param victim      目标实体
     * @param attacker    来源实体（触发者）
     * @param finalDamage 最终数值（伤害、治愈量等）
     * @param tag         配置标记，用于匹配 {@code indicator/} 配置文件中的条目；
     *                    传入 {@code null} 或空串时回退到 {@code default} 配置
     * @return 是否至少有一名玩家在范围内看到了指示器
     */
    boolean showIndicator(Entity victim, Entity attacker, double finalDamage, String tag);

    // ── 重载 ─────────────────────────────────────────────────────────────────

    /** 从磁盘重载所有动画定义。 */
    void reload();
}

