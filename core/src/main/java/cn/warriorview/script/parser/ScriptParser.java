package cn.warriorview.script.parser;

import cn.warriorview.script.core.ScriptIR.FlowNode;
import cn.warriorview.script.core.ScriptIR.FlowNodeType;
import cn.warriorview.script.core.ScriptIR.IRType;
import cn.warriorview.script.core.ScriptIR.ScriptUnit;
import cn.warriorview.script.core.ScriptIR.VarDecl;
import com.google.common.base.CaseFormat;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.primitives.Doubles;
import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;

import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * YAML 脚本解析器，合并入口解析、流程节点分发、值类型推导和属性链解析。
 */
@SuppressWarnings("null")
public final class ScriptParser {

    private static final Yaml YAML = new Yaml();

    /**
     * 解析 YAML 输入流为 IR {@link ScriptUnit}。
     */
    @SuppressWarnings("unchecked")
    public ScriptUnit parse(InputStream input) {
        Map<String, Object> root = YAML.load(input);

        // 顶层字段
        String payloadClassStr = (String) root.get("event");
        int priority = ScriptParser.ValueParser.parseInteger(
                String.valueOf(root.getOrDefault("priority", "0")), 0);

        // 变量声明
        Map<String, String> varMap = (Map<String, String>) root.getOrDefault("variables", Map.of());
        Class<?> payloadClazz;
        try {
            payloadClazz = Class.forName(payloadClassStr);
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("Payload class not found: " + payloadClassStr, e);
        }

        ImmutableList.Builder<VarDecl> vars = ImmutableList.builder();
        for (Map.Entry<String, String> entry : varMap.entrySet()) {
            String name = entry.getKey();
            String property = entry.getValue();
            IRType type = PropertyResolver.resolveType(payloadClazz, property);
            vars.add(new VarDecl(name, property, type));
        }

        // 流程列表
        List<Map<String, Object>> flowList = (List<Map<String, Object>>) root.getOrDefault("flow", List.of());
        ImmutableList.Builder<FlowNode> flow = ImmutableList.builder();
        for (Map<String, Object> nodeMap : flowList) {
            flow.add(parseFlowNode(nodeMap));
        }

        return new ScriptUnit(payloadClassStr, priority, vars.build(), flow.build());
    }

    /**
     * 解析单个流程节点，通过 {@link FlowNodeType} 枚举分发到对应 Handler。
     */
    private FlowNode parseFlowNode(Map<String, Object> yaml) {
        String typeStr = (String) yaml.get("type");
        // 无 type 但有 action 字段 → ACTION
        FlowNodeType type = (typeStr == null && yaml.containsKey("action"))
                ? FlowNodeType.ACTION
                : FlowNodeType.fromYaml(typeStr);
        return type.handler().parse(yaml);
    }

    // ======================== 值解析 ========================

    /**
     * 值类型解析工具。
     */
    public static final class ValueParser {

        /** 模板字符串占位符 */
        private static final Pattern TEMPLATE_PATTERN = Pattern.compile("\\{(\\w+)}");

        private ValueParser() {
        }

        /**
         * 推导值的 IR 类型。
         */
        public static IRType inferType(Object value) {
            if (value == null)
                return IRType.OBJECT;
            if (value instanceof Integer)
                return IRType.INT;
            if (value instanceof Long)
                return IRType.LONG;
            if (value instanceof Double || value instanceof Float)
                return IRType.DOUBLE;
            if (value instanceof Boolean)
                return IRType.BOOLEAN;
            if (value instanceof String s) {
                // 全大写下划线 → 枚举
                if (s.matches("[A-Z][A-Z0-9_]+"))
                    return IRType.ENUM;
                return IRType.STRING;
            }
            return IRType.OBJECT;
        }

        /**
         * 安全解析数字字符串，使用 Guava tryParse 避免异常驱动。
         */
        public static Object parseNumber(String s) {
            Integer i = Ints.tryParse(s);
            if (i != null)
                return i;
            Long l = Longs.tryParse(s);
            if (l != null)
                return l;
            Double d = Doubles.tryParse(s);
            if (d != null)
                return d;
            return s;
        }

        /**
         * 安全解析整数，带默认值。
         */
        public static int parseInteger(String s, int def) {
            if (s == null)
                return def;
            Integer i = Ints.tryParse(s);
            return i != null ? i : def;
        }

        /**
         * 解析模板字符串，提取变量占位符。
         *
         * @return 交替的字面量和变量名列表，例如 ["你好 ", "name", "！"]
         */
        public static List<String> parseTemplate(String template) {
            List<String> parts = new ArrayList<>();
            Matcher matcher = TEMPLATE_PATTERN.matcher(template);
            int last = 0;
            while (matcher.find()) {
                if (matcher.start() > last) {
                    parts.add(template.substring(last, matcher.start()));
                }
                parts.add(matcher.group(1)); // 变量名
                last = matcher.end();
            }
            if (last < template.length()) {
                parts.add(template.substring(last));
            }
            return parts;
        }

        /**
         * 判断字符串是否包含模板占位符。
         */
        public static boolean isTemplate(String s) {
            return TEMPLATE_PATTERN.matcher(s).find();
        }
    }

    // ======================== 属性解析 ========================

    /**
     * 事件属性路径解析器。
     * <p>
     * 将 YAML 变量映射的属性名（如 {@code "entity"}、{@code "damage"}）
     * 解析为事件类的 getter 方法名（如 {@code "getEntity"}、{@code "getDamage"}），
     * 并推导返回类型。
     */
    public static final class PropertyResolver {

        /** getter 签名缓存：key="className#property" → getter Method */
        private static final LoadingCache<String, Method> GETTER_CACHE = CacheBuilder.newBuilder()
                .maximumSize(512)
                .expireAfterAccess(10, TimeUnit.MINUTES)
                .build(new CacheLoader<>() {
                    @Override
                    public Method load(@SuppressWarnings("NullableProblems") String key) throws Exception {
                        int sep = key.indexOf('#');
                        String className = key.substring(0, sep);
                        String property = key.substring(sep + 1);
                        Class<?> clazz = Class.forName(className);
                        return resolveGetter(clazz, property);
                    }
                });

        private PropertyResolver() {
        }

        /**
         * 解析属性的 IR 类型。支持链式属性（以 {@code .} 分隔）。
         */
        public static IRType resolveType(Class<?> payloadClass, String property) {
            Class<?> returnType = resolveReturnClass(payloadClass, property);
            return classToIRType(returnType);
        }

        /**
         * 解析属性的 Java 返回类型。支持链式属性。
         */
        public static Class<?> resolveReturnClass(Class<?> owner, String property) {
            String[] parts = property.split("\\.");
            Class<?> current = owner;
            for (String part : parts) {
                Method getter = getGetter(current, part);
                current = getter.getReturnType();
            }
            return current;
        }

        /**
         * 获取 getter 方法名。
         */
        public static String getGetterName(String property) {
            // Guava CaseFormat: lower_camel → UpperCamel，再加 "get" 前缀
            String capitalized = CaseFormat.LOWER_CAMEL.to(CaseFormat.UPPER_CAMEL, property);
            return "get" + capitalized;
        }

        /**
         * 获取 getter Method（带缓存）。
         */
        public static Method getGetter(Class<?> owner, String property) {
            try {
                return GETTER_CACHE.get(owner.getName() + "#" + property);
            } catch (Exception e) {
                throw new IllegalArgumentException(
                        "Cannot resolve getter for '" + property + "' on " + owner.getName(), e);
            }
        }

        /**
         * 解析 getter 方法（无缓存，内部逻辑）。
         */
        private static Method resolveGetter(Class<?> clazz, String property) throws NoSuchMethodException {
            // 尝试 getXxx
            String getterName = getGetterName(property);
            try {
                return clazz.getMethod(getterName);
            } catch (NoSuchMethodException ignored) {
            }

            // 尝试 isXxx（boolean）
            String isName = "is" + CaseFormat.LOWER_CAMEL.to(CaseFormat.UPPER_CAMEL, property);
            try {
                return clazz.getMethod(isName);
            } catch (NoSuchMethodException ignored) {
            }

            // 尝试直接使用属性名作为方法名（如 "cause" → "getCause"）
            try {
                return clazz.getMethod("get" + Character.toUpperCase(property.charAt(0)) + property.substring(1));
            } catch (NoSuchMethodException ignored) {
            }

            throw new NoSuchMethodException(
                    "No getter found for '" + property + "' on " + clazz.getName());
        }

        /**
         * Java 类型 → IR 类型映射。
         */
        private static IRType classToIRType(Class<?> clazz) {
            if (clazz == int.class || clazz == Integer.class)
                return IRType.INT;
            if (clazz == long.class || clazz == Long.class)
                return IRType.LONG;
            if (clazz == double.class || clazz == Double.class)
                return IRType.DOUBLE;
            if (clazz == float.class || clazz == Float.class)
                return IRType.DOUBLE;
            if (clazz == boolean.class || clazz == Boolean.class)
                return IRType.BOOLEAN;
            if (clazz == String.class)
                return IRType.STRING;
            if (clazz.isEnum())
                return IRType.ENUM;
            return IRType.OBJECT;
        }
    }
}
