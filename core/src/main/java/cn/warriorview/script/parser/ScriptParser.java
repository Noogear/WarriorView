package cn.warriorview.script.parser;

import cn.warriorview.script.core.ScriptIR;
import cn.warriorview.script.core.ScriptIR.FlowNode;
import cn.warriorview.script.core.ScriptIR.FlowNodeType;
import cn.warriorview.script.core.ScriptIR.IRType;
import cn.warriorview.script.core.ScriptIR.ScriptUnit;
import cn.warriorview.script.core.ScriptIR.VarDecl;
import cn.warriorview.script.parser.accessor.PropertyAccessor;
import com.google.common.base.CaseFormat;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.primitives.Doubles;
import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;
import com.google.common.reflect.TypeToken;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * YAML 脚本解析器，合并入口解析、流程节点分发、值类型推导和属性链解析。
 */
@SuppressWarnings("null")
public final class ScriptParser {

    /**
     * 将预先解析好的 Map 数据结构转换为强类型的 IR {@link ScriptUnit}。
     * 消除对具体序列化格式（如 YAML/JSON）的依赖，数据可由宿主环境（如 Bukkit Configuration）提供。
     *
     * @param root 包含 event、priority、variables、flow 键的核心 Map
     */
    @SuppressWarnings("unchecked")
    public static ScriptUnit parse(Map<String, Object> root) {
        // 顶层字段
        String idStr = String.valueOf(root.getOrDefault("id", "AnonymousScript"));
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

        return new ScriptUnit(idStr, payloadClassStr, priority, vars.build(), parseFlowNodes(flowList));
    }

    /**
     * 解析单个流程节点，通过 {@link FlowNodeType} 枚举分发到对应 Handler。
     * <p>
     * 支持短语法：
     * <ul>
     * <li>无 {@code type} 且有 {@code action} 字段 → ACTION</li>
     * <li>有 {@code return} 字段 → RETURN_VALUE（即 {@code - return: 42}）</li>
     * </ul>
     */
    public static FlowNode parseFlowNode(Map<String, Object> yaml) {
        String typeStr = (String) yaml.get("type");
        FlowNodeType type = null;

        if (typeStr == null) {
            if (yaml.containsKey("action")) {
                type = FlowNodeType.ACTION;
            } else if (yaml.containsKey("return")) {
                type = FlowNodeType.RETURN;
            } else if (yaml.containsKey("check")) {
                type = FlowNodeType.CHECK;
                Map<String, Object> rebuilt = new java.util.HashMap<>(yaml);
                Object variable = rebuilt.remove("check");
                if (variable != null)
                    rebuilt.put("variable", variable);
                yaml = rebuilt;
            } else if (yaml.containsKey("switch")) {
                type = FlowNodeType.SWITCH;
                Map<String, Object> rebuilt = new java.util.HashMap<>(yaml);
                Object variable = rebuilt.remove("switch");
                if (variable != null)
                    rebuilt.put("variable", variable);
                yaml = rebuilt;
            } else if (yaml.containsKey("any")) {
                type = FlowNodeType.ANY;
            } else if (yaml.containsKey("all")) {
                type = FlowNodeType.ALL;
            } else {
                // 启用动态推断：寻找第一个非保留字段作为 Action 名字
                String inferredAction = null;
                Object inferredArgs = null;
                for (Map.Entry<String, Object> entry : yaml.entrySet()) {
                    String k = entry.getKey();
                    if (!k.equals("store") && !k.equals("args") && !k.equals("type") && !k.equals("return")) {
                        inferredAction = k;
                        inferredArgs = entry.getValue();
                        break;
                    }
                }

                if (inferredAction != null) {
                    Map<String, Object> rebuilt = new java.util.HashMap<>(yaml);
                    rebuilt.remove(inferredAction);
                    rebuilt.put("action", inferredAction);
                    if (inferredArgs instanceof List) {
                        rebuilt.put("args", inferredArgs);
                    } else if (inferredArgs != null) {
                        rebuilt.put("args", List.of(inferredArgs));
                    }
                    yaml = rebuilt;
                    type = FlowNodeType.ACTION;
                }
            }
        }

        if (type == null) {
            type = FlowNodeType.fromYaml(typeStr);
        }
        return type.handler().parse(yaml);
    }

    /**
     * 解析反序列化出的 List 形式的流程节点。
     * 用于非完整 ScriptUnit 场景下的局部逻辑 AST 构建。
     */
    public static ImmutableList<FlowNode> parseFlow(List<?> flowList) {
        if (flowList == null) {
            return ImmutableList.of();
        }
        return parseFlowNodes(flowList);
    }

    /**
     * 将 YAML 中反序列化出来的节点列表转换为强类型的 AST 节点列表。
     * <p>
     * 支持 {@code - return} 纯字符串简写（SnakeYAML 将其解析为 String）。
     */
    @SuppressWarnings("unchecked")
    private static ImmutableList<FlowNode> parseFlowNodes(List<?> flowList) {
        if (flowList == null || flowList.isEmpty()) {
            return ImmutableList.of();
        }
        ImmutableList.Builder<FlowNode> flow = ImmutableList.builder();
        for (Object item : flowList) {
            if (item instanceof String s) {
                if (s.equalsIgnoreCase("return")) {
                    // 对应 YAML 中的 "- return"（空返回）
                    flow.add(FlowNodeType.RETURN.handler().parse(Map.of()));
                } else {
                    // 新增：自动包装纯字符串动作 (例如 "- healAllPlayers")
                    flow.add(parseFlowNode(Map.of("action", s)));
                }
            } else if (item instanceof Map<?, ?> rawMap) {
                flow.add(parseFlowNode((Map<String, Object>) rawMap));
            } else {
                throw new IllegalArgumentException("Unexpected flow node type: " + item);
            }
        }
        return flow.build();
    }

    // ======================== 值解析 ========================

    /**
     * 值类型解析工具。
     */
    public static final class ValueParser {

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

        private static final Pattern INDEX_PATTERN = Pattern.compile("(.+)\\[(.+)\\]");

        private PropertyResolver() {
        }

        /**
         * 提取属性链的根基名称。
         * 例如从 "player.inventory[0]" 提取出 "player"。
         * 专门用于给编译器进行同祖先对象的局部变量缓冲优化。
         */
        public static String getRootProperty(String propertyPath) {
            int dotIdx = propertyPath.indexOf('.');
            int bracketIdx = propertyPath.indexOf('[');

            int splitIdx = -1;
            if (dotIdx != -1 && bracketIdx != -1) {
                splitIdx = Math.min(dotIdx, bracketIdx);
            } else if (dotIdx != -1) {
                splitIdx = dotIdx;
            } else if (bracketIdx != -1) {
                splitIdx = bracketIdx;
            }

            return (splitIdx == -1) ? propertyPath : propertyPath.substring(0, splitIdx);
        }

        /**
         * 解析属性的 IR 类型。支持链式属性（以 {@code .} 分隔）和集合/Map索引（如 list[0] 或 map[key]）。
         */
        public static IRType resolveType(Class<?> payloadClass, String property) {
            TypeToken<?> currentType = TypeToken.of(payloadClass);
            List<PropertyAccessor> accessors = resolveAccessors(currentType, property);
            if (accessors.isEmpty()) {
                return ScriptIR.IRType.fromClass(payloadClass);
            }
            return ScriptIR.IRType.fromToken(accessors.get(accessors.size() - 1).returnType());
        }

        /**
         * 解析属性为一系列的 PropertyAccessor 指令集，保留了全泛型分析链。
         */
        public static List<PropertyAccessor> resolveAccessors(TypeToken<?> ownerType, String property) {
            List<PropertyAccessor> result = new ArrayList<>();
            Iterable<String> parts = Splitter.on('.').split(property);
            TypeToken<?> currentType = ownerType;

            for (String part : parts) {
                Matcher matcher = INDEX_PATTERN.matcher(part);
                if (matcher.matches()) {
                    // 形如: inventory[0] 或 metadata[key]
                    String baseProp = matcher.group(1);
                    String indexStr = matcher.group(2);

                    // 1. 先解析基础属性
                    Method baseGetter = resolveGetter(currentType.getRawType(), baseProp);
                    TypeToken<?> baseType = currentType.resolveType(baseGetter.getGenericReturnType());
                    result.add(new cn.warriorview.script.parser.accessor.MethodAccessor(baseGetter, baseType));
                    currentType = baseType;

                    // 2. 解析索引部分
                    if (List.class.isAssignableFrom(currentType.getRawType())) {
                        int index = Integer.parseInt(indexStr);
                        TypeToken<?> elementType = extractListType(currentType);
                        result.add(new cn.warriorview.script.parser.accessor.ListAccessor(index, elementType));
                        currentType = elementType;
                    } else if (Map.class.isAssignableFrom(currentType.getRawType())) {
                        // 简单处理：去推断 Map 的 V
                        TypeToken<?> valueType = extractMapValueType(currentType);

                        // 由于 YAML 传入的 key 是字符串，我们在 AST 中按 String 类型对待
                        // 若是纯数字且去引号的可以再处理，但作为 propertyPath 字符串，我们直接注入 String 键
                        String key = indexStr;
                        // 支持剥离单双引号（例如 metadata['damage_all']）
                        if ((key.startsWith("'") && key.endsWith("'"))
                                || (key.startsWith("\"") && key.endsWith("\""))) {
                            key = key.substring(1, key.length() - 1);
                        }

                        result.add(new cn.warriorview.script.parser.accessor.MapAccessor(key, valueType));
                        currentType = valueType;
                    } else {
                        throw new IllegalArgumentException(
                                "Type " + currentType + " is not a supported collection for indexing: " + part);
                    }
                } else {
                    // 普通属性
                    Method getter = resolveGetter(currentType.getRawType(), part);
                    currentType = currentType.resolveType(getter.getGenericReturnType());
                    result.add(new cn.warriorview.script.parser.accessor.MethodAccessor(getter, currentType));
                }
            }
            return result;
        }

        private static TypeToken<?> extractListType(TypeToken<?> listType) {
            try {
                // List<E> -> 获取 E
                java.lang.reflect.TypeVariable<?> param = List.class.getTypeParameters()[0];
                return listType.resolveType(param);
            } catch (Exception e) {
                return TypeToken.of(Object.class);
            }
        }

        private static TypeToken<?> extractMapValueType(TypeToken<?> mapType) {
            try {
                // Map<K, V> -> 获取 V
                java.lang.reflect.TypeVariable<?> param = Map.class.getTypeParameters()[1];
                return mapType.resolveType(param);
            } catch (Exception e) {
                return TypeToken.of(Object.class);
            }
        }

        /**
         * 获取 getter 方法名。
         */
        public static String getGetterName(String property) {
            String capitalized = CaseFormat.LOWER_CAMEL.to(CaseFormat.UPPER_CAMEL, property);
            return "get" + capitalized;
        }

        /**
         * 解析 getter 方法。
         */
        public static Method resolveGetter(Class<?> clazz, String property) {
            String getterName = getGetterName(property);
            try {
                return clazz.getMethod(getterName);
            } catch (NoSuchMethodException ignored) {
            }

            String isName = "is" + CaseFormat.LOWER_CAMEL.to(CaseFormat.UPPER_CAMEL, property);
            try {
                return clazz.getMethod(isName);
            } catch (NoSuchMethodException ignored) {
            }

            try {
                return clazz.getMethod("get" + Character.toUpperCase(property.charAt(0)) + property.substring(1));
            } catch (NoSuchMethodException ignored) {
            }

            // 最后尝试 Record 组件或 Fluent API 风格纯同名访问器 (例如: record.name())
            try {
                Method fallback = clazz.getMethod(property);
                // 必须过滤掉无返回值的普通方法，防止被当成 getter 从而在求值时造成执行副作用（如 clear() 等）
                if (fallback.getReturnType() != void.class) {
                    return fallback;
                }
            } catch (NoSuchMethodException ignored) {
            }

            throw new IllegalArgumentException("No getter found for '" + property + "' on " + clazz.getName());
        }

    }
}
