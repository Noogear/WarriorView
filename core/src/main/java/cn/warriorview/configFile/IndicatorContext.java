package cn.warriorview.configFile;

import cn.warriorview.animation.registry.AnimationRegistry;
import cn.warriorview.formatter.CharReplaceRegistry;
import cn.warriorview.formatter.NumberFormatRegistry;

import java.util.function.UnaryOperator;

/**
 * {@link IndicatorConfig#resolve} 的 {@code @PostLoad} 上下文，
 * 持有加载期跨注册表查找所需的依赖。
 */
public record IndicatorContext(
        AnimationRegistry    animationRegistry,
        NumberFormatRegistry numberFormatRegistry,
        CharReplaceRegistry  charReplaceRegistry,
        UnaryOperator<String> preprocessor) {

    /**
     * 兼容旧构造（无预处理器）。
     */
    public IndicatorContext(AnimationRegistry animationRegistry,
                            NumberFormatRegistry numberFormatRegistry,
                            CharReplaceRegistry charReplaceRegistry) {
        this(animationRegistry, numberFormatRegistry, charReplaceRegistry, UnaryOperator.identity());
    }
}
