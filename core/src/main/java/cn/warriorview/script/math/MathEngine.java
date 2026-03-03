package cn.warriorview.script.math;

import cn.warriorview.script.codegen.ASMUtils;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Math 表达式引擎公共 API。
 *
 * <h2>使用方式</h2>
 *
 * <pre>{@code
 * var expr = MathEngine.compile("{hp} / {max} * 100", "hp", "max");
 * double pct = expr.evaluate(60.0, 100.0); // → 60.0
 *
 * // 0–3 变量时可向下转型获得零数组开销的快速路径
 * double v = ((MathEngine.Expr2) expr).eval(60.0, 100.0);
 * }</pre>
 *
 * <p>
 * {@link #compile} 将表达式直接编译为 JVM 字节码（ASM）。
 * 结果会被全局缓存（相同表达式 + 变量名 → 返回同一实例）。
 *
 * <h2>优化清单</h2>
 * <ul>
 * <li><b>编译缓存</b>：相同 key 不重复生成类</li>
 * <li><b>特化接口 Expr0–3</b>：0–3 个变量直接读 double 局部变量，消除 varargs 数组分配</li>
 * <li><b>幂整数特化</b>：{@code x^2/3/4} 展开为 DUP2+DMUL 链，不调用 Math.pow</li>
 * <li><b>重复变量缓存</b>：≥4 变量时出现 ≥2 次的变量 DSTORE 到局部槽，后续 DLOAD</li>
 * <li><b>常量折叠 + 代数化简</b>：由 {@link MathParser} 完成</li>
 * </ul>
 *
 * <h2>线程安全</h2>
 * {@link CompiledMathExpression} 实现是无状态的，可被多线程共享，无需 clone。
 */
public final class MathEngine {

    private MathEngine() {
    }

    // ======================== 公开接口 ========================

    /**
     * 通用评估接口（兼容所有变量数量）。
     * 变量顺序对应编译时传入的 {@code varNames} 顺序。
     */
    @FunctionalInterface
    public interface CompiledMathExpression {
        double evaluate(double... vars);
    }

    /** 0 变量特化——直接调用 {@code eval()} 可消除 varargs 数组分配。 */
    public interface Expr0 extends CompiledMathExpression {
        double eval();
        @Override default double evaluate(double... v) { return eval(); }
    }

    /** 1 变量特化——直接调用 {@code eval(double)} 可消除 varargs 数组分配。 */
    public interface Expr1 extends CompiledMathExpression {
        double eval(double v0);
        @Override default double evaluate(double... v) { return eval(v[0]); }
    }

    /** 2 变量特化——直接调用 {@code eval(double, double)} 可消除 varargs 数组分配。 */
    public interface Expr2 extends CompiledMathExpression {
        double eval(double v0, double v1);
        @Override default double evaluate(double... v) { return eval(v[0], v[1]); }
    }

    /** 3 变量特化——直接调用 {@code eval(double, double, double)} 可消除 varargs 数组分配。 */
    public interface Expr3 extends CompiledMathExpression {
        double eval(double v0, double v1, double v2);
        @Override default double evaluate(double... v) { return eval(v[0], v[1], v[2]); }
    }

    // ======================== 编译 ========================

    private static final AtomicInteger CLASS_COUNTER = new AtomicInteger(0);

    /** 编译结果缓存：key = expression + "\0" + varNames。相同表达式不重复生成类。 */
    private static final ConcurrentHashMap<String, CompiledMathExpression> COMPILE_CACHE =
            new ConcurrentHashMap<>();

    // 生成类的包路径
    private static final String GENERATED_PKG =
            MathEngine.class.getPackageName().replace('.', '/') + "/generated";

    // CompiledMathExpression 的 JVM 内部名称
    private static final String EXPR_INTERFACE =
            org.objectweb.asm.Type.getInternalName(CompiledMathExpression.class);

    // evaluate(double[]) 方法描述符
    private static final String EVAL_DESC = "([D)D";

    /**
     * 编译表达式为 JVM 字节码，结果全局缓存。
     *
     * @param expression 数学表达式
     * @param varNames   变量名（顺序即 evaluate 时数组下标）
     */
    public static CompiledMathExpression compile(String expression, String... varNames) {
        // ① 编译缓存：相同 (表达式, 变量列表) → 直接复用
        String cacheKey = expression + '\0' + Arrays.toString(varNames);
        return COMPILE_CACHE.computeIfAbsent(cacheKey, k -> doCompile(expression, varNames));
    }

    /**
     * 绕过编译缓存，强制重新解析 + 生成字节码。
     *
     * <p>仅用于编译速度基准测试，正常业务请使用 {@link #compile}。
     */
    static CompiledMathExpression compileFresh(String expression, String... varNames) {
        return doCompile(expression, varNames);
    }

    private static CompiledMathExpression doCompile(String expression, String[] varNames) {
        Map<String, Integer> indexMap = buildIndexMap(varNames);
        MathNode root = MathParser.parseWithIndex(expression, indexMap);

        int varCount = varNames.length;

        // ② 全常量短路：整棵树折叠为单个字面量，直接返回 lambda，避免 ASM 类生成 + 类加载开销
        // 按 varCount 返回对应特化接口，确保可强转为 Expr0–3
        if (root instanceof MathNode.LiteralNode l) {
            double v = l.value();
            if (varCount <= 3) {
                return switch (varCount) {
                    case 0 -> (Expr0) () -> v;
                    case 1 -> (Expr1) (v0) -> v;
                    case 2 -> (Expr2) (v0, v1) -> v;
                    case 3 -> (Expr3) (v0, v1, v2) -> v;
                    default -> throw new AssertionError();
                };
            }
            return (double... ignored) -> v;
        }

        if (varCount <= 3) {
            // ② 特化路径：0–3 变量，变量直接从方法参数读取，无数组分配
            return compileSpecialized(root, varCount);
        } else {
            // ③ 通用路径：≥4 变量，double[] 数组 + 重复变量局部缓存
            return compileArray(root, varCount);
        }
    }

    // ── ② 特化编译（0–3 变量） ────────────────────────────────────────────────

    private static CompiledMathExpression compileSpecialized(MathNode root, int varCount) {
        // 选择对应的特化接口
        Class<?> specInterface = switch (varCount) {
            case 0 -> Expr0.class;
            case 1 -> Expr1.class;
            case 2 -> Expr2.class;
            case 3 -> Expr3.class;
            default -> throw new AssertionError();
        };
        String specInternal = org.objectweb.asm.Type.getInternalName(specInterface);
        // eval 方法描述符：()D / (D)D / (DD)D / (DDD)D
        String specDesc = "(" + "D".repeat(varCount) + ")D";

        int id = CLASS_COUNTER.incrementAndGet();
        String className = GENERATED_PKG + "/MathExprS$" + id;

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V21,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                className, null, "java/lang/Object",
                new String[]{ specInternal });

        emitDefaultConstructor(cw);

        // varSlots[i] = 方法参数的局部变量槽（slot 0=this, 1-2=v0, 3-4=v1, 5-6=v2）
        int[] varSlots = new int[varCount];
        for (int i = 0; i < varCount; i++) {
            varSlots[i] = 1 + i * 2; // double 占 2 个槽
        }

        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "eval", specDesc, null, null);
        mv.visitCode();
        emitNode(root, mv, varSlots);
        mv.visitInsn(Opcodes.DRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        cw.visitEnd();
        return loadClass(cw.toByteArray(), className);
    }

    // ── ③ 数组编译（≥4 变量，带重复变量缓存） ─────────────────────────────────

    private static CompiledMathExpression compileArray(MathNode root, int varCount) {
        // 统计每个变量在 AST 中出现次数（委托给 MathNode 工具方法）
        Map<Integer, Integer> usageCount = MathNode.countUsages(root);

        // varSlots[i]: -1 = 每次从 double[] 数组读取；≥2 = 缓存到该局部槽（DSTORE 一次，DLOAD 多次）
        // 槽分布：0=this, 1=vars[], 2+(2*k)=第 k 个被缓存的变量
        int[] varSlots = new int[varCount];
        Arrays.fill(varSlots, -1);
        int nextSlot = 2;
        for (Map.Entry<Integer, Integer> e : usageCount.entrySet()) {
            if (e.getValue() >= 2) {
                varSlots[e.getKey()] = nextSlot;
                nextSlot += 2;
            }
        }

        int id = CLASS_COUNTER.incrementAndGet();
        String className = GENERATED_PKG + "/MathExprA$" + id;

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V21,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                className, null, "java/lang/Object",
                new String[]{ EXPR_INTERFACE });

        emitDefaultConstructor(cw);

        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "evaluate", EVAL_DESC, null, null);
        mv.visitCode();

        // 方法头：将出现 ≥2 次的变量预取到局部槽（DSTORE）
        for (int i = 0; i < varCount; i++) {
            if (varSlots[i] >= 0) {
                mv.visitVarInsn(Opcodes.ALOAD, 1);
                ASMUtils.emitIntConst(mv, i);
                mv.visitInsn(Opcodes.DALOAD);
                mv.visitVarInsn(Opcodes.DSTORE, varSlots[i]);
            }
        }

        emitNode(root, mv, varSlots);
        mv.visitInsn(Opcodes.DRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        cw.visitEnd();
        return loadClass(cw.toByteArray(), className);
    }

    // ======================== 内部工具 ========================

    private static Map<String, Integer> buildIndexMap(String[] varNames) {
        Map<String, Integer> map = new java.util.HashMap<>(varNames.length * 2);
        for (int i = 0; i < varNames.length; i++) {
            map.put(varNames[i], i);
        }
        return map;
    }

    private static void emitDefaultConstructor(ClassWriter cw) {
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(1, 1);
        init.visitEnd();
    }

    private static CompiledMathExpression loadClass(byte[] bytes, String className) {
        try {
            Class<?> clazz = MathClassLoader.INSTANCE.defineClass(className.replace('/', '.'), bytes);
            return (CompiledMathExpression) clazz.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load generated math expression class", e);
        }
    }

    // ======================== ASM 字节码发射 ========================

    /**
     * 递归将 {@link MathNode} 发射为 JVM 字节码。
     * 实际逻辑委托给 {@link MathNodeEmitter}，保留此方法作为包内公共入口。
     *
     * @param varSlots 变量加载策略（见 {@link MathNodeEmitter#slotBased(int[])}）
     */
    static void emitNode(MathNode node, MethodVisitor mv, int[] varSlots) {
        MathNodeEmitter.emit(node, mv, MathNodeEmitter.slotBased(varSlots));
    }

    // ======================== 类加载器 ========================

    /** 专用类加载器，用于加载动态生成的 Math 表达式类。 */
    private static final class MathClassLoader extends ClassLoader {
        static final MathClassLoader INSTANCE = new MathClassLoader();

        private MathClassLoader() {
            super(MathEngine.class.getClassLoader());
        }

        Class<?> defineClass(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
