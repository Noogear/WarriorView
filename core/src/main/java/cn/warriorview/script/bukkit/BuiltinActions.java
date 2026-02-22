package cn.warriorview.script.bukkit;

import cn.warriorview.script.action.ActionRegistry.ScriptAction;
import net.kyori.adventure.text.Component;
import org.bukkit.Effect;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;

/**
 * 内置脚本动作提供类。
 * <p>
 * 所有方法均为 {@code public static}，通过 {@link ScriptAction} 注解标注。
 * 由 {@link ActionRegistry#scanAndRegister(Class[])} 自动发现注册。
 */
public final class BuiltinActions {

    private BuiltinActions() {
    }

    /**
     * 向实体发送消息（Adventure Component）。
     */
    @ScriptAction("sendMessage")
    public static void sendMessage(Player target, String message) {
        target.sendMessage(Component.text(message));
    }

    /**
     * 在实体位置播放特效。
     */
    @ScriptAction("playEffect")
    public static void playEffect(Entity target, String effectName, int data) {
        target.getWorld().playEffect(target.getLocation(), Effect.valueOf(effectName), data);
    }

    /**
     * 取消事件。
     */
    @ScriptAction("cancel")
    public static void cancel(Cancellable event) {
        event.setCancelled(true);
    }

    /**
     * 向实体发送 ActionBar 消息。
     */
    @ScriptAction("actionBar")
    public static void actionBar(Player target, String message) {
        target.sendActionBar(Component.text(message));
    }

    /**
     * 为实体设置飞行速度。
     */
    @ScriptAction("setFlySpeed")
    public static void setFlySpeed(Player target, float speed) {
        target.setFlySpeed(speed);
    }
}
