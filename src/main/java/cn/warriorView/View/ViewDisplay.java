package cn.warriorView.View;

import cn.warriorView.Object.Range;
import cn.warriorView.Object.Replacement;

public class ViewDisplay {

    private final String textFormat;
    private final Range scale;
    private final byte removeCount;
    private final boolean onlyPlayer;
    private final Replacement replacement;
    private final boolean shadow;
    private final double viewRange;
    private final byte viewMarge;
    private final int backgroundColor;


    public ViewDisplay(String textFormat, Range scale, byte removeCount, boolean onlyPlayer, Replacement replacement,boolean shadow, double viewRange, byte viewMarge, int backgroundColor) {
        this.textFormat = textFormat;
        this.scale = scale;
        this.removeCount = removeCount;
        this.onlyPlayer = onlyPlayer;
        this.replacement = replacement;
        this.shadow = shadow;
        this.viewRange = viewRange;
        this.viewMarge = viewMarge;
        this.backgroundColor = backgroundColor;

    }

    public String getTextFormat() {
        return textFormat;
    }

    public double getScale() {
        return scale.getRandom();
    }

    public byte getRemoveCount() {
        return removeCount;
    }

    public boolean isOnlyPlayer() {
        return onlyPlayer;
    }

    public Replacement getReplacement() {
        return replacement;
    }

    public boolean isShadow() {
        return shadow;
    }

    public double getViewRange() {
        return viewRange;
    }

    public byte getViewMarge() {
        return viewMarge;
    }

    public int getBackgroundColor() {
        return backgroundColor;
    }


}
