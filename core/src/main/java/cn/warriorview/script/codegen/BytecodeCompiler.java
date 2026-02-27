package cn.warriorview.script.codegen;

import cn.warriorview.script.core.CompilationContext;
import cn.warriorview.script.core.CompilationContext.ConstantDef;
import cn.warriorview.script.core.ScriptIR;
import cn.warriorview.script.core.ScriptIR.FlowNode;
import cn.warriorview.script.core.ScriptIR.ScriptUnit;
import cn.warriorview.script.core.ScriptIR.VarDecl;
import cn.warriorview.script.parser.ScriptParser;
import com.google.common.collect.ImmutableList;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import static cn.warriorview.script.codegen.ASMUtils.*;

import java.lang.invoke.CallSite;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * ASM 字节码编译器。
 * <p>
 * 将优化后的 IR {@link ScriptUnit} 编译为实现 {@code Consumer<Event>} 的字节码类。
 * <p>
 * 编译期优化（只读保证下）：
 * <ul>
 * <li>static final 常量提升（Pattern/Set/数组 → {@code <clinit>}）</li>
 * <li>CSE：公共 getter 前缀只调一次</li>
 * <li>死变量消除：flow 中未引用的变量不提取</li>
 * <li>手动帧计算：跳过 {@code COMPUTE_FRAMES}</li>
 * <li>invokedynamic StringConcatFactory 零分配拼接</li>
 * </ul>
 */
public final class BytecodeCompiler implements Opcodes {

    /** 所有脚本统一编译为此接口，简化内部模式选择 */
    private static final String FUNCTION_INTERNAL = Type.getInternalName(Function.class);
    private static final String OBJECT_INTERNAL = "java/lang/Object";

    /** StringConcatFactory bootstrap handle */
    private static final Handle STRING_CONCAT_HANDLE = new Handle(
            H_INVOKESTATIC,
            "java/lang/invoke/StringConcatFactory",
            "makeConcatWithConstants",
            MethodType.methodType(
                    CallSite.class, MethodHandles.Lookup.class, String.class,
                    MethodType.class, String.class, Object[].class).toMethodDescriptorString(),
            false);

    public byte[] compile(ScriptUnit unit, CompilationContext ctx) {
        String basePackage = BytecodeCompiler.class.getPackage().getName().replace('.', '/');
        String className = basePackage + "/generated/Script$" + Integer.toHexString(unit.hashCode());
        String payloadInternal = unit.payloadClass().replace('.', '/');

        List<ConstantDef> constants = ctx.hoistedConstants();
        Set<String> liveVars = ctx.liveVars();

        // 所有脚本统一生成 Function<Object,Object>，干通返回 null，有值返回真实值。
        // 调用侧通过 CompilationPipeline.newHandler() 茇薄包装为 Consumer。
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V21, ACC_PUBLIC | ACC_FINAL | ACC_SUPER,
                className, null, OBJECT_INTERNAL,
                new String[] { FUNCTION_INTERNAL });

        emitStaticFields(cw, constants);
        emitClinit(cw, className, constants);
        emitConstructor(cw);
        emitBridgeApply(cw, className, payloadInternal);
        emitApplyMethod(cw, className, unit, ctx, liveVars, payloadInternal);

        cw.visitEnd();
        return cw.toByteArray();
    }

    // ======================== static final 字段 ========================

    private void emitStaticFields(ClassWriter cw, List<ConstantDef> constants) {
        for (ConstantDef def : constants) {
            String descriptor = switch (def.kind()) {
                case PATTERN -> "Ljava/util/regex/Pattern;";
                case STRING_SET -> "Ljava/util/Set;";
                case INT_ARRAY -> "[I";
                case DOUBLE_ARRAY -> "[D";
            };
            cw.visitField(ACC_PRIVATE | ACC_STATIC | ACC_FINAL,
                    def.fieldName(), descriptor, null, null).visitEnd();
        }
    }

    @SuppressWarnings("unchecked")
    private void emitClinit(ClassWriter cw, String className, List<ConstantDef> constants) {
        if (constants.isEmpty())
            return;

        MethodVisitor mv = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        mv.visitCode();

        for (ConstantDef def : constants) {
            switch (def.kind()) {
                case PATTERN -> {
                    mv.visitLdcInsn((String) def.value());
                    mv.visitMethodInsn(INVOKESTATIC, "java/util/regex/Pattern", "compile",
                            "(Ljava/lang/String;)Ljava/util/regex/Pattern;", false);
                    mv.visitFieldInsn(PUTSTATIC, className,
                            def.fieldName(), "Ljava/util/regex/Pattern;");
                }
                case STRING_SET -> {
                    ImmutableList<Object> vals = (ImmutableList<Object>) def.value();
                    emitIntConst(mv, vals.size());
                    mv.visitTypeInsn(ANEWARRAY, "java/lang/Object");
                    for (int i = 0; i < vals.size(); i++) {
                        mv.visitInsn(DUP);
                        emitIntConst(mv, i);
                        mv.visitLdcInsn(vals.get(i).toString());
                        mv.visitInsn(AASTORE);
                    }
                    mv.visitMethodInsn(INVOKESTATIC, "java/util/Set", "of",
                            "([Ljava/lang/Object;)Ljava/util/Set;", true);
                    mv.visitFieldInsn(PUTSTATIC, className,
                            def.fieldName(), "Ljava/util/Set;");
                }
                case DOUBLE_ARRAY -> {
                    double[] arr = (double[]) def.value();
                    emitIntConst(mv, arr.length);
                    mv.visitIntInsn(NEWARRAY, T_DOUBLE);
                    for (int i = 0; i < arr.length; i++) {
                        mv.visitInsn(DUP);
                        emitIntConst(mv, i);
                        emitDoubleConst(mv, arr[i]);
                        mv.visitInsn(DASTORE);
                    }
                    mv.visitFieldInsn(PUTSTATIC, className,
                            def.fieldName(), "[D");
                }
                case INT_ARRAY -> {
                    int[] arr = (int[]) def.value();
                    emitIntConst(mv, arr.length);
                    mv.visitIntInsn(NEWARRAY, T_INT);
                    for (int i = 0; i < arr.length; i++) {
                        mv.visitInsn(DUP);
                        emitIntConst(mv, i);
                        emitIntConst(mv, arr[i]);
                        mv.visitInsn(IASTORE);
                    }
                    mv.visitFieldInsn(PUTSTATIC, className,
                            def.fieldName(), "[I");
                }
            }
        }

        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    // ======================== 构造器 + 桥接 ========================

    private void emitConstructor(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESPECIAL, OBJECT_INTERNAL, "<init>", "()V", false);
        emitVoidReturn(mv);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
    }

    // ======================== 桥接 + apply 方法 ========================

    /**
     * bridge：apply(Object)Object → apply(PayloadType)Object ，满足 Function 接口的类型擦除要求。
     */
    private void emitBridgeApply(ClassWriter cw, String className, String payloadInternal) {
        MethodVisitor mv = cw.visitMethod(
                ACC_PUBLIC | ACC_BRIDGE | ACC_SYNTHETIC,
                "apply", "(Ljava/lang/Object;)Ljava/lang/Object;", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitTypeInsn(CHECKCAST, payloadInternal);
        mv.visitMethodInsn(INVOKEVIRTUAL, className, "apply",
                "(L" + payloadInternal + ";)Ljava/lang/Object;", false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(2, 2);
        mv.visitEnd();
    }

    /**
     * 生成 {@code apply(PayloadType)Object} 方法。
     * <ul>
     * <li>RETURN 节点 → handler 发射 {@code ACONST_NULL + ARETURN}（返回 null）</li>
     * <li>RETURN_VALUE 节点 → handler 发射 {@code XLOAD + [装箱] + ARETURN}（返回实值）</li>
     * <li>尾部干通兼容：未到达任何显式返回时，返回 null。</li>
     * </ul>
     */
    private void emitApplyMethod(ClassWriter cw, String className,
            ScriptUnit unit, CompilationContext ctx,
            Set<String> liveVars, String payloadInternal) {
        String descriptor = "(L" + payloadInternal + ";)Ljava/lang/Object;";

        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "apply", descriptor, null, null);
        mv.visitCode();

        // ---- try-catch 错误隔离 ----
        org.objectweb.asm.Label tryStart = new org.objectweb.asm.Label();
        org.objectweb.asm.Label tryEnd = new org.objectweb.asm.Label();
        org.objectweb.asm.Label catchHandler = new org.objectweb.asm.Label();
        mv.visitTryCatchBlock(tryStart, tryEnd, catchHandler, "java/lang/Throwable");

        mv.visitLabel(tryStart);

        emitVarExtractionWithCSE(mv, unit.vars(), ctx, payloadInternal, liveVars);

        for (FlowNode node : unit.flow()) {
            FlowNode enhancedNode = node.withAttr("_className", className);
            enhancedNode.type().handler().emit(enhancedNode, mv, ctx);
        }

        // 正常干通返回 null
        mv.visitInsn(ACONST_NULL);
        mv.visitInsn(ARETURN);
        mv.visitLabel(tryEnd);

        // ---- catch(Throwable t) ----
        mv.visitLabel(catchHandler);
        // 栈顶: Throwable, 存到临时槽
        int exSlot = ctx.nextSlot();
        mv.visitVarInsn(ASTORE, exSlot);

        // Logger.getLogger("WarriorView-Script").severe("Script error in <className>")
        mv.visitLdcInsn("WarriorView-Script");
        mv.visitMethodInsn(INVOKESTATIC, "java/util/logging/Logger", "getLogger",
                "(Ljava/lang/String;)Ljava/util/logging/Logger;", false);
        mv.visitLdcInsn("Script error in " + className);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/util/logging/Logger", "severe",
                "(Ljava/lang/String;)V", false);

        // throwable.printStackTrace()
        mv.visitVarInsn(ALOAD, exSlot);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Throwable", "printStackTrace",
                "()V", false);

        // return null
        mv.visitInsn(ACONST_NULL);
        mv.visitInsn(ARETURN);

        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /**
     * CSE 变量提取：检测公共前缀（按点号后的第一段划分），只调用一次。
     * 改为了完全基于 TypeToken 和 PropertyAccessor 的方案。
     */
    private void emitVarExtractionWithCSE(MethodVisitor mv, ImmutableList<VarDecl> vars,
            CompilationContext ctx, String payloadInternal,
            Set<String> liveVars) {

        // 分组策略：按原始 property 的第一段（例如 "player.inventory" 的 "player"）
        // 这一段必须能抽出独立的 Accessor，因为有可能第一段就是 map['damage']，此时作为整体也可以复用。
        // 但为了通用性，按完整的 propertyPath 重新拉取一次 Accessor 组
        Map<String, List<VarDecl>> groups = new LinkedHashMap<>();
        for (VarDecl var : vars) {
            if (!liveVars.contains(var.name()))
                continue;

            // 按 Parser 规定的属性语法截取根基名称（提取第一段复用前缀）
            String firstPart = cn.warriorview.script.parser.ScriptParser.PropertyResolver
                    .getRootProperty(var.property());
            groups.computeIfAbsent(firstPart, k -> new ArrayList<>()).add(var);
        }

        Map<String, Integer> cachedPrefixes = new HashMap<>();
        int tempSlot = ctx.nextSlot();

        for (Map.Entry<String, List<VarDecl>> entry : groups.entrySet()) {
            String prefix = entry.getKey();
            List<VarDecl> group = entry.getValue();

            if (group.size() > 1) {
                // 有复用价值，提取第一段
                mv.visitVarInsn(ALOAD, 1);

                // 解析第一段的 Accessor
                List<cn.warriorview.script.parser.accessor.PropertyAccessor> prefixAccessors = cn.warriorview.script.parser.ScriptParser.PropertyResolver
                        .resolveAccessors(
                                com.google.common.reflect.TypeToken.of(ctx.payloadClass()), prefix);

                // 只有一段（第一段必然只有一个）
                cn.warriorview.script.parser.accessor.PropertyAccessor firstAcr = prefixAccessors.get(0);
                firstAcr.emitLoad(mv);

                mv.visitVarInsn(ASTORE, tempSlot);
                cachedPrefixes.put(prefix, tempSlot);
                int currentCache = tempSlot;
                tempSlot++;

                // 其余段跟进
                for (VarDecl var : group) {
                    mv.visitVarInsn(ALOAD, currentCache);

                    List<cn.warriorview.script.parser.accessor.PropertyAccessor> fullAccessors = cn.warriorview.script.parser.ScriptParser.PropertyResolver
                            .resolveAccessors(
                                    com.google.common.reflect.TypeToken.of(ctx.payloadClass()), var.property());

                    // 从第 1 个之后（索引 1）开始发射
                    for (int i = 1; i < fullAccessors.size(); i++) {
                        cn.warriorview.script.parser.accessor.PropertyAccessor acr = fullAccessors.get(i);
                        acr.emitLoad(mv);
                    }

                    int storeOp = ASMUtils.storeOpcode(var.type());
                    mv.visitVarInsn(storeOp, ctx.getSlot(var.name()));
                }
            } else {
                emitSingleVarExtraction(mv, group.get(0), ctx, payloadInternal);
            }
        }
    }

    private void emitSingleVarExtraction(MethodVisitor mv, VarDecl var,
            CompilationContext ctx, String eventInternal) {
        int slot = ctx.getSlot(var.name());
        mv.visitVarInsn(ALOAD, 1);

        List<cn.warriorview.script.parser.accessor.PropertyAccessor> accessors = cn.warriorview.script.parser.ScriptParser.PropertyResolver
                .resolveAccessors(
                        com.google.common.reflect.TypeToken.of(ctx.payloadClass()), var.property());

        for (cn.warriorview.script.parser.accessor.PropertyAccessor acr : accessors) {
            acr.emitLoad(mv);
        }

        int storeOp = ASMUtils.storeOpcode(var.type());
        mv.visitVarInsn(storeOp, slot);
    }

    // ======================== invokedynamic 字符串拼接 ========================

    /**
     * 使用 {@code invokedynamic StringConcatFactory.makeConcatWithConstants} 发射字符串拼接。
     */
    public static void emitStringConcat(MethodVisitor mv, String template, CompilationContext ctx) {
        List<String> parts = ScriptIR.parseTemplate(template);

        StringBuilder recipe = new StringBuilder();
        StringBuilder descriptor = new StringBuilder("(");

        for (String part : parts) {
            if (isTemplatePart(template, part)) {
                recipe.append('\u0001');
                int slot = ctx.getSlot(part);
                ScriptIR.IRType type = ctx.getType(part);
                switch (type.base()) {
                    case INT:
                    case BOOLEAN:
                        mv.visitVarInsn(ILOAD, slot);
                        descriptor.append("I");
                        break;
                    case LONG:
                        mv.visitVarInsn(LLOAD, slot);
                        descriptor.append("J");
                        break;
                    case DOUBLE:
                        mv.visitVarInsn(DLOAD, slot);
                        descriptor.append("D");
                        break;
                    default:
                        mv.visitVarInsn(ALOAD, slot);
                        descriptor.append("Ljava/lang/Object;");
                        break;
                }
            } else {
                for (char c : part.toCharArray()) {
                    if (c == '\u0001' || c == '\u0002') {
                        recipe.append('\u0002').append(c);
                    } else {
                        recipe.append(c);
                    }
                }
            }
        }

        descriptor.append(")Ljava/lang/String;");

        mv.visitInvokeDynamicInsn(
                "makeConcatWithConstants",
                descriptor.toString(),
                STRING_CONCAT_HANDLE,
                recipe.toString());
    }

    private static boolean isTemplatePart(String fullTemplate, String part) {
        return fullTemplate.contains("{" + part + "}");
    }

    // ======================== 属性下沉发射 ========================

    /**
     * 发射属性下沉加载序列：ALOAD 1 + PropertyAccessor 链。
     * 桥接 PropertyResolver 解析与 ASM 字节码发射，与 {@link #emitStringConcat} 同级。
     *
     * @param mv          方法访问器
     * @param ctx         编译上下文
     * @param sinkingProp 下沉的属性表达式 (e.g. "health")
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public static void emitSunkPropertyLoad(MethodVisitor mv, CompilationContext ctx,
            String sinkingProp) {
        mv.visitVarInsn(Opcodes.ALOAD, 1);

        List<cn.warriorview.script.parser.accessor.PropertyAccessor> accessors = ScriptParser.PropertyResolver
                .resolveAccessors(
                        com.google.common.reflect.TypeToken.of((Class) ctx.payloadClass()), sinkingProp);

        for (cn.warriorview.script.parser.accessor.PropertyAccessor acr : accessors) {
            acr.emitLoad(mv);
        }
    }

}
