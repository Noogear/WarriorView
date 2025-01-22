package cn.warriorView.Manager;

import cn.warriorView.Object.Replacement;

import java.util.HashMap;
import java.util.Map;

public class ReplacementManager {
    
    private final Map<String, Replacement> replacements;

    public ReplacementManager() {
        this.replacements = new HashMap<>();
    }

    public void load() {
        replacements.clear();
    }

    public Replacement getReplacement(String groupId) {
        return replacements.get(groupId);
    }

}
