package cn.warriorview.configFile;

import cn.warriorview.animation.api.Billboard;
import cn.warriorview.animation.api.IndicatorSpawnStrategy;
import gloomlib.configuration.api.ConfigurationPart;
import gloomlib.configuration.api.annotation.Ignore;

/**
 * {@link IndicatorConfig} 的权限变体定义。
 *
 * <p>每个变体指定一个权限节点 {@link #permission} 和若干可选覆盖字段。
 * 未指定的字段（保持 {@code null}）将继承父 {@link IndicatorConfig} 的对应值。</p>
 *
 * <p>父配置的 {@link IndicatorConfig#resolve} 阶段会将各变体与父配置合并，
 * 生成可直接使用的 {@link #effective}（完整 {@link IndicatorConfig} 副本）。</p>
 *
 * <h3>YAML 示例</h3>
 * <pre>{@code
 * variants:
 *   - permission: "warriorview.indicator.vip"
 *     text-format: "<gold><bold>⚔ {damage}</bold>"
 *     animation: spring_bounce
 *     # 未填写的字段自动继承上级 ENTITY_ATTACK 的配置
 *   - permission: "warriorview.indicator.svip"
 *     text-format: "<rainbow>{damage}</rainbow>"
 * }</pre>
 */
public class IndicatorVariant extends ConfigurationPart {

    /** 触发此变体所需的权限节点（必填）。 */
    public String permission = null;

    // ── 可选覆盖字段（null = 继承父配置） ──────────────────────────────────────
    // 所有基本类型使用装箱形式，以便区分"未指定"（null）与"显式设为默认值"

    public String                 textFormat       = null;
    public Integer                decimalPlaces    = null;
    public IndicatorSpawnStrategy position         = null;
    public Billboard              billboard        = null;
    public Boolean                seeThrough       = null;
    public Boolean                textShadow       = null;
    public Integer                background       = null;
    public Float                  viewRange        = null;
    public Integer                teleportDuration = null;
    public Integer                brightness       = null;
    public Float                  shadowRadius     = null;
    public Float                  shadowStrength   = null;
    public Integer                glowColor        = null;
    public Integer                lineWidth        = null;
    public Boolean                onlyPlayer       = null;
    public Double                 maxDistance      = null;
    public String                 animation        = null;
    public String                 numberFormat     = null;
    public String                 charReplace      = null;

    // ── 运行期解析产物（由父 IndicatorConfig.resolve 注入） ──────────────────

    /**
     * 合并父配置覆盖后的完整配置对象，加载期由 {@link IndicatorConfig#resolve} 填充，
     * 运行期直接使用，无需再次查找注册表。
     */
    @Ignore public transient IndicatorConfig effective = null;
}
