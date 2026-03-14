package cn.warriorview.animation.parse;

import cn.warriorview.animation.api.Space;
import cn.warriorview.animation.data.BakedFrame;
import cn.warriorview.animation.data.BakedSequence;
import cn.warriorview.animation.data.DisplaySettings;
import cn.warriorview.animation.data.TransformSnapshot;
import cn.warriorview.animation.definition.KeyframeDef;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Parses a {@code type: keyframe} animation section into a fully pre-baked
 * {@link KeyframeDef}.
 *
 * <h3>YAML structure</h3>
 * <pre>
 * my_anim:
 *   type: keyframe
 *   settings: { ... }
 *   timeline:
 *     # first frame uses `time:` to set the absolute tick offset
 *     - time: 0
 *       translation: [0, 0, 0]
 *       scale: [1, 1, 1]
 *
 *     # subsequent frames use `duration:` for the interpolation window
 *     - duration: 10
 *       move: { up: 0.5 }
 *       size: 1.5
 *       opacity: 50%
 *
 *     - duration: 5
 *       translation: [0, 1.0, 0]
 *       left_rotation: [0, 0, 0, 1]
 *       right_rotation: [0, 0.7071, 0, 0.7071]
 * </pre>
 *
 * <p>The resulting {@link BakedFrame} array is shared across all concurrent plays
 * of this animation – it is fully immutable and thread-safe.</p>
 */
public final class KeyframeParser {

    private KeyframeParser() {}

    /**
     * Parses the animation section and returns a pre-baked {@link KeyframeDef}.
     *
     * @param name    the animation's unique identifier
     * @param section the YAML section under {@code <name>:} (contains {@code type},
     *                {@code settings}, {@code timeline})
     * @return a fully baked {@link KeyframeDef}, or {@code null} if the timeline is absent
     */
    public static KeyframeDef parse(String name, ConfigurationSection section) {
        DisplaySettings settings = SettingsParser.parse(section.getConfigurationSection("settings"));
        Space space = Space.fromName(section.getString("space"));

        List<Map<?, ?>> rawTimeline = section.getMapList("timeline");
        if (rawTimeline.isEmpty()) {
            System.err.println("[WarriorView] Keyframe animation '" + name + "' has no timeline.");
            return null;
        }

        List<BakedFrame> frames = new ArrayList<>(rawTimeline.size());
        TransformSnapshot prev = TransformSnapshot.IDENTITY;
        float[] deltas = new float[SyntaxSugarResolver.DELTA_COUNT];
        int currentTick = 0;

        for (Map<?, ?> entry : rawTimeline) {
            MemoryConfiguration frameSection = new MemoryConfiguration();
            for (var kv : entry.entrySet())
                frameSection.set(kv.getKey().toString(), kv.getValue());

            // Determine tick offset
            int interpolationTicks;
            if (frameSection.contains("time")) {
                currentTick = frameSection.getInt("time", 0);
                interpolationTicks = 0; // initial frame – no interpolation delay
            } else {
                interpolationTicks = frameSection.getInt("duration", 1);
                // currentTick advances by duration
                currentTick += interpolationTicks;
            }

            // Resolve transform via sugar + raw fields
            TransformSnapshot snap = SyntaxSugarResolver.resolve(prev, frameSection, deltas);
            prev = snap;

            // Zero-delta deduplication: skip frames whose transform is identical to the
            // last emitted frame.  TransformSnapshot is a record so equals() compares all
            // 15 primitive fields exactly.
            //
            // Frame 0 is always emitted — it is sent as part of the spawn packet in
            // TextDisplayPackets.spawnInto(), so it must exist regardless.
            //
            // currentTick still advances even when a frame is skipped, which is correct:
            // the hold period is simply absorbed by the previous frame's interpolation.
            if (!frames.isEmpty() && snap.equals(frames.get(frames.size() - 1).snapshot())) {
                continue;
            }

            frames.add(new BakedFrame(currentTick, 0, interpolationTicks, snap));
        }

        if (frames.isEmpty()) return null;

        BakedFrame[] frameArray = frames.toArray(new BakedFrame[0]);
        int totalTicks = frameArray[frameArray.length - 1].tickOffset();
        BakedSequence seq = new BakedSequence(frameArray, totalTicks, settings);
        return new KeyframeDef(name, settings, space, seq);
    }
}
