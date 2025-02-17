package cn.warriorView.View;

import cn.warriorView.Object.Animation.Animation;
import cn.warriorView.Object.Replace;
import cn.warriorView.Object.Scale;

public record ViewParams(
        String textFormat,
        Replace replace,
        Scale scale,
        boolean shadow,
        byte textOpacity,
        float viewRange,
        byte viewMarge,
        int backgroundColor,
        boolean seeThrough,
        boolean onlyPlayer,
        Animation animation,
        String position
) {
    public ViewParams {
        if (viewRange < 0) {
            throw new IllegalArgumentException("View range cannot be negative");
        }
    }

}
