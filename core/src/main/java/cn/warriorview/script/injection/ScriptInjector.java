package cn.warriorview.script.injection;

import cn.warriorview.script.api.ScriptHost;
import cn.warriorview.script.core.CompilationPipeline;
import cn.warriorview.script.handler.ActionNodeHandler;
import cn.warriorview.script.handler.CheckNodeHandler;
import cn.warriorview.script.handler.ReturnNodeHandler;
import cn.warriorview.script.handler.SwitchNodeHandler;
import com.google.common.base.Preconditions;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 脚本注入器。
 * <p>
 * 将 YAML 脚本编译并通过 {@link ScriptHost} 注入到宿主平台。
 */
public final class ScriptInjector {

    private final ScriptHost host;
    private final CompilationPipeline pipeline;
    private final List<RegisteredScript> registered = new ArrayList<>();

    // 确保 Handler 注册（类加载触发 static 块）
    static {
        CheckNodeHandler.init();
        SwitchNodeHandler.init();
        ReturnNodeHandler.init();
        ActionNodeHandler.init();
    }

    public ScriptInjector(ScriptHost host) {
        this.host = Preconditions.checkNotNull(host, "host");
        this.pipeline = new CompilationPipeline();
    }

    /**
     * 编译并注入一个 YAML 脚本。
     */
    public RegisteredScript inject(InputStream yamlInput) {
        CompilationPipeline.CompiledScript compiled = pipeline.compile(yamlInput);

        // 获取荷载类
        Class<?> payloadClass;
        try {
            payloadClass = Class.forName(compiled.ir().payloadClass());
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("Payload class not found: " + compiled.ir().payloadClass(), e);
        }

        Consumer<Object> handler = (Consumer<Object>) compiled.newHandler();
        int priority = compiled.ir().priority();

        Object token = host.registerEvent(payloadClass, priority, handler);
        RegisteredScript reg = new RegisteredScript(token, payloadClass, handler);
        registered.add(reg);
        return reg;
    }

    /**
     * 注入通用代码回调。
     */
    public void injectCallback(Runnable compiled) {
        Preconditions.checkNotNull(compiled, "compiled");
        compiled.run();
    }

    /**
     * 卸载单个脚本。
     */
    public void eject(RegisteredScript script) {
        host.unregisterEvent(script.token());
        registered.remove(script);
    }

    /**
     * 卸载所有已注入的脚本。
     */
    public void ejectAll() {
        for (RegisteredScript reg : registered) {
            host.unregisterEvent(reg.token());
        }
        registered.clear();
    }

    /**
     * 获取已注册脚本数量。
     */
    public int registeredCount() {
        return registered.size();
    }

    /**
     * 已注册脚本记录。
     */
    public record RegisteredScript(
            Object token,
            Class<?> payloadClass,
            Consumer<Object> handler) {
    }
}
