package cn.warriorview.script.handler;

import cn.warriorview.script.codegen.ASMUtils;
import cn.warriorview.script.core.CompilationContext;
import cn.warriorview.script.core.ScriptIR.FlowNode;
import cn.warriorview.script.core.ScriptIR.FlowNodeType;
import cn.warriorview.script.core.ScriptIR.NodeCapability;
import cn.warriorview.script.parser.ScriptParser;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import cn.warriorview.script.core.ScriptIR.ConditionEmitter;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

/**
 * ANY / ALL 复合条件处理器。
 * <p>
 * 复用 {@link CheckNodeHandler#emitCondition} 发射单个条件的字节码，
 * 仅控制跳转方向实现 OR（ANY）和 AND（ALL）语义。
 * <p>
 * 支持任意嵌套：ANY 内可包含 ALL，ALL 内可包含 ANY。
 */
public final class CompositeCheckHandler implements cn.warriorview.script.core.ScriptIR.FlowNodeHandler,
        cn.warriorview.script.core.ScriptIR.NodeMutator {

    static {
        FlowNodeType.registerHandler(FlowNodeType.ANY, CompositeCheckHandler::new);
        FlowNodeType.registerHandler(FlowNodeType.ALL, CompositeCheckHandler::new);
    }

    public static void init() {
    }

    @Override
    @SuppressWarnings("unchecked")
    public FlowNode parse(Map<String, Object> yaml) {
        // 判断是 any 还是 all
        Object anyRaw = yaml.get("any");
        Object allRaw = yaml.get("all");
        boolean isAny = anyRaw != null;

        List<?> conditionList = (List<?>) (isAny ? anyRaw : allRaw);
        FlowNodeType type = isAny ? FlowNodeType.ANY : FlowNodeType.ALL;

        if (conditionList == null || conditionList.isEmpty()) {
            throw new cn.warriorview.script.core.ScriptCompileException(
                    type.name() + " node requires a non-empty list of conditions.");
        }

        // 解析子条件列表，每个子项可以是 CHECK、嵌套的 ANY/ALL
        ImmutableList.Builder<FlowNode> children = ImmutableList.builder();
        for (Object item : conditionList) {
            if (item instanceof Map<?, ?> rawMap) {
                @SuppressWarnings("unchecked")
                Map<String, Object> childYaml = (Map<String, Object>) rawMap;

                if (childYaml.containsKey("any") || childYaml.containsKey("all")) {
                    // 嵌套的 ANY/ALL 节点
                    children.add(ScriptParser.parseFlowNode(childYaml));
                } else {
                    // 普通 CHECK 条件（复用 CheckNodeHandler.parse）
                    children.add(FlowNodeType.CHECK.handler().parse(childYaml));
                }
            } else {
                throw new cn.warriorview.script.core.ScriptCompileException(
                        "Invalid condition in " + type.name() + " node: " + item);
            }
        }

        ImmutableMap.Builder<String, Object> attrs = ImmutableMap.builder();
        attrs.put("children", children.build());

        // 支持 on_fail
        List<?> onFailRaw = (List<?>) yaml.get("on_fail");
        if (onFailRaw != null) {
            attrs.put("onFailNodes", ScriptParser.parseFlow(onFailRaw));
        }

        return new FlowNode(type, attrs.build());
    }

    @Override
    public void emit(FlowNode node, MethodVisitor mv, CompilationContext ctx) {
        ImmutableList<FlowNode> children = node.getRequiredAttr("children");
        boolean isAny = node.type() == FlowNodeType.ANY;

        if (isAny) {
            emitAny(node, children, mv, ctx);
        } else {
            emitAll(node, children, mv, ctx);
        }
    }

    /**
     * ANY（OR 短路）：任一子条件成立即跳过失败路径。
     * 
     * <pre>
     *   cond1 → IF_PASS → passLabel
     *   cond2 → IF_PASS → passLabel
     *   ...
     *   (全部失败) → on_fail + RETURN
     *   passLabel: 继续执行
     * </pre>
     */
    private void emitAny(FlowNode node, ImmutableList<FlowNode> children,
            MethodVisitor mv, CompilationContext ctx) {
        Label passLabel = new Label();

        for (FlowNode child : children) {
            if (child.type() == FlowNodeType.ANY || child.type() == FlowNodeType.ALL) {
                // 嵌套复合节点：用临时 boolean 变量桥接
                emitNestedComposite(child, mv, ctx, passLabel, true);
            } else if (child.type().handler() instanceof ConditionEmitter emitter) {
                // 单个条件：发射条件，反转跳转方向 → 成立时跳到 passLabel
                int jumpOp = emitter.emitCondition(child, mv, ctx);
                int invertedOp = ASMUtils.invertJump(jumpOp);
                mv.visitJumpInsn(invertedOp, passLabel);
            } else {
                throw new cn.warriorview.script.core.ScriptCompileException(
                        "Node type " + child.type() + " is not supported inside ANY node.");
            }
        }

        // 全部不满足 → on_fail + return
        emitOnFail(node, mv, ctx);
        mv.visitInsn(Opcodes.RETURN);

        mv.visitLabel(passLabel);
    }

    /**
     * ALL（AND 短路）：任一子条件失败即触发失败路径。
     * 
     * <pre>
     *   cond1 → IF_FAIL → failLabel
     *   cond2 → IF_FAIL → failLabel
     *   ...
     *   (全部通过) → 继续执行
     *   failLabel: on_fail + RETURN
     * </pre>
     */
    private void emitAll(FlowNode node, ImmutableList<FlowNode> children,
            MethodVisitor mv, CompilationContext ctx) {
        Label failLabel = new Label();
        Label continueLabel = new Label();

        for (FlowNode child : children) {
            if (child.type() == FlowNodeType.ANY || child.type() == FlowNodeType.ALL) {
                emitNestedComposite(child, mv, ctx, failLabel, false);
            } else if (child.type().handler() instanceof ConditionEmitter emitter) {
                int jumpOp = emitter.emitCondition(child, mv, ctx);
                // jumpOp 是"条件成立时应跳转"的 opcode
                // 我们需要"条件失败时跳到 failLabel"→ 不满足时跳转
                int invertedOp = ASMUtils.invertJump(jumpOp);
                mv.visitJumpInsn(invertedOp, failLabel);
            } else {
                throw new cn.warriorview.script.core.ScriptCompileException(
                        "Node type " + child.type() + " is not supported inside ALL node.");
            }
        }

        // 全部通过
        mv.visitJumpInsn(Opcodes.GOTO, continueLabel);

        // 失败路径
        mv.visitLabel(failLabel);
        emitOnFail(node, mv, ctx);
        mv.visitInsn(Opcodes.RETURN);

        mv.visitLabel(continueLabel);
    }

    /**
     * 处理嵌套的 ANY/ALL：递归 emit，检测结果并桥接到父级的 pass/fail 标签。
     */
    private void emitNestedComposite(FlowNode child, MethodVisitor mv,
            CompilationContext ctx, Label parentTarget, boolean jumpOnPass) {
        // 嵌套复合节点的 emit 已完整处理 pass/fail 路径（内部有 RETURN）
        // 但我们需要的是"评估结果"而非直接 RETURN
        // 所以我们需要特殊处理：用和 CheckNodeHandler.emit 相同的模式
        // 为嵌套节点创建它自己的 pass/fail 标签

        ImmutableList<FlowNode> grandChildren = child.getRequiredAttr("children");
        boolean isNestedAny = child.type() == FlowNodeType.ANY;

        Label nestedPassLabel = new Label();
        Label nestedFailLabel = new Label();

        if (isNestedAny) {
            // 嵌套 ANY：任一成立 → nestedPassLabel
            for (FlowNode gc : grandChildren) {
                if (gc.type() == FlowNodeType.ANY || gc.type() == FlowNodeType.ALL) {
                    emitNestedComposite(gc, mv, ctx, nestedPassLabel, true);
                } else if (gc.type().handler() instanceof ConditionEmitter emitter) {
                    int jumpOp = emitter.emitCondition(gc, mv, ctx);
                    mv.visitJumpInsn(ASMUtils.invertJump(jumpOp), nestedPassLabel);
                }
            }
            // 全部不满足 → nestedFail
            mv.visitJumpInsn(Opcodes.GOTO, nestedFailLabel);
            mv.visitLabel(nestedPassLabel);

            // 嵌套 any 通过 → 如果父期望 jumpOnPass，跳到 parentTarget
            if (jumpOnPass) {
                mv.visitJumpInsn(Opcodes.GOTO, parentTarget);
            }
            // 否则继续（ALL 父级需要继续检查下一个子条件）

            mv.visitLabel(nestedFailLabel);
            if (!jumpOnPass) {
                // 嵌套 any 失败 → ALL 父级的某个条件失败 → 跳到 parentTarget(failLabel)
                mv.visitJumpInsn(Opcodes.GOTO, parentTarget);
            }
        } else {
            // 嵌套 ALL：任一失败 → nestedFailLabel
            for (FlowNode gc : grandChildren) {
                if (gc.type() == FlowNodeType.ANY || gc.type() == FlowNodeType.ALL) {
                    emitNestedComposite(gc, mv, ctx, nestedFailLabel, false);
                } else if (gc.type().handler() instanceof ConditionEmitter emitter) {
                    int jumpOp = emitter.emitCondition(gc, mv, ctx);
                    mv.visitJumpInsn(jumpOp, nestedFailLabel);
                }
            }
            // 全部通过 → nestedPass
            mv.visitJumpInsn(Opcodes.GOTO, nestedPassLabel);
            mv.visitLabel(nestedFailLabel);

            if (!jumpOnPass) {
                // 嵌套 all 失败 → 跳到 parentTarget
                mv.visitJumpInsn(Opcodes.GOTO, parentTarget);
            }

            mv.visitLabel(nestedPassLabel);
            if (jumpOnPass) {
                mv.visitJumpInsn(Opcodes.GOTO, parentTarget);
            }
        }
    }

    private void emitOnFail(FlowNode node, MethodVisitor mv, CompilationContext ctx) {
        ImmutableList<FlowNode> onFailNodes = node.getAttrOrDefault("onFailNodes", null);
        if (onFailNodes != null) {
            for (FlowNode failNode : onFailNodes) {
                failNode.type().handler().emit(failNode, mv, ctx);
            }
        }
    }

    @Override
    public EnumSet<NodeCapability> capabilities() {
        return EnumSet.of(NodeCapability.HAS_CONDITION);
    }

    @Override
    public Iterable<FlowNode> traverseChildren(FlowNode node) {
        ImmutableList<FlowNode> children = node.getAttrOrDefault("children", null);
        ImmutableList<FlowNode> onFailNodes = node.getAttrOrDefault("onFailNodes", null);
        if (children == null && onFailNodes == null) {
            return ImmutableList.of();
        }
        ArrayList<FlowNode> result = new ArrayList<>();
        if (children != null)
            result.addAll(children);
        if (onFailNodes != null)
            result.addAll(onFailNodes);
        return result;
    }

    @Override
    public FlowNode mapChildren(FlowNode node, java.util.function.Function<FlowNode, FlowNode> mapper) {
        ImmutableList<FlowNode> children = node.getAttrOrDefault("children", null);
        if (children == null) {
            return node;
        }

        boolean changed = false;
        ImmutableList.Builder<FlowNode> remapped = ImmutableList.builder();
        for (FlowNode child : children) {
            FlowNode mappedChild = mapper.apply(child);
            remapped.add(mappedChild);
            if (mappedChild != child) {
                changed = true;
            }
        }

        if (changed) {
            return node.withAttr("children", remapped.build());
        }
        return node;
    }
}
