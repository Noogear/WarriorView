package cn.warriorView.Configuration;

import cn.warriorView.Main;
import cn.warriorView.View.ViewDisplay;

public class FileManager {
    private Main plugin;
    private ViewDisplay defaultDamageView;
    private ViewDisplay defaultRegainView;

    public FileManager(Main main) {
        plugin = main;
        load();
    }

    public void load() {
        defaultDamageView = null;
        defaultRegainView = null;


    }

}
