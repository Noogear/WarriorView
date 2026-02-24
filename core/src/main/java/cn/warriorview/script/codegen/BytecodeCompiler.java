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
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.lang.invoke.CallSite;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

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

    private static final String CONSUMER_INTERNAL = Type.getInternalName(Consumer.class);
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
        String className = "cn/warriorview/script/generated/Script$" +
                Integer.toHexString(unit.hashCode());
        String payloadInternal = unit.payloadClass().replace('.', '/');

        // 从优化器产出读取（零 node 依赖）
        List<ConstantDef> constants = ctx.hoistedConstants();
        Set<String> liveVars = ctx.liveVars();

        // 手动帧：去除 COMPUTE_FRAMES，仅保留 COMPUTE_MAXS
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V21, ACC_PUBLIC | ACC_FINAL | ACC_SUPER,
                className, null, OBJECT_INTERNAL,
                new String[] { CONSUMER_INTERNAL });

        // static final 常量字段
        emitStaticFields(cw, constants);
        emitClinit(cw, className, constants);

        emitConstructor(cw);
        emitBridgeAccept(cw, className, payloadInternal);
        emitAcceptMethod(cw, className, unit, ctx, liveVars, payloadInternal);

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
        mv.visitInsn(RETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
    }

    private void emitBridgeAccept(ClassWriter cw, String className, String payloadInternal) {
        MethodVisitor mv = cw.visitMethod(
                ACC_PUBLIC | ACC_BRIDGE | ACC_SYNTHETIC,
                "accept", "(Ljava/lang/Object;)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitTypeInsn(CHECKCAST, payloadInternal);
        mv.visitMethodInsn(INVOKEVIRTUAL, className, "accept",
                "(L" + payloadInternal + ";)V", false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(2, 2);
        mv.visitEnd();
    }

    // ======================== accept 方法（含 CSE） ========================

    private void emitAcceptMethod(ClassWriter cw, String className,
            ScriptUnit unit, CompilationContext ctx,
            Set<String> liveVars, String payloadInternal) {
        String descriptor = "(L" + payloadInternal + ";)V";

        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "accept", descriptor, null, null);
        mv.visitCode();

        // CSE：按 getter 链前缀分组 → 公共前缀只调一次
        emitVarExtractionWithCSE(mv, unit.vars(), ctx, payloadInternal, liveVars);

        for (FlowNode node : unit.flow()) {
            // 将编译时生成类的 internal name 传递给节点供预编译常量获取使用
            FlowNode enhancedNode = node.withAttr("_className", className);
            enhancedNode.type().handler().emit(enhancedNode, mv, ctx);
        }

        mv.visitInsn(RETURN);
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

            // 按 '[' 或 '.' 第一个出现的作为复用前缀
            String prop = var.property();
            int dotIdx = prop.indexOf('.');
            int bracketIdx = prop.indexOf('[');

            int splitIdx = -1;
            if (dotIdx != -1 && bracketIdx != -1)
                splitIdx = Math.min(dotIdx, bracketIdx);
            else if (dotIdx != -1)
                splitIdx = dotIdx;
            else if (bracketIdx != -1)
                splitIdx = bracketIdx;

            String firstPart = (splitIdx == -1) ? prop : prop.substring(0, splitIdx);
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
                boolean isInterface = ctx.payloadClass().isInterface();
                firstAcr.emitLoad(mv, isInterface);

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

                    Class<?> currentClass = firstAcr.returnType().getRawType();
                    // 从第 1 个之后（索引 1）开始发射
                    for (int i = 1; i < fullAccessors.size(); i++) {
                        cn.warriorview.script.parser.accessor.PropertyAccessor acr = fullAccessors.get(i);
                        boolean nextIsInterface = currentClass.isInterface();
                        acr.emitLoad(mv, nextIsInterface);
                        currentClass = acr.returnType().getRawType();
                    }

                    int storeOp = switch (var.type()) {
                        case INT, BOOLEAN -> ISTORE;
                        case LONG -> LSTORE;
                        case DOUBLE -> DSTORE;
                        default -> ASTORE;
                    };
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

        Class<?> currentClass = ctx.payloadClass();
        for (cn.warriorview.script.parser.accessor.PropertyAccessor acr : accessors) {
            boolean isInterface = currentClass.isInterface();
            acr.emitLoad(mv, isInterface);
            currentClass = acr.returnType().getRawType();
        }

        int storeOp = switch (var.type()) {
            case INT, BOOLEAN -> ISTORE;
            case LONG -> LSTORE;
            case DOUBLE -> DSTORE;
            default -> ASTORE;
        };
        mv.visitVarInsn(storeOp, slot);
    }

    // ======================== invokedynamic 字符串拼接 ========================

    /**
     * 使用 {@code invokedynamic StringConcatFactory.makeConcatWithConstants} 发射字符串拼接。
     */
    public static void emitStringConcat(MethodVisitor mv, String template, CompilationContext ctx) {
        List<String> parts = ScriptParser.ValueParser.parseTemplate(template);

        StringBuilder recipe = new StringBuilder();
        StringBuilder descriptor = new StringBuilder("(");

        for (String part : parts) {
            if (isTemplatePart(template, part)) {
                recipe.append('\u0001');
                int slot = ctx.getSlot(part);
                ScriptIR.IRType type = ctx.getType(part);
                switch (type) {
                    case INT, BOOLEAN -> {
                        mv.visitVarInsn(ILOAD, slot);
                        descriptor.append("I");
                    }
                    case LONG -> {
                        mv.visitVarInsn(LLOAD, slot);
                        descriptor.append("J");
                    }
                    case DOUBLE -> {
                        mv.visitVarInsn(DLOAD, slot);
                        descriptor.append("D");
                    }
                    default -> {
                        mv.visitVarInsn(ALOAD, slot);
                        descriptor.append("Ljava/lang/Object;");
                    }
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

    // ======================== 常量加载工具 ========================

    public static void emitIntConst(MethodVisitor mv, int value) {
        if (value >= -1 && value <= 5) {
            mv.visitInsn(ICONST_0 + value);
        } else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
            mv.visitIntInsn(BIPUSH, value);
        } else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
            mv.visitIntInsn(SIPUSH, value);
        } else {
            mv.visitLdcInsn(value);
        }
    }

    public static void emitLongConst(MethodVisitor mv, long value) {
        if (value == 0L) {
            mv.visitInsn(LCONST_0);
        } else if (value == 1L) {
            mv.visitInsn(LCONST_1);
        } else {
            mv.visitLdcInsn(value);
        }
    }

    public static void emitDoubleConst(MethodVisitor mv, double value) {
        if (value == 0.0d) {
            mv.visitInsn(DCONST_0);
        } else if (value == 1.0d) {
            mv.visitInsn(DCONST_1);
        } else {
            mv.visitLdcInsn(value);
        }
    }

    public static void emitNullGuard(MethodVisitor mv, int slot, Label nullLabel) {
        mv.visitVarInsn(ALOAD, slot);
        mv.visitJumpInsn(IFNULL, nullLabel);
    }
}
