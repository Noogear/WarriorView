package cn.warriorview.animation.engine;

import cn.warriorview.animation.data.BakedFrame;
import cn.warriorview.animation.data.DisplaySettings;
import cn.warriorview.animation.data.TransformSnapshot;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.util.Vector3f;
import com.github.retrooper.packetevents.util.Quaternion4f;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.UUID;

/**
 * Low-level PacketEvents wrapper for TextDisplay entity lifecycle and frame updates.
 *
 * <h3>Zero-GC design</h3>
 * <ul>
 *   <li>A {@code ThreadLocal<EntityData<?>[]>} pool holds a fixed-size array that is
 *       reused on every call to {@link #sendTransformFrame}.  The array itself is never
 *       reallocated; only the {@link EntityData} wrappers inside it are replaced.</li>
 *   <li>{@link Vector3f} and {@link Quaternion4f} values are allocated per call, but
 *       PacketEvents requires these objects only until the packet is sent, so the GC
 *       pressure is limited to short-lived objects in the young generation.</li>
 * </ul>
 *
 * <h3>TextDisplay metadata indexes (MC 1.21.x)</h3>
 * <pre>
 *   8  = interpolation_delay                  (int)
 *   9  = transformation_interpolation_duration (int)
 *  10  = position_rotation_interpolation_dur.  (int)   — teleportDuration
 *  11  = translation                           (Vector3f)
 *  12  = scale                                 (Vector3f)
 *  13  = left_rotation                         (Quaternion4f)
 *  14  = right_rotation                        (Quaternion4f)
 *  15  = billboard_constraints                 (byte)
 *  16  = brightness_override                   (int)   — (blockLight &lt;&lt; 4 | skyLight &lt;&lt; 20)
 *  17  = view_range                            (float)
 *  18  = shadow_radius                         (float)
 *  19  = shadow_strength                       (float)
 *  22  = glow_color_override                   (int)
 *  23  = text                                  (Component)
 *  24  = line_width                            (int)
 *  25  = background_color                      (int ARGB)
 *  26  = text_opacity                          (byte)
 *  27  = style_flags                           (byte)  — bit0=shadow, bit1=see-through
 * </pre>
 */
public final class TextDisplayPackets {

    /**
     * Per-frame transform metadata cache: {@link BakedFrame} identity → pre-built {@link EntityData} list.
     *
     * <p>Uses {@link IdentityHashMap} for O(1) lookup via {@code System.identityHashCode()} —
     * avoids the structural {@code hashCode()} of the {@link BakedFrame} record
     * (which hashes all 15+ float fields of {@link TransformSnapshot} on every tick call).</p>
     *
     * <p>Keyframe animation frames are long-lived (plugin lifetime); their entries persist
     * indefinitely.  Equation animation frames are per-instance; call
     * {@link #evictFrameCache(BakedFrame[])} when the instance is destroyed to release them.</p>
     *
     * <p>Thread-safety: engine is single-consumer; all accesses are on the same scheduler thread.</p>
     */
    private static final IdentityHashMap<BakedFrame, List<EntityData<?>>> FRAME_META_CACHE =
            new IdentityHashMap<>();

    private TextDisplayPackets() {}

    // -------------------------------------------------------------------------
    // Spawn
    // -------------------------------------------------------------------------

    /**
     * Spawns a text display entity for the given set of viewers and sends the
     * initial full metadata (settings + first frame).
     *
     * @param entityId  pre-allocated entity id (from {@code EntityIdUtil.next()})
     * @param entityUid new UUID for the entity
     * @param at        spawn location
     * @param text      initial chat component (may be updated per-frame via overlay)
     * @param settings  display settings from the animation definition
     * @param first     first frame to apply immediately at spawn
     * @param viewers   player list to send packets to
     */
    public static void spawn(
            int entityId,
            UUID entityUid,
            Location at,
            Component text,
            DisplaySettings settings,
            BakedFrame first,
            Player[] viewers,
            int viewerCount
    ) {
        // --- Spawn packet ---
        var spawnPacket = new WrapperPlayServerSpawnEntity(
                entityId,
                entityUid,
                EntityTypes.TEXT_DISPLAY,
                SpigotConversionUtil.fromBukkitLocation(at),
                at.getYaw(),
                0,
                null
        );

        // --- Initial full metadata ---
        List<EntityData<?>> meta = buildInitialMeta(text, settings, first);
        var metaPacket = new WrapperPlayServerEntityMetadata(entityId, meta);

        for (int i = 0; i < viewerCount; i++) {
            Player p = viewers[i];
            var channel = PacketEvents.getAPI().getPlayerManager().getChannel(p);
            if (channel == null) continue;
            PacketEvents.getAPI().getProtocolManager().sendPacket(channel, spawnPacket);
            PacketEvents.getAPI().getProtocolManager().sendPacket(channel, metaPacket);
        }
    }

    // -------------------------------------------------------------------------
    // Transform frame update
    // -------------------------------------------------------------------------

    /**
     * Sends a minimal transform metadata update for a single frame to the given viewers.
     * Uses the {@link #TRANSFORM_POOL ThreadLocal pool} to avoid allocating the metadata
     * list on every call.
     */
    public static void sendTransformFrame(int entityId, BakedFrame frame, Player[] viewers, int viewerCount) {
        var packet = new WrapperPlayServerEntityMetadata(entityId, frameMetaOf(frame));
        for (int i = 0; i < viewerCount; i++) {
            var channel = PacketEvents.getAPI().getPlayerManager().getChannel(viewers[i]);
            if (channel != null) {
                PacketEvents.getAPI().getProtocolManager().sendPacket(channel, packet);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Destroy
    // -------------------------------------------------------------------------

    /**
     * Destroys the text display entity for the given viewers.
     */
    public static void destroy(int entityId, Player[] viewers, int viewerCount) {
        var packet = new WrapperPlayServerDestroyEntities(entityId);
        for (int i = 0; i < viewerCount; i++) {
            var channel = PacketEvents.getAPI().getPlayerManager().getChannel(viewers[i]);
            if (channel != null) {
                PacketEvents.getAPI().getProtocolManager().sendPacket(channel, packet);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Returns the cached immutable {@link EntityData} list for the given frame,
     * building and caching it on first access.
     *
     * <p>Uses identity-based lookup: O(1) via {@code System.identityHashCode()},
     * no structural field hashing.</p>
     */
    private static List<EntityData<?>> frameMetaOf(BakedFrame frame) {
        return FRAME_META_CACHE.computeIfAbsent(frame, TextDisplayPackets::buildFrameMetaList);
    }

    /**
     * Pre-builds one {@link WrapperPlayServerEntityMetadata} per frame for the
     * given entity ID.  Called once at spawn time so the tick loop can
     * {@link PacketCollector#collect} a cached pointer instead of allocating a
     * new wrapper on every advance.
     *
     * @param entityId unique entity id for the animation instance
     * @param frames   all baked frames of the animation sequence
     * @return array of pre-built metadata packets, index-aligned with {@code frames}
     */
    static WrapperPlayServerEntityMetadata[] buildFramePackets(
            int entityId, BakedFrame[] frames) {
        WrapperPlayServerEntityMetadata[] pkts =
                new WrapperPlayServerEntityMetadata[frames.length];
        for (int i = 0; i < frames.length; i++) {
            pkts[i] = new WrapperPlayServerEntityMetadata(entityId, frameMetaOf(frames[i]));
        }
        return pkts;
    }

    /**
     * Removes all entries for the given frames from the cache.
     * Called when an equation-animation instance is destroyed, so its
     * {@link BakedFrame} objects (and the cached metadata lists) can be GC'd.
     * Keyframe animation frames are long-lived and must NOT be evicted.
     */
    static void evictFrameCache(BakedFrame[] frames) {
        for (BakedFrame f : frames) FRAME_META_CACHE.remove(f);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static List<EntityData<?>> buildFrameMetaList(BakedFrame frame) {
        TransformSnapshot s = frame.snapshot();
        return List.of(
                new EntityData(8,  EntityDataTypes.INT,       frame.interpolationDelay()),
                new EntityData(9,  EntityDataTypes.INT,       frame.interpolationTicks()),
                new EntityData(11, EntityDataTypes.VECTOR3F,  new Vector3f(s.tx(), s.ty(), s.tz())),
                new EntityData(12, EntityDataTypes.VECTOR3F,  new Vector3f(s.sx(), s.sy(), s.sz())),
                new EntityData(13, EntityDataTypes.QUATERNION, new Quaternion4f(s.lrx(), s.lry(), s.lrz(), s.lrw())),
                new EntityData(14, EntityDataTypes.QUATERNION, new Quaternion4f(s.rrx(), s.rry(), s.rrz(), s.rrw()))
        );
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static List<EntityData<?>> buildInitialMeta(
            Component text,
            DisplaySettings settings,
            BakedFrame first
    ) {
        byte styleFlags = 0;
        if (settings.textShadow())  styleFlags |= 0x01;
        if (settings.seeThrough())  styleFlags |= 0x02;
        byte opacity = first.snapshot().textOpacity() < 0 ? (byte) -1 : first.snapshot().textOpacity();

        List<EntityData<?>> frameMeta = frameMetaOf(first);
        List<EntityData<?>> out = new ArrayList<>(frameMeta.size() + 12);
        out.addAll(frameMeta);                                          // reuse cached transform entries
        out.add(new EntityData(10, EntityDataTypes.INT,           settings.teleportDuration()));
        out.add(new EntityData(15, EntityDataTypes.BYTE,          settings.billboard().protocolId()));
        if (settings.brightness() >= 0) {
            out.add(new EntityData(16, EntityDataTypes.INT,        settings.brightness()));
        }
        out.add(new EntityData(17, EntityDataTypes.FLOAT,         settings.viewRange()));
        out.add(new EntityData(18, EntityDataTypes.FLOAT,         settings.shadowRadius()));
        out.add(new EntityData(19, EntityDataTypes.FLOAT,         settings.shadowStrength()));
        if (settings.glowColorOverride() != 0) {
            out.add(new EntityData(22, EntityDataTypes.INT,        settings.glowColorOverride()));
        }
        out.add(new EntityData(23, EntityDataTypes.ADV_COMPONENT, text));
        out.add(new EntityData(24, EntityDataTypes.INT,           settings.lineWidth()));
        out.add(new EntityData(25, EntityDataTypes.INT,           settings.backgroundColor()));
        out.add(new EntityData(26, EntityDataTypes.BYTE,          opacity));
        out.add(new EntityData(27, EntityDataTypes.BYTE,          styleFlags));
        return out;
    }

    // =========================================================================
    // Collector-based overloads (tick-driven / bundle-merge path)
    // =========================================================================
    // These mirror the direct-send methods above but route packets through a
    // PacketCollector instead of calling sendPacket immediately.  The collector
    // stores only a pointer to each wrapper (flyweight); the actual on-wire
    // encoding happens once per player when the collector is flushed.

    /**
     * Collector-based spawn: builds spawn + initial-metadata packets and hands
     * them to {@code collector} for deferred, bundle-merged delivery.
     */
    public static void spawnInto(
            int entityId,
            UUID entityUid,
            Location at,
            Component text,
            DisplaySettings settings,
            BakedFrame first,
            Player[] viewers,
            int viewerCount,
            PacketCollector collector
    ) {
        var spawnPacket = new WrapperPlayServerSpawnEntity(
                entityId,
                entityUid,
                EntityTypes.TEXT_DISPLAY,
                SpigotConversionUtil.fromBukkitLocation(at),
                at.getYaw(),
                0,
                null
        );
        List<EntityData<?>> meta = buildInitialMeta(text, settings, first);
        var metaPacket = new WrapperPlayServerEntityMetadata(entityId, meta);

        // Both packets share all viewers — pointer copy only (flyweight)
        collector.collect(viewers, viewerCount, spawnPacket);
        collector.collect(viewers, viewerCount, metaPacket);
    }

    /**
     * Collector-based transform frame: routes the update through the collector.
     *
     * <p>Uses a {@link WeakHashMap} cache keyed on {@link BakedFrame} identity to
     * return a pre-built immutable {@link EntityData} list — no
     * {@link Vector3f}/{@link Quaternion4f}/{@link EntityData} objects are allocated
     * at call time.  Only a thin {@link WrapperPlayServerEntityMetadata} wrapper
     * (carrying the per-instance entityId) is created per frame per instance.</p>
     */
    public static void frameInto(
            int entityId,
            BakedFrame frame,
            Player[] viewers,
            int viewerCount,
            PacketCollector collector
    ) {
        // frameMetaOf() returns a cached, immutable List built once per unique BakedFrame.
        // A new WrapperPlayServerEntityMetadata is still needed per instance because it
        // carries the entityId, but no EntityData / Vector3f / Quaternion4f are allocated.
        var packet = new WrapperPlayServerEntityMetadata(entityId, frameMetaOf(frame));
        collector.collect(viewers, viewerCount, packet);
    }

    /**
     * Collector-based destroy: routes the destroy packet through the collector.
     */
    public static void destroyInto(
            int entityId,
            Player[] viewers,
            int viewerCount,
            PacketCollector collector
    ) {
        collector.collect(viewers, viewerCount, new WrapperPlayServerDestroyEntities(entityId));
    }
}
