package cn.warriorview.api.manager;

import java.util.Collection;

/**
 * 伤害指示器配置管理器。
 * 管理 {@code indicator/} 目录下定义的伤害指示器标签配置（如 {@code ENTITY_ATTACK}、{@code CRITICAL}）。
 */
public interface IndicatorManager {

    /**
     * 获取所有已注册的指示器标签名称。
     *
     * @return 只读的标签名称集合
     */
    Collection<String> getTags();

    /**
     * 查询指定 tag 是否已注册。
     *
     * @param tag 标签名称
     * @return 如果存在对应配置则返回 {@code true}
     */
    boolean hasTag(String tag);

    /**
     * 重新加载所有指示器配置。
     */
    void reload();
}
