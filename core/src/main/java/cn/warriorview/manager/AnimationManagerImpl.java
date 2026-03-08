package cn.warriorview.manager;

import cn.warriorview.animation.definition.AnimationDef;
import cn.warriorview.api.manager.AnimationManager;
import cn.warriorview.configFile.AnimationConfig;
import cn.warriorview.listener.IndicatorHandler;

import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.stream.Collectors;

/**
 * {@link AnimationManager} 的 Core 实现。
 *
 * <p>职责限定为动画播放、动画查询、指示器触发和动画定义重载。
 * 跨模块的全局重载由 {@link cn.warriorview.Main} 直接协调。</p>
 */
public final class AnimationManagerImpl implements AnimationManager {

    private final AnimationConfig config;
    private final IndicatorHandler indicatorHandler;

    public AnimationManagerImpl(AnimationConfig config,
                                IndicatorHandler indicatorHandler) {
        this.config           = config;
        this.indicatorHandler = indicatorHandler;
    }

    @Override
    public void playAnimation(String name, Location anchor, Component text,
                              Player[] viewers, int viewerCount) {
        AnimationDef def = config.getRegistry().get(name);
        if (def == null) return;
        config.getPlayer().play(def, anchor, text, viewers, viewerCount);
    }

    @Override
    public boolean showIndicator(Entity victim, Entity attacker,
                                  double finalDamage, String tag) {
        return indicatorHandler.onIndicator(victim, attacker, finalDamage, tag);
    }

    @Override
    public Collection<String> getAnimationNames() {
        return config.getRegistry().all().stream()
                .map(AnimationDef::name)
                .collect(Collectors.toUnmodifiableList());
    }

    @Override
    public boolean hasAnimation(String name) {
        return config.getRegistry().contains(name);
    }

    @Override
    public void reload() {
        config.reload();
    }
}
