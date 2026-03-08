package cn.warriorview.configFile;

import cn.warriorview.animation.registry.AnimationRegistry;
import cn.warriorview.formatter.CharReplaceRegistry;
import cn.warriorview.formatter.NumberFormatRegistry;

/**
 * {@link IndicatorConfig#resolve} 的 {@code @PostLoad} 上下文，
 * 持有加载期跨注册表查找所需的依赖。
 */
public record IndicatorContext(
        AnimationRegistry    animationRegistry,
        NumberFormatRegistry numberFormatRegistry,
        CharReplaceRegistry  charReplaceRegistry) {}
