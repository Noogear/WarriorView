package cn.warriorview.listener;

import org.objectweb.asm.*;
import org.objectweb.asm.commons.GeneratorAdapter;
import org.objectweb.asm.commons.Method;

import java.util.*;
import java.util.concurrent.atomic.LongAdder;

/**
 * ASM 字节码逻辑编译器。
 * 根据 YAML 配置编译事件处理字节码，通过 {@link org.objectweb.asm.commons.GeneratorAdapter}
 * 生成指令。
 *
 * @see cn.warriorview.listener.JvmTypeHelper
 * @see cn.warriorview.listener.AccessorPathResolver
 * @see cn.warriorview.listener.ConstantFolder
 */
@SuppressWarnings("unchecked")
public class AsmLogicCompiler implements Opcodes {

    // ───────── 类型常量 ─────────

    private static final Type STRING_TYPE = Type.getType(String.class);
    private static final Type OBJECT_TYPE = Type.getType(Object.class);
    private static final Type COLLECTION_TYPE = Type.getType(java.util.Collection.class);
    private static final Type PATTERN_TYPE = Type.getType(java.util.regex.Pattern.class);
    private static final Type MATCHER_TYPE = Type.getType(java.util.regex.Matcher.class);
    private static final Type SB_TYPE = Type.getType(StringBuilder.class);
    private static final Type ENUM_TYPE = Type.getType(Enum.class);
    private static final Type LONG_ADDER_TYPE = Type.getType(LongAdder.class);

    // ───────── 方法常量（消除重复 Method.getMethod 调用） ─────────

    private static final Method TO_STRING = Method.getMethod("String toString ()");
    private static final Method EQUALS = Method.getMethod("boolean equals (Object)");

    // ───────── 构建配置 ─────────

    private final Map<String, Object> config;
    private final Class<?> eventClass;
    private final String internalClassName;
    private final boolean profiling;
    private final LongAdder[] counters;
    private final String generatedInterfaceName;
    private final String actionUtilName;
    private final ConstantFolder constantFolder;

    /** 正则 pattern → 静态字段名（复杂 pattern，需 Pattern.compile） */
    private final Map<String, String> regexFieldMap = new LinkedHashMap<>();
    /** 正则强度降低结果（简单 pattern → String 方法） */
    private final Map<String, ReducedRegex> regexReductions = new HashMap<>();

    // ───────── 正则强度降低：枚举内聚方法签名 ─────────

    /** 简单正则的强度降低结果。枚举值自带目标方法签名和提取后的字面量。 */
    enum ReducedRegex {
        STARTS_WITH("boolean startsWith (String)"),
        ENDS_WITH("boolean endsWith (String)"),
        EQUALS("boolean equals (Object)"),
        CONTAINS("boolean contains (CharSequence)");

        private final Method method;

        ReducedRegex(String methodSig) {
            this.method = Method.getMethod(methodSig);
        }

        /** 从原始 pattern 中提取字面量（去除 ^/$） */
        static String extractLiteral(String pattern) {
            int start = pattern.startsWith("^") ? 1 : 0;
            int end = pattern.endsWith("$") ? pattern.length() - 1 : pattern.length();
            return pattern.substring(start, end);
        }

        /** 分析 pattern 是否可强度降低，不可降低返回 null */
        static ReducedRegex analyze(String pattern) {
            boolean anchorStart = pattern.startsWith("^");
            boolean anchorEnd = pattern.endsWith("$");
            String core = extractLiteral(pattern);

            if (core.isEmpty() || containsRegexMeta(core))
                return null;

            if (anchorStart && anchorEnd)
                return EQUALS;
            if (anchorStart)
                return STARTS_WITH;
            if (anchorEnd)
                return ENDS_WITH;
            return CONTAINS;
        }

        private static boolean containsRegexMeta(String text) {
            for (int i = 0; i < text.length(); i++) {
                if (".+*?[](){}|\\^$".indexOf(text.charAt(i)) >= 0)
                    return true;
            }
            return false;
        }
    }

    // ───────── 构造 ─────────

    public AsmLogicCompiler(Map<String, Object> config, Class<?> eventClass, String className,
            boolean profiling, LongAdder[] counters,
            String generatedInterfaceName, String actionUtilName) {
        this.config = Objects.requireNonNull(config, "config 不能为 null");
        this.eventClass = Objects.requireNonNull(eventClass, "eventClass 不能为 null");
        this.internalClassName = className.replace('.', '/');
        this.profiling = profiling;
        this.counters = counters;
        this.generatedInterfaceName = generatedInterfaceName;
        this.actionUtilName = actionUtilName;
        this.constantFolder = new ConstantFolder();
    }

    // ───────── 编译入口 ─────────

    public byte[] compile() throws Exception {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);

        String[] interfaces = generatedInterfaceName != null
                ? new String[] { generatedInterfaceName }
                : null;
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL, internalClassName, null, "java/lang/Object", interfaces);

        if (profiling) {
            cw.visitField(ACC_PUBLIC | ACC_STATIC, "COUNTERS",
                    "[Ljava/util/concurrent/atomic/LongAdder;", null, null).visitEnd();
        }

        collectRegexPatterns();
        emitPatternFields(cw);
        emitDefaultConstructor(cw);

        Type eventAsmType = Type.getType(eventClass);
        GeneratorAdapter ga = new GeneratorAdapter(ACC_PUBLIC,
                Method.getMethod("void execute (org.bukkit.event.Event)"),
                null, null, cw);
        ga.visitCode();

        AnalysisResult analysis = performStaticAnalysis();

        ga.loadArg(0);
        ga.checkCast(eventAsmType);
        int eventLocal = ga.newLocal(eventAsmType);
        ga.storeLocal(eventLocal);

        List<Map<String, Object>> flows = getFlow();
        optimizeFlowOrder(flows, analysis);

        Label exitLabel = ga.newLabel();
        emitFlow(ga, flows, analysis, exitLabel, eventLocal);

        ga.mark(exitLabel);
        ga.returnValue();
        ga.endMethod();
        cw.visitEnd();

        return cw.toByteArray();
    }

    public Map<String, String> getRegexFieldMap() {
        return Collections.unmodifiableMap(regexFieldMap);
    }

    // ───────── config 辅助 ─────────

    private List<Map<String, Object>> getFlow() {
        return (List<Map<String, Object>>) config.get("flow");
    }

    private void collectRegexPatterns() {
        List<Map<String, Object>> flow = getFlow();
        if (flow == null)
            return;

        int idx = 0;
        for (Map<String, Object> node : flow) {
            if ("check".equals(node.get("type")) && "regex".equals(node.get("op"))) {
                String pattern = String.valueOf(node.get("value"));
                ReducedRegex reduction = ReducedRegex.analyze(pattern);
                if (reduction != null) {
                    regexReductions.put(pattern, reduction);
                } else {
                    regexFieldMap.putIfAbsent(pattern, "REGEX_" + idx++);
                }
            }
        }
    }

    private void emitPatternFields(ClassWriter cw) {
        for (String fieldName : regexFieldMap.values()) {
            cw.visitField(ACC_PUBLIC | ACC_STATIC, fieldName,
                    PATTERN_TYPE.getDescriptor(), null, null).visitEnd();
        }
    }

    // ═══════════════════════════════════════════
    // 第一部分：字节码生成 — 流程编排
    // ═══════════════════════════════════════════

    private void emitFlow(GeneratorAdapter ga, List<Map<String, Object>> flow,
            AnalysisResult analysis, Label exitLabel, int eventLocal) throws Exception {
        Set<Integer> knownNotNull = new HashSet<>();
        for (Map<String, Object> node : flow) {
            switch (String.valueOf(node.get("type"))) {
                case "check" -> emitCheckNode(ga, node, analysis, exitLabel, knownNotNull, eventLocal);
                case "extract" -> emitExtractNode(ga, node, analysis, knownNotNull, eventLocal);
                case "switch" -> emitEnumSwitchTable(ga, node, analysis, exitLabel, eventLocal);
                case "action" -> emitActionInvocation(ga, node, analysis);
            }
        }
    }

    private void emitCheckNode(GeneratorAdapter ga, Map<String, Object> node,
            AnalysisResult analysis, Label exitLabel,
            Set<Integer> knownNotNull, int eventLocal) throws Exception {
        String varName = (String) node.get("variable");
        VariableMeta meta = analysis.vars.get(varName);
        if (meta == null)
            return;

        String op = String.valueOf(node.get("op"));

        Object val;
        if ("is".equals(op)) {
            Object raw = node.get("value");
            val = (raw instanceof String className) ? constantFolder.resolveClass(className) : raw;
        } else {
            val = constantFolder.fold(node.get("value"), meta.type);
        }

        if (isDeadCheck(op, val, meta))
            return;

        if (meta.refCount == 1 && !meta.isCached) {
            emitPathExtraction(ga, meta, eventLocal, false, knownNotNull);
        } else {
            ga.loadLocal(meta.slot);
        }

        int checkId = (int) node.getOrDefault("_check_id", -1);
        Label recordAndFail = (profiling && checkId != -1) ? ga.newLabel() : exitLabel;
        emitTypedComparison(ga, op, val, meta, recordAndFail, knownNotNull);
        emitProfilingBridge(ga, checkId, recordAndFail, exitLabel);
    }

    private void emitExtractNode(GeneratorAdapter ga, Map<String, Object> node,
            AnalysisResult analysis, Set<Integer> knownNotNull,
            int eventLocal) throws Exception {
        String varName = (String) node.get("name");
        VariableMeta meta = analysis.vars.get(varName);
        if (meta == null || !meta.isCached)
            return;

        emitPathExtraction(ga, meta, eventLocal, knownNotNull.contains(meta.slot), knownNotNull);
        ga.storeLocal(meta.slot);
        knownNotNull.add(meta.slot);
    }

    // ═══════════════════════════════════════════
    // 第二部分：字节码生成 — 条件比较
    // ═══════════════════════════════════════════

    private void emitTypedComparison(GeneratorAdapter ga, String op, Object val,
            VariableMeta meta, Label failTarget,
            Set<Integer> knownNotNull) {
        switch (op) {
            case "contains" -> {
                emitContainsCheck(ga, val, meta, failTarget);
                return;
            }
            case "regex" -> {
                String pattern = String.valueOf(val);
                ReducedRegex reduction = regexReductions.get(pattern);
                if (reduction != null) {
                    emitReducedRegex(ga, reduction, pattern, meta, failTarget);
                } else {
                    emitRegexCheck(ga, val, failTarget);
                }
                return;
            }
        }

        if (meta.type == boolean.class) {
            ga.push(((boolean) val) ? 1 : 0);
            ga.ifICmp(GeneratorAdapter.NE, failTarget);
            return;
        }

        if (JvmTypeHelper.isNumericType(meta.type)) {
            emitNumericComparison(ga, op, val, meta.type, failTarget);
            return;
        }

        emitReferenceComparison(ga, op, val, meta, failTarget, knownNotNull);
    }

    private static void emitNumericComparison(GeneratorAdapter ga, String op,
            Object val, Class<?> type, Label failTarget) {
        Type primitiveType = JvmTypeHelper.numericPrimitiveType(type);
        if (!type.isPrimitive())
            ga.unbox(primitiveType);
        pushNumericConstant(ga, val, primitiveType);
        ga.ifCmp(primitiveType, JvmTypeHelper.toCompareMode(op), failTarget);
    }

    private static void pushNumericConstant(GeneratorAdapter ga, Object val, Type targetType) {
        Number num = (Number) val;
        switch (targetType.getSort()) {
            case Type.INT -> ga.push(num.intValue());
            case Type.LONG -> ga.push(num.longValue());
            case Type.FLOAT -> ga.push(num.floatValue());
            case Type.DOUBLE -> ga.push(num.doubleValue());
            default -> ga.push(num.intValue());
        }
    }

    private void emitReferenceComparison(GeneratorAdapter ga, String op, Object val,
            VariableMeta meta, Label failTarget,
            Set<Integer> knownNotNull) {
        switch (op) {
            case "not_null" -> {
                if (knownNotNull.contains(meta.slot))
                    return;
                ga.ifNull(failTarget);
                if (meta.isCached)
                    knownNotNull.add(meta.slot);
            }
            case "is" -> {
                Class<?> targetClass = (Class<?>) val;
                ga.instanceOf(Type.getType(targetClass));
                emitBooleanGuard(ga, failTarget);
                meta.narrowedType = targetClass;
                if (meta.isCached)
                    knownNotNull.add(meta.slot);
            }
            default -> {
                ga.push(String.valueOf(val));
                ga.invokeVirtual(OBJECT_TYPE, EQUALS);
                emitBooleanGuard(ga, failTarget);
            }
        }
    }

    private void emitContainsCheck(GeneratorAdapter ga, Object val,
            VariableMeta meta, Label failTarget) {
        String targetStr = String.valueOf(val);

        if (String.class.isAssignableFrom(meta.type) || CharSequence.class.isAssignableFrom(meta.type)) {
            ga.push(targetStr);
            ga.invokeVirtual(STRING_TYPE, Method.getMethod("boolean contains (CharSequence)"));
        } else if (Collection.class.isAssignableFrom(meta.type)) {
            ga.push(targetStr);
            ga.invokeInterface(COLLECTION_TYPE, Method.getMethod("boolean contains (Object)"));
        } else {
            emitToString(ga, meta);
            ga.push(targetStr);
            ga.invokeVirtual(STRING_TYPE, Method.getMethod("boolean contains (CharSequence)"));
        }
        emitBooleanGuard(ga, failTarget);
    }

    private void emitRegexCheck(GeneratorAdapter ga, Object val, Label failTarget) {
        String pattern = String.valueOf(val);
        ga.invokeVirtual(OBJECT_TYPE, TO_STRING);
        ga.getStatic(Type.getObjectType(internalClassName), regexFieldMap.get(pattern), PATTERN_TYPE);
        ga.swap();
        ga.invokeVirtual(PATTERN_TYPE, Method.getMethod("java.util.regex.Matcher matcher (CharSequence)"));
        ga.invokeVirtual(MATCHER_TYPE, Method.getMethod("boolean find ()"));
        emitBooleanGuard(ga, failTarget);
    }

    private void emitReducedRegex(GeneratorAdapter ga, ReducedRegex reduction,
            String pattern, VariableMeta meta, Label failTarget) {
        emitToString(ga, meta);
        ga.push(ReducedRegex.extractLiteral(pattern));
        ga.invokeVirtual(STRING_TYPE, reduction.method);
        emitBooleanGuard(ga, failTarget);
    }

    // ───────── 公共字节码片段 ─────────

    /** 栈顶值不是 String/CharSequence 时，插入 toString 调用 */
    private static void emitToString(GeneratorAdapter ga, VariableMeta meta) {
        if (!String.class.isAssignableFrom(meta.type)
                && !CharSequence.class.isAssignableFrom(meta.type)) {
            ga.invokeVirtual(OBJECT_TYPE, TO_STRING);
        }
    }

    /** 栈顶 boolean → 为 false 时跳转 failTarget */
    private static void emitBooleanGuard(GeneratorAdapter ga, Label failTarget) {
        ga.ifZCmp(GeneratorAdapter.EQ, failTarget);
    }

    private void emitProfilingBridge(GeneratorAdapter ga, int checkId,
            Label recordAndFail, Label failLabel) {
        if (!profiling || checkId == -1)
            return;

        Label skip = ga.newLabel();
        ga.goTo(skip);
        ga.mark(recordAndFail);

        ga.getStatic(Type.getObjectType(internalClassName), "COUNTERS",
                Type.getType(LongAdder[].class));
        ga.push(checkId);
        ga.arrayLoad(LONG_ADDER_TYPE);
        ga.invokeVirtual(LONG_ADDER_TYPE, Method.getMethod("void increment ()"));

        ga.goTo(failLabel);
        ga.mark(skip);
    }

    // ═══════════════════════════════════════════
    // 第三部分：字节码生成 — 路径提取 / 枚举 Switch / Action
    // ═══════════════════════════════════════════

    private void emitPathExtraction(GeneratorAdapter ga, VariableMeta meta,
            int eventLocal, boolean skipFirstNullCheck,
            Set<Integer> knownNotNull) {
        Label nullExit = ga.newLabel();
        Label done = ga.newLabel();

        int startStep = 0;
        if (meta.parentVar != null && meta.parentVar.isCached && meta.parentPrefixLen > 0) {
            ga.loadLocal(meta.parentVar.slot);
            startStep = meta.parentPrefixLen;
            skipFirstNullCheck = knownNotNull.contains(meta.parentVar.slot);
        } else {
            ga.loadLocal(eventLocal);
        }

        for (int i = startStep; i < meta.steps.size(); i++) {
            AccessorPathResolver.PathStep step = meta.steps.get(i);

            boolean isLastStep = (i == meta.steps.size() - 1);
            boolean returnsPrimitive = !isLastStep && step.returnType().isPrimitive();
            boolean shouldNullCheck = !(i == startStep && skipFirstNullCheck) && !returnsPrimitive;

            if (shouldNullCheck) {
                ga.dup();
                ga.ifNull(nullExit);
            }

            Class<?> callOwner = (i == 0 && meta.parentVar != null && meta.parentVar.narrowedType != null)
                    ? meta.parentVar.narrowedType
                    : step.owner();
            Type ownerType = Type.getType(callOwner);

            if (step.isField()) {
                ga.getField(ownerType, step.name(), Type.getType(step.returnType()));
            } else {
                ga.invokeVirtual(ownerType, new Method(step.name(), step.descriptor()));
            }
        }

        ga.goTo(done);
        ga.mark(nullExit);
        ga.pop();
        emitDefaultValue(ga, meta.type);
        ga.mark(done);
    }

    /** 压入类型的默认零值 */
    private static void emitDefaultValue(GeneratorAdapter ga, Class<?> type) {
        if (type.isPrimitive()) {
            switch (Type.getType(type).getSort()) {
                case Type.DOUBLE -> ga.push(0.0);
                case Type.FLOAT -> ga.push(0.0f);
                case Type.LONG -> ga.push(0L);
                default -> ga.push(0);
            }
        } else {
            ga.visitInsn(ACONST_NULL);
        }
    }

    private void emitEnumSwitchTable(GeneratorAdapter ga, Map<String, Object> node,
            AnalysisResult analysis, Label globalExit, int eventLocal) throws Exception {
        VariableMeta meta = analysis.vars.get(node.get("variable"));
        if (meta == null || !meta.type.isEnum())
            return;

        Enum<?>[] enumConstants = (Enum<?>[]) meta.type.getMethod("values").invoke(null);
        Map<String, List<Map<String, Object>>> cases = (Map<String, List<Map<String, Object>>>) node.get("cases");

        Label defaultLabel = ga.newLabel();
        Label endLabel = ga.newLabel();
        Label[] caseLabels = new Label[enumConstants.length];
        for (int i = 0; i < enumConstants.length; i++) {
            caseLabels[i] = cases.containsKey(enumConstants[i].name()) ? ga.newLabel() : defaultLabel;
        }

        ga.loadLocal(meta.slot);
        ga.dup();
        ga.ifNull(defaultLabel);
        ga.invokeVirtual(ENUM_TYPE, Method.getMethod("int ordinal ()"));
        ga.visitTableSwitchInsn(0, enumConstants.length - 1, defaultLabel, caseLabels);

        for (int i = 0; i < enumConstants.length; i++) {
            if (caseLabels[i] != defaultLabel) {
                ga.mark(caseLabels[i]);
                emitFlow(ga, cases.get(enumConstants[i].name()), analysis, globalExit, eventLocal);
                ga.goTo(endLabel);
            }
        }

        ga.mark(defaultLabel);
        ga.pop();
        ga.mark(endLabel);
    }

    private void emitActionInvocation(GeneratorAdapter ga, Map<String, Object> node,
            AnalysisResult analysis) {
        String actionName = (String) node.get("name");
        List<String> args = (List<String>) node.get("args");
        Type actionUtilType = Type.getObjectType(actionUtilName);

        ga.invokeStatic(actionUtilType,
                Method.getMethod("StringBuilder getThreadLocalBuilder ()"));
        ga.dup();
        ga.push(0);
        ga.invokeVirtual(SB_TYPE, Method.getMethod("void setLength (int)"));

        for (String arg : args) {
            ga.dup();
            if (arg.contains("{")) {
                String varName = arg.substring(arg.indexOf('{') + 1, arg.indexOf('}'));
                VariableMeta vMeta = analysis.vars.get(varName);
                ga.loadLocal(vMeta.slot);
                ga.invokeVirtual(SB_TYPE,
                        new Method("append", JvmTypeHelper.appendDescriptor(vMeta.type)));
            } else {
                ga.push(arg);
                ga.invokeVirtual(SB_TYPE, Method.getMethod("StringBuilder append (String)"));
            }
            ga.pop();
        }

        ga.invokeVirtual(SB_TYPE, TO_STRING);
        ga.loadArg(0);
        ga.swap();
        ga.invokeStatic(actionUtilType,
                new Method(actionName, "(Lorg/bukkit/event/Event;Ljava/lang/String;)V"));
    }

    private void emitDefaultConstructor(ClassWriter cw) {
        GeneratorAdapter ga = new GeneratorAdapter(ACC_PUBLIC,
                Method.getMethod("void <init> ()"), null, null, cw);
        ga.visitCode();
        ga.loadThis();
        ga.invokeConstructor(OBJECT_TYPE, Method.getMethod("void <init> ()"));
        ga.returnValue();
        ga.endMethod();
    }

    // ═══════════════════════════════════════════
    // 第四部分：静态分析
    // ═══════════════════════════════════════════

    private AnalysisResult performStaticAnalysis() throws Exception {
        AnalysisResult result = new AnalysisResult();
        Map<String, String> varConfigs = (Map<String, String>) config.get("variables");
        List<Map<String, Object>> flow = getFlow();

        int checkIdx = 0;
        for (Map<String, Object> node : flow) {
            String nodeType = String.valueOf(node.get("type"));
            if ("check".equals(nodeType)) {
                node.put("_check_id", checkIdx++);
                result.incrementRefCount((String) node.get("variable"));
            } else if ("action".equals(nodeType)) {
                List<String> args = (List<String>) node.get("args");
                for (String arg : args) {
                    if (arg.contains("{")) {
                        result.incrementRefCount(arg.substring(arg.indexOf('{') + 1, arg.indexOf('}')));
                    }
                }
            }
        }

        int nextSlot = 3;
        for (Map.Entry<String, String> entry : varConfigs.entrySet()) {
            String name = entry.getKey();
            if (result.getRefCount(name) <= 0)
                continue;

            AccessorPathResolver.ResolveResult resolved = AccessorPathResolver.resolve(eventClass, entry.getValue());

            VariableMeta meta = new VariableMeta(entry.getValue(), resolved);
            meta.refCount = result.getRefCount(name);

            for (VariableMeta other : result.vars.values()) {
                if (meta.fullPath.startsWith(other.fullPath + ".")) {
                    meta.parentVar = other;
                    meta.parentPrefixLen = other.steps.size();
                }
            }

            meta.isCached = meta.refCount > 1
                    || isParentPath(meta.fullPath, varConfigs.values());
            if (meta.isCached) {
                meta.slot = nextSlot;
                Type slotType = meta.type.isPrimitive()
                        ? Type.getType(meta.type)
                        : OBJECT_TYPE;
                nextSlot += slotType.getSize();
            }

            result.vars.put(name, meta);
        }

        return result;
    }

    // ═══════════════════════════════════════════
    // 第五部分：流程优化 — 拓扑排序 + PGO 二级排序
    // ═══════════════════════════════════════════

    private void optimizeFlowOrder(List<Map<String, Object>> flows, AnalysisResult analysis) {
        if (flows == null || flows.size() <= 1)
            return;

        int n = flows.size();
        List<List<Integer>> adjacency = new ArrayList<>(n);
        int[] inDegree = new int[n];
        for (int i = 0; i < n; i++)
            adjacency.add(new ArrayList<>());

        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (i != j && hasDependency(flows.get(i), flows.get(j))) {
                    adjacency.get(i).add(j);
                    inDegree[j]++;
                }
            }
        }

        PriorityQueue<Integer> ready = new PriorityQueue<>((a, b) -> {
            if (!profiling && counters != null) {
                int id1 = (int) flows.get(a).getOrDefault("_check_id", -1);
                int id2 = (int) flows.get(b).getOrDefault("_check_id", -1);
                if (id1 != -1 && id2 != -1) {
                    long f1 = counters[id1].sum();
                    long f2 = counters[id2].sum();
                    if (f1 != f2)
                        return Long.compare(f2, f1);
                }
            }
            return Integer.compare(getNodeWeight(flows.get(a)), getNodeWeight(flows.get(b)));
        });

        for (int i = 0; i < n; i++) {
            if (inDegree[i] == 0)
                ready.add(i);
        }

        List<Map<String, Object>> sorted = new ArrayList<>(n);
        while (!ready.isEmpty()) {
            int current = ready.poll();
            sorted.add(flows.get(current));
            for (int dependent : adjacency.get(current)) {
                if (--inDegree[dependent] == 0)
                    ready.add(dependent);
            }
        }

        if (sorted.size() < n) {
            Set<Map<String, Object>> included = Collections.newSetFromMap(new IdentityHashMap<>());
            included.addAll(sorted);
            for (Map<String, Object> node : flows) {
                if (!included.contains(node))
                    sorted.add(node);
            }
        }

        flows.clear();
        flows.addAll(sorted);
    }

    private static int getNodeWeight(Map<String, Object> node) {
        return switch (String.valueOf(node.get("op")).toLowerCase()) {
            case "null", "not_null" -> 1;
            case "is" -> 2;
            case "==", "!=", ">", ">=", "<", "<=" -> 3;
            case "contains" -> 10;
            case "regex" -> 20;
            default -> 5;
        };
    }

    private static boolean hasDependency(Map<String, Object> producer, Map<String, Object> consumer) {
        String outputVar = (String) producer.get("name");
        String inputVar = (String) consumer.get("variable");
        return outputVar != null && outputVar.equals(inputVar);
    }

    private static boolean isParentPath(String path, Collection<String> allPaths) {
        String prefix = path + ".";
        return allPaths.stream().anyMatch(p -> p.startsWith(prefix));
    }

    // ═══════════════════════════════════════════
    // DCE
    // ═══════════════════════════════════════════

    private static boolean isDeadCheck(String op, Object val, VariableMeta meta) {
        return switch (op) {
            case "not_null" -> meta.type.isPrimitive();
            case "is" -> (val instanceof Class<?> target) && target.isAssignableFrom(meta.type);
            default -> false;
        };
    }

    // ═══════════════════════════════════════════
    // 内部数据模型
    // ═══════════════════════════════════════════

    static class VariableMeta {
        final String fullPath;
        final List<AccessorPathResolver.PathStep> steps;
        final Class<?> type;
        int slot;
        int refCount;
        Class<?> narrowedType;
        VariableMeta parentVar;
        int parentPrefixLen;
        boolean isCached;

        VariableMeta(String path, AccessorPathResolver.ResolveResult resolved) {
            this.fullPath = path;
            this.steps = resolved.steps();
            this.type = resolved.finalType();
        }
    }

    static class AnalysisResult {
        final Map<String, VariableMeta> vars = new LinkedHashMap<>();
        private final Map<String, Integer> refCounts = new HashMap<>();

        void incrementRefCount(String name) {
            refCounts.merge(name, 1, Integer::sum);
        }

        int getRefCount(String name) {
            return refCounts.getOrDefault(name, 0);
        }
    }
}