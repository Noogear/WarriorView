package cn.warriorview.listener;

import cn.warriorview.animation.data.DisplaySettings;
import cn.warriorview.animation.definition.AnimationDef;
import cn.warriorview.animation.engine.AnimationPlayer;
import cn.warriorview.configFile.IndicatorConfig;
import cn.warriorview.configFile.IndicatorConfigLoader;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import net.kyori.adventure.text.Component;

import com.github.retrooper.packetevents.util.Vector3d;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import io.netty.util.internal.shaded.org.jctools.queues.MpscUnboundedArrayQueue;

import java.util.UUID;

/**
 * 指示器引擎：接收事件快照（伤害、治愈等），在专用线程上执行位置计算、文字格式化并驱动动画。
 *
 * <p>主要流程：</p>
 * <ol>
 *   <li>主线程调用 {@link #onIndicator} → 按距离过滤观察者，生成 {@link IndicatorSnapshot} 快照入队</li>
 *   <li>引擎线程 {@link #engineTick} → 去重/合并同 tick 同实体同配置的数值 → 按
 *       {@link IndicatorConfig} 决定位置策略、文字模板、动画名称 → 播放动画</li>
 * </ol>
 *
 * <p>每次事件携带一个 <b>tag</b>（可为 null），通过 {@link IndicatorConfigLoader}
 * 映射到具体的 {@link IndicatorConfig}，不同 tag 可产生完全不同外观的指示器。</p>
 */
public class IndicatorHandler implements Listener {

    // ── Queues shared between main thread and engine thread ──────────────────
    private final MpscUnboundedArrayQueue<IndicatorSnapshot> eventQueue = new MpscUnboundedArrayQueue<>(2048);
    private final MpscUnboundedArrayQueue<Player>          quitQueue  = new MpscUnboundedArrayQueue<>(128);

    // ── Engine-thread-only state (single consumer, no locks) ─────────────────
    /**
     * Per-tick deduplication: composite key → accumulated indicator.
     * Key = (configIdentityHash << 32) | entityId，同 tick 同实体同配置的多次数值会合并。
     */
    private final Long2ObjectOpenHashMap<ActiveIndicator> tickAccumulator = new Long2ObjectOpenHashMap<>();

    // ── Dependencies ─────────────────────────────────────────────────────────
    private final AnimationPlayer       animationPlayer;
    private final IndicatorConfigLoader configLoader;

    private final double maxDistanceSq;

    public IndicatorHandler(AnimationPlayer animationPlayer,
                            IndicatorConfigLoader configLoader,
                            double maxDistance) {
        this.animationPlayer = animationPlayer;
        this.configLoader    = configLoader;
        this.maxDistanceSq   = maxDistance * maxDistance;
    }

    // ── Main-thread event sinks ───────────────────────────────────────────────

    /**
     * 处理一次指示器事件。按距离过滤观察者后将快照入队到引擎线程。
     *
     * @param victim      目标实体
     * @param attacker    来源实体（可为 null；投射物实体会自动溯源到发射者进行 only-player 判定）
     * @param finalDamage 最终数值（伤害、治愈量等）
     * @param tag         配置标记（可为 null 或空串，回退到 {@code default} 配置）
     * @return 是否至少有一名观察者在范围内
     */
    public boolean onIndicator(Entity victim, Entity attacker, double finalDamage, String tag) {
        IndicatorConfig config = configLoader.get(tag);

        // only-player 检查：投射物溯源到发射者再判定
        if (config.onlyPlayer) {
            Entity real = attacker;
            if (attacker instanceof Projectile proj && proj.getShooter() instanceof Entity shooter) {
                real = shooter;
            }
            if (!(real instanceof Player)) return false;
        }

        // null attacker 回退到 victim（治愈等场景 / API 容错）
        if (attacker == null) attacker = victim;

        var trackers = victim.getTrackedBy();
        if (trackers.isEmpty()) return false;

        Player[] preciseViewers = new Player[trackers.size()];
        int validCount = 0;

        double vX = victim.getX(), vY = victim.getY(), vZ = victim.getZ();
        for (Player p : trackers) {
            double dX = vX - p.getX(), dY = vY - p.getY(), dZ = vZ - p.getZ();
            if (dX * dX + dY * dY + dZ * dZ <= maxDistanceSq) {
                preciseViewers[validCount++] = p;
            }
        }
        if (validCount == 0) return false;

        boolean isLiving = attacker instanceof LivingEntity;
        double eyeX = attacker.getX();
        double eyeY = attacker.getY() + (isLiving ? ((LivingEntity) attacker).getEyeHeight() : 0);
        double eyeZ = attacker.getZ();
        float attackerPitch = attacker.getPitch();
        float attackerYaw   = attacker.getYaw();

        eventQueue.relaxedOffer(new IndicatorSnapshot(
                config,
                finalDamage,
                eyeX, eyeY, eyeZ,
                attackerPitch, attackerYaw,
                victim.getEntityId(),
                vX, vY, vZ,
                victim.getWidth(), victim.getHeight(),
                victim.getWorld().getUID(),
                preciseViewers, validCount));
        return true;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        quitQueue.relaxedOffer(e.getPlayer());
    }

    // ── Engine tick (must be called on a single dedicated thread each tick) ──

    public void engineTick() {
        // ── Phase 0: drain quit queue ────────────────────────────────────────
        Player q;
        while ((q = quitQueue.poll()) != null) {
            animationPlayer.removeViewer(q);
        }

        // ── Phase 1: drain events, accumulate values per entity + config ─────
        tickAccumulator.clear();
        IndicatorSnapshot event;
        while ((event = eventQueue.poll()) != null) {
            long key = accumKey(event.vId, event.config);

            ActiveIndicator ind = tickAccumulator.get(key);
            if (ind == null) {
                // 使用 IndicatorConfig.position 策略计算生成位置
                Vector3d loc = event.config.position.resolve(
                        event.aX, event.aY, event.aZ,
                        event.pitch, event.yaw,
                        event.vX, event.vY, event.vZ,
                        event.vH, event.vW);

                ind = new ActiveIndicator(
                        event.config,
                        event.finalDamage,
                        loc.x, loc.y, loc.z,
                        event.worldUid,
                        event.viewers, event.viewerCount);
                tickAccumulator.put(key, ind);
            } else {
                ind.damage += event.finalDamage; // merge multi-event within same tick
            }
        }

        // ── Phase 2: enqueue new animations into the engine ──────────────────
        if (!tickAccumulator.isEmpty()) {
            for (ActiveIndicator ind : tickAccumulator.values()) {
                AnimationDef def = ind.config.animationDef; // 加载期 @PostLoad 绑定，无运行期查找
                if (def == null) continue;

                World world = Bukkit.getWorld(ind.worldUid);
                if (world == null) continue;

                Location loc = new Location(world, ind.startX, ind.startY, ind.startZ);
                Component text = formatDamage(ind.damage, ind.config);

                // 使用 IndicatorConfig 的显示属性覆盖动画定义的默认值
                DisplaySettings settings = ind.config.toDisplaySettings(def.settings().offset());
                animationPlayer.play(def, loc, text, settings, ind.viewers, ind.viewerCount);
            }
        }
    }

    // ── Formatting ────────────────────────────────────────────────────────────

    /** 格式化数值并返回富文本 Component（运行期零 MiniMessage 解析）。 */
    private static Component formatDamage(double damage, IndicatorConfig config) {
        return config.buildText(config.formatValue(damage));
    }

    // ── Accumulator key ───────────────────────────────────────────────────────

    /**
     * 组合键：高 32 位为 config 的 identity hash，低 32 位为 entity id。
     * 同 tick 同实体同配置的事件会合并到一个 ActiveIndicator 中。
     */
    private static long accumKey(int entityId, IndicatorConfig config) {
        return ((long) System.identityHashCode(config) << 32) | (entityId & 0xFFFFFFFFL);
    }

    // ── Value types ───────────────────────────────────────────────────────────

    /**
     * 不可变事件快照，在主线程生成，传递到引擎线程处理。
     * 携带 {@link IndicatorConfig} 引用以决定位置策略、文字模板和动画名称。
     */
    private record IndicatorSnapshot(
            IndicatorConfig config,
            double   finalDamage,
            double   aX, double aY, double aZ,
            float    pitch, float yaw,
            int      vId,
            double   vX, double vY, double vZ,
            double   vW, double vH,
            UUID     worldUid,
            Player[] viewers, int viewerCount) {}

    /** 可变的单 tick 累积器，仅引擎线程访问。 */
    private static final class ActiveIndicator {
        final IndicatorConfig config;
        double         damage;
        final double   startX, startY, startZ;
        final UUID     worldUid;
        final Player[] viewers;
        final int      viewerCount;

        ActiveIndicator(IndicatorConfig config, double damage,
                        double x, double y, double z,
                        UUID worldUid, Player[] viewers, int viewerCount) {
            this.config      = config;
            this.damage      = damage;
            this.startX      = x;
            this.startY      = y;
            this.startZ      = z;
            this.worldUid    = worldUid;
            this.viewers     = viewers;
            this.viewerCount = viewerCount;
        }
    }
}
