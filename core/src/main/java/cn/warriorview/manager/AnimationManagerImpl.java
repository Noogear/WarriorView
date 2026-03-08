package cn.warriorview.manager;

import cn.warriorview.animation.definition.AnimationDef;
import cn.warriorview.animation.engine.AnimationPlayer;
import cn.warriorview.animation.registry.AnimationRegistry;
import cn.warriorview.api.manager.AnimationManager;
import cn.warriorview.configFile.AnimationConfig;
import cn.warriorview.configFile.IndicatorConfigLoader;
import cn.warriorview.listener.DamageHandler;
import cn.warriorview.formatter.CharReplaceRegistry;
import cn.warriorview.formatter.NumberFormatRegistry;

import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * {@link AnimationManager} 的 Core 实现。
 *
 * <p>将 API 调用委托给内部的 {@link AnimationPlayer}、{@link AnimationRegistry}、
 * {@link DamageHandler}、{@link BukkitScriptManager} 和 {@link IndicatorConfigLoader}。</p>
 */
public final class AnimationManagerImpl implements AnimationManager {

    private final AnimationConfig       config;
    private final DamageHandler         damageHandler;
    private final BukkitScriptManager   scriptManager;
    private final IndicatorConfigLoader indicatorConfigLoader;
    private final NumberFormatRegistry  numberFormatRegistry;
    private final CharReplaceRegistry   charReplaceRegistry;

    public AnimationManagerImpl(AnimationConfig config,
                                DamageHandler damageHandler,
                                BukkitScriptManager scriptManager,
                                IndicatorConfigLoader indicatorConfigLoader,
                                NumberFormatRegistry numberFormatRegistry,
                                CharReplaceRegistry  charReplaceRegistry) {
        this.config                = config;
        this.damageHandler         = damageHandler;
        this.scriptManager         = scriptManager;
        this.indicatorConfigLoader = indicatorConfigLoader;
        this.numberFormatRegistry  = numberFormatRegistry;
        this.charReplaceRegistry   = charReplaceRegistry;
    }

    @Override
    public void playAnimation(String name, Location anchor, Component text,
                              Player[] viewers, int viewerCount) {
        AnimationDef def = config.getRegistry().get(name);
        if (def == null) return;
        config.getPlayer().play(def, anchor, text, viewers, viewerCount);
    }

    @Override
    public boolean showDamageIndicator(Entity victim, Entity attacker,
                                       double finalDamage, String tag) {
        return damageHandler.onDamage(victim, attacker, finalDamage, tag);
    }

    @Override
    public Collection<String> getAnimationNames() {
        return config.getRegistry().all().stream()
                .map(AnimationDef::name)
                .collect(Collectors.toUnmodifiableList());
    }

    @Override
    public void reloadAnimations() {
        config.reload();
        // 动画定义已刷新，重建指示器配置中的 AnimationDef 闭包绑定
        indicatorConfigLoader.load();
    }

    @Override
    public void reloadScripts() {
        scriptManager.reloadScripts();
    }

    @Override
    public boolean hasAnimation(String name) {
        return config.getRegistry().contains(name);
    }

    @Override
    public Collection<String> getIndicatorTags() {
        return indicatorConfigLoader.getTags();
    }

    @Override
    public void reloadIndicatorConfigs() {
        indicatorConfigLoader.load();
    }

    @Override
    public void reloadFormatters() {
        numberFormatRegistry.reload();
        charReplaceRegistry.reload();
        // 重新加载指示器配置，确保格式引用最新
        indicatorConfigLoader.load();
    }

    @Override
    public Map<String, Boolean> smartReloadAll() {
        Map<String, Boolean> result = new LinkedHashMap<>();

        // 动画
        boolean animChanged = config.hasChanges();
        if (animChanged) {
            config.reload();
        }
        result.put("animations", animChanged);

        // 格式器（number-format + char-replace）
        boolean nfChanged  = numberFormatRegistry.smartReload();
        boolean crChanged  = charReplaceRegistry.smartReload();
        result.put("formatters", nfChanged || crChanged);

        // 指示器配置——如果动画或格式器变了也需要重载（绑定引用会变）
        boolean indicatorChanged;
        if (animChanged || nfChanged || crChanged) {
            indicatorConfigLoader.load();
            indicatorChanged = true;
        } else {
            indicatorChanged = indicatorConfigLoader.smartReload();
        }
        result.put("indicators", indicatorChanged);

        // 脚本（事件映射总是全量重载，但我们可以标记它）
        // 脚本模块不支持增量重载，这里仍然 "always reload"
        scriptManager.reloadScripts();
        result.put("scripts", true);

        return result;
    }
}
