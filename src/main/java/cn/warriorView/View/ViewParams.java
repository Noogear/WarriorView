package cn.warriorView.View;

import cn.warriorView.Object.Animation.IAnimation;
import cn.warriorView.Object.Format.TextFormat;
import cn.warriorView.Object.Offset;
import cn.warriorView.Object.Scale.IScale;

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
