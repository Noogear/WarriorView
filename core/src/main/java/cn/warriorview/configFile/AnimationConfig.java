package cn.warriorview.configFile;

import cn.warriorview.animation.engine.AnimationPlayer;
import cn.warriorview.animation.load.AnimationFileLoader;
import cn.warriorview.animation.registry.AnimationRegistry;
import cn.warriorview.util.RapidTransientScheduler;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Facade that wires together the animation sub-system components.
 *
 * <p>Call {@link #reload()} to (re-)scan all YAML files in
 * {@code plugins/WarriorView/animations/} and rebuild the registry.</p>
 */
public final class AnimationConfig {

    private final AnimationRegistry  registry;
    private final AnimationPlayer    player;
    private final AnimationFileLoader loader;

    public AnimationConfig(JavaPlugin plugin, RapidTransientScheduler scheduler) {
        this.registry = new AnimationRegistry();
        this.player   = new AnimationPlayer(scheduler); // self-managed via RapidTransientScheduler
        this.loader   = new AnimationFileLoader(plugin);
    }

    /** (Re-)loads all animation files and rebuilds the registry. */
    public void reload() {
        loader.load(registry);
    }

    /**
     * Returns {@code true} if any animation YAML file has changed since the last load.
     */
    public boolean hasChanges() {
        return loader.hasChanges();
    }

    public AnimationRegistry getRegistry() { return registry; }
    public AnimationPlayer   getPlayer()   { return player;   }
}
