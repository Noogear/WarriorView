package cn.warriorview.script.core;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableList;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.objectweb.asm.Type;

/**
 * 编译上下文，管理变量槽位分配、类型信息传播、常量池和反射缓存。
 * <p>
 * 每次编译一个 {@link ScriptIR.ScriptUnit} 时创建一个实例。
 */
@SuppressWarnings("null")
public final class CompilationContext {

    /** 变量名 ↔ 局部变量槽位（双向映射） */
    private final ImmutableBiMap<String, Integer> varSlots;

    /** payload 别名 → slot 1（别名与 payload 共享同一槽位，不能放入 BiMap） */
    private final ImmutableMap<String, Integer> aliasSlots;

    /** 变量名 → IR 类型 */
    private final ImmutableMap<String, ScriptIR.IRType> typeTable;

    /** 编译时已知常量 */
    private final ImmutableMap<String, Object> constants;

    /** 载荷类（如 Event） */
    private final Class<?> payloadClass;

    /** getter 方法反射缓存（跨编译复用） */
    private static final LoadingCache<String, MethodHandle> METHOD_CACHE = CacheBuilder.newBuilder()
            .maximumSize(256)
            .expireAfterAccess(10, TimeUnit.MINUTES)
            .build(new CacheLoader<>() {
                @Override
                public MethodHandle load(String key) throws Exception {
                    // key 格式："className#methodName"
                    int sep = key.indexOf('#');
                    String className = key.substring(0, sep);
                    String methodName = key.substring(sep + 1);
                    Class<?> clazz = Class.forName(className);
                    Method method = clazz.getMethod(methodName);
                    return MethodHandles.lookup().unreflect(method);
                }
            });

    /** PGO 分支权重数据（可选） */
    private final Map<String, double[]> branchWeights = new HashMap<>();

    /**
     * emit 阶段维护的变量类型窄化表。
     * 当某变量通过 {@code check: op: instanceof} 后，编译器在成功路径上将目标类写入此表，
     * 后续节点 emit 时可利用窄化类型发射更精确的 CHECKCAST 并解析子类属性链。
     * <p>
     * 使用 {@link #snapshotNarrowed()} / {@link #restoreNarrowed} 在 any/all 分支边界做快照隔离。
     */
    private final Map<String, Class<?>> narrowedClasses = new HashMap<>();

    /** 常量提升定义（由 ScriptOptimizer 填充） */
    private ImmutableList<ConstantDef> hoistedConstants = ImmutableList.of();

    /** 活跃变量集合（由 ScriptOptimizer 填充） */
    private Set<String> liveVars = new HashSet<>();

    /** 目标接口的内部名称（如 java/util/function/ToIntFunction） */
    private final String targetInterfaceInternalName;

    /** 目标接口的方法名（如 applyAsInt） */
    private final String targetMethodName;

    /** 目标接口的方法字节码描述符（如 (Ljava/lang/Object;)I） */
    private final String targetMethodDescriptor;

    /** 目标接口的返回类型 (ASM) */
    private final Type targetReturnType;

    /** 下一个可用的局部变量槽位（正确计算了 double/long 各占 2 个 slot） */
    private final int nextSlot;

    private CompilationContext(Builder builder) {
        this.varSlots = builder.varSlots.build();
        this.aliasSlots = builder.aliasSlots.build();
        this.typeTable = builder.typeTable.build();
        this.constants = builder.constants.build();
        this.payloadClass = builder.payloadClass;
        this.targetInterfaceInternalName = builder.targetInterfaceInternalName;
        this.targetMethodName = builder.targetMethodName;
        this.targetMethodDescriptor = builder.targetMethodDescriptor;
        this.targetReturnType = builder.targetReturnType;
        // 使用 Builder 中精确维护的 slotCounter，确保 double/long 各占 2 个 slot 的情况被正确计算。
        this.nextSlot = builder.slotCounter;
    }

    public int getSlot(String varName) {
        Integer slot = varSlots.get(varName);
        if (slot == null) slot = aliasSlots.get(varName);
        if (slot == null) {
            throw new IllegalArgumentException("Undefined variable: " + varName);
        }
        return slot;
    }

    public String getVarName(int slot) {
        return varSlots.inverse().get(slot);
    }

    public ScriptIR.IRType getType(String varName) {
        return typeTable.getOrDefault(varName, ScriptIR.IRType.OBJECT);
    }

    public boolean isConstant(String varName) {
        return constants.containsKey(varName);
    }

    @SuppressWarnings("unchecked")
    public <T> T getConstant(String varName) {
        return (T) constants.get(varName);
    }

    public Class<?> payloadClass() {
        return payloadClass;
    }

    public String targetInterfaceInternalName() {
        return targetInterfaceInternalName;
    }

    public String targetMethodName() {
        return targetMethodName;
    }

    public String targetMethodDescriptor() {
        return targetMethodDescriptor;
    }

    public Type targetReturnType() {
        return targetReturnType;
    }

    public int nextSlot() {
        return nextSlot;
    }

    /**
     * 从缓存获取 MethodHandle。
     */
    public static MethodHandle resolveMethod(Class<?> owner, String methodName) {
        try {
            return METHOD_CACHE.get(owner.getName() + "#" + methodName);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot resolve method: " + owner.getName() + "#" + methodName, e);
        }
    }

    /**
     * 获取方法的返回类型。
     */
    public static Class<?> resolveReturnType(Class<?> owner, String methodName) {
        MethodHandle mh = resolveMethod(owner, methodName);
        return mh.type().returnType();
    }

    public void putBranchWeights(String switchId, double[] weights) {
        branchWeights.put(switchId, weights);
    }

    public double[] getBranchWeights(String switchId) {
        return branchWeights.get(switchId);
    }

    // ======================== 类型窄化 ========================

    /**
     * 注册变量的窄化类型。在 {@code check: instanceof} 成功路径后调用。
     *
     * @param varName 变量名
     * @param clazz   窄化后的目标类
     */
    public void narrowType(String varName, Class<?> clazz) {
        narrowedClasses.put(varName, clazz);
    }

    /**
     * 获取变量的窄化类型。若该变量未经过 instanceof 窄化则返回 {@code null}。
     *
     * @param varName 变量名
     * @return 窄化类，或 null
     */
    public Class<?> getNarrowedClass(String varName) {
        return narrowedClasses.get(varName);
    }

    /**
     * 返回当前窄化表的副本，供 any/all 分支进入前保存快照。
     */
    public Map<String, Class<?>> snapshotNarrowed() {
        return new HashMap<>(narrowedClasses);
    }

    /**
     * 从快照恢复窄化表，用于 any/all 分支退出时还原作用域。
     *
     * @param snapshot 由 {@link #snapshotNarrowed()} 返回的副本
     */
    public void restoreNarrowed(Map<String, Class<?>> snapshot) {
        narrowedClasses.clear();
        narrowedClasses.putAll(snapshot);
    }

    // ======================== 优化器产出 ========================

    /** 编译期需提升为 static final 的常量。 */
    public record ConstantDef(String fieldName, ConstantKind kind, Object value) {
    }

    public enum ConstantKind {
        PATTERN, STRING_SET, INT_ARRAY, DOUBLE_ARRAY
    }

    public void setHoistedConstants(ImmutableList<ConstantDef> constants) {
        this.hoistedConstants = constants;
    }

    public ImmutableList<ConstantDef> hoistedConstants() {
        return hoistedConstants;
    }

    public void setLiveVars(Set<String> vars) {
        this.liveVars = vars;
    }

    public Set<String> liveVars() {
        return liveVars;
    }

    // ======================== Builder ========================

    public static Builder builder(Class<?> payloadClass) {
        return new Builder(payloadClass);
    }

    public static final class Builder {
        private final Class<?> payloadClass;
        private final ImmutableBiMap.Builder<String, Integer> varSlots = ImmutableBiMap.builder();
        private final ImmutableMap.Builder<String, Integer> aliasSlots = ImmutableMap.builder();
        private final ImmutableMap.Builder<String, ScriptIR.IRType> typeTable = ImmutableMap.builder();
        private final ImmutableMap.Builder<String, Object> constants = ImmutableMap.builder();
        private int slotCounter = 2; // 0=this, 1=payload

        // Defaults to Action Handler via Function<Object, Object>
        private String targetInterfaceInternalName = "java/util/function/Function";
        private String targetMethodName = "apply";
        private String targetMethodDescriptor = "(Ljava/lang/Object;)Ljava/lang/Object;";
        private Type targetReturnType = Type.getType(Object.class);

        private Builder(Class<?> payloadClass) {
            this.payloadClass = payloadClass;
            // 预留 payload 的类型
            typeTable.put("payload", ScriptIR.IRType.OBJECT);
            varSlots.put("payload", 1);
        }

        /**
         * 注册 payload 别名：与 slot 1 共享，类型为 payload 的具体类。
         * 不占用新槽位，不能放入 BiMap（BiMap 要求值唯一）。
         */
        public Builder addPayloadAlias(String name, ScriptIR.IRType payloadType) {
            aliasSlots.put(name, 1);
            typeTable.put(name, payloadType);
            return this;
        }

        /**
         * 分配一个变量槽位。
         */
        public Builder addVar(String name, ScriptIR.IRType type) {
            varSlots.put(name, slotCounter);
            typeTable.put(name, type);
            // double/long 占两个槽位
            slotCounter += (type == ScriptIR.IRType.DOUBLE || type == ScriptIR.IRType.LONG) ? 2 : 1;
            return this;
        }

        public Builder addConstant(String name, Object value) {
            constants.put(name, value);
            return this;
        }

        public Builder targetMethod(String internalName, String name, String descriptor, Type returnType) {
            this.targetInterfaceInternalName = internalName;
            this.targetMethodName = name;
            this.targetMethodDescriptor = descriptor;
            this.targetReturnType = returnType;
            return this;
        }

        public CompilationContext build() {
            return new CompilationContext(this);
        }
    }
}
