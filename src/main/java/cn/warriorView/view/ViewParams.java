package cn.warriorView.view;

import cn.warriorView.object.Offset;
import cn.warriorView.object.animation.IAnimation;
import cn.warriorView.object.format.TextFormat;
import cn.warriorView.object.scale.IScale;

import java.util.List;

public record ViewParams(
        TextFormat textFormat,
        IScale scale,
        boolean shadow,
        byte textOpacity,
        float viewRange,
        byte viewMarge,
        int backgroundColor,
        boolean seeThrough,
        boolean onlyPlayer,
        List<IAnimation> animations,
        String position,
        Offset offset
) {
    public ViewParams {
        if (viewRange < 0) {
            throw new IllegalArgumentException("View range cannot be negative");
        }

        if (viewMarge < 0) {
            throw new IllegalArgumentException("View Marge cannot be negative");
        }


    }

}
