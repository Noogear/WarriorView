package cn.warriorview.script.handler;

import cn.warriorview.script.codegen.ASMUtils;
import cn.warriorview.script.core.ParseContext;
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
    public FlowNode parse(ParseContext ctx) {
        // 判断是 any 还是 all
        Object anyRaw = ctx.get("any");
        Object allRaw = ctx.get("all");
        boolean isAny = anyRaw != null;

        List<?> conditionList = (List<?>) (isAny ? anyRaw : allRaw);
        FlowNodeType type = isAny ? FlowNodeType.ANY : FlowNodeType.ALL;

        if (conditionList == null || conditionList.isEmpty()) {
            throw ctx.error(type.name() + " node requires a non-empty list of conditions.");
        }

        // 解析子条件列表，每个子项可以是 CHECK、嵌套的 ANY/ALL
        ImmutableList.Builder<FlowNode> children = ImmutableList.builder();
        for (Object item : conditionList) {
            if (item instanceof Map<?, ?> rawMap) {
                Map<String, Object> childYaml = (Map<String, Object>) rawMap;

                if (childYaml.containsKey("any") || childYaml.containsKey("all")) {
                    // 嵌套的 ANY/ALL 节点
                    children.add(ScriptParser.parseFlowNode(ctx.withAttrs(childYaml)));
                } else {
                    // 普通 CHECK 条件（复用 CheckNodeHandler.parse）
                    children.add(FlowNodeType.CHECK.handler().parse(ctx.withAttrs(childYaml)));
                }
            } else {
                throw ctx.error("Invalid condition in " + type.name() + " node: " + item);
            }
        }

        ImmutableMap.Builder<String, Object> nodeAttrs = ImmutableMap.builder();
        nodeAttrs.put("children", children.build());

        // 支持 on_fail
        List<?> onFailRaw = ctx.get("on_fail");
        if (onFailRaw != null) {
            nodeAttrs.put("onFailNodes", ScriptParser.parseFlow(onFailRaw));
        }

        return new FlowNode(type, nodeAttrs.build());
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

        // 进入 any 前保存快照： any 内部的 instanceof 窄化不应泄漏到父级作用域
        Map<String, Class<?>> outerSnapshot = ctx.snapshotNarrowed();

        for (FlowNode child : children) {
            // 每个分支使用各自独立的快照，防止吉兆互串窄化
            Map<String, Class<?>> branchSnapshot = ctx.snapshotNarrowed();
            if (child.type() == FlowNodeType.ANY || child.type() == FlowNodeType.ALL) {
                // 嵌套复合节点：用临时 boolean 变量桥接
                emitNestedComposite(child, mv, ctx, passLabel, true);
            } else if (child.type().handler() instanceof ConditionEmitter emitter) {
                // 单个条件：条件成立时跳到 passLabel（OR 短路：任一 TRUE → 跳过 fail handler）
                int jumpOp = emitter.emitCondition(child, mv, ctx);
                mv.visitJumpInsn(jumpOp, passLabel);
            } else {
                throw cn.warriorview.script.core.ScriptCompileException.create(child,
                        "Node type " + child.type() + " is not supported inside ANY node.");
            }
            ctx.restoreNarrowed(branchSnapshot);
        }

        // 全部不满足 → on_fail + 提前退出（返回类型和方法签名一致）
        emitOnFail(node, mv, ctx);
        CheckNodeHandler.emitEarlyReturn(mv, ctx);

        mv.visitLabel(passLabel);
        // any 内部快照不传出到父级
        ctx.restoreNarrowed(outerSnapshot);
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

        // 进入 all 前保存快照
        Map<String, Class<?>> outerSnapshot = ctx.snapshotNarrowed();

        for (FlowNode child : children) {
            // 每个分支使用各自独立的快照
            Map<String, Class<?>> branchSnapshot = ctx.snapshotNarrowed();
            if (child.type() == FlowNodeType.ANY || child.type() == FlowNodeType.ALL) {
                emitNestedComposite(child, mv, ctx, failLabel, false);
            } else if (child.type().handler() instanceof ConditionEmitter emitter) {
                int jumpOp = emitter.emitCondition(child, mv, ctx);
                // jumpOp 是"条件成立时应跳转"的 opcode
                // 我们需要"条件失败时跳到 failLabel"→ 不满足时跳转
                int invertedOp = ASMUtils.invertJump(jumpOp);
                mv.visitJumpInsn(invertedOp, failLabel);
            } else {
                throw cn.warriorview.script.core.ScriptCompileException.create(child,
                        "Node type " + child.type() + " is not supported inside ALL node.");
            }
            ctx.restoreNarrowed(branchSnapshot);
        }

        // 全部通过
        mv.visitJumpInsn(Opcodes.GOTO, continueLabel);

        // 失败路径
        mv.visitLabel(failLabel);
        emitOnFail(node, mv, ctx);
        CheckNodeHandler.emitEarlyReturn(mv, ctx);

        mv.visitLabel(continueLabel);
        // all 内部快照不传出到父级
        ctx.restoreNarrowed(outerSnapshot);
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
            // 嵌套 ANY：任一 TRUE → nestedPassLabel（OR 短路）
            for (FlowNode gc : grandChildren) {
                if (gc.type() == FlowNodeType.ANY || gc.type() == FlowNodeType.ALL) {
                    emitNestedComposite(gc, mv, ctx, nestedPassLabel, true);
                } else if (gc.type().handler() instanceof ConditionEmitter emitter) {
                    int jumpOp = emitter.emitCondition(gc, mv, ctx);
                    mv.visitJumpInsn(jumpOp, nestedPassLabel); // TRUE → jump to pass
                }
            }
            // 所有条件均不满足 → 显式跳到 nestedFailLabel（避免 fall-through 到 passLabel）
            mv.visitJumpInsn(Opcodes.GOTO, nestedFailLabel);

            mv.visitLabel(nestedPassLabel);
            if (jumpOnPass) {
                // ANY 在 ANY 内：任一通过 → 跳到父级 passLabel
                mv.visitJumpInsn(Opcodes.GOTO, parentTarget);
                // fail 路径 fall-through 到下一条父 ANY 子条件
                mv.visitLabel(nestedFailLabel);
            } else {
                // ANY 在 ALL 内：任一通过 → 跳过失败路径，继续 ALL 下一个子条件
                Label nestedDoneLabel = new Label();
                mv.visitJumpInsn(Opcodes.GOTO, nestedDoneLabel);
                mv.visitLabel(nestedFailLabel);
                // 全部失败 → ALL 某条件失败 → 跳到父级 failLabel
                mv.visitJumpInsn(Opcodes.GOTO, parentTarget);
                mv.visitLabel(nestedDoneLabel);
                // continue：继续 ALL 的下一个子条件
            }
        } else {
            // 嵌套 ALL：任一 FALSE → nestedFailLabel（AND 短路：失败即跳）
            for (FlowNode gc : grandChildren) {
                if (gc.type() == FlowNodeType.ANY || gc.type() == FlowNodeType.ALL) {
                    emitNestedComposite(gc, mv, ctx, nestedFailLabel, false);
                } else if (gc.type().handler() instanceof ConditionEmitter emitter) {
                    int jumpOp = emitter.emitCondition(gc, mv, ctx);
                    mv.visitJumpInsn(ASMUtils.invertJump(jumpOp), nestedFailLabel); // FALSE → jump to fail
                }
            }
            // 所有条件均通过 → 显式跳到 nestedPassLabel（避免 fall-through 到 failLabel）
            mv.visitJumpInsn(Opcodes.GOTO, nestedPassLabel);

            mv.visitLabel(nestedFailLabel);
            if (!jumpOnPass) {
                // ALL 在 ALL 内：失败 → 跳到父级 failLabel
                mv.visitJumpInsn(Opcodes.GOTO, parentTarget);
                // pass 路径 fall-through 到下一个 ALL 子条件
                mv.visitLabel(nestedPassLabel);
            } else {
                // ALL 在 ANY 内：失败 → 跳过通过路径，继续 ANY 下一个子条件
                Label nestedDoneLabel = new Label();
                mv.visitJumpInsn(Opcodes.GOTO, nestedDoneLabel);
                mv.visitLabel(nestedPassLabel);
                // 全部通过 → ANY 父级可 pass → 跳到父级 passLabel
                mv.visitJumpInsn(Opcodes.GOTO, parentTarget);
                mv.visitLabel(nestedDoneLabel);
                // continue：继续 ANY 的下一个子条件
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
