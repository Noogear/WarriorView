package cn.warriorview.api.manager;

/**
 * 配置管理器接口。
 * <p>
 * 统一管理插件的配置文件加载与重载。
 */
public interface ConfigManager {

    /**
     * 重载所有配置文件。
     */
    void reloadAll();

    /**
     * 获取指定路径的配置值。
     *
     * @param path 配置路径（YAML 点分路径，如 "settings.damage.enabled"）。
     * @param def  默认值。
     * @param <T>  值类型。
     * @return 配置值。
     */
    <T> T get(String path, T def);

    /**
     * 设置指定路径的配置值（运行时）。
     *
     * @param path  配置路径。
     * @param value 值。
     */
    void set(String path, Object value);
}
