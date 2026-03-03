package cn.warriorview.script.math;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Stack;

// 以下仅用于 NAMED_CONSTANTS 初始化
import static java.util.Map.entry;

/**
 * Shunting Yard 数学表达式解析器。
 *
 * <p>
 * 扩展特性：
 * <ul>
 * <li><b>常量折叠</b>：若二元操作符两侧均为字面量，编译期直接计算并折叠为单个
 * {@link MathNode.LiteralNode}，无运行时开销。</li>
 * <li><b>编译期变量绑定</b>：通过 {@link #parseWithIndex(String, Map)} 将变量名在编译期绑定为
 * {@code double[]} 数组下标，评估时为纯数组索引操作，无装箱。</li>
 * </ul>
 */
public class MathParser {

    /** 预定义常量名称 → 值映射（在标识符识别时优先匹配）。 */
    private static final Map<String, Double> NAMED_CONSTANTS = Map.ofEntries(
            entry("pi",    Math.PI),
            entry("e",     Math.E),
            entry("true",  1.0),
            entry("false", 0.0)
    );

    private final String input;
    private int pos = 0;
    private Token prevToken = null;

    private enum TokenType {
        NUMBER, VARIABLE, FUNCTION, OPERATOR, LPAREN, RPAREN, COMMA
    }

    private record Token(TokenType type, Object value) {
    }

    public MathParser(String input) {
        this.input = input;
    }

    /**
     * 解析表达式，变量节点使用名称（脚本内置路径）。
     */
    public static MathNode parse(String expression) {
        return new MathParser(expression).parseExpression();
    }

    /**
     * 解析表达式，并将变量名编译期绑定到 {@code double[]} 下标。
     *
     * @param expression 数学表达式字符串
     * @param varIndex   变量名 → 数组下标的映射（在编译时确定）
     * @return 已完成索引绑定的 AST（变量节点带 {@code index >= 0}）
     */
    public static MathNode parseWithIndex(String expression, Map<String, Integer> varIndex) {
        MathNode root = new MathParser(expression).parseExpression();
        return bindIndex(root, varIndex);
    }

    // ======================== 内部解析 ========================

    private MathNode parseExpression() {
        Stack<MathNode> nodes = new Stack<>();
        Stack<Object> operators = new Stack<>(); // Operator | MathFunction | Character '('

        while (pos < input.length()) {
            char c = input.charAt(pos);

            if (Character.isWhitespace(c)) {
                pos++;
                continue;
            }

            if (c == '(') {
                operators.push(c);
                prevToken = new Token(TokenType.LPAREN, c);
                pos++;
            } else if (c == ')') {
                while (!operators.isEmpty() && !operators.peek().equals('(')) {
                    popOperatorToNode(operators, nodes);
                }
                if (!operators.isEmpty() && operators.peek().equals('(')) {
                    operators.pop();
                } else {
                    throw new IllegalArgumentException("Mismatched parentheses");
                }
                if (!operators.isEmpty() && operators.peek() instanceof MathFunction) {
                    MathFunction func = (MathFunction) operators.pop();
                    List<MathNode> args = new ArrayList<>(func.getArgCount());
                    for (int i = 0; i < func.getArgCount(); i++) {
                        if (nodes.isEmpty())
                            throw new IllegalArgumentException("Not enough arguments for function " + func);
                        args.add(0, nodes.pop());
                    }
                    nodes.push(new MathNode.FunctionNode(func, args));
                }
                prevToken = new Token(TokenType.RPAREN, c);
                pos++;
            } else if (c == ',') {
                while (!operators.isEmpty() && !operators.peek().equals('(')) {
                    popOperatorToNode(operators, nodes);
                }
                prevToken = new Token(TokenType.COMMA, c);
                pos++;
            } else if (isOperatorStartChar(c)) {
                // 双字符前瞻（==, !=, >=, <=, ||, &&）
                String twoChar = (pos + 1 < input.length())
                        ? String.valueOf(c) + input.charAt(pos + 1) : null;
                Operator twoOp = (twoChar != null) ? Operator.fromSymbol(twoChar) : null;

                if (twoOp != null) {
                    // 所有双字符运算符均为左结合二元运算符
                    pushBinaryOp(twoOp, operators, nodes);
                    prevToken = new Token(TokenType.OPERATOR, twoChar);
                    pos += 2;
                } else if (c == '!') {
                    // 单独的 ! → 逻辑非（一元前缀）
                    operators.push("UNARY_NOT");
                    prevToken = new Token(TokenType.OPERATOR, c);
                    pos++;
                } else {
                    Operator op = Operator.fromSymbol(c);
                    boolean isUnary = (c == '-' || c == '+') &&
                            (prevToken == null || prevToken.type == TokenType.LPAREN
                                    || prevToken.type == TokenType.COMMA
                                    || prevToken.type == TokenType.OPERATOR);

                    if (isUnary) {
                        operators.push(c == '-' ? "UNARY_MINUS" : "UNARY_PLUS");
                    } else {
                        if (op == null)
                            throw new IllegalArgumentException("Unknown operator: " + c);
                        pushBinaryOp(op, operators, nodes);
                    }
                    prevToken = new Token(TokenType.OPERATOR, c);
                    pos++;
                }
            } else if (Character.isDigit(c) || c == '.') {
                int start = pos;
                while (pos < input.length() && (Character.isDigit(input.charAt(pos)) || input.charAt(pos) == '.')) {
                    pos++;
                }
                // 科学计数法：如 2E7、1.5e-3
                if (pos < input.length() && (input.charAt(pos) == 'E' || input.charAt(pos) == 'e')) {
                    int ePos = pos;
                    pos++; // consume 'E'/'e'
                    if (pos < input.length() && (input.charAt(pos) == '+' || input.charAt(pos) == '-')) {
                        pos++; // consume optional sign
                    }
                    if (pos < input.length() && Character.isDigit(input.charAt(pos))) {
                        while (pos < input.length() && Character.isDigit(input.charAt(pos))) pos++;
                    } else {
                        pos = ePos; // 不是合法科学计数法，回滚
                    }
                }
                double val = Double.parseDouble(input.substring(start, pos));
                nodes.push(new MathNode.LiteralNode(val));
                prevToken = new Token(TokenType.NUMBER, val);
            } else if (Character.isLetter(c) || c == '_' || c == '{') {
                boolean isBraced = (c == '{');
                int start = pos;
                if (isBraced) {
                    pos++;
                    while (pos < input.length() && input.charAt(pos) != '}')
                        pos++;
                    if (pos < input.length())
                        pos++; // consume '}'
                } else {
                    while (pos < input.length()
                            && (Character.isLetterOrDigit(input.charAt(pos)) || input.charAt(pos) == '_')) {
                        pos++;
                    }
                }
                String identifier = isBraced ? input.substring(start + 1, pos - 1) : input.substring(start, pos);

                // 1. 预定义常量优先（pi、e、true、false）
                Double namedConst = !isBraced ? NAMED_CONSTANTS.get(identifier) : null;
                if (namedConst != null) {
                    nodes.push(new MathNode.LiteralNode(namedConst));
                    prevToken = new Token(TokenType.NUMBER, namedConst);
                } else {
                    // 2. 内置函数
                    MathFunction func = MathFunction.fromName(identifier);
                    if (func != null && !isBraced) {
                        operators.push(func);
                        prevToken = new Token(TokenType.FUNCTION, func);
                    } else {
                        // 3. 用户定义变量
                        nodes.push(new MathNode.VariableNode(identifier));
                        prevToken = new Token(TokenType.VARIABLE, identifier);
                    }
                }
            } else {
                throw new IllegalArgumentException("Unexpected character in math expression: " + c);
            }
        }

        while (!operators.isEmpty()) {
            if (operators.peek().equals('('))
                throw new IllegalArgumentException("Mismatched parentheses");
            popOperatorToNode(operators, nodes);
        }

        if (nodes.isEmpty())
            throw new IllegalArgumentException("Empty expression");
        if (nodes.size() > 1)
            throw new IllegalArgumentException("Invalid expression format");

        // 后序折叠 pass：处理单遍 Shunting-Yard 憟留的复合化简（x*x*x→x^3、0-(0-x)→x 等）
        return foldNode(nodes.pop());
    }

    /**
     * 弹出一个操作符并将原始节点压入 nodes 栈。
     *
     * <p><b>纯解析，零优化</b>：仅负责节点构造，不做任何常量折叠或恒等式化简。
     * 所有优化均属于 {@link #foldNode}，使两个职责完全分离。
     */
    private void popOperatorToNode(Stack<Object> operators, Stack<MathNode> nodes) {
        Object opObj = operators.pop();
        if (opObj.equals("UNARY_MINUS")) {
            if (nodes.isEmpty())
                throw new IllegalArgumentException("Missing operand for unary minus");
            nodes.push(new MathNode.UnaryNode(nodes.pop(), true));
        } else if (opObj.equals("UNARY_PLUS")) {
            if (nodes.isEmpty())
                throw new IllegalArgumentException("Missing operand for unary plus");
            // +x == x，操作数已在栈顶，不需额外操作
        } else if (opObj.equals("UNARY_NOT")) {
            if (nodes.isEmpty())
                throw new IllegalArgumentException("Missing operand for logical not");
            // !x 语义：(x == 0.0)。常量折叠由 foldNode 处理
            nodes.push(new MathNode.BinaryNode(nodes.pop(), new MathNode.LiteralNode(0.0), Operator.EQ));
        } else if (opObj instanceof Operator op) {
            if (nodes.size() < 2)
                throw new IllegalArgumentException("Missing operands for operator " + opObj);
            MathNode right = nodes.pop();
            MathNode left  = nodes.pop();
            nodes.push(new MathNode.BinaryNode(left, right, op));
        } else if (opObj instanceof MathFunction func) {
            List<MathNode> args = new ArrayList<>(func.getArgCount());
            for (int i = 0; i < func.getArgCount(); i++) {
                if (nodes.isEmpty())
                    throw new IllegalArgumentException("Not enough arguments for function " + func);
                args.add(0, nodes.pop());
            }
            nodes.push(new MathNode.FunctionNode(func, args));
        }
    }

    /**
     * 代数恒等式化简（一侧为字面量时尝试消除冗余节点）。
     *
     * <ul>
     * <li>{@code x*0} / {@code 0*x} → {@code 0}</li>
     * <li>{@code x*1} / {@code 1*x} → {@code x}</li>
     * <li>{@code x+0} / {@code 0+x} → {@code x}</li>
     * <li>{@code x-0} → {@code x}，{@code 0-x} → {@code -x}</li>
     * <li>{@code x/1} → {@code x}</li>
     * <li>{@code x^0} → {@code 1}，{@code x^1} → {@code x}</li>
     * <li>{@code -1*x} / {@code x*-1} → {@code -x}（DNEG 替代 DMUL）</li>
     * <li>{@code x*x}（同变量）→ {@code x^2}，触发 DUP2+DMUL 特化</li>
     * <li>{@code false&&expr} / {@code expr&&false} → {@code false}</li>
     * <li>{@code true||expr} / {@code expr||true} → {@code true}</li>
     * </ul>
     *
     * @return 化简后的节点；若无法化简则返回 {@code null}
     */
    private static MathNode tryFoldIdentity(MathNode left, MathNode right, Operator op) {
        return switch (op) {
            case ADD -> {
                if (isZero(left)) yield right;
                if (isZero(right)) yield left;
                yield null;
            }
            case SUBTRACT -> {
                if (isZero(right)) yield left;
                // 0 - x → -x（消除加减节点，换为取负指令）
                if (isZero(left)) yield new MathNode.UnaryNode(right, true);
                yield null;
            }
            case MULTIPLY -> {
                if (isZero(left) || isZero(right)) yield new MathNode.LiteralNode(0.0);
                if (isOne(left)) yield right;
                if (isOne(right)) yield left;
                // -1 * x / x * -1 → -x（单条 DNEG 替代 DMUL）
                if (isNegOne(left)) yield new MathNode.UnaryNode(right, true);
                if (isNegOne(right)) yield new MathNode.UnaryNode(left, true);
                // x * x（同变量）→ x^2，触发 MathNodeEmitter 的 DUP2+DMUL 特化路径
                if (sameVar(left, right))
                    yield new MathNode.BinaryNode(left, new MathNode.LiteralNode(2.0), Operator.POWER);
                // x^n * x → x^(n+1)：累积幂次，使 x*x*x 最终触发 DUP2×n 特化
                if (left instanceof MathNode.BinaryNode bl && bl.op() == Operator.POWER
                        && bl.right() instanceof MathNode.LiteralNode exp && sameVar(bl.left(), right))
                    yield new MathNode.BinaryNode(bl.left(), new MathNode.LiteralNode(exp.value() + 1.0), Operator.POWER);
                // x * x^n → x^(n+1)
                if (right instanceof MathNode.BinaryNode br && br.op() == Operator.POWER
                        && br.right() instanceof MathNode.LiteralNode exp && sameVar(left, br.left()))
                    yield new MathNode.BinaryNode(br.left(), new MathNode.LiteralNode(exp.value() + 1.0), Operator.POWER);
                // x^n * x^m → x^(n+m)
                if (left instanceof MathNode.BinaryNode bl2 && bl2.op() == Operator.POWER
                        && bl2.right() instanceof MathNode.LiteralNode el
                        && right instanceof MathNode.BinaryNode br2 && br2.op() == Operator.POWER
                        && br2.right() instanceof MathNode.LiteralNode er
                        && sameVar(bl2.left(), br2.left()))
                    yield new MathNode.BinaryNode(bl2.left(), new MathNode.LiteralNode(el.value() + er.value()), Operator.POWER);
                yield null;
            }
            case DIVIDE -> {
                if (isOne(right)) yield left;
                yield null;
            }
            case POWER -> {
                if (isZero(right)) yield new MathNode.LiteralNode(1.0);
                if (isOne(right)) yield left;
                yield null;
            }
            case AND -> {
                // false && expr → false（右侧死代码消除）
                if (isZero(left)) yield new MathNode.LiteralNode(0.0);
                if (isZero(right)) yield new MathNode.LiteralNode(0.0);
                yield null;
            }
            case OR -> {
                // true || expr → true（右侧死代码消除）
                if (isNonZeroLiteral(left)) yield new MathNode.LiteralNode(1.0);
                if (isNonZeroLiteral(right)) yield new MathNode.LiteralNode(1.0);
                yield null;
            }
            default -> null;
        };
    }

    private static boolean isZero(MathNode n) {
        return n instanceof MathNode.LiteralNode l && l.value() == 0.0;
    }

    private static boolean isOne(MathNode n) {
        return n instanceof MathNode.LiteralNode l && l.value() == 1.0;
    }

    private static boolean isNegOne(MathNode n) {
        return n instanceof MathNode.LiteralNode l && l.value() == -1.0;
    }

    /** 字面量且值非零（用于 OR 短路：非零即为真）。 */
    private static boolean isNonZeroLiteral(MathNode n) {
        return n instanceof MathNode.LiteralNode l && l.value() != 0.0;
    }

    /** 两个节点是否为同名/同下标变量（用于 x*x → x^2 归约）。 */
    private static boolean sameVar(MathNode a, MathNode b) {
        if (a instanceof MathNode.VariableNode va && b instanceof MathNode.VariableNode vb) {
            // 独立 API 路径（index >= 0）按下标比较；脚本 IR 路径按名称比较
            return va.index() >= 0 ? va.index() == vb.index() : va.name().equals(vb.name());
        }
        return false;
    }

    // ======================== 后序折叠 pass ========================

    /**
     * 对整棵 AST 做一次后序（自底向上）递归折叠。
     *
     * <p>本方法是<b>单一权威优化入口</b>：所有常量折叠、恒等式化简、双重取负消除、
     * 幂次累积和 AND/OR 短路均在此完成，不分散到其他地方。
     *
     * <p>关键：当 {@link #tryFoldIdentity} 产生新节点时对产物再调用 {@code foldNode}，
     * 使得一次后序 pass 就能递归处理任意层深的复合折叠（如 0-(0-x)→x）。
     */
    private static MathNode foldNode(MathNode node) {
        return switch (node) {
            case MathNode.LiteralNode lit  -> lit;
            case MathNode.VariableNode v   -> v;

            case MathNode.UnaryNode u -> {
                MathNode operand = foldNode(u.operand());
                if (!u.isNegation()) yield operand;                    // +x = x
                if (operand instanceof MathNode.LiteralNode lit)
                    yield new MathNode.LiteralNode(-lit.value());       // -literal → 折叠
                if (operand instanceof MathNode.UnaryNode inner && inner.isNegation())
                    yield inner.operand();                              // --x → x
                yield operand == u.operand() ? u : new MathNode.UnaryNode(operand, true);
            }

            case MathNode.FunctionNode f -> {
                // 先对全部子节点折叠，再尝试函数霪开（无论子节点是否改变）
                List<MathNode> newArgs = new ArrayList<>(f.arguments().size());
                for (MathNode arg : f.arguments()) newArgs.add(foldNode(arg));
                if (f.function().isFoldable()
                        && newArgs.stream().allMatch(a -> a instanceof MathNode.LiteralNode)) {
                    double[] vals = newArgs.stream()
                            .mapToDouble(a -> ((MathNode.LiteralNode) a).value()).toArray();
                    yield new MathNode.LiteralNode(f.function().apply(vals));
                }
                boolean changed = false;
                for (int i = 0; i < newArgs.size(); i++)
                    if (newArgs.get(i) != f.arguments().get(i)) { changed = true; break; }
                yield changed ? new MathNode.FunctionNode(f.function(), newArgs) : f;
            }

            case MathNode.BinaryNode b -> {
                MathNode left  = foldNode(b.left());
                MathNode right = foldNode(b.right());
                // 两侧均为字面量——直接计算
                if (left instanceof MathNode.LiteralNode l && right instanceof MathNode.LiteralNode r)
                    yield new MathNode.LiteralNode(b.op().apply(l.value(), r.value()));
                // 代数恒等式 + 结构化简（对产物再折以处理复合情况）
                MathNode folded = tryFoldIdentity(left, right, b.op());
                if (folded != null) yield foldNode(folded);
                yield (left == b.left() && right == b.right()) ? b
                        : new MathNode.BinaryNode(left, right, b.op());
            }
        };
    }

    /** 判断字符是否可能是运算符的起始字符（含双字符运算符的首字符）。 */
    private boolean isOperatorStartChar(char c) {
        return c == '+' || c == '-' || c == '*' || c == '/' || c == '%' || c == '^'
                || c == '=' || c == '!' || c == '>' || c == '<' || c == '|' || c == '&';
    }

    /**
     * 将一个左结合/右结合二元运算符按 Shunting-Yard 规则压栈。
     * 先弹出优先级更高（或同级左结合）的待定运算符，再压入当前运算符。
     */
    private void pushBinaryOp(Operator op, Stack<Object> operators, Stack<MathNode> nodes) {
        while (!operators.isEmpty()) {
            Object topOpObj = operators.peek();
            if (topOpObj.equals('(') || topOpObj instanceof MathFunction)
                break;
            int topPrecedence = topOpObj instanceof Operator topOp ? topOp.getPrecedence() : 4;
            if ((op.isLeftAssociative() && op.getPrecedence() <= topPrecedence) ||
                    (!op.isLeftAssociative() && op.getPrecedence() < topPrecedence)) {
                popOperatorToNode(operators, nodes);
            } else {
                break;
            }
        }
        operators.push(op);
    }

    // ======================== 编译期变量索引绑定 ========================

    /**
     * 将 AST 中所有 {@link MathNode.VariableNode} 的名称替换为数组下标，
     * 用于 {@code double[]} 路径（零装箱评估）。
     */
    private static MathNode bindIndex(MathNode node, Map<String, Integer> varIndex) {
        return switch (node) {
            case MathNode.LiteralNode lit -> lit;
            case MathNode.VariableNode v -> {
                Integer idx = varIndex.get(v.name());
                if (idx == null)
                    throw new IllegalArgumentException("Unknown variable in expression: " + v.name());
                yield new MathNode.VariableNode(v.name(), idx);
            }
            case MathNode.UnaryNode u -> new MathNode.UnaryNode(bindIndex(u.operand(), varIndex), u.isNegation());
            case MathNode.BinaryNode b ->
                new MathNode.BinaryNode(bindIndex(b.left(), varIndex), bindIndex(b.right(), varIndex), b.op());
            case MathNode.FunctionNode f -> {
                List<MathNode> newArgs = new ArrayList<>(f.arguments().size());
                for (MathNode arg : f.arguments())
                    newArgs.add(bindIndex(arg, varIndex));
                yield new MathNode.FunctionNode(f.function(), newArgs);
            }
        };
    }
}
