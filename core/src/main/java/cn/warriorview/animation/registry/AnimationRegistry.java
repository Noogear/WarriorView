package cn.warriorview.animation.registry;

import cn.warriorview.animation.definition.AnimationDef;

import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central registry for all loaded {@link AnimationDef} instances.
 *
 * <p>The underlying store is a {@link ConcurrentHashMap}, which allows safe
 * concurrent reads from the main thread (event handlers) without locks.
 * Writes happen only during plugin (re)load on the main thread, so no
 * explicit synchronisation is needed for mutation.</p>
 */
public final class AnimationRegistry {

    private final ConcurrentHashMap<String, AnimationDef> store = new ConcurrentHashMap<>();

    /** Registers a definition, replacing any existing entry with the same name. */
    public void register(AnimationDef def) {
        store.put(def.name(), def);
    }

    /**
     * Looks up an animation definition by name.
     *
     * @param name the unique identifier
     * @return the definition, or {@code null} if not found
     */
    public AnimationDef get(String name) {
        return store.get(name);
    }

    /** Returns {@code true} if a definition with {@code name} is registered. */
    public boolean contains(String name) {
        return store.containsKey(name);
    }

    /** Returns an unmodifiable view of all registered definitions. */
    public Collection<AnimationDef> all() {
        return Collections.unmodifiableCollection(store.values());
    }

    /** Returns the total number of registered definitions. */
    public int size() {
        return store.size();
    }

    /** Removes all registered definitions. Call on plugin disable / reload. */
    public void clear() {
        store.clear();
    }
}
