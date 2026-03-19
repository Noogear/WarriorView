package cn.warriorview.listener;

import cn.warriorview.animation.data.DisplaySettings;
import cn.warriorview.animation.definition.AnimationDef;
import cn.warriorview.animation.engine.AnimationPlayer;
import cn.warriorview.configFile.IndicatorConfig;
import cn.warriorview.configFile.IndicatorConfigLoader;
import cn.warriorview.integration.PermissionChecker;
import cn.warriorview.util.RapidTransientScheduler;

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

import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 指示器引擎：接收事件快照（伤害、治愈等），在专用线程上执行位置计算、文字格式化并驱动动画。
 *
 * <p>
 * 主要流程：
 * </p>
 * <ol>
 * <li>主线程调用 {@link #onIndicator} → 仅读取 Entity API（位置、TrackedBy），将原始快照入队；
 * 若无 pending 批次，则向 {@link RapidTransientScheduler} 提交一次 {@code dispatchNow}
 * 任务。</li>
 * <li>调度器线程 {@link #processBatch} → 距离过滤、去重/合并、解算位置、格式化文字 → 播放动画。
 * 所有计算均在异步线程完成，主线程仅承担不可避免的 Entity API 读取。</li>
 * </ol>
 *
 * <p>
 * 每次事件携带一个 <b>tag</b>（可为 null），通过 {@link IndicatorConfigLoader}
 * 映射到具体的 {@link IndicatorConfig}，不同 tag 可产生完全不同外观的指示器。
 * </p>
 */
public class IndicatorHandler implements Listener {

    // ── Queues shared between main thread and engine thread ──────────────────
    private final MpscUnboundedArrayQueue<IndicatorSnapshot> eventQueue = new MpscUnboundedArrayQueue<>(2048);
    private final MpscUnboundedArrayQueue<Player> quitQueue = new MpscUnboundedArrayQueue<>(128);

    // ── Engine-thread-only state (single consumer, no locks) ─────────────────
    /**
     * Per-batch deduplication: composite key → accumulated indicator.
     * Key = (configIdentityHash << 32) | entityId，同批次同实体同配置的多次数值会合并。
     */
    private final Long2ObjectOpenHashMap<ActiveIndicator> tickAccumulator = new Long2ObjectOpenHashMap<>();

    // ── Dependencies ─────────────────────────────────────────────────────────
    private final AnimationPlayer animationPlayer;
    private final IndicatorConfigLoader configLoader;
    private final RapidTransientScheduler scheduler;
    private final PermissionChecker permChecker;

    /**
     * 变体解析缓存：{@code playerUUID → (baseConfig → effectiveConfig)}。
     *
     * <p>
     * 外层 {@link ConcurrentHashMap} 由调度器线程（读/写）和 LuckPerms 事件线程、主线程（remove）
     * 并发访问。内层为 {@link HashMap}，仅调度器线程访问，无需并发安全奖层。
     * 使用 {@link IndicatorConfig} 的默认 identity hashCode/equals，保证配置对象重载后自然失效。
     * </p>
     */
    private final ConcurrentHashMap<UUID, HashMap<IndicatorConfig, IndicatorConfig>> variantCache = new ConcurrentHashMap<>();

    /**
     * 引擎线程专用：记录当前事件中已累加过伤害的 effectiveConfig（用于跨观察者去重）。
     * 大小上限 = variants.size() + 1（变体数通常 ≤ 8），请勿在多线程中共享。
     */
    private final IndicatorConfig[] seenEffectiveInEvent = new IndicatorConfig[16];
    private int seenEffectiveCount;

    /**
     * 响应式批处理派发标志。CAS 保证每批事件在调度器中只有一个 processBatch 任务排队，
     * 避免重复派发。由主线程写（compareAndSet），由调度器线程重置（set(false)）。
     */
    private final AtomicBoolean batchScheduled = new AtomicBoolean(false);

    /** 同 {@link #batchScheduled}，用于 {@link #drainQuits} 的去重派发。 */
    private final AtomicBoolean quitScheduled = new AtomicBoolean(false);



    /**
     * Reusable scratch Location for {@link #processBatch()} — safe because it runs
     * on a single scheduler thread.
     */
    private final Location _scratchLoc = new Location(null, 0, 0, 0);

    /**
     * 获取玩家对应的有效 {@link IndicatorConfig}。
     *
     * <p>
     * 经由双层缓存，命中为 O(1)；未命中时计算并存入缓存。
     * LP 路径通过 {@link #invalidateVariantCache} 事件驱动精确失效；
     * Bukkit 路径由 {@link #pollVariantCache} 守护任务批量刷新。
     * </p>
     */
    private IndicatorConfig getEffectiveConfig(Player viewer, IndicatorConfig base) {
        if (!base.hasVariants()) return base;
        UUID uuid = viewer.getUniqueId();
        // 热路径：两次无锁 get 即可命中，避开 computeIfAbsent 的额外开销
        HashMap<IndicatorConfig, IndicatorConfig> playerCache = variantCache.get(uuid);
        if (playerCache != null) {
            IndicatorConfig cached = playerCache.get(base);
            if (cached != null) return cached;
        } else {
            playerCache = variantCache.computeIfAbsent(uuid, k -> new HashMap<>());
        }
        IndicatorConfig effective = base.resolveVariantFor(viewer, permChecker);
        // 仅在玩家仍在线时写缓存：防止离线瞬间与主线程 variantCache.remove 产生竞态，
        // 导致已删除的 UUID 被调度器线程重新插入 zombie 条目。
        if (viewer.isOnline()) playerCache.put(base, effective);
        return effective;
    }

    public IndicatorHandler(AnimationPlayer animationPlayer,
            IndicatorConfigLoader configLoader,
            RapidTransientScheduler scheduler,
            PermissionChecker permChecker) {
        this.animationPlayer = animationPlayer;
        this.configLoader = configLoader;
        this.scheduler = scheduler;
        this.permChecker = permChecker;
    }

    // ── Main-thread event sinks ───────────────────────────────────────────────

    /**
     * 处理一次指示器事件。在主线程读取 Entity API 数据后将原始快照入队，
     * 距离过滤及后续所有计算推入 {@link RapidTransientScheduler} 的第一帧异步执行。
     *
     * <p>
     * 返回 {@code false} 当且仅当：onlyPlayer 检查失败，或目标实体无追踪者。
     * 若有追踪者但均超出范围，仍返回 {@code true}（异步阶段过滤后不产生动画）。
     * </p>
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
        if (trackers.isEmpty()) {
            return false;
        }

        IndicatorConfig config = configLoader.get(tag);

        // only-player 检查：投射物溯源到发射者再判定
        if (config.onlyPlayer) {
            Entity real = source;
            if (source instanceof Projectile proj && proj.getShooter() instanceof Entity shooter) {
                real = shooter;
            }
            if (!(real instanceof Player)) {
                return false;
            }
        }

        // null source 回退到 target（治愈等场景 / API 容错）
        if (source == null)
            source = target;

        // 在主线程完成所有 Entity API 读取（位置、眼高、世界 UUID 等不可在异步线程安全读取）
        Player[] allViewers = trackers.toArray(new Player[0]);
        double tX = target.getX(), tY = target.getY(), tZ = target.getZ();
        double sX = source.getX();
        double sY = source.getY();
        double sZ = source.getZ();
        double sEyeY = source instanceof LivingEntity le ? sY + le.getEyeHeight() : sY;
        double tEyeY = tY + (target instanceof LivingEntity tle ? tle.getEyeHeight() : 0);
        float sourcePitch = source.getPitch();
        float sourceYaw = source.getYaw();

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

    /**
     * 清除指定玩家的变体缓存。由 LuckPerms {@code UserDataRecalculateEvent} 触发，
     * 确保权限变更后下一次查询使用最新结果。
     */
    public void invalidateVariantCache(UUID uuid) {
        variantCache.remove(uuid);
    }

    /** 清除全部玩家的变体缓存。配置重载后调用，防止旧配置对象残留。 */
    public void clearVariantCache() {
        variantCache.clear();
    }

    /**
     * Bukkit 路径守护任务：每 200 活跃 Tick（≈10 秒）重新解析所有缓存玩家的变体权限。
     * 离线玩家条目在此一并清除，防止内存泄漏（兼作 zombie 条目的兜底清理）。
     *
     * <p>
     * 由 {@code dispatchDaemon} 注册，运行于调度器线程，与 {@link #processBatch} 线程相同，
     * 无额外并发竞争；LP 事件线程仅以 {@link ConcurrentHashMap#remove} 操作外层 map，安全。
     * </p>
     */
    public void pollVariantCache() {
        if (variantCache.isEmpty()) return;
        variantCache.forEach((uuid, playerCache) -> {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null) {
                variantCache.remove(uuid);
                return;
            }
            playerCache.replaceAll((base, cached) -> base.resolveVariantFor(player, permChecker));
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        // 无论哪条路径，退出时立即清除该玩家的变体缓存，防止内存泄漏。
        // LP 路径无 UserDataRecalculateEvent 触发，否则条目永不自动清除。
        variantCache.remove(e.getPlayer().getUniqueId());
        quitQueue.relaxedOffer(e.getPlayer());
        // 事件驱动：CAS 保证同批退出只派发一次 drain 任务。
        // 先判断 isRunning() 再 CAS：避免调度器休眠时将 quitScheduled 置为 true
        // 却不派发任务，导致标志永久泄漏、后续退出无法触发快速路径。
        // 若调度器已休眠，quitQueue 由下次 processBatch Phase 0 兜底消费。
        if (scheduler.isRunning() && quitScheduled.compareAndSet(false, true)) {
            scheduler.dispatchNow(this::drainQuits);
        }
    }

    /**
     * 清理退出玩家的观察者引用。由 {@link #onQuit} 事件驱动触发一次性任务，
     * {@link #processBatch} Phase 0 中也会内联执行一次。
     */
    private void drainQuits() {
        quitScheduled.set(false);
        Player q;
        while ((q = quitQueue.poll()) != null) {
            animationPlayer.removeViewer(q);
        }
    }

    // ── Async batch processor (dispatched via RapidTransientScheduler.dispatchNow)
    // ──

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

        // ── Phase 1: drain events; filter viewers by distance (async-safe reads in
        // Paper),
        // accumulate values per entity + config/variant ─────────────────────
        tickAccumulator.clear();
        IndicatorSnapshot event;
        while ((event = eventQueue.poll()) != null) {
            IndicatorConfig baseConfig = event.config;

            if (!baseConfig.hasVariants()) {
                // ── 快速路径：无变体，全部观察者使用同一配置（同原有逻辑） ──────────────
                long key = accumKey(event.tId, baseConfig);
                ActiveIndicator ind = tickAccumulator.get(key);
                if (ind == null) {
                    Player[] filtered = null;
                    int filteredCount = 0;
                    double tX = event.tX, tY = event.tY, tZ = event.tZ;
                    for (int i = 0; i < event.viewerCount; i++) {
                        Player p = event.viewers[i];
                        double dX = tX - p.getX(), dY = tY - p.getY(), dZ = tZ - p.getZ();
                        if (dX * dX + dY * dY + dZ * dZ <= baseConfig.maxDistanceSq) {
                            if (filtered == null)
                                filtered = new Player[event.viewerCount];
                            filtered[filteredCount++] = p;
                        }
                    }
                    if (filteredCount == 0)
                        continue;
                    Vector3d loc = baseConfig.position.resolve(
                            event.sX, event.sY, event.sZ, event.sEyeY, event.tEyeY,
                            event.pitch, event.yaw,
                            event.tX, event.tY, event.tZ,
                            event.tH, event.tW);
                    tickAccumulator.put(key, new ActiveIndicator(
                            baseConfig, event.finalDamage,
                            loc.x, loc.y, loc.z, event.yaw, event.world,
                            filtered, filteredCount));
                } else {
                    ind.damage += event.finalDamage;
                }
            } else {
                // ── 变体路径：逐观察者解析有效配置，按 (entity + effectiveConfig) 分组 ──
                //
                // 设计要点：
                // • 同一事件中每个 effectiveConfig 只累加一次伤害（seenEffectiveInEvent 去重）。
                // • 同 tick 第二次看到相同 (entity, effectiveConfig)，仅追加新观察者 + 累加伤害。
                // • 数组 seenEffectiveInEvent 引擎线程独享，无锁，重置代价 O(1)。
                seenEffectiveCount = 0;
                double tX = event.tX, tY = event.tY, tZ = event.tZ;
                for (int i = 0; i < event.viewerCount; i++) {
                    Player p = event.viewers[i];
                    IndicatorConfig effective = getEffectiveConfig(p, baseConfig);
                    double dX = tX - p.getX(), dY = tY - p.getY(), dZ = tZ - p.getZ();
                    if (dX * dX + dY * dY + dZ * dZ > effective.maxDistanceSq)
                        continue;

                    long key = accumKey(event.tId, effective);
                    ActiveIndicator ind = tickAccumulator.get(key);
                    if (ind == null) {
                        // 该 (entity, effectiveConfig) 本 tick 首次出现
                        Player[] viewers = new Player[event.viewerCount];
                        viewers[0] = p;
                        Vector3d loc = effective.position.resolve(
                                event.sX, event.sY, event.sZ, event.sEyeY, event.tEyeY,
                                event.pitch, event.yaw,
                                event.tX, event.tY, event.tZ,
                                event.tH, event.tW);
                        tickAccumulator.put(key, new ActiveIndicator(
                                effective, event.finalDamage,
                                loc.x, loc.y, loc.z, event.yaw, event.world,
                                viewers, 1));
                        // 标记此 effectiveConfig 本事件已累加伤害
                        if (seenEffectiveCount < seenEffectiveInEvent.length)
                            seenEffectiveInEvent[seenEffectiveCount++] = effective;
                    } else {
                        // 已有累积器：检查本事件是否已为该 effectiveConfig 累加过伤害
                        boolean damageSeen = false;
                        for (int j = 0; j < seenEffectiveCount; j++) {
                            if (seenEffectiveInEvent[j] == effective) {
                                damageSeen = true;
                                break;
                            }
                        }
                        if (!damageSeen) {
                            ind.damage += event.finalDamage;
                            if (seenEffectiveCount < seenEffectiveInEvent.length)
                                seenEffectiveInEvent[seenEffectiveCount++] = effective;
                        }
                        // 追加新观察者（去重避免同实体多次触发重复发包）
                        if (ind.viewerCount < ind.viewers.length) {
                            boolean alreadyIn = false;
                            for (int j = 0; j < ind.viewerCount; j++) {
                                if (ind.viewers[j] == p) {
                                    alreadyIn = true;
                                    break;
                                }
                            }
                            if (!alreadyIn)
                                ind.viewers[ind.viewerCount++] = p;
                        }
                    }
                }
            }
        }

        // ── Phase 2: enqueue new animations into the engine ──────────────────
        if (!tickAccumulator.isEmpty()) {
            for (ActiveIndicator ind : tickAccumulator.values()) {
                AnimationDef def = ind.config.animationDef; // 加载期 @PostLoad 绑定，无运行期查找
                if (def == null)
                    continue;

                _scratchLoc.setWorld(ind.world);
                _scratchLoc.setX(ind.startX);
                _scratchLoc.setY(ind.startY);
                _scratchLoc.setZ(ind.startZ);
                Component text = formatDamage(ind.damage, ind.config);

                // 使用 IndicatorConfig 的显示属性覆盖动画定义的默认值
                DisplaySettings settings = ind.config.toDisplaySettings(def.settings());
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
            double finalDamage,
            double sX, double sY, double sZ, double sEyeY, double tEyeY,
            float pitch, float yaw,
            int tId,
            double tX, double tY, double tZ,
            double tW, double tH,
            World world,
            Player[] viewers, int viewerCount) {
    }

    /** 可变的单 tick 累积器，仅引擎线程访问。 */
    private static final class ActiveIndicator {
        final IndicatorConfig config;
        double damage;
        final double startX, startY, startZ;
        final float attackerYaw;
        final World world;
        final Player[] viewers;
        int viewerCount; // 非 final：变体路径下可追加新观察者

        ActiveIndicator(IndicatorConfig config, double damage,
                double x, double y, double z,
                float attackerYaw,
                World world, Player[] viewers, int viewerCount) {
            this.config = config;
            this.damage = damage;
            this.startX = x;
            this.startY = y;
            this.startZ = z;
            this.attackerYaw = attackerYaw;
            this.world = world;
            this.viewers = viewers;
            this.viewerCount = viewerCount;
        }
    }
}
