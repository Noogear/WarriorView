package cn.warriorview.configFile;

import gloomlib.configuration.api.ConfigurationFile;
import gloomlib.configuration.api.ConfigurationPart;
import gloomlib.configuration.api.annotation.Header;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

/**
 * 从 {@code message.yml} 加载 MiniMessage 格式的消息模板。
 *
 * <p>继承 {@link ConfigurationFile}，所有字段由 GloomLib 自动从 YAML 反序列化。
 * 嵌套 section 由 {@link ConfigurationPart} 内部类表达。</p>
 */
@Header({
        "WarriorView 消息配置",
        "使用 MiniMessage 格式，占位符用 <placeholder> 表示。"
})
public class MessageConfig extends ConfigurationFile {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    // ── 通用 ─────────────────────────────────────────────────────────────

    public String prefix = "<gold>[WarriorView] ";

    // ── 嵌套 section ────────────────────────────────────────────────────

    public Reload reload = new Reload();
    public ListMessages list = new ListMessages();
    public Info info = new Info();

    public static class Reload extends ConfigurationPart {
        /** 占位符: {@code <elapsed>} */
        public String smartComplete   = "<green>智能重载完成 <gray>(<elapsed>ms)";
        /** 占位符: {@code <module>} */
        public String moduleUpdated   = "  <gray><module>: <yellow>已更新";
        /** 占位符: {@code <module>} */
        public String moduleUnchanged = "  <gray><module>: <dark_gray>无变更";
        /** 占位符: {@code <target>}, {@code <elapsed>} */
        public String complete        = "<green>已重载 <yellow><target> <gray>(<elapsed>ms)";
    }

    public static class ListMessages extends ConfigurationPart {
        /** 占位符: {@code <count>} */
        public String actionsHeader    = "<green>已注册动作 <gray>(<count>)";
        public String mappingsHeader   = "<green>事件映射脚本 <gray>(<count>)";
        public String indicatorsHeader = "<green>指示器标签 <gray>(<count>)";
        public String animationsHeader = "<green>动画定义 <gray>(<count>)";
        /** 占位符: {@code <name>} */
        public String entry            = "  <aqua>• <name>";
    }

    public static class Info extends ConfigurationPart {
        /** 占位符: {@code <version>} */
        public String header        = "  <gold><bold>WarriorView</bold> <yellow><version>";
        public String separator     = "  <dark_gray>─────────────────────────────";
        /** 以下全部使用占位符: {@code <count>} */
        public String animations    = "  <gray>动画定义: <white><count>";
        public String indicators    = "  <gray>指示器标签: <white><count>";
        public String mappings      = "  <gray>事件映射: <white><count>";
        public String actions       = "  <gray>注册动作: <white><count>";
        public String numberFormats = "  <gray>数字格式: <white><count>";
        public String charReplaces  = "  <gray>字符替换: <white><count>";
    }

    // ── MiniMessage 格式化工具 ──────────────────────────────────────────

    /** 将模板字符串解析为 {@link Component}（不附加 prefix）。 */
    public Component format(String template, TagResolver... resolvers) {
        return MM.deserialize(template, resolvers);
    }

    /** 将模板字符串解析为 {@link Component}（附加 prefix）。 */
    public Component prefixed(String template, TagResolver... resolvers) {
        return MM.deserialize(prefix + template, resolvers);
    }

    /** 快捷方法：创建一个不解析的占位符。 */
    public static TagResolver of(String key, String value) {
        return Placeholder.unparsed(key, value);
    }

    /** 快捷方法：创建一个不解析的占位符（int 值）。 */
    public static TagResolver of(String key, int value) {
        return Placeholder.unparsed(key, String.valueOf(value));
    }

    /** 快捷方法：创建一个不解析的占位符（long 值）。 */
    public static TagResolver of(String key, long value) {
        return Placeholder.unparsed(key, String.valueOf(value));
    }
}
