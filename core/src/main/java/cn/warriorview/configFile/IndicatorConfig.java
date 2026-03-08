package cn.warriorview.configFile;

import cn.warriorview.animation.api.Billboard;
import cn.warriorview.animation.api.IndicatorSpawnStrategy;
import cn.warriorview.animation.data.DisplaySettings;
import cn.warriorview.animation.data.OffsetExpr;
import cn.warriorview.animation.definition.AnimationDef;
import cn.warriorview.formatter.ValueFormatter;

import gloomlib.configuration.api.ConfigurationPart;
import gloomlib.configuration.api.annotation.DefaultResources;
import gloomlib.configuration.api.annotation.Ignore;
import gloomlib.configuration.api.annotation.PostLoad;
import gloomlib.configuration.api.annotation.Template;

/**
 * 单个标签（tag）对应的伤害指示器配置。
 *
 * <p>每个 tag 映射一组 TextDisplay 显示参数 + 生成策略，由 {@link IndicatorConfigLoader}
 * 从 {@code indicator/} 目录下的 YAML 文件加载。</p>
 *
 * <p>YAML 字段名通过 camelCase → kebab-case 自动映射
 * （如 {@code textFormat} → {@code text-format}）。</p>
 *
 * <p>{@code animation}、{@code numberFormat}、{@code charReplace} 为 YAML 中的字符串引用名，
 * 在 {@link #resolve(IndicatorContext)} 中解析为运行期对象。</p>
 */
@DefaultResources({
        "indicator/damage-indicator.yml",
        "indicator/regain-indicator.yml"
})
@Template(name = "default")
public class IndicatorConfig extends ConfigurationPart {

    // ── YAML 字段（camelCase → kebab-case 自动映射） ────────────────────────

    public String                 textFormat       = "<white>{damage}";
    public int                    decimalPlaces    = 1;
    public IndicatorSpawnStrategy position         = IndicatorSpawnStrategy.PROJECTED;
    public Billboard              billboard        = Billboard.CENTER;
    public boolean                seeThrough       = false;
    public boolean                textShadow       = true;
    public int                    background       = DisplaySettings.DEFAULT_BACKGROUND;
    public float                  viewRange        = 1.0f;
    public int                    teleportDuration = 0;
    public int                    brightness       = -1;
    public float                  shadowRadius     = 0f;
    public float                  shadowStrength   = 1.0f;
    public int                    glowColor        = 0;
    public int                    lineWidth        = 200;
    public boolean                onlyPlayer       = true;

    /** YAML 中的动画名称引用，{@link #resolve} 后绑定为 {@link #animationDef}。 */
    public String animation    = null;
    /** YAML 中的量化规则名称引用。 */
    public String numberFormat = null;
    /** YAML 中的字符替换规则名称引用。 */
    public String charReplace  = null;

    // ── 运行期解析产物（不写入 YAML） ───────────────────────────────────────

    @Ignore public transient AnimationDef   animationDef;
    @Ignore public transient ValueFormatter formatter = ValueFormatter.NONE;

    // ── @PostLoad：加载期跨注册表绑定 ───────────────────────────────────────

    @PostLoad
    public void resolve(IndicatorContext ctx) {
        if (ctx == null) return;
        animationDef = animation != null ? ctx.animationRegistry().get(animation) : null;
        formatter = ctx.numberFormatRegistry().buildFormatter(
                numberFormat, ctx.charReplaceRegistry(), charReplace);
    }

    // ── 便捷方法 ────────────────────────────────────────────────────────────

    /**
     * 将本配置的静态属性合并到一个 {@link DisplaySettings}。
     * 偏移表达式由调用方从 {@link #animationDef} 中取得并传入。
     */
    public DisplaySettings toDisplaySettings(OffsetExpr offset) {
        return new DisplaySettings(billboard, seeThrough, textShadow,
                background, viewRange, teleportDuration, brightness,
                shadowRadius, shadowStrength, glowColor, lineWidth, offset);
    }

    /**
     * 将数值格式化为最终插入 {@code {damage}} 的字符串。
     * 使用加载期绑定的 {@link ValueFormatter}（quantize + 字符替换）。
     */
    public String formatValue(double value) {
        return formatter.format(value, decimalPlaces);
    }
}
