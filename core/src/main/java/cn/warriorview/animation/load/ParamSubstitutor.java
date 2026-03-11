package cn.warriorview.animation.load;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recursively substitutes {@code $param_name} references in a
 * {@link ConfigurationSection} tree using a provided parameter map.
 *
 * <p>Two substitution modes are applied automatically:</p>
 * <ul>
 *   <li><b>Exact match</b> – the entire YAML value is exactly {@code $param_name}.
 *       The value is replaced with the parameter's original typed value
 *       (Number, String, etc.), preserving the type for downstream parsers.</li>
 *   <li><b>Inline interpolation</b> – {@code $param_name} appears inside a larger
 *       string ({@code "sin(t * $speed)"}). Each occurrence is replaced with
 *       the parameter value's {@code toString()}, and the result remains a String.</li>
 * </ul>
 *
 * <p>All substitution happens at load time.  The resulting section is
 * indistinguishable from a hand-written one, so downstream parsers
 * ({@code KeyframeParser}, {@code EquationParser}) work unchanged.</p>
 */
public final class ParamSubstitutor {

    /** Matches a standalone param reference: entire value is {@code $name}. */
    private static final Pattern EXACT = Pattern.compile("^\\$([a-zA-Z_]\\w*)$");

    /** Matches any inline {@code $name} occurrence inside a larger string. */
    private static final Pattern INLINE = Pattern.compile("\\$([a-zA-Z_]\\w*)");

    private ParamSubstitutor() {}

    /**
     * Substitutes all {@code $param} references in {@code section} in-place.
     *
     * @param section the configuration section to process (modified in-place)
     * @param params  parameter name → value map
     */
    public static void substitute(ConfigurationSection section, Map<String, Object> params) {
        if (params.isEmpty()) return;
        for (String key : section.getKeys(false)) {
            Object value = section.get(key);
            Object replaced = substituteValue(value, params);
            if (replaced != value) {
                section.set(key, replaced);
            }
        }
    }

    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static Object substituteValue(Object value, Map<String, Object> params) {
        if (value instanceof String s) {
            return substituteString(s, params);
        }
        if (value instanceof ConfigurationSection cs) {
            substitute(cs, params);
            return cs;
        }
        if (value instanceof List<?> list) {
            return substituteList((List<Object>) list, params);
        }
        if (value instanceof Map<?, ?> map) {
            return substituteMap((Map<String, Object>) map, params);
        }
        // Numbers, booleans, null — no substitution needed
        return value;
    }

    private static Object substituteString(String s, Map<String, Object> params) {
        // Exact match: "$param_name" as the entire value → type-preserving replace
        Matcher exact = EXACT.matcher(s);
        if (exact.matches()) {
            String paramName = exact.group(1);
            Object val = params.get(paramName);
            return val != null ? val : s;
        }

        // Inline interpolation: replace each $name inside a larger string
        if (!s.contains("$")) return s;
        Matcher m = INLINE.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String paramName = m.group(1);
            Object val = params.get(paramName);
            m.appendReplacement(sb, Matcher.quoteReplacement(val != null ? val.toString() : m.group()));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static List<Object> substituteList(List<Object> list, Map<String, Object> params) {
        for (int i = 0, size = list.size(); i < size; i++) {
            Object item = list.get(i);
            Object replaced = substituteValue(item, params);
            if (replaced != item) {
                // First change found — copy preceding items and continue in-place
                List<Object> result = new ArrayList<>(size);
                for (int j = 0; j < i; j++) result.add(list.get(j));
                result.add(replaced);
                for (int j = i + 1; j < size; j++) {
                    result.add(substituteValue(list.get(j), params));
                }
                return result;
            }
        }
        return list;
    }

    private static Map<String, Object> substituteMap(Map<String, Object> map, Map<String, Object> params) {
        for (var entry : map.entrySet()) {
            Object replaced = substituteValue(entry.getValue(), params);
            if (replaced != entry.getValue()) {
                // First change found — build a new map with all entries substituted
                var result = new java.util.LinkedHashMap<String, Object>(map.size());
                for (var e : map.entrySet()) {
                    result.put(e.getKey(), substituteValue(e.getValue(), params));
                }
                return result;
            }
        }
        return map;
    }
}
