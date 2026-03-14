package cn.warriorview.script;

import cn.warriorview.action.BuiltinActions;
import gloomlib.script.api.ScriptHost;
import gloomlib.script.api.injection.ScriptInjector;
import gloomlib.script.core.NodeRegistry;
import gloomlib.script.core.handler.ActionNodeHandler;
import gloomlib.script.core.parser.ScriptParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 模拟 Paper 环境，验证 event-mapping/*.yml 能正确编译。
 *
 * <p>使用真实的 Bukkit/Paper 类（{@code EntityDamageByEntityEvent}、
 * {@code EntityDamageEvent.DamageCause}、{@code EntityRegainHealthEvent.RegainReason} 等），
 * 通过脚本引擎编译事件映射脚本，确保整条编译链路——包括枚举/子类变量的静态类型推断——
 * 能完整走通，无需 {@code instanceof} 收窄。</p>
 *
 * <p>这等价于在服务端执行 {@code onEnable} 期间 {@link cn.warriorview.manager.BukkitScriptManager#reloadScripts()}
 * 调用链的编译阶段，复现并验证了原先的崩溃场景已修复。</p>
 */
@DisplayName("EventMapping YAML 编译测试（模拟 Paper 环境）")
class TestEventMappingCompilation {

    private static ScriptInjector injector;

    @BeforeAll
    static void setup() {
        // 注册所有内置 handler
        NodeRegistry.registerDefaults();

        // 注册内置动作（showIndicator）
        ActionNodeHandler.registry().scanAndRegister(BuiltinActions.class);

        // 构造 mock ScriptHost：registerEvent 仅返回 handler，不依赖 Bukkit 服务端
        ScriptHost mockHost = new ScriptHost() {
            @Override
            public Object registerEvent(Class<?> eventClass, int priority, Consumer<Object> handler) {
                return handler; // mock: 直接返回 consumer，不注册到 Bukkit
            }

            @Override
            public void unregisterEvent(Object token) {}
        };

        injector = new ScriptInjector(mockHost);
    }

    // ── 编译成功断言 ──────────────────────────────────────────────────────────

    @ParameterizedTest(name = "编译 {0}")
    @ValueSource(strings = {
            "event-mapping/damage-indicator.yml",
            "event-mapping/regain-indicator.yml"
    })
    @DisplayName("event-mapping YAML 编译无异常")
    void eventMappingCompilesWithoutError(String resourcePath) throws IOException {
        String yaml = loadResource(resourcePath);
        Map<String, Object> root = ScriptParser.parseYaml(yaml);

        assertDoesNotThrow(
                () -> injector.inject(root),
                "Script should compile without error: " + resourcePath);
    }

    // ── 关键类存在性验证 ──────────────────────────────────────────────────────

    /**
     * 验证 Bukkit/Paper 关键类在测试 classpath 中可用。
     * 若这些类不可访问，编译时 Class.forName() 会抛 ClassNotFoundException，
     * 导致上面的测试因错误原因失败。
     */
    @Test
    @DisplayName("Paper 事件与枚举类在类路径中可访问")
    void paperClassesAreAccessible() {
        assertDoesNotThrow(() -> Class.forName("org.bukkit.event.entity.EntityDamageByEntityEvent"),
                "EntityDamageByEntityEvent should be on classpath");
        assertDoesNotThrow(() -> Class.forName("org.bukkit.event.entity.EntityDamageEvent$DamageCause"),
                "EntityDamageEvent$DamageCause should be on classpath");
        assertDoesNotThrow(() -> Class.forName("org.bukkit.event.entity.EntityRegainHealthEvent"),
                "EntityRegainHealthEvent should be on classpath");
        assertDoesNotThrow(() -> Class.forName("org.bukkit.event.entity.EntityRegainHealthEvent$RegainReason"),
                "EntityRegainHealthEvent$RegainReason should be on classpath");
        assertDoesNotThrow(() -> Class.forName("org.bukkit.entity.LivingEntity"),
                "LivingEntity should be on classpath");
        assertDoesNotThrow(() -> Class.forName("org.bukkit.entity.Entity"),
                "Entity should be on classpath");
    }

    // ── 已注入脚本能生成可调用的 Consumer ───────────────────────────────────

    @Test
    @DisplayName("damage-indicator 编译后返回非 null Consumer 令牌")
    void damageIndicatorProducesCallableToken() throws IOException {
        String yaml = loadResource("event-mapping/damage-indicator.yml");
        Map<String, Object> root = ScriptParser.parseYaml(yaml);

        ScriptInjector.RegisteredScript reg = assertDoesNotThrow(() -> injector.inject(root));
        assertNotNull(reg.token(), "Compiled script token must not be null");
    }

    @Test
    @DisplayName("regain-indicator 编译后返回非 null Consumer 令牌")
    void regainIndicatorProducesCallableToken() throws IOException {
        String yaml = loadResource("event-mapping/regain-indicator.yml");
        Map<String, Object> root = ScriptParser.parseYaml(yaml);

        ScriptInjector.RegisteredScript reg = assertDoesNotThrow(() -> injector.inject(root));
        assertNotNull(reg.token(), "Compiled script token must not be null");
    }

    // ── 工具方法 ─────────────────────────────────────────────────────────────

    private static String loadResource(String path) throws IOException {
        try (InputStream is = TestEventMappingCompilation.class
                .getClassLoader().getResourceAsStream(path)) {
            assertNotNull(is, "Resource not found on classpath: " + path);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
