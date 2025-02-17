package cn.warriorView.View;

import cn.warriorView.Object.Animation.Animation;
import cn.warriorView.Object.TextFormat.Replacement;
import cn.warriorView.Object.Scale;
import cn.warriorView.Object.TextFormat.TextQuantize;

public record ViewParams(
        TextQuantize textFormat,
        Replacement replacement,
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

        if(viewMarge < 0) {
            throw new IllegalArgumentException("View Marge cannot be negative");
        }


    }

}
