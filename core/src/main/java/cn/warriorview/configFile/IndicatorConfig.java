package cn.warriorview.configFile;

import cn.warriorview.animation.api.Billboard;
import cn.warriorview.animation.api.IndicatorSpawnStrategy;
import cn.warriorview.animation.data.DisplaySettings;
import cn.warriorview.animation.definition.AnimationDef;
import cn.warriorview.formatter.ValueFormatter;
import cn.warriorview.integration.PermissionChecker;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

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
    public IndicatorSpawnStrategy position         = IndicatorSpawnStrategy.AIM;
    public Billboard              billboard        = Billboard.CENTER;
    public boolean                seeThrough       = true;
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
    public double                 maxDistance      = 24.0;

    /** YAML 中的动画名称引用，{@link #resolve} 后绑定为 {@link #animationDef}。 */
    public String animation    = null;
    /** YAML 中的量化规则名称引用。 */
    public String numberFormat = null;
    /** YAML 中的字符替换规则名称引用。 */
    public String charReplace  = null;

    /**
     * 权限变体列表（可选）。自上而下匹配：第一个满足权限的变体生效，其余忽略。
     *
     * <p>仅当 {@code only-player} 为 {@code true} 时才与玩家关联生效。
     * 权限检查始终可用；{@code integrations.luckperms.enabled} 决定的是
     * 使用高性能 LuckPerms 内存检查器还是回退至 {@link PermissionChecker#BUKKIT}。</p>
     *
     * <p>变体中未指定的字段自动继承本配置的值。</p>
     */
    public List<IndicatorVariant> variants = new ArrayList<>();

    // ── 运行期解析产物（不写入 YAML） ───────────────────────────────────────

    @Ignore public transient AnimationDef   animationDef;
    @Ignore public transient ValueFormatter formatter = ValueFormatter.NONE;
    @Ignore public transient double         maxDistanceSq;

    /** 加载期烘焙的权限变体数组，按优先级排列；{@code null} 表示无变体。 */
    @Ignore private transient ResolvedVariant[] resolvedVariants;

    /** 已烘焙的变体：权限节点 + 完整合并配置（含全部父字段覆盖）。 */
    private record ResolvedVariant(String permission, IndicatorConfig effective) {}

    /**
     * 加载期用 MiniMessage 预解析的 Component 模板。
     * {@code \uFFFD}（U+FFFD）标记数值插入点，运行期通过 {@link #buildText} 替换，
     * 完全跳过 MiniMessage 运行期解析，且正确保留该点的所有样式继承。
     */
    @Ignore public transient Component cachedTemplate = Component.empty();

    /** 用于在 Component 树中定位数值插入点的预编译模式（加载期固定）。 */
    private static final String VALUE_SENTINEL   = "\uFFFD";
    private static final Pattern VALUE_PATTERN   = Pattern.compile(Pattern.quote(VALUE_SENTINEL));

    // ── @PostLoad：加载期跨注册表绑定 ───────────────────────────────────────

    @PostLoad
    public void resolve(IndicatorContext ctx) {
        String resolvedTextFormat = ctx != null ? ctx.preprocessor().apply(textFormat) : textFormat;
        cachedTemplate = MiniMessage.miniMessage()
                .deserialize(resolvedTextFormat.replace("{damage}", VALUE_SENTINEL));
        maxDistanceSq = maxDistance * maxDistance;
        if (ctx == null) return;
        animationDef = animation != null ? ctx.animationRegistry().get(animation) : null;
        if (animation != null && animationDef == null) {
            cn.warriorview.util.Log.warn("[IndicatorConfig] Animation '{}' not found in registry. "
                    + "Check animations/ directory for missing preset files.", animation);
        }
        formatter = ctx.numberFormatRegistry().buildFormatter(
                numberFormat, ctx.charReplaceRegistry(), charReplace, decimalPlaces);

        // 烘焙权限变体：将各变体与本配置合并，生成完整的 IndicatorConfig 副本
        if (!variants.isEmpty()) {
            ResolvedVariant[] arr = new ResolvedVariant[variants.size()];
            int count = 0;
            for (IndicatorVariant v : variants) {
                if (v.permission == null || v.permission.isBlank()) continue;
                IndicatorConfig eff = buildEffectiveConfig(v, ctx);
                v.effective = eff;
                arr[count++] = new ResolvedVariant(v.permission, eff);
            }
            resolvedVariants = count > 0 ? Arrays.copyOf(arr, count) : null;
        } else {
            resolvedVariants = null;
        }
    }

    // ── 变体解析 ────────────────────────────────────────────────────────────

    /**
     * 是否包含有效的权限变体（加载期已烘焙）。
     * 仅在存在变体时才需要进行 {@link #resolveVariantFor} 调用。
     */
    public boolean hasVariants() {
        return resolvedVariants != null;
    }

    /**
     * 自上而下匹配变体权限，返回第一个满足权限的变体有效配置。
     * 若无匹配变体，返回 {@code this}（以本配置为基准）。
     *
     * <p>此方法可安全地从调度器异步线程调用；{@link PermissionChecker} 实现
     * 应保证线程安全（LuckPerms 实现读取内存缓存，Bukkit 实现经由 Paper 线程安全保障）。</p>
     *
     * @param player  需要判断权限的在线玩家（观察者）
     * @param checker 权限检查器（由插件启动时注入）
     * @return 匹配的变体有效配置，或 {@code this}（无匹配时）
     */
    public IndicatorConfig resolveVariantFor(Player player, PermissionChecker checker) {
        ResolvedVariant[] rv = resolvedVariants;
        if (rv == null) return this;
        for (ResolvedVariant r : rv) {
            if (checker.hasPermission(player, r.permission())) {
                return r.effective();
            }
        }
        return this;
    }

    /**
     * 将变体覆盖字段合并到本配置，生成完整的独立 {@link IndicatorConfig} 副本。
     * 变体中 {@code null} 的字段使用本配置的值（继承）；非 {@code null} 则覆盖。
     */
    private IndicatorConfig buildEffectiveConfig(IndicatorVariant v, IndicatorContext ctx) {
        IndicatorConfig eff = new IndicatorConfig();
        eff.textFormat       = v.textFormat       != null ? v.textFormat       : this.textFormat;
        eff.decimalPlaces    = v.decimalPlaces     != null ? v.decimalPlaces    : this.decimalPlaces;
        eff.position         = v.position         != null ? v.position         : this.position;
        eff.billboard        = v.billboard        != null ? v.billboard        : this.billboard;
        eff.seeThrough       = v.seeThrough       != null ? v.seeThrough       : this.seeThrough;
        eff.textShadow       = v.textShadow       != null ? v.textShadow       : this.textShadow;
        eff.background       = v.background       != null ? v.background       : this.background;
        eff.viewRange        = v.viewRange        != null ? v.viewRange        : this.viewRange;
        eff.teleportDuration = v.teleportDuration != null ? v.teleportDuration : this.teleportDuration;
        eff.brightness       = v.brightness       != null ? v.brightness       : this.brightness;
        eff.shadowRadius     = v.shadowRadius     != null ? v.shadowRadius     : this.shadowRadius;
        eff.shadowStrength   = v.shadowStrength   != null ? v.shadowStrength   : this.shadowStrength;
        eff.glowColor        = v.glowColor        != null ? v.glowColor        : this.glowColor;
        eff.lineWidth        = v.lineWidth        != null ? v.lineWidth        : this.lineWidth;
        eff.onlyPlayer       = v.onlyPlayer       != null ? v.onlyPlayer       : this.onlyPlayer;
        eff.maxDistance      = v.maxDistance      != null ? v.maxDistance      : this.maxDistance;
        eff.animation        = v.animation        != null ? v.animation        : this.animation;
        eff.numberFormat     = v.numberFormat     != null ? v.numberFormat     : this.numberFormat;
        eff.charReplace      = v.charReplace      != null ? v.charReplace      : this.charReplace;
        // variants 留空：有效配置不递归嵌套变体
        eff.resolve(ctx);
        return eff;
    }

    // ── 便捷方法 ────────────────────────────────────────────────────────────

    /**
     * 以本配置的字段为底，用动画定义中非默认的字段局部覆盖，生成最终
     * {@link DisplaySettings}。
     *
     * <p>优先级：animation 显式值 &gt; IndicatorConfig 值 &gt; IndicatorConfig 默认值。</p>
     * <p>{@code billboard} 与 {@code offset} 始终取自动画定义（动画知道自身的坐标空间需求）。</p>
     *
     * @param anim 动画定义自带的 {@link DisplaySettings}
     */
    public DisplaySettings toDisplaySettings(DisplaySettings anim) {
        DisplaySettings d = DisplaySettings.DEFAULT;
        return new DisplaySettings(
                anim.billboard(),                                                         // 始终取动画
                anim.seeThrough()      != d.seeThrough()      ? anim.seeThrough()      : seeThrough,
                anim.textShadow()      != d.textShadow()      ? anim.textShadow()      : textShadow,
                anim.backgroundColor() != d.backgroundColor() ? anim.backgroundColor() : background,
                anim.viewRange()       != d.viewRange()       ? anim.viewRange()       : viewRange,
                anim.teleportDuration()!= d.teleportDuration()? anim.teleportDuration(): teleportDuration,
                anim.brightness()      != d.brightness()      ? anim.brightness()      : brightness,
                anim.shadowRadius()    != d.shadowRadius()    ? anim.shadowRadius()    : shadowRadius,
                anim.shadowStrength()  != d.shadowStrength()  ? anim.shadowStrength()  : shadowStrength,
                anim.glowColorOverride() != d.glowColorOverride() ? anim.glowColorOverride() : glowColor,
                anim.lineWidth()       != d.lineWidth()       ? anim.lineWidth()       : lineWidth,
                anim.offset()                                                             // 始终取动画
        );
    }

    /**
     * 将数值格式化为最终插入 {@code {damage}} 的字符串。
     * 使用加载期绑定的 {@link ValueFormatter}（quantize + 字符替换）。
     */
    public String formatValue(double value) {
        return formatter.format(value);
    }

    /**
     * 用预解析模板直接构造富文本 Component，运行期不进行任何 MiniMessage 解析。
     *
     * <p>{@link Component#replaceText} 在 Component 树中定位哨兵字符节点，
     * 以 {@code num} 替换其文字内容，同时完整保留该位置的样式继承（颜色、渐变、装饰等）。</p>
     *
     * @param num 已由 {@link #formatValue} 格式化的数值字符串
     */
    public Component buildText(String num) {
        return cachedTemplate.replaceText(b -> b
                .match(VALUE_PATTERN)
                .replacement((m, builder) -> builder.content(num).build()));
    }
}
