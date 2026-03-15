package cn.warriorview.animation.parse;

import cn.warriorview.animation.data.TransformSnapshot;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 KeyframeParser 对 move syntax sugar 的解析。
 * 重点测试 MemoryConfiguration.set() 对嵌套 Map 的处理。
 */
@DisplayName("Keyframe move syntax sugar 解析")
class TestKeyframeMoveSugar {

    private static final float EPSILON = 1e-4f;

    @Test
    @DisplayName("MemoryConfiguration.set(key, Map) 不会自动转为 ConfigurationSection")
    void memoryConfigSetMapNotAutoConverted() {
        MemoryConfiguration config = new MemoryConfiguration();
        Map<String, Object> moveMap = new LinkedHashMap<>();
        moveMap.put("up", 0.6);

        // 这是 KeyframeParser 当前的写法：直接 set(key, map)
        config.set("move", moveMap);

        // 问题：getConfigurationSection 返回 null
        ConfigurationSection moveSection = config.getConfigurationSection("move");
        // 这个断言应该失败，证明 bug 存在
        assertNull(moveSection, "MemorySection.set() 不会自动转换 Map → ConfigurationSection");

        // 但 get() 能拿到原始 Map
        Object raw = config.get("move");
        assertNotNull(raw);
        assertInstanceOf(Map.class, raw);
    }

    @Test
    @DisplayName("createSection(key, Map) 能正确创建 ConfigurationSection")
    void createSectionConvertsMap() {
        MemoryConfiguration config = new MemoryConfiguration();
        Map<String, Object> moveMap = new LinkedHashMap<>();
        moveMap.put("up", 0.6);

        // 修复方式：用 createSection 替代 set
        config.createSection("move", moveMap);

        ConfigurationSection moveSection = config.getConfigurationSection("move");
        assertNotNull(moveSection, "createSection 应该创建 ConfigurationSection");
        assertEquals(0.6, moveSection.getDouble("up"), EPSILON);
    }

    @Test
    @DisplayName("move: { up: 0.6 } 经 SyntaxSugarResolver 后 ty 应为 0.6")
    void moveSugarProducesCorrectTy() {
        MemoryConfiguration config = new MemoryConfiguration();
        // 模拟修复后的 KeyframeParser 行为
        Map<String, Object> moveMap = new LinkedHashMap<>();
        moveMap.put("up", 0.6);
        config.createSection("move", moveMap);
        config.set("duration", 6);

        TransformSnapshot snap = SyntaxSugarResolver.resolve(TransformSnapshot.IDENTITY, config);
        assertEquals(0.6f, snap.ty(), EPSILON, "move: { up: 0.6 } → ty 应为 0.6");
    }

    @Test
    @DisplayName("多帧 move 累加：frame1 up:0.6 → frame2 down:0.2 → ty 应为 0.4")
    void multiFrameMoveAccumulation() {
        // Frame 1: move up 0.6
        MemoryConfiguration f1 = new MemoryConfiguration();
        Map<String, Object> move1 = new LinkedHashMap<>();
        move1.put("up", 0.6);
        f1.createSection("move", move1);

        float[] deltas = new float[SyntaxSugarResolver.DELTA_COUNT];
        TransformSnapshot snap1 = SyntaxSugarResolver.resolve(TransformSnapshot.IDENTITY, f1, deltas);
        assertEquals(0.6f, snap1.ty(), EPSILON);

        // Frame 2: move down 0.2 (net = 0.6 - 0.2 = 0.4)
        MemoryConfiguration f2 = new MemoryConfiguration();
        Map<String, Object> move2 = new LinkedHashMap<>();
        move2.put("down", 0.2);
        f2.createSection("move", move2);

        TransformSnapshot snap2 = SyntaxSugarResolver.resolve(snap1, f2, deltas);
        assertEquals(0.4f, snap2.ty(), EPSILON);
    }

    @Test
    @DisplayName("修复验证：用 createSection 存 Map → move 正确解析 → ty=0.6")
    void fixVerification_createSectionProducesCorrectTy() {
        MemoryConfiguration config = new MemoryConfiguration();
        Map<String, Object> moveMap = new LinkedHashMap<>();
        moveMap.put("up", 0.6);
        // 修复后的方式：createSection
        config.createSection("move", moveMap);

        TransformSnapshot snap = SyntaxSugarResolver.resolve(TransformSnapshot.IDENTITY, config);
        assertEquals(0.6f, snap.ty(), EPSILON, "修复后：createSection(Map) → move 正确解析");
    }
}
