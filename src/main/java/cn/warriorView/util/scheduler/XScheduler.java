package cn.warriorView.util.scheduler;

import cn.warriorView.Main;

public class XScheduler {

    public static XScheduler instance;
    private final IScheduler scheduler;

    public XScheduler(Main main, boolean isFolia) {
        instance = this;
        if (isFolia) {
            scheduler = new FoliaScheduler(main);
        } else {
            scheduler = new BukkitScheduler(main);
        }
    }

    public static IScheduler get() {
        return instance.scheduler;
    }

}
