package cn.warriorview.listener;

import cn.warriorview.animation.data.DisplaySettings;
import cn.warriorview.animation.definition.AnimationDef;
import cn.warriorview.animation.engine.AnimationPlayer;
import cn.warriorview.configFile.IndicatorConfig;
import cn.warriorview.configFile.IndicatorConfigLoader;
import cn.warriorview.util.RapidTransientScheduler;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import net.kyori.adventure.text.Component;

import com.github.retrooper.packetevents.util.Vector3d;

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

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 指示器引擎：接收事件快照（伤害、治愈等），在专用线程上执行位置计算、文字格式化并驱动动画。
 *
 * <p>主要流程：</p>
 * <ol>
 *   <li>主线程调用 {@link #onIndicator} → 仅读取 Entity API（位置、TrackedBy），将原始快照入队；
 *       若无 pending 批次，则向 {@link RapidTransientScheduler} 提交一次 {@code dispatchNow} 任务。</li>
 *   <li>调度器线程 {@link #processBatch} → 距离过滤、去重/合并、解算位置、格式化文字 → 播放动画。
 *       所有计算均在异步线程完成，主线程仅承担不可避免的 Entity API 读取。</li>
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
     * Per-batch deduplication: composite key → accumulated indicator.
     * Key = (configIdentityHash << 32) | entityId，同批次同实体同配置的多次数值会合并。
     */
    private final Long2ObjectOpenHashMap<ActiveIndicator> tickAccumulator = new Long2ObjectOpenHashMap<>();

    // ── Dependencies ─────────────────────────────────────────────────────────
    private final AnimationPlayer          animationPlayer;
    private final IndicatorConfigLoader    configLoader;
    private final RapidTransientScheduler  scheduler;

    private final double maxDistanceSq;

    /**
     * 响应式批处理派发标志。CAS 保证每批事件在调度器中只有一个 processBatch 任务排队，
     * 避免重复派发。由主线程写（compareAndSet），由调度器线程重置（set(false)）。
     */
    private final AtomicBoolean batchScheduled = new AtomicBoolean(false);

    /** Reusable scratch Location for {@link #processBatch()} — safe because it runs on a single scheduler thread. */
    private final Location _scratchLoc = new Location(null, 0, 0, 0);

    public IndicatorHandler(AnimationPlayer animationPlayer,
                            IndicatorConfigLoader configLoader,
                            double maxDistance,
                            RapidTransientScheduler scheduler) {
        this.animationPlayer = animationPlayer;
        this.configLoader    = configLoader;
        this.maxDistanceSq   = maxDistance * maxDistance;
        this.scheduler       = scheduler;
    }

    // ── Main-thread event sinks ───────────────────────────────────────────────

    /**
     * 处理一次指示器事件。在主线程读取 Entity API 数据后将原始快照入队，
     * 距离过滤及后续所有计算推入 {@link RapidTransientScheduler} 的第一帧异步执行。
     *
     * <p>返回 {@code false} 当且仅当：onlyPlayer 检查失败，或目标实体无追踪者。
     * 若有追踪者但均超出范围，仍返回 {@code true}（异步阶段过滤后不产生动画）。</p>
     *
     * @param target      目标实体
     * @param source      来源实体（可为 null；投射物实体会自动港源到发射者进行 only-player 判定）
     * @param finalDamage 最终数值（伤害、治愈量等）
     * @param tag         配置标记（可为 null 或空串，回退到 {@code default} 配置）
     * @return 是否已将事件入队（存在追踪者且通过 onlyPlayer 检查）
     */
    public boolean onIndicator(Entity target, Entity source, double finalDamage, String tag) {
        // 最早快速出口：无人追踪则跳过后续所有计算
        var trackers = target.getTrackedBy();
        if (trackers.isEmpty()) { return false; }

        IndicatorConfig config = configLoader.get(tag);

        // only-player 检查：投射物溯源到发射者再判定
        if (config.onlyPlayer) {
            Entity real = source;
            if (source instanceof Projectile proj && proj.getShooter() instanceof Entity shooter) {
                real = shooter;
            }
            if (!(real instanceof Player)) { return false; }
        }

        // null source 回退到 target（治愈等场景 / API 容错）
        if (source == null) source = target;

        // 在主线程完成所有 Entity API 读取（位置、眼高、世界 UUID 等不可在异步线程安全读取）
        Player[] allViewers = trackers.toArray(new Player[0]);
        double tX = target.getX(), tY = target.getY(), tZ = target.getZ();
        double sX = source.getX();
        double sY = source.getY();
        double sZ = source.getZ();
        double sEyeY = source instanceof LivingEntity le ? sY + le.getEyeHeight() : sY;
        double tEyeY = tY + (target instanceof LivingEntity tle ? tle.getEyeHeight() : 0);
        float sourcePitch = source.getPitch();
        float sourceYaw   = source.getYaw();

        eventQueue.relaxedOffer(new IndicatorSnapshot(
                config,
                finalDamage,
                sX, sY, sZ, sEyeY, tEyeY,
                sourcePitch, sourceYaw,
                target.getEntityId(),
                tX, tY, tZ,
                target.getWidth(), target.getHeight(),
                target.getWorld(),
                allViewers, allViewers.length));

        // CAS 保证同一批事件只派发一次 processBatch——调度器无事件时完全空闲
        if (batchScheduled.compareAndSet(false, true)) {
            scheduler.dispatchNow(this::processBatch);
        }
        return true;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        quitQueue.relaxedOffer(e.getPlayer());
    }

    // ── Quit-only drain (low-frequency timer, covers idle periods with no combat) ──

    /**
     * 仅清理退出玩家的观察者引用。由低频定时器驱动，覆盖无战斗事件时的空闲场景。
     * {@link #processBatch} 中也会完整执行一次，避免战斗期间引用积压。
     */
    public void drainQuits() {
        Player q;
        while ((q = quitQueue.poll()) != null) {
            animationPlayer.removeViewer(q);
        }
    }

    // ── Async batch processor (dispatched via RapidTransientScheduler.dispatchNow) ──

    /**
     * 在调度器线程执行全部计算：距离过滤、去重合并、位置解算、文字格式化、动画派发。
     * 由 {@link #onIndicator} 通过 CAS 触发，每批事件最多一个实例排队。
     */
    private void processBatch() {
        // 先重置标志，允许后续到来的事件触发新一轮派发
        batchScheduled.set(false);

        // ── Phase 0: drain quit queue ────────────────────────────────────────
        Player q;
        while ((q = quitQueue.poll()) != null) {
            animationPlayer.removeViewer(q);
        }

        // ── Phase 1: drain events; filter viewers by distance (async-safe reads in Paper),
        //             accumulate values per entity + config ─────────────────────────────
        tickAccumulator.clear();
        IndicatorSnapshot event;
        while ((event = eventQueue.poll()) != null) {
            long key = accumKey(event.tId, event.config);

            ActiveIndicator ind = tickAccumulator.get(key);
            if (ind == null) {
                // 距离过滤：在调度器线程异步读取玩家位置（Paper 允许对实体坐标的并发只读）
                Player[] filtered = null;
                int filteredCount = 0;
                double tX = event.tX, tY = event.tY, tZ = event.tZ;
                for (int i = 0; i < event.viewerCount; i++) {
                    Player p = event.viewers[i];
                    double dX = tX - p.getX(), dY = tY - p.getY(), dZ = tZ - p.getZ();
                    if (dX * dX + dY * dY + dZ * dZ <= maxDistanceSq) {
                        if (filtered == null) filtered = new Player[event.viewerCount];
                        filtered[filteredCount++] = p;
                    }
                }
                if (filteredCount == 0) continue;

                // 使用 IndicatorConfig.position 策略计算生成位置
                Vector3d loc = event.config.position.resolve(
                        event.sX, event.sY, event.sZ, event.sEyeY, event.tEyeY,
                        event.pitch, event.yaw,
                        event.tX, event.tY, event.tZ,
                        event.tH, event.tW);

                ind = new ActiveIndicator(
                        event.config,
                        event.finalDamage,
                        loc.x, loc.y, loc.z,
                        event.yaw,
                        event.world,
                        filtered, filteredCount);
                tickAccumulator.put(key, ind);
            } else {
                ind.damage += event.finalDamage; // merge multi-event within same batch
            }
        }

        // ── Phase 2: enqueue new animations into the engine ──────────────────
        if (!tickAccumulator.isEmpty()) {
            for (ActiveIndicator ind : tickAccumulator.values()) {
                AnimationDef def = ind.config.animationDef; // 加载期 @PostLoad 绑定，无运行期查找
                if (def == null) continue;

                _scratchLoc.setWorld(ind.world);
                _scratchLoc.setX(ind.startX);
                _scratchLoc.setY(ind.startY);
                _scratchLoc.setZ(ind.startZ);
                Component text = formatDamage(ind.damage, ind.config);

                // 使用 IndicatorConfig 的显示属性覆盖动画定义的默认值
                DisplaySettings settings = ind.config.toDisplaySettings(def.settings().offset());
                animationPlayer.play(def, _scratchLoc, text, settings, ind.attackerYaw, ind.viewers, ind.viewerCount);
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
            double   sX, double sY, double sZ, double sEyeY, double tEyeY,
            float    pitch, float yaw,
            int      tId,
            double   tX, double tY, double tZ,
            double   tW, double tH,
            World    world,
            Player[] viewers, int viewerCount) {}

    /** 可变的单 tick 累积器，仅引擎线程访问。 */
    private static final class ActiveIndicator {
        final IndicatorConfig config;
        double         damage;
        final double   startX, startY, startZ;
        final float    attackerYaw;
        final World    world;
        final Player[] viewers;
        final int      viewerCount;

        ActiveIndicator(IndicatorConfig config, double damage,
                        double x, double y, double z,
                        float attackerYaw,
                        World world, Player[] viewers, int viewerCount) {
            this.config      = config;
            this.damage      = damage;
            this.startX      = x;
            this.startY      = y;
            this.startZ      = z;
            this.attackerYaw = attackerYaw;
            this.world       = world;
            this.viewers     = viewers;
            this.viewerCount = viewerCount;
        }
    }
}
