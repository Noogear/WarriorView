package cn.warriorview.animation.load;

import org.bukkit.configuration.ConfigurationSection;

import java.util.Map;

/**
 * Holds a raw animation template definition before parameter substitution.
 *
 * <p>Templates are <em>not</em> registered into the {@code AnimationRegistry}.
 * They exist only during the loading phase and are expanded inline when
 * referenced via {@code type: preset} + {@code id: <template_name>}.</p>
 *
 * @param name       unique template identifier
 * @param rawSection the original YAML section (deep-copied before each expansion)
 * @param defaults   parameter name → default value (String or Number)
 */
public record TemplateEntry(
        String                name,
        ConfigurationSection  rawSection,
        Map<String, Object>   defaults
) {}
