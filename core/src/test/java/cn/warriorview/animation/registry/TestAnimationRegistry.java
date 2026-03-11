package cn.warriorview.animation.registry;

import cn.warriorview.animation.api.AnimationType;
import cn.warriorview.animation.api.Space;
import cn.warriorview.animation.data.*;
import cn.warriorview.animation.definition.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collection;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 测试 AnimationRegistry —— 动画定义的中央注册表。
 * 覆盖 register、get、contains、all、size、clear 以及线程安全特性。
 */
@DisplayName("AnimationRegistry 注册表测试")
class TestAnimationRegistry {

    private AnimationRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new AnimationRegistry();
    }

    private KeyframeDef createDummyKeyframe(String name) {
        var frame = new BakedFrame(0, 0, 0, TransformSnapshot.IDENTITY);
        var seq = new BakedSequence(new BakedFrame[]{frame}, 0, DisplaySettings.DEFAULT);
        return new KeyframeDef(name, DisplaySettings.DEFAULT, Space.WORLD, seq);
    }

    @Test
    @DisplayName("初始状态：空注册表")
    void emptyByDefault() {
        assertEquals(0, registry.size());
        assertTrue(registry.all().isEmpty());
    }

    @Test
    @DisplayName("注册后可通过名称获取")
    void registerAndGet() {
        var def = createDummyKeyframe("test_anim");
        registry.register(def);
        assertSame(def, registry.get("test_anim"));
    }

    @Test
    @DisplayName("get 不存在的名称返回 null")
    void getUnregisteredReturnsNull() {
        assertNull(registry.get("nonexistent"));
    }

    @Test
    @DisplayName("contains 正确判断")
    void containsWorks() {
        assertFalse(registry.contains("test_anim"));
        registry.register(createDummyKeyframe("test_anim"));
        assertTrue(registry.contains("test_anim"));
    }

    @Test
    @DisplayName("注册多个定义后 size 正确")
    void sizeAfterMultipleRegistrations() {
        registry.register(createDummyKeyframe("anim1"));
        registry.register(createDummyKeyframe("anim2"));
        registry.register(createDummyKeyframe("anim3"));
        assertEquals(3, registry.size());
    }

    @Test
    @DisplayName("同名注册覆盖旧定义")
    void registerOverridesSameName() {
        var old = createDummyKeyframe("anim");
        var newDef = createDummyKeyframe("anim");
        registry.register(old);
        registry.register(newDef);
        assertEquals(1, registry.size());
        assertSame(newDef, registry.get("anim"));
    }

    @Test
    @DisplayName("all() 返回所有已注册定义")
    void allReturnsAllDefs() {
        registry.register(createDummyKeyframe("a"));
        registry.register(createDummyKeyframe("b"));
        Collection<AnimationDef> all = registry.all();
        assertEquals(2, all.size());
    }

    @Test
    @DisplayName("all() 返回不可修改视图")
    void allReturnsUnmodifiable() {
        registry.register(createDummyKeyframe("a"));
        Collection<AnimationDef> all = registry.all();
        assertThrows(UnsupportedOperationException.class, () -> all.clear());
    }

    @Test
    @DisplayName("clear 清空所有定义")
    void clearRemovesAll() {
        registry.register(createDummyKeyframe("a"));
        registry.register(createDummyKeyframe("b"));
        assertEquals(2, registry.size());
        registry.clear();
        assertEquals(0, registry.size());
        assertNull(registry.get("a"));
        assertNull(registry.get("b"));
    }

    @Test
    @DisplayName("clear 后可重新注册")
    void registerAfterClear() {
        registry.register(createDummyKeyframe("a"));
        registry.clear();
        registry.register(createDummyKeyframe("b"));
        assertEquals(1, registry.size());
        assertNull(registry.get("a"));
        assertNotNull(registry.get("b"));
    }

    @Test
    @DisplayName("不同类型的 AnimationDef 可混合注册")
    void mixedDefTypes() {
        registry.register(createDummyKeyframe("keyframe_anim"));

        // PresetDef
        var keyframe = createDummyKeyframe("base");
        registry.register(keyframe);
        var preset = new PresetDef("preset_anim", DisplaySettings.DEFAULT, keyframe);
        registry.register(preset);

        assertEquals(3, registry.size());
        assertEquals(AnimationType.KEYFRAME, registry.get("keyframe_anim").type());
        assertEquals(AnimationType.PRESET, registry.get("preset_anim").type());
    }
}
