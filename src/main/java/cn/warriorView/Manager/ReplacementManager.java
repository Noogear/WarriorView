package cn.warriorView.Manager;

import cn.warriorView.Object.Replacement;

import java.util.HashMap;
import java.util.Map;

public class ReplacementManager {
    
    private final Map<String, Replacement> replacements;

    public ReplacementManager() {
        this.replacements = new HashMap<>();
        load();
    }

    public void load() {
    }

    public Replacement getReplacement(String groupId) {
        return replacements.get(groupId);
    }

}
