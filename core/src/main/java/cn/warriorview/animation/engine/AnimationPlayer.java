package cn.warriorview.animation.engine;

import cn.warriorview.animation.data.BakedFrame;
import cn.warriorview.animation.data.BakedSequence;
import cn.warriorview.animation.data.DisplaySettings;
import cn.warriorview.animation.definition.AnimationDef;
import cn.warriorview.animation.definition.EquationDef;
import cn.warriorview.animation.definition.KeyframeDef;
import cn.warriorview.animation.definition.PresetDef;
import cn.warriorview.animation.api.Space;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import net.kyori.adventure.text.Component;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import cn.warriorview.util.RapidTransientScheduler;

import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Scheduler-driven orchestrator for TextDisplay animation instances.
 *
 * <h3>Architecture overview</h3>
 * <pre>
 *  play(def, anchor, text, viewers)
 *    └── scheduler.dispatchNow(spawnCallback)
 *          ├── spawn entity + send immediate frames (tickOffset ≤ 0) via collector
 *          ├── schedule first delayed frame (inst itself is Runnable, chain-dispatches)
 *          └── scheduler.dispatchLater(destroyCallback, totalTicks + 2)
 *
 *  scheduler.tick()  (RapidTransientScheduler)
 *    ├── processChain  →  AnimationInstance.run() → collector.collect()
 *    └── postTickHook  →  collector.flush()  ← one Bundle per player
 * </pre>
 *
 * <p>All timing is delegated to {@link RapidTransientScheduler}: no manual age
 * counting, no per-tick frame comparison loops.  Each instance chain-dispatches
 * itself as a {@link Runnable} for the next frame, so the time-wheel holds at
 * most <b>1</b> TransientTask per instance at any time — zero lambda allocation
 * on the frame path.</p>
 *
 * <p>Bundle merge is preserved via {@link PacketCollector}: frame callbacks
 * {@code collect()} packets, and the scheduler's {@code postTickHook} calls
 * {@code collector.flush()} once all callbacks for the tick have completed.</p>
 */
public final class AnimationPlayer {

    /** All currently active animation instances. Scheduler-thread-only. */
    private final ObjectArrayList<AnimationInstance> active = new ObjectArrayList<>(64);

    /** Accumulates packets per tick; flushed by scheduler's postTickHook. */
    private final PacketCollector collector = new PacketCollector();

    /** Scheduler that drives all animation lifecycle callbacks. */
    private final RapidTransientScheduler scheduler;

    public AnimationPlayer(RapidTransientScheduler scheduler) {
        this.scheduler = scheduler;
    }

    /** Returns the collector so the scheduler's postTickHook can flush it. */
    public PacketCollector collector() { return collector; }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Enqueues an animation for playback.
     * Thread-safe: may be called from the main thread, an event handler, or any
     * other thread.  Actual playback (spawn packets) happens on the next
     * {@link #tick()} call.
     *
     * @param viewerCount number of valid entries at the start of {@code viewers}
     */
    public void play(AnimationDef def, Location anchor, Component text,
                     Player[] viewers, int viewerCount) {
        play(def, anchor, text, null, 0f, viewers, viewerCount);
    }

    public void play(AnimationDef def, Location anchor, Component text,
                     DisplaySettings settingsOverride,
                     Player[] viewers, int viewerCount) {
        play(def, anchor, text, settingsOverride, 0f, viewers, viewerCount);
    }

    /**
     * 带 {@link DisplaySettings} 覆盖 + 攻击者朝向的播放入口。
     *
     * @param settingsOverride 非 null 时覆盖动画定义自带的 DisplaySettings（billboard、
     *                         背景色、view-range 等），{@code null} 则使用动画默认值。
     *                         offset 表达式始终从动画定义获取。
     * @param attackerYaw      攻击者的 yaw 角度（MC 度数）；仅当动画的
     *                         {@code space() == Space.VIEW} 时用于 XZ 旋转。
     */
    public void play(AnimationDef def, Location anchor, Component text,
                     DisplaySettings settingsOverride, float attackerYaw,
                     Player[] viewers, int viewerCount) {
        if (viewers == null || viewerCount == 0) return;

        AnimationDef concrete = unwrap(def);
        double r = ThreadLocalRandom.current().nextDouble();

        // Resolve effective settings: explicit override > PresetDef settings > concrete def settings.
        // unwrap() strips PresetDef wrappers, so def.settings() preserves the outermost
        // preset's overrides (billboard, background, view-range, etc.) that would otherwise
        // be lost when we access concrete.settings().
        DisplaySettings effective = settingsOverride != null ? settingsOverride : def.settings();
        float[] offsetBuf = new float[3];
        effective.offset().evaluateInto(r, offsetBuf);

        // 视角空间旋转参数（仅 VIEW 时计算三角函数，WORLD 时跳过）
        final boolean viewSpace = concrete.space() == Space.VIEW;
        float cos = 1f, sin = 0f;
        if (viewSpace) {
            float yawRad = (float) Math.toRadians(-attackerYaw);
            cos = (float) Math.cos(yawRad);
            sin = (float) Math.sin(yawRad);
        }

        // 密封接口多态分派：
        //   EquationDef + VIEW  → bakeRotated() 内联旋转，零中间分配
        //   EquationDef + WORLD → bake(r) 常规路径
        //   KeyframeDef + VIEW  → bake(r) 返回共享序列，rotateXZ() 产生 per-instance 拷贝
        //   KeyframeDef + WORLD → bake(r) 直接返回共享序列，零拷贝
        BakedSequence seq;
        if (viewSpace && concrete instanceof EquationDef eq) {
            seq = eq.bakeRotated(r, cos, sin);
        } else {
            seq = concrete.bake(r);
            if (viewSpace) seq = seq.rotateXZ(cos, sin);
        }

        if (seq.frames().length == 0) return;

        // 偏移量同步旋转（视角空间 offset 也需要转到世界坐标）
        if (viewSpace) {
            float ox = offsetBuf[0], oz = offsetBuf[2];
            offsetBuf[0] =  ox * cos + oz * sin;
            offsetBuf[2] = -ox * sin + oz * cos;
        }

        Location spawnAt = new Location(
                anchor.getWorld(),
                anchor.getX() + offsetBuf[0],
                anchor.getY() + offsetBuf[1],
                anchor.getZ() + offsetBuf[2]);

        // Allocate entity ID and UUID on the calling thread (thread-safe via Bukkit.getUnsafe)
        @SuppressWarnings("deprecation")
        int entityId = Bukkit.getUnsafe().nextEntityId();
        UUID entityUid = UUID.randomUUID();

        // sharedSeq = true 仅当帧数组是全局共享的（KeyframeDef + WORLD 空间）。
        // 以下两种情况需要 evict 缓存：
        //   1. EquationDef（始终 per-instance）
        //   2. KeyframeDef + VIEW（rotateXZ 产生 per-instance 拷贝，不能用共享标识判断）
        boolean sharedSeq = concrete instanceof KeyframeDef && !viewSpace;

        AnimationInstance inst = new AnimationInstance(
                entityId, entityUid, seq, spawnAt, text,
                effective, viewers, viewerCount, sharedSeq,
                scheduler, collector, this);
        // inst itself acts as the spawn Runnable (run() handles dual-mode via spawned flag)
        // — eliminates the per-play() lambda allocation.
        scheduler.dispatchNow(inst);
    }

    /** Convenience overload: uses all entries in {@code viewers}. */
    public void play(AnimationDef def, Location anchor, Component text, Player[] viewers) {
        play(def, anchor, text, null, 0f, viewers, viewers == null ? 0 : viewers.length);
    }

    /**
     * Evicts a disconnected player from all active animation instances.
     * Must be called from the scheduler thread.
     */
    public void removeViewer(Player player) {
        collector.remove(player);
        for (int i = active.size() - 1; i >= 0; i--) {
            AnimationInstance inst = active.get(i);
            evictFrom(inst, player);
            if (inst.viewerCount == 0) {
                destroyInstance(inst);
            }
        }
    }

    // ── Spawn / destroy lifecycle ─────────────────────────────────────────────

    /** Called on the scheduler thread via {@code dispatchNow}. */
    private void spawnInstance(AnimationInstance inst) {
        if (inst.viewerCount == 0) return;
        inst.listIndex = active.size();
        active.add(inst);

        BakedFrame[] frames = inst.sequence.frames();

        // Spawn entity with frame[0] — inst.settingsOverride is always set
        // (resolved in play() from explicit override / PresetDef / concrete def).
        TextDisplayPackets.spawnInto(inst.entityId, inst.entityUid, inst.spawnAt,
                inst.text, inst.settingsOverride, frames[0],
                inst.viewers, inst.viewerCount, collector);

        // Send all frames due at spawn tick (tickOffset <= 0) immediately
        while (inst.nextFrameIdx < frames.length
                && frames[inst.nextFrameIdx].tickOffset() <= 0) {
            collector.collect(inst.viewers, inst.viewerCount,
                    inst.framePkts[inst.nextFrameIdx]);
            inst.nextFrameIdx++;
        }

        // Kick off the chain-dispatch for remaining frames
        if (inst.nextFrameIdx < frames.length) {
            long delay = frames[inst.nextFrameIdx].tickOffset();
            scheduler.dispatchLater(inst, delay > 0 ? delay : 1);
        }
        // totalTicks includes the sentinel hold frame appended at bake time.
        // The sentinel keeps the MC client's interpolation active for 2 extra ticks
        // after the last real frame, preventing text_opacity reset before destroy.
        scheduler.dispatchLater(inst.destroyTask, inst.sequence.totalTicks());
    }

    /** Destroys and cleans up an animation instance. Idempotent. */
    private void destroyInstance(AnimationInstance inst) {
        if (inst.destroyed) return;
        inst.destroyed = true;
        if (inst.viewerCount > 0) {
            TextDisplayPackets.destroyInto(
                    inst.entityId, inst.viewers, inst.viewerCount, collector);
        }
        if (!inst.sharedFrames) {
            TextDisplayPackets.evictFrameCache(inst.sequence.frames());
        }
        // O(1) swap-remove: move the last element into inst's slot, then shrink.
        int idx = inst.listIndex;
        inst.listIndex = -1;
        int last = active.size() - 1;
        if (idx >= 0 && idx <= last) {
            if (idx != last) {
                AnimationInstance tail = active.get(last);
                active.set(idx, tail);
                tail.listIndex = idx;
            }
            active.remove(last); // no-shift remove: ObjectArrayList fast-path for last index
        }
    }

    // ── Internal types ────────────────────────────────────────────────────────

    /**
     * Bookkeeping for a single animation playthrough.
     * <p>Implements {@link Runnable}: the {@link #run()} method is the chain-dispatch
     * frame callback.  The instance itself is passed to {@code dispatchLater(this, delta)}
     * so the time-wheel holds at most <b>1</b> TransientTask per instance, and zero
     * lambda objects are allocated on the frame-advance path.</p>
     */
    static final class AnimationInstance implements Runnable {

        final int              entityId;
        final UUID             entityUid;
        final BakedSequence    sequence;
        final Location         spawnAt;
        final Component        text;
        final DisplaySettings  settingsOverride;
        final Player[]         viewers;
        int                    viewerCount;
        final boolean          sharedFrames;

        /** Pre-built metadata packets, index-aligned with {@code sequence.frames()}. */
        final WrapperPlayServerEntityMetadata[] framePkts;

        /** Index of the next frame yet to be sent.  Frame[0] is sent at spawn time. */
        int nextFrameIdx = 1;
        boolean destroyed;

        /**
         * Position of this instance in {@link AnimationPlayer#active}.
         * Maintained by {@link AnimationPlayer} for O(1) swap-remove.
         * Scheduler-thread-only; -1 means not currently in the list.
         */
        int listIndex = -1;

        private final RapidTransientScheduler scheduler;
        private final PacketCollector collector;
        private final AnimationPlayer player;

        /**
         * Pre-allocated destroy callback — captured once at construction, reused by
         * {@link AnimationPlayer#spawnInstance}. Avoids a per-spawn lambda allocation.
         */
        final Runnable destroyTask;

        /** False until the first {@link #run()} call, which performs the spawn. */
        private boolean spawned = false;

        AnimationInstance(int entityId, UUID entityUid, BakedSequence sequence,
                          Location spawnAt, Component text,
                          DisplaySettings settingsOverride,
                          Player[] viewers, int viewerCount,
                          boolean sharedFrames,
                          RapidTransientScheduler scheduler,
                          PacketCollector collector,
                          AnimationPlayer player) {
            this.entityId         = entityId;
            this.entityUid        = entityUid;
            this.sequence         = sequence;
            this.spawnAt          = spawnAt;
            this.text             = text;
            this.settingsOverride = settingsOverride;
            this.viewers          = viewers;
            this.viewerCount      = viewerCount;
            this.sharedFrames     = sharedFrames;
            this.scheduler        = scheduler;
            this.collector        = collector;
            this.player           = player;
            this.destroyTask      = () -> player.destroyInstance(this);
            this.framePkts = TextDisplayPackets.buildFramePackets(entityId, sequence.frames());
        }

        /**
         * Dual-mode Runnable:
         * <ul>
         *   <li>First call ({@code !spawned}): performs entity spawn, replaces the
         *       per-{@code play()} lambda that was previously allocated.</li>
         *   <li>Subsequent calls: chain-dispatch frame callback — sends the current
         *       frame(s) and reschedules for the next delta.</li>
         * </ul>
         * Zero lambda allocation on either path.
         */
        @Override
        public void run() {
            if (!spawned) {
                spawned = true;
                player.spawnInstance(this);
                return;
            }
            if (destroyed || viewerCount == 0) return;
            BakedFrame[] frames = sequence.frames();
            int sentTick = frames[nextFrameIdx].tickOffset();
            do {
                collector.collect(viewers, viewerCount, framePkts[nextFrameIdx]);
                nextFrameIdx++;
            } while (nextFrameIdx < frames.length
                     && frames[nextFrameIdx].tickOffset() == sentTick);

            if (nextFrameIdx < frames.length) {
                long delta = frames[nextFrameIdx].tickOffset() - sentTick;
                scheduler.dispatchLater(this, delta);
            }
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /** Recursively unwraps a {@link PresetDef} to its concrete definition. */
    private static AnimationDef unwrap(AnimationDef def) {
        while (def instanceof PresetDef preset) def = preset.resolved();
        return def;
    }

    /**
     * Removes {@code target} from {@code inst.viewers[0..viewerCount-1]} if present,
     * using a swap-with-last-slot strategy: O(n), zero allocations.
     * The evicted slot is null-cleared to release the reference promptly.
     */
    private static void evictFrom(AnimationInstance inst, Player target) {
        for (int i = 0; i < inst.viewerCount; i++) {
            if (inst.viewers[i] == target) {
                int last = inst.viewerCount - 1;
                inst.viewers[i]    = inst.viewers[last];
                inst.viewers[last] = null;
                inst.viewerCount--;
                return; // a player appears at most once per instance
            }
        }
    }
}
