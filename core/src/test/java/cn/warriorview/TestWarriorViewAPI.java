package cn.warriorview;

import cn.warriorview.api.WarriorView;
import cn.warriorview.api.WarriorViewAPI;
import cn.warriorview.api.manager.AnimationManager;
import cn.warriorview.api.manager.CharReplaceManager;
import cn.warriorview.api.manager.NumberFormatManager;
import cn.warriorview.api.manager.ScriptManager;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 测试 WarriorViewAPI 的静态访问入口行为。
 * 覆盖注册、获取、注销和状态异常。
 */
@DisplayName("WarriorViewAPI 静态入口测试")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TestWarriorViewAPI {

    @AfterEach
    void cleanup() {
        WarriorViewAPI.unregister();
    }

    @Test
    @Order(1)
    @DisplayName("未注册时 getProvider 抛出 IllegalStateException")
    void getProviderBeforeRegister() {
        WarriorViewAPI.unregister(); // 确保未注册状态
        assertThrows(IllegalStateException.class, WarriorViewAPI::getProvider);
    }

    @Test
    @Order(2)
    @DisplayName("注册后 getProvider 返回注册的实现")
    void registerAndGetProvider() {
        WarriorView mock = createMockWarriorView();
        WarriorViewAPI.register(mock);
        assertSame(mock, WarriorViewAPI.getProvider());
    }

    @Test
    @Order(3)
    @DisplayName("注销后 getProvider 再次抛出异常")
    void unregisterMakesProviderNull() {
        WarriorViewAPI.register(createMockWarriorView());
        WarriorViewAPI.unregister();
        assertThrows(IllegalStateException.class, WarriorViewAPI::getProvider);
    }

    @Test
    @Order(4)
    @DisplayName("重复注册覆盖旧实现")
    void registerOverridesPrevious() {
        WarriorView first = createMockWarriorView();
        WarriorView second = createMockWarriorView();
        WarriorViewAPI.register(first);
        WarriorViewAPI.register(second);
        assertSame(second, WarriorViewAPI.getProvider());
    }

    @Test
    @Order(5)
    @DisplayName("重复注销不报错")
    void multipleUnregistersAreSafe() {
        assertDoesNotThrow(() -> {
            WarriorViewAPI.unregister();
            WarriorViewAPI.unregister();
            WarriorViewAPI.unregister();
        });
    }

    /**
     * 创建一个最小化的 WarriorView 匿名实现用于测试。
     */
    private WarriorView createMockWarriorView() {
        return new WarriorView() {
            @Override public AnimationManager getAnimationManager() { return null; }
            @Override public NumberFormatManager getNumberFormatManager() { return null; }
            @Override public CharReplaceManager getCharReplaceManager() { return null; }
            @Override public ScriptManager getScriptManager() { return null; }
            @Override public String getVersion() { return "test-1.0.0"; }
        };
    }
}
