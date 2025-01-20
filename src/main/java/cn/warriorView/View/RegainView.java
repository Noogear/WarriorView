package cn.warriorView.View;

import cn.warriorView.Object.Range;
import cn.warriorView.Object.Replacement;

public class RegainView extends ViewDisplay {

    private Position position;

    public RegainView(String textFormat, Range scale, byte removeCount, boolean onlyPlayer, Replacement replacement, boolean shadow, double viewRange, byte viewMarge, int backgroundColor) {
        super(textFormat, scale, removeCount, onlyPlayer, replacement, shadow, viewRange, viewMarge, backgroundColor);
    }

    enum Position{
        EYE,
        FOOT
    }

}
