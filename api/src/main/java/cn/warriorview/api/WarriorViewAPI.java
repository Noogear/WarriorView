package cn.warriorview.api;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

/**
 * WarriorView API 的静态访问入口。
 * <p>
 * 外部插件通过本类获取 API 实例：
 * 
 * <pre>{@code
 * WarriorView api = WarriorViewAPI.getProvider();
 * api.reloadAll();
 * }</pre>
 */
public final class WarriorViewAPI {
    private static WarriorView provider;

    private WarriorViewAPI() {
    }

    public static @NotNull WarriorView getProvider() {
        if (provider == null) {
            throw new IllegalStateException("WarriorView 尚未初始化，请确认插件已启用。");
        }
        return provider;
    }


    @ApiStatus.Internal
    public static void register(@NotNull WarriorView impl) {
        provider = impl;
    }

    @ApiStatus.Internal
    public static void unregister() {
        provider = null;
    }
}
