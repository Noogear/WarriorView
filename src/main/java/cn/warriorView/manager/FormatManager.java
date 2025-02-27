package cn.warriorView.manager;

import cn.warriorView.object.format.INumber;
import cn.warriorView.object.format.IText;
import cn.warriorView.object.format.TextFormat;
import cn.warriorView.object.format.number.NumberCommon;
import cn.warriorView.object.format.number.NumberQuantize;
import cn.warriorView.object.format.text.TextCommon;
import cn.warriorView.object.format.text.TextReplace;
import cn.warriorView.util.TextUtils;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

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
        Set<String> topKeys = yamlConfiguration.getKeys(false);
        for (String topKey : topKeys) {
            Map<Integer, String> quantize = new HashMap<>();
            Map<String, String> replace = new HashMap<>();
            ConfigurationSection section = yamlConfiguration.getConfigurationSection(topKey);
            if (section == null) continue;
            ConfigurationSection quantizeSection = section.getConfigurationSection("quantize");
            if (quantizeSection != null) {
                for (String key : quantizeSection.getKeys(false)) {
                    String val = quantizeSection.getString(key);
                    if (key.equals(val) || val == null) continue;
                    quantize.put(Integer.parseInt(key), TextUtils.unescapeUnicode(val));
                }
            }
            ConfigurationSection replaceSection = section.getConfigurationSection("replace");
            if (replaceSection != null) {
                for (String key : replaceSection.getKeys(false)) {
                    String val = replaceSection.getString(key);
                    if (key.equals(val) || val == null) continue;
                    replace.put(TextUtils.unescapeUnicode(key), TextUtils.unescapeUnicode(val));
                }
            }
            IText text;
            INumber number;
            if (quantize.isEmpty()) {
                number = numberCommon;
            } else {
                number = NumberQuantize.create(quantize);
            }
            numberMap.put(topKey, number);
            if (replace.isEmpty()) {
                text = textCommon;
            } else {
                text = TextReplace.create(replace);
            }
            textMap.put(topKey, text);
        }
    }

    public TextFormat getTextFormat(String text, String rule) {
        return TextFormat.create(text, numberMap.getOrDefault(rule, numberCommon), textMap.getOrDefault(rule, textCommon));
    }
}

