package cn.strikeView.Util;

import java.util.concurrent.atomic.AtomicInteger;

public class EntityUtil {

    private static final AtomicInteger autoEntityId = new AtomicInteger(1000000);

    public static int getAutoEntityId() {
        return autoEntityId.incrementAndGet();
    }



}
