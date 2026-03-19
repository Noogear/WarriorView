package cn.warriorview.integration;

import cn.warriorview.util.Log;
import me.clip.placeholderapi.PlaceholderAPIPlugin;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import me.clip.placeholderapi.expansion.manager.LocalExpansionManager;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * PlaceholderAPI 集成：加载/重载期解析全局占位符，运行期零开销。
 *
 * <p>绕过 {@code PlaceholderAPI.setPlaceholders()} 的 CharsReplacer 间接层，
 * 直接通过 {@link LocalExpansionManager} 查找扩展并调用 {@code onRequest(null, params)}。
 * 同一加载会话内相同占位符仅解析一次（{@link #resolveCache}）。</p>
 */
public final class PlaceholderAPIIntegration {

    private final LocalExpansionManager expansionManager;

    /** key = 占位符内容（不含 %），value = 解析结果（null = 未注册/返回 null）。每次 {@link #beginResolveSession()} 重建。 */
    private Map<String, String> resolveCache = new HashMap<>();
    private volatile boolean hasUnresolved;

    private PlaceholderAPIIntegration(LocalExpansionManager manager) {
        this.expansionManager = manager;
    }

    /** 尝试加载；PAPI 不可用时返回 {@code null}。 */
    public static PlaceholderAPIIntegration tryLoad(Plugin plugin) {
        if (plugin.getServer().getPluginManager().getPlugin("PlaceholderAPI") == null) {
            return null;
        }
        try {
            LocalExpansionManager manager = PlaceholderAPIPlugin.getInstance()
                    .getLocalExpansionManager();
            Log.info("[Integration] PlaceholderAPI integration loaded successfully ({} expansions registered).",
                    manager.getIdentifiers().size());
            return new PlaceholderAPIIntegration(manager);
        } catch (Exception e) {
            Log.warn("[Integration] PlaceholderAPI integration failed: {}", e.getMessage());
            return null;
        }
    }

    /** 开始新加载会话：清空缓存和未解析标记。每次 reload 前调用。 */
    public void beginResolveSession() {
        resolveCache = new HashMap<>();
        hasUnresolved = false;
    }

    /**
     * 解析字符串中的全局 PAPI 占位符（{@code %identifier_params%} 格式）。
     * 无占位符时直接返回原引用（零分配）。
     */
    public String resolve(String text) {
        if (text == null) return null;

        int len = text.length();
        if (len < 5 || text.indexOf('%') < 0) return text; // 最短: %a_b%

        StringBuilder sb = null;
        int lastAppend = 0;

        int i = 0;
        while (i < len) {
            int start = text.indexOf('%', i);
            if (start < 0 || start + 3 >= len) break; // 剩余不足以构成占位符

            int end = text.indexOf('%', start + 1);
            if (end < 0) break;

            String placeholder = text.substring(start + 1, end);

            int sep = placeholder.indexOf('_');
            if (sep < 1) {
                i = start + 1;
                continue;
            }

            String identifier = placeholder.substring(0, sep).toLowerCase();
            if (!isValidIdentifier(identifier)) {
                i = start + 1;
                continue;
            }

            String resolved = resolvePlaceholder(placeholder, identifier, sep);

            if (resolved != null) {
                if (sb == null) sb = new StringBuilder(len);
                sb.append(text, lastAppend, start);
                sb.append(resolved);
                lastAppend = end + 1;
            }

            i = end + 1;
        }

        if (sb == null) return text;
        sb.append(text, lastAppend, len);
        return sb.toString();
    }

    /** 单个占位符解析（带缓存）。containsKey 区分「缓存为 null」与「未缓存」。 */
    private String resolvePlaceholder(String placeholder, String identifier, int sep) {
        if (resolveCache.containsKey(placeholder)) {
            String cached = resolveCache.get(placeholder);
            if (cached == null) hasUnresolved = true;
            return cached;
        }

        PlaceholderExpansion expansion = expansionManager.getExpansion(identifier);
        if (expansion == null) {
            hasUnresolved = true;
            resolveCache.put(placeholder, null);
            return null;
        }

        String params = placeholder.substring(sep + 1);
        String result = expansion.onRequest(null, params);

        if (result == null) {
            hasUnresolved = true;
        }
        resolveCache.put(placeholder, result);
        return result;
    }

    private static boolean isValidIdentifier(String id) {
        for (int i = 0, len = id.length(); i < len; i++) {
            char c = id.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9'))) return false;
        }
        return true;
    }

    /** 是否存在未解析的占位符（用于判断 ServerLoadEvent 后是否需要重试）。 */
    public boolean hasUnresolvedPlaceholders() {
        return hasUnresolved;
    }

    public UnaryOperator<String> asResolver() {
        return this::resolve;
    }
}
