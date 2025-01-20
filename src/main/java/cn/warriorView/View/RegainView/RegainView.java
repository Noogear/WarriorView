package cn.warriorView.View.RegainView;

import cn.warriorView.Object.Range;
import cn.warriorView.Object.Replacement;
import cn.warriorView.View.ViewDisplay;

public class RegainView extends ViewDisplay {

    private final Position position;
    private final boolean onlyPlayer;

    public RegainView(String textFormat, Range scale, byte removeCount, boolean onlyPlayer, Replacement replacement, boolean shadow, double viewRange, byte viewMarge, int backgroundColor, Position position) {
        super(textFormat, scale, removeCount, replacement, shadow, viewRange, viewMarge, backgroundColor);
        this.position = position;
        this.onlyPlayer = onlyPlayer;
    }

    public Position getPosition() {
        return position;
    }

    public boolean isOnlyPlayer() {
        return onlyPlayer;
    }

    public enum Position {
        EYE,
        FOOT
    }

}
