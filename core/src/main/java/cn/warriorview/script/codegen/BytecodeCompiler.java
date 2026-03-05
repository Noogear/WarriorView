package cn.warriorview.script.codegen;

import cn.warriorview.script.core.CompilationContext;
import cn.warriorview.script.core.CompilationContext.ConstantDef;
import cn.warriorview.script.core.ScriptErrorHandler;
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

    /**
     * 外置常量池 bootstrap handle，指向 {@link ScriptConstantBootstrap#bootstrap}。
     * <p>
     * 由 {@link CheckOpEmitters} 在生成 Pattern / Set / 数组常量访问时共享。
     */
    static final Handle CONST_BOOTSTRAP_HANDLE = new Handle(
            H_INVOKESTATIC,
            "cn/warriorview/script/codegen/ScriptConstantBootstrap",
            "bootstrap",
            MethodType.methodType(
                    CallSite.class, MethodHandles.Lookup.class, String.class,
                    MethodType.class, String.class).toMethodDescriptorString(),
            false);

    public byte[] compile(ScriptUnit unit, CompilationContext ctx) {
        // 生成类名必须与 GeneratedScriptHost.LOOKUP 同包，通过其暴露的包前缀常量构造
        String className = cn.warriorview.script.codegen.generated.GeneratedScriptHost.PACKAGE_PREFIX
                + "/Script$" + Integer.toHexString(unit.hashCode());
        String payloadInternal = unit.payloadClass().replace('.', '/');

        List<ConstantDef> constants = ctx.hoistedConstants();
        Set<String> liveVars = ctx.liveVars();

        // 实现 CompilationContext 提供的动态目标接口
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V21, ACC_PUBLIC | ACC_FINAL | ACC_SUPER,
                className, null, OBJECT_INTERNAL,
                new String[] { ctx.targetInterfaceInternalName() });

        // 常量外置到 ScriptConstantBootstrap，无需 static final 字段和 <clinit>
        ScriptConstantBootstrap.registerAll(constants);
        cw.visitField(ACC_PUBLIC | ACC_FINAL, "$scriptId", "Ljava/lang/String;", null, null).visitEnd();
        emitConstructor(cw, className);

        // 使用擦除签名，方法体内内联 CHECKCAST + ASTORE 缩窄 slot 1，消除 bridge 方法
        emitTargetMethod(cw, className, unit, ctx, liveVars, payloadInternal, ctx.targetMethodDescriptor());

        cw.visitEnd();
        return cw.toByteArray();
    }

    // ======================== 构造器 ========================

    private void emitConstructor(ClassWriter cw, String className) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", "(Ljava/lang/String;)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESPECIAL, OBJECT_INTERNAL, "<init>", "()V", false);

        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitFieldInsn(PUTFIELD, className, "$scriptId", "Ljava/lang/String;");

        emitVoidReturn(mv);
        mv.visitMaxs(2, 2);
        mv.visitEnd();
    }

    // ======================== 目标方法生成 ========================

    /**
     * 生成实现周期方法的主体。
     * <p>
     * 使用擦除签名（SAM 的原始签名），方法体首部内联 CHECKCAST + ASTORE
     * 缩窄 slot 1 类型，实现与原先 bridge 方法完全相同的调用语义，
     * 同时消除了 bridge 方法本身（节省 150–250B 元空间/类）。
     */
    private void emitTargetMethod(ClassWriter cw, String className,
            ScriptUnit unit, CompilationContext ctx,
            Set<String> liveVars, String payloadInternal, String methodDescriptor) {

        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, ctx.targetMethodName(), methodDescriptor, null, null);
        mv.visitCode();

        // ---- try-catch 错误隔离 ----
        org.objectweb.asm.Label tryStart = new org.objectweb.asm.Label();
        org.objectweb.asm.Label tryEnd = new org.objectweb.asm.Label();
        org.objectweb.asm.Label catchHandler = new org.objectweb.asm.Label();
        mv.visitTryCatchBlock(tryStart, tryEnd, catchHandler, "java/lang/Throwable");

        mv.visitLabel(tryStart);

        // 内联 CHECKCAST + ASTORE 取代 bridge 方法的类型转换，元空间损耗减少 150–250B/类
        org.objectweb.asm.Type[] argTypes = org.objectweb.asm.Type.getArgumentTypes(methodDescriptor);
        if (argTypes.length > 0 && !argTypes[0].getInternalName().equals(payloadInternal)) {
            mv.visitVarInsn(ALOAD, 1);
            mv.visitTypeInsn(CHECKCAST, payloadInternal);
            mv.visitVarInsn(ASTORE, 1);
        }

        emitVarExtractionWithCSE(mv, unit.vars(), ctx, payloadInternal, liveVars);

        for (FlowNode node : unit.flow()) {
            // 常量已外置，不再需要向节点注入 _className
            int line = node.getLineNumber();
            if (line > 0) {
                org.objectweb.asm.Label sourceLineLabel = new org.objectweb.asm.Label();
                mv.visitLabel(sourceLineLabel);
                mv.visitLineNumber(line, sourceLineLabel);
            }

            node.type().handler().emit(node, mv, ctx);

            if (node.type().handler().capabilities().contains(ScriptIR.NodeCapability.TERMINATES_FLOW)) {
                // 短路优化：如果前一个节点明确包含 TERMINATES_FLOW 断言，停止往下发射。
                break;
            }
        }

        // 正常干通返回：依据原生需求返回默认的 0 或 null
        emitDefaultReturn(mv, ctx.targetReturnType());
        mv.visitLabel(tryEnd);

        // ---- catch(Throwable t) ----
        mv.visitLabel(catchHandler);
        // 栈顶: Throwable, 存到临时槽
        int exSlot = ctx.nextSlot();
        mv.visitVarInsn(ASTORE, exSlot);

        // 调用 ScriptErrorHandler.handleException(Throwable, String, String)
        mv.visitVarInsn(ALOAD, exSlot);
        mv.visitLdcInsn(className);
        mv.visitVarInsn(ALOAD, 0); // this
        mv.visitFieldInsn(GETFIELD, className, "$scriptId", "Ljava/lang/String;");
        mv.visitMethodInsn(INVOKESTATIC, org.objectweb.asm.Type.getInternalName(ScriptErrorHandler.class),
                "handleException", "(Ljava/lang/Throwable;Ljava/lang/String;Ljava/lang/String;)V", false);

        // 异常干通返回：依据原生需求返回默认的 0 或 null
        emitDefaultReturn(mv, ctx.targetReturnType());

        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private void emitDefaultReturn(MethodVisitor mv, org.objectweb.asm.Type retType) {
        if (retType.getSort() == org.objectweb.asm.Type.VOID) {
            mv.visitInsn(RETURN);
        } else if (retType.getSort() == org.objectweb.asm.Type.OBJECT
                || retType.getSort() == org.objectweb.asm.Type.ARRAY) {
            mv.visitInsn(ACONST_NULL);
            mv.visitInsn(ARETURN);
        } else if (retType.getSort() == org.objectweb.asm.Type.DOUBLE) {
            mv.visitInsn(DCONST_0);
            mv.visitInsn(DRETURN);
        } else if (retType.getSort() == org.objectweb.asm.Type.FLOAT) {
            mv.visitInsn(FCONST_0);
            mv.visitInsn(FRETURN);
        } else if (retType.getSort() == org.objectweb.asm.Type.LONG) {
            mv.visitInsn(LCONST_0);
            mv.visitInsn(LRETURN);
        } else {
            // int/boolean/short/byte/char
            mv.visitInsn(ICONST_0);
            mv.visitInsn(IRETURN);
        }
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
            if (var.isPayloadAlias())
                continue; // 别名直接引用 slot 1（方法入参），无需任何提取指令

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
     * <p>
     * 支持普通变量占位符 {@code {hp}} 和窄化点链 {@code {entity.name}}。
     * 窄化点链要求目标变量已经通过 {@code check: instanceof} 完成窄化。
     */
    public static void emitStringConcat(MethodVisitor mv, String template, CompilationContext ctx) {
        List<String> parts = ScriptIR.parseTemplate(template);
        StringBuilder recipe     = new StringBuilder();
        StringBuilder descriptor = new StringBuilder("(");

        for (String part : parts) {
            // ---- 纯文本段：转义 recipe 保留字符后原样追加 ----
            if (!isTemplatePart(template, part)) {
                for (char c : part.toCharArray()) {
                    if (c == '\u0001' || c == '\u0002') recipe.append('\u0002');
                    recipe.append(c);
                }
                continue;
            }

            // ---- 占位符段：发射 LOAD + 追加描述符 ----
            recipe.append('\u0001');
            if (ScriptIR.isDottedPart(part)) {
                // 窄化点链：ALOAD slot + CHECKCAST + accessor 链，返回末端类型
                Class<?> propRaw = emitNarrowedPropertyLoad(mv, ctx, part).getRawType();
                descriptor.append(concatDescriptorOf(propRaw));
            } else {
                // 普通变量槽：类型感知 LOAD
                descriptor.append(emitSlotLoad(mv, ctx.getSlot(part), ctx.getType(part)));
            }
        }

        descriptor.append(")Ljava/lang/String;");
        mv.visitInvokeDynamicInsn(
                "makeConcatWithConstants",
                descriptor.toString(),
                STRING_CONCAT_HANDLE,
                recipe.toString());
    }

    /**
     * 返回 invokedynamic MethodType 参数中对应原生类型的描述符片段。
     * 非原生类型统一用 {@code Ljava/lang/Object;}。
     * int 与 boolean 均映射至 {@code I}（与 JVM 局部变量槽类型一致）。
     */
    private static String concatDescriptorOf(Class<?> raw) {
        if (raw == int.class)     return "I";
        if (raw == boolean.class) return "Z"; // StringConcatFactory 用 Z 才输出 true/false
        if (raw == long.class)    return "J";
        if (raw == double.class)  return "D";
        if (raw == float.class)   return "F";
        return "Ljava/lang/Object;";
    }

    /**
     * 针对给定槽和 IR 类型发射类型正确的 LOAD 指令，返回对应描述符片段。
     * 将"发射指令"与"生成描述符"合二为一，消除原有两路并行的 switch。
     */
    private static String emitSlotLoad(MethodVisitor mv, int slot, ScriptIR.IRType type) {
        return switch (type.base()) {
            case INT     -> { mv.visitVarInsn(ILOAD, slot); yield "I"; }
            case BOOLEAN -> { mv.visitVarInsn(ILOAD, slot); yield "Z"; } // 槽类型同 int，但描述符用 Z
            case LONG    -> { mv.visitVarInsn(LLOAD, slot); yield "J"; }
            case DOUBLE  -> { mv.visitVarInsn(DLOAD, slot); yield "D"; }
            default      -> { mv.visitVarInsn(ALOAD, slot); yield "Ljava/lang/Object;"; }
        };
    }

    /**
     * 发射窄化点链属性读取： ALOAD slot + CHECKCAST narrowedClass + 属性链 emitLoad。
     * <p>
     * 例：{@code entity.playerListName} →
     * {@code ALOAD slot; CHECKCAST Player; INVOKEVIRTUAL Player.getPlayerListName}。
     *
     * @param mv   MethodVisitor
     * @param ctx  编译上下文
     * @param part 点链引用，如 {@code "entity.name"}
     * @throws cn.warriorview.script.core.ScriptCompileException 若变量未窄化
     */
    /**
     * 发射窄化点链属性读取，并返回最终 accessor 的真实返回类型（供调用方决定 invokedynamic 描述符）。
     *
     * @return 末端 accessor 的 {@code TypeToken}；若无 accessor 则返回 {@code TypeToken.of(narrowed)}
     */
    public static com.google.common.reflect.TypeToken<?> emitNarrowedPropertyLoad(
            MethodVisitor mv, CompilationContext ctx, String part) {
        String[] kv = ScriptIR.splitDotted(part);
        String varName = kv[0];
        String propPath = kv[1];

        Class<?> narrowed = ctx.getNarrowedClass(varName);
        if (narrowed == null) {
            throw new cn.warriorview.script.core.ScriptCompileException(
                    "Dotted template {" + part + "}: variable '" + varName +
                    "' has no narrowed type. Add 'check: op: instanceof' before this action.");
        }

        int slot = ctx.getSlot(varName);
        mv.visitVarInsn(ALOAD, slot);
        mv.visitTypeInsn(CHECKCAST, org.objectweb.asm.Type.getInternalName(narrowed));

        List<cn.warriorview.script.parser.accessor.PropertyAccessor> accessors =
                ScriptParser.PropertyResolver.resolveAccessors(
                        com.google.common.reflect.TypeToken.of(narrowed), propPath);
        for (cn.warriorview.script.parser.accessor.PropertyAccessor acr : accessors) {
            acr.emitLoad(mv);
        }
        return accessors.isEmpty()
                ? com.google.common.reflect.TypeToken.of(narrowed)
                : accessors.get(accessors.size() - 1).returnType();
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
