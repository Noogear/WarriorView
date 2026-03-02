package cn.warriorview.script.core;

/**
 * 脚本编译异常。
 * 当出现语法、类型或结构错误时抛出，通常会包含纯英文的友好多行提示。
 */
public class ScriptCompileException extends RuntimeException {
    public ScriptCompileException(String message) {
        super(message);
    }

    public ScriptCompileException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * 根据出现错误的语法节点自动注入行号信息。
     * 例如将 "Invalid type" 装饰为 "[Line: 5] Invalid type"
     */
    public static ScriptCompileException create(ScriptIR.FlowNode node, String message) {
        if (node != null && node.getLineNumber() > 0) {
            return new ScriptCompileException(String.format("[Line: %d] %s", node.getLineNumber(), message));
        }
        return new ScriptCompileException(message);
    }
}
