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
}
