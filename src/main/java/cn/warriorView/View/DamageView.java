package cn.warriorView.View;

import cn.warriorView.Object.Range;
import cn.warriorView.Object.Replacement;

public class DamageView extends ViewDisplay {

    private final Position position;

    public DamageView(String textFormat, Range scale, byte removeCount, Replacement replacement, boolean shadow, double viewRange, byte viewMarge, int backgroundColor, Position position) {
        super(textFormat, scale, removeCount, replacement, shadow, viewRange, viewMarge, backgroundColor);
        this.position = position;
    }

    public Position getPosition() {
        return position;
    }

    public enum Position {
        EYE,
        FOOT
    }


}
