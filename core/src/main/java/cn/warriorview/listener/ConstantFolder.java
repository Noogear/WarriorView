package cn.warriorview.listener;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 编译期常量折叠器。
 * 将配置值预计算为 JVM 常量：类名 → {@link java.lang.Class}，四则运算 → 数值。
 */
public final class ConstantFolder {

    private static final Logger LOGGER = Logger.getLogger(ConstantFolder.class.getName());
    private static final Map<String, Class<?>> classCache = new ConcurrentHashMap<>();

    /** 二元算术表达式 */
    private static final Pattern BINARY_EXPR = Pattern.compile(
            "(-?[\\d.]+)\\s*([+\\-*/])\\s*(-?[\\d.]+)");

    /** 常量折叠入口 */
    public Object fold(Object value, Class<?> targetType) {
        if (!(value instanceof String text))
            return value;

        if (targetType == Class.class)
            return resolveClass(text);

        if (JvmTypeUtils.isNumericType(targetType)) {
            Matcher m = BINARY_EXPR.matcher(text.trim());
            if (m.matches())
                return foldArithmetic(m, targetType);
        }

        return value;
    }

    /** 类名 → {@link java.lang.Class} 对象，带缓存 */
    public Class<?> resolveClass(String className) {
        Objects.requireNonNull(className, "className 不能为 null");
        return classCache.computeIfAbsent(className, name -> {
            try {
                return Class.forName(name);
            } catch (ClassNotFoundException e) {
                LOGGER.log(Level.WARNING, "常量折叠：无法解析类名 ''{0}''，使用 Object 作为回退", name);
                return Object.class;
            }
        });
    }

    private static Object foldArithmetic(Matcher m, Class<?> targetType) {
        double left = Double.parseDouble(m.group(1));
        double right = Double.parseDouble(m.group(3));
        double result = switch (m.group(2).charAt(0)) {
            case '+' -> left + right;
            case '-' -> left - right;
            case '*' -> left * right;
            case '/' -> right != 0 ? left / right : Double.NaN;
            default -> throw new IllegalStateException();
        };
        if (targetType == int.class)
            return (int) result;
        if (targetType == long.class)
            return (long) result;
        if (targetType == float.class)
            return (float) result;
        return result;
    }
}
