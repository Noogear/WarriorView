package cn.warriorview.animation.engine;

import cn.warriorview.animation.data.BakedFrame;
import cn.warriorview.animation.data.BakedSequence;
import cn.warriorview.animation.data.DisplaySettings;
import cn.warriorview.animation.definition.AnimationDef;
import cn.warriorview.animation.definition.EquationDef;
import cn.warriorview.animation.definition.PresetDef;

import io.netty.util.internal.shaded.org.jctools.queues.MpscUnboundedArrayQueue;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import net.kyori.adventure.text.Component;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import cn.warriorview.util.RapidTransientScheduler;
import cn.warriorview.util.RapidTransientScheduler.TaskHandle;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tick-driven orchestrator for TextDisplay animation instances.
 *
 * <h3>Architecture overview</h3>
 * <pre>
 *  ┌─────────────────────────────────────────────────────────────────────┐
 *  │ Main / event thread(s)                                              │
 *  │   play(def, anchor, text, viewers)  ─── MPSC queue ──►             │
 *  ├─────────────────────────────────────────────────────────────────────┤
 *  │ Single engine thread  (called once per tick from IndicatorHandler)  │
 *  │   tick()                                                            │
 *  │     1. Advance active instances → frameInto(…, collector)          │
 *  │     2. Destroy finished instances → destroyInto(…, collector)      │
 *  │     3. Drain pending queue → spawnInto(…, collector)              │
 *  │     4. collector.flush()  ← one Bundle per player, all animations  │
 *  └─────────────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h3>Flyweight + Bundle merge</h3>
 * Packet wrapper objects are built once per frame and stored <em>by pointer</em>
 * in every relevant viewer's mailbox inside {@link PacketCollector}.  When
 * {@link #tick} ends, the collector sends one
 * {@link com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBundle
 * BUNDLE_DELIMITER-wrapped} sequence per player — regardless of how many
 * concurrent animations contributed packets this tick.
 *
 * <h3>Zero-GC notes</h3>
 * <ul>
 *   <li>Keyframe {@link BakedSequence} is shared across all instances: zero copy.</li>
 *   <li>Equation {@link BakedSequence} is allocated once at spawn time per instance.</li>
 *   <li>{@link TextDisplayPackets#frameInto} uses a {@code ThreadLocal} pool for
 *       the transform {@code EntityData[]} array.</li>
 *   <li>The {@link PacketCollector}'s {@code ObjectArrayList} mailboxes are cleared
 *       (not replaced) each tick, so no repeated allocation.</li>
 * </ul>
 */
public final class AnimationPlayer {

    // ── Cross-thread handoff ──────────────────────────────────────────────────
    /** Lock-free MPSC queue: any thread enqueues via {@link #play}, engine thread drains. */
    private final MpscUnboundedArrayQueue<AnimationInstance> pendingSpawn =
            new MpscUnboundedArrayQueue<>(256);

    // ── Engine-thread-only state ──────────────────────────────────────────────
    /** All currently active animation instances. Single-consumer, no synchronisation needed. */
    private final ObjectArrayList<AnimationInstance> active = new ObjectArrayList<>(64);

    /** Accumulates packets for this tick; flushed at the end of {@link #tick}. */
    private final PacketCollector collector = new PacketCollector();

    // ── Self-managed schedule ─────────────────────────────────────────────────
    /** Scheduler that drives {@link #tick()} while animations are active. */
    private final RapidTransientScheduler scheduler;

    /**
     * Handle for the next scheduled {@link #tick()} invocation, or {@code null} when idle.
     * Written atomically by any thread via {@link #wakeUp()}; reset at the start of
     * each {@link #tick()} call.
     */
    private final AtomicReference<TaskHandle> tickHandleRef = new AtomicReference<>(null);

    // ── Constructor ───────────────────────────────────────────────────────────

    /**
     * @param scheduler the scheduler used to self-drive {@link #tick()} while
     *                  animations are active.  The engine goes idle automatically
     *                  when the active list is empty; {@link #play} re-wakes it.
     */
    public AnimationPlayer(RapidTransientScheduler scheduler) {
        this.scheduler = scheduler;
    }

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
        play(def, anchor, text, null, viewers, viewerCount);
    }

    /**
     * 带 {@link DisplaySettings} 覆盖的播放入口。
     *
     * @param settingsOverride 非 null 时覆盖动画定义自带的 DisplaySettings（billboard、
     *                         背景色、view-range 等），{@code null} 则使用动画默认值。
     *                         offset 表达式始终从动画定义获取。
     */
    public void play(AnimationDef def, Location anchor, Component text,
                     DisplaySettings settingsOverride,
                     Player[] viewers, int viewerCount) {
        if (viewers == null || viewerCount == 0) return;

        AnimationDef concrete = unwrap(def);
        double r = ThreadLocalRandom.current().nextDouble();

        // Evaluate per-instance random offset (always from animation definition)
        DisplaySettings effective = settingsOverride != null ? settingsOverride : concrete.settings();
        float[] offsetBuf = new float[3];
        effective.offset().evaluateInto(r, offsetBuf);

        Location spawnAt = new Location(
                anchor.getWorld(),
                anchor.getX() + offsetBuf[0],
                anchor.getY() + offsetBuf[1],
                anchor.getZ() + offsetBuf[2]);

        // Allocate entity ID and UUID on the calling thread (thread-safe via Bukkit.getUnsafe)
        @SuppressWarnings("deprecation")
        int entityId = Bukkit.getUnsafe().nextEntityId();
        UUID entityUid = UUID.randomUUID();

        // 密封接口多态分派：KeyframeDef 返回共享预烘焙序列（零拷贝），EquationDef 实例级烘焙（单次分配）
        BakedSequence seq = concrete.bake(r);

        if (seq.frames().length == 0) return;

        pendingSpawn.relaxedOffer(
                new AnimationInstance(entityId, entityUid, seq, spawnAt, text,
                        settingsOverride, viewers, viewerCount,
                        !(concrete instanceof EquationDef)));
        wakeUp();
    }

    /** Convenience overload: uses all entries in {@code viewers}. */
    public void play(AnimationDef def, Location anchor, Component text, Player[] viewers) {
        play(def, anchor, text, null, viewers, viewers == null ? 0 : viewers.length);
    }

    /**
     * Advances the animation engine by one tick.
     *
     * <p>Must be called once per tick from a <em>single dedicated engine thread</em>.
     * The sequence is:</p>
     * <ol>
     *   <li>Advance all active instances: send any transform frames due this tick.</li>
     *   <li>Destroy instances whose total duration has elapsed.</li>
     *   <li>Drain the pending queue: spawn newly enqueued instances.</li>
     *   <li>Flush the {@link PacketCollector} → one Bundle per player for steps 1–3.</li>
     * </ol>
     */
    public void tick() {
        // Consumed — will be reset below when we reschedule.
        tickHandleRef.set(null);

        // ── Phase A: advance active instances; flush destroy for cancelled ────
        for (int i = active.size() - 1; i >= 0; i--) {
            AnimationInstance inst = active.get(i);

            if (inst.cancelled) {
                // dispatchLater fired: send destroy packet now and evict.
                TextDisplayPackets.destroyInto(
                        inst.entityId, inst.viewers, inst.viewerCount, collector);
                if (!inst.keyframe) TextDisplayPackets.evictFrameCache(inst.sequence.frames());
                active.remove(i);
                continue;
            }

            inst.age++;

            // Send all frames whose absolute tick-offset has arrived this tick.
            BakedFrame[] frames = inst.sequence.frames();
            while (inst.nextFrameIdx < frames.length
                    && frames[inst.nextFrameIdx].tickOffset() <= inst.age) {
                TextDisplayPackets.frameInto(
                        inst.entityId, frames[inst.nextFrameIdx],
                        inst.viewers, inst.viewerCount, collector);
                inst.nextFrameIdx++;
            }
        }

        // ── Phase B: spawn pending instances ─────────────────────────────────
        // Newly spawned instances are processed AFTER advancing existing ones so
        // their age stays at 0 until the next tick.
        AnimationInstance pending;
        while ((pending = pendingSpawn.poll()) != null) {
            DisplaySettings spawnSettings = pending.settingsOverride != null
                    ? pending.settingsOverride
                    : pending.sequence.settings();
            TextDisplayPackets.spawnInto(
                    pending.entityId, pending.entityUid, pending.spawnAt,
                    pending.text, spawnSettings, pending.sequence.frames()[0],
                    pending.viewers, pending.viewerCount, collector);
            active.add(pending);

            // ── Option 3: precise destroy via dispatchLater ───────────────────
            // Schedules the destroy exactly when the animation finishes, eliminating
            // the per-tick age comparison that was previously in Phase A.
            final AnimationInstance inst = pending;
            final long destroyAfter = inst.sequence.totalTicks() + 2L;
            scheduler.dispatchLater(() -> {
                inst.cancelled = true;
                wakeUp(); // ensure tick() runs on the same scheduler thread to flush the destroy packet
            }, destroyAfter);
        }

        // ── Phase C: flush — one Bundle per player ────────────────────────────
        collector.flush();

        // ── Phase D: idle sleep + frame-precise wakeup ────────────────────────
        if (active.isEmpty() && pendingSpawn.isEmpty()) {
            // No active animations: enter idle.  wakeUp() will restart us when play() enqueues.
            return;
        }

        // Find the minimum ticks until the next keyframe fires across all instances.
        // Instances that have already sent all frames (waiting only for their
        // dispatchLater destroy) are skipped — wakeUp() from the destroy callback handles them.
        long minSleep = Long.MAX_VALUE;
        for (int i = 0; i < active.size(); i++) {
            AnimationInstance inst = active.get(i);
            if (inst.cancelled) { minSleep = 1; break; }
            BakedFrame[] frames = inst.sequence.frames();
            if (inst.nextFrameIdx < frames.length) {
                long sleep = frames[inst.nextFrameIdx].tickOffset() - inst.age;
                if (sleep < minSleep) minSleep = sleep;
            }
        }

        if (minSleep == Long.MAX_VALUE) {
            // All living instances are fully animated; waiting for dispatchLater destroys.
            // Do not schedule a tick now — wakeUp() from those callbacks will do it.
            return;
        }

        long nextWake = Math.max(1L, minSleep);
        TaskHandle h = scheduler.dispatchLater(this::tick, nextWake);
        tickHandleRef.set(h);
    }

    /**
     * Notifies the engine that {@code player} has quit.
     *
     * <p>Two things happen immediately on the engine thread:</p>
     * <ol>
     *   <li>The player's mailbox is removed from {@link PacketCollector} so no
     *       further packets are queued for them.</li>
     *   <li>The player is evicted from every active {@link AnimationInstance}'s
     *       {@code viewers[]} array.  Instances whose viewer count drops to zero
     *       are removed from the active list — there is nothing left to animate for.</li>
     * </ol>
     *
     * <p>This is <em>event-driven</em> (fires only on actual disconnect) rather than
     * polling every N ticks, so the steady-state cost is zero.</p>
     */
    public void removeViewer(Player player) {
        collector.remove(player);
        // Evict from all active instances; remove instances that become viewerless.
        for (int i = active.size() - 1; i >= 0; i--) {
            AnimationInstance inst = active.get(i);
            evictFrom(inst, player);
            if (inst.viewerCount == 0) {
                active.remove(i); // reverse order: safe
            }
        }
    }

    /**
     * Ensures {@link #tick()} will be called on the scheduler's next available tick.
     * Safe to call from <em>any</em> thread.  If a tick is already scheduled this is a no-op.
     */
    private void wakeUp() {
        if (tickHandleRef.get() == null) {
            TaskHandle h = scheduler.dispatchNow(this::tick);
            if (!tickHandleRef.compareAndSet(null, h)) {
                h.cancel(); // another caller beat us to it
            }
        }
    }

    // ── Internal types ────────────────────────────────────────────────────────

    /**
     * Mutable bookkeeping for a single active animation playthrough.
     * Engine-thread-only after enqueue; no synchronisation needed.
     */
    static final class AnimationInstance {

        final int              entityId;
        final UUID             entityUid;
        final BakedSequence    sequence;
        final Location         spawnAt;
        final Component        text;
        /** 非 null 时覆盖 {@code sequence.settings()} 的显示属性。 */
        final DisplaySettings  settingsOverride;
        final Player[]         viewers;
        int                    viewerCount; // mutable: reduced by cullViewers() on disconnect

        /** True for keyframe animations (shared BakedFrames); false for equation (per-instance frames). */
        final boolean          keyframe;

        /** Ticks elapsed since this instance was spawned.  0 on the spawn tick. */
        int age          = 0;
        /** Index of the next frame yet to be sent.  Frame[0] is sent at spawn time. */
        int nextFrameIdx = 1;

        /**
         * Set to {@code true} by the {@code dispatchLater} destroy callback.
         * {@link #tick()} detects this flag in Phase A, sends the destroy packet,
         * and evicts the instance from the active list.
         */
        boolean cancelled = false;

        AnimationInstance(int entityId, UUID entityUid, BakedSequence sequence,
                          Location spawnAt, Component text,
                          DisplaySettings settingsOverride,
                          Player[] viewers, int viewerCount,
                          boolean keyframe) {
            this.entityId         = entityId;
            this.entityUid        = entityUid;
            this.sequence         = sequence;
            this.spawnAt          = spawnAt;
            this.text             = text;
            this.settingsOverride = settingsOverride;
            this.viewers          = viewers;
            this.viewerCount      = viewerCount;
            this.keyframe         = keyframe;
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
