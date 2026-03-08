package cn.warriorview.action;

import cn.warriorview.api.WarriorViewAPI;
import gloomlib.script.api.action.ActionRegistry.ScriptAction;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;

/**
 * 内置脚本动作 — 通用指示器发射器。
 *
 * <p>{@code consumesPayload = false}，四个参数全部由 event-mapping YAML
 * 通过 {@code variables} + {@code args} 提取并传入，不依赖任何事件类型。</p>
 *
 * <p>第三方插件可通过 {@link cn.warriorview.api.WarriorViewAPI#getProvider()
 * WarriorViewAPI.getProvider().getScriptManager().registerActionClass(BuiltinActions.class)}
 * 的方式注册自定义 action 类（参照本类的写法）。</p>
 */
public final class BuiltinActions {

    private BuiltinActions() {}

    @ScriptAction(value = "showIndicator", consumesPayload = false)
    public static void showIndicator(LivingEntity target, Entity source, double value, String tag) {
        WarriorViewAPI.getProvider().getAnimationManager()
                .showDamageIndicator(target, source, value, tag);
    }
}
