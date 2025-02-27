package cn.warriorView.manager;

import cn.warriorView.object.format.FormatFactory;
import cn.warriorView.object.format.INumber;
import cn.warriorView.object.format.IText;
import cn.warriorView.object.format.TextFormat;
import cn.warriorView.object.format.number.NumberCommon;
import cn.warriorView.object.format.text.TextCommon;
import cn.warriorView.util.TextUtils;
import cn.warriorView.util.XLogger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.HashMap;
import java.util.Map;

public class FormatManager {

    private final Map<String, IText> textMap;
    private final Map<String, INumber> numberMap;
    private final INumber numberCommon;
    private final IText textCommon;

    public FormatManager() {
        this.textMap = new HashMap<>();
        this.numberMap = new HashMap<>();
        this.numberCommon = new NumberCommon();
        this.textCommon = new TextCommon();
    }

    public void init() {
        textMap.clear();
        numberMap.clear();
    }

    public void load(YamlConfiguration yamlConfiguration) {
        yamlConfiguration.options().pathSeparator(':');
        for (String topKey : yamlConfiguration.getKeys(false)) {
            ConfigurationSection section = yamlConfiguration.getConfigurationSection(topKey);
            if (section == null) continue;

            // ========== 数字格式化部分 ==========
            ConfigurationSection quantizeSection = section.getConfigurationSection("quantize");
            if (quantizeSection != null) {
                Map<Integer, String> quantize = new HashMap<>();
                for (String key : quantizeSection.getKeys(false)) {
                    String val = TextUtils.unescapeUnicode(quantizeSection.getString(key));
                    if (key.equals(val) || val == null) continue;
                    quantize.put(Integer.parseInt(key), val);
                }
                INumber number = quantize.isEmpty() ? numberCommon : FormatFactory.createNumberFormat(quantize);
                numberMap.put(topKey, number);
            }

            // ====== 关键修改：文本替换部分 ======
            ConfigurationSection replaceSection = section.getConfigurationSection("replace");
            if (replaceSection != null) {
                Map<String, String> replace = new HashMap<>();

                // 新增调试：打印原始键值对
                XLogger.info("=====  开始解析替换规则 [" + topKey + "] =====");
                for (String rawKey : replaceSection.getValues(false).keySet()) {
                    Object rawValue = replaceSection.get(rawKey);
                    XLogger.info(" 原始键: [" + rawKey + "] | 类型: " + (rawValue != null ? rawValue.getClass() : "null"));
                    // 空键检测
                    if (rawKey.isEmpty()) {
                        XLogger.err(" 错误：键名为空，跳过处理");
                        continue;
                    }
                    // 值类型校验
                    if (!(rawValue instanceof String)) {
                        XLogger.err(" 错误：键 [" + rawKey + "] 的值类型非法，应为字符串");
                        continue;
                    }
                    String val = TextUtils.unescapeUnicode((String) rawValue);
                    // 单字符键验证（根据需求可选）
                    if (rawKey.length() != 1) {
                        XLogger.err(" 警告：键 [" + rawKey + "] 长度非1，可能不符合预期");
                    }
                    // 跳过无效替换
                    if (rawKey.equals(val) || val == null) {
                        XLogger.warn(" 跳过无效替换：键 [" + rawKey + "] 值与原值相同");
                        continue;
                    }
                    replace.put(rawKey, val);
                    XLogger.info(" 成功注册替换规则: " + rawKey + " → " + val);
                }

                IText text = replace.isEmpty() ? textCommon : FormatFactory.createTextFormat(replace);
                textMap.put(topKey, text);
            }
        }
    }

    public TextFormat getTextFormat(String text, String rule) {
        return FormatFactory.create(text, numberMap.getOrDefault(rule, numberCommon), textMap.getOrDefault(rule, textCommon));
    }
}

