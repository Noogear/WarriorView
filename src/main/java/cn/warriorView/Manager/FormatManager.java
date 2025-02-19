package cn.warriorView.Manager;

import cn.warriorView.Object.Format.Number.INumber;
import cn.warriorView.Object.Format.Number.NumberCommon;
import cn.warriorView.Object.Format.Number.NumberQuantize;
import cn.warriorView.Object.Format.Text.IText;
import cn.warriorView.Object.Format.Text.TextCommon;
import cn.warriorView.Object.Format.Text.TextReplace;
import cn.warriorView.Object.Format.TextFormat;
import cn.warriorView.Util.TextUtils;
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

