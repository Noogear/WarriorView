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

    public byte[] compile(ScriptUnit unit, CompilationContext ctx) {
        String basePackage = BytecodeCompiler.class.getPackage().getName().replace('.', '/');
        String className = basePackage + "/generated/Script$" + Integer.toHexString(unit.hashCode());
        String payloadInternal = unit.payloadClass().replace('.', '/');

        List<ConstantDef> constants = ctx.hoistedConstants();
        Set<String> liveVars = ctx.liveVars();

        // 实现 CompilationContext 提供的动态目标接口
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V21, ACC_PUBLIC | ACC_FINAL | ACC_SUPER,
                className, null, OBJECT_INTERNAL,
                new String[] { ctx.targetInterfaceInternalName() });

        emitStaticFields(cw, constants);
        cw.visitField(ACC_PUBLIC | ACC_FINAL, "$scriptId", "Ljava/lang/String;", null, null).visitEnd();
        emitClinit(cw, className, constants);
        emitConstructor(cw, className);

        String typedDescriptor = "(L" + payloadInternal + ";)" + ctx.targetReturnType().getDescriptor();

        // 当接口参数因为泛型擦除变成 Object，或者与具体 Payload 不一致时，生成桥接方法
        if (!ctx.targetMethodDescriptor().equals(typedDescriptor)) {
            emitBridgeMethod(cw, className, payloadInternal, ctx.targetMethodName(), ctx.targetMethodDescriptor(),
                    typedDescriptor);
        }

        emitTargetMethod(cw, className, unit, ctx, liveVars, payloadInternal, typedDescriptor);

        cw.visitEnd();
        byte[] bytes = cw.toByteArray();
        try {
            java.nio.file.Files.write(java.nio.file.Paths.get("ScriptDump.class"), bytes);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return bytes;
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

    // ======================== 桥接 + apply 方法 ========================

    /**
     * bridge 桥接方法：例如实现了 Function 接口但需要类型转换。
     * 调用真实的强类型 PayloadType 签名方法。
     */
    private void emitBridgeMethod(ClassWriter cw, String className, String payloadInternal,
            String targetMethodName, String erasedDescriptor, String typedDescriptor) {
        MethodVisitor mv = cw.visitMethod(
                ACC_PUBLIC | ACC_BRIDGE | ACC_SYNTHETIC,
                targetMethodName, erasedDescriptor, null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0); // this

        // 提取被擦除的入参指令
        org.objectweb.asm.Type erasedArgs[] = org.objectweb.asm.Type.getArgumentTypes(erasedDescriptor);
        if (erasedArgs.length > 0) {
            org.objectweb.asm.Type firstArg = erasedArgs[0];
            mv.visitVarInsn(firstArg.getOpcode(ILOAD), 1);
            if (!firstArg.getInternalName().equals(payloadInternal)) {
                mv.visitTypeInsn(CHECKCAST, payloadInternal);
            }
        }

        // 调用我们生成的强类型方法
        mv.visitMethodInsn(INVOKEVIRTUAL, className, targetMethodName, typedDescriptor, false);

        // 返回转换
        org.objectweb.asm.Type returnType = org.objectweb.asm.Type.getReturnType(erasedDescriptor);
        mv.visitInsn(returnType.getOpcode(IRETURN));

        mv.visitMaxs(2, 2);
        mv.visitEnd();
    }

    /**
     * 生成目标强类型方法主体。
     * <ul>
     * <li>RETURN 节点 → 由 ReturnNodeHandler 自行发射底层原始返回码（IRETURN、ARETURN 等）</li>
     * <li>尾部干通兼容：若未显式执行任何 Return，根据目标返回类型返回默认值（引用的 null，或者原生的 0）</li>
     * </ul>
     */
    private void emitTargetMethod(ClassWriter cw, String className,
            ScriptUnit unit, CompilationContext ctx,
            Set<String> liveVars, String payloadInternal, String typedDescriptor) {

        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, ctx.targetMethodName(), typedDescriptor, null, null);
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

            // 核心功能：自动将 SnakeYAML 提取的脚本行号注入生成的 JVM 核心字节码中
            int line = enhancedNode.getLineNumber();
            if (line > 0) {
                org.objectweb.asm.Label sourceLineLabel = new org.objectweb.asm.Label();
                mv.visitLabel(sourceLineLabel);
                mv.visitLineNumber(line, sourceLineLabel);
            }

            enhancedNode.type().handler().emit(enhancedNode, mv, ctx);

            if (enhancedNode.type().handler().capabilities().contains(ScriptIR.NodeCapability.TERMINATES_FLOW)) {
                // 短路优化：如果前一个节点明确包含 TERMINATES_FLOW 断言，停止往下发射。
                // 这在遇到 RETURN / ERROR 节点时阻止死代码的强制发射。
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
