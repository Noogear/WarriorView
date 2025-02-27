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
        for (String topKey : yamlConfiguration.getKeys(false)) {
            ConfigurationSection section = yamlConfiguration.getConfigurationSection(topKey);
            if (section == null) continue;
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

            ConfigurationSection replaceSection = section.getConfigurationSection("replace");
            if (replaceSection != null) {
                Map<String, String> replace = new HashMap<>();
                for (String key : replaceSection.getKeys(false)) {
                    String val = TextUtils.unescapeUnicode(replaceSection.getString(key));
                    XLogger.info("replace: " + key + " -> " + val);
                    if (key.length() != 1) {
                        XLogger.err("replace key must be single character: " + key);
                        continue;
                    }
                    if (key.equals(val) || val == null) continue;
                    replace.put(key, val);
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

