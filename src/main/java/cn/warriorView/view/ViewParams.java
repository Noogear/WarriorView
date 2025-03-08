package cn.warriorView.view;

import cn.warriorView.object.Offset;
import cn.warriorView.object.animation.IAnimation;
import cn.warriorView.object.format.TextFormat;
import cn.warriorView.object.scale.IScale;
import cn.warriorView.util.XLogger;

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
            viewRange = 0;
            XLogger.err("View range cannot be negative");
        }

        if (viewMarge < 0) {
            viewMarge = 0;
            XLogger.err("View Marge cannot be negative");
        }
    }

}
