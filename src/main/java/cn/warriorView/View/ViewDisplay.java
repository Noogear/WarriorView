package cn.warriorView.View;

import cn.warriorView.Object.Range;
import cn.warriorView.Object.Replacement;
import cn.warriorView.Object.Animation.Animation;

public abstract class ViewDisplay {

    private final String textFormat;
    private final Replacement replacement;
    private final Range scale;
    private final boolean shadow;
    private final float viewRange;
    private final byte viewMarge;
    private final int backgroundColor;
    private final boolean seeThrough;
    private final boolean onlyPlayer;
    private final Animation animation;

    protected ViewDisplay(String textFormat, Replacement replacement, Range scale, boolean shadow, float viewRange, byte viewMarge, int backgroundColor, boolean seeThrough, boolean onlyPlayer, Animation animation) {
        this.textFormat = textFormat;
        this.replacement = replacement;
        this.scale = scale;
        this.shadow = shadow;
        this.viewRange = viewRange;
        this.viewMarge = viewMarge;
        this.backgroundColor = backgroundColor;
        this.seeThrough = seeThrough;
        this.onlyPlayer = onlyPlayer;
        this.animation = animation;
    }

    public boolean isOnlyPlayer() {
        return onlyPlayer;
    }


    public String getTextFormat() {
        return textFormat;
    }

    public float getScale() {
        return scale.getRandom();
    }

    public Animation getAnimation() {
        return animation;
    }

    public Replacement getReplacement() {
        return replacement;
    }

    public boolean isShadow() {
        return shadow;
    }

    public float getViewRange() {
        return viewRange;
    }

    public byte getViewMarge() {
        return viewMarge;
    }

    public int getBackgroundColor() {
        return backgroundColor;
    }

    public boolean isSeeThrough() {
        return seeThrough;
    }


}
