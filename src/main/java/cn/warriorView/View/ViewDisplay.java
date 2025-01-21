package cn.warriorView.View;

import cn.warriorView.Object.Range;
import cn.warriorView.Object.Replacement;
import cn.warriorView.View.Animation.Animation;

public abstract class ViewDisplay {

    private final String textFormat;
    private final Range scale;
    private final Animation animation;
    private final Replacement replacement;
    private final boolean shadow;
    private final float viewRange;
    private final byte viewMarge;
    private final int backgroundColor;
    private final boolean seeThrough;


    public ViewDisplay(String textFormat, Range scale, Animation animation, Replacement replacement, boolean shadow, float viewRange, byte viewMarge, int backgroundColor, boolean seeThrough) {
        this.textFormat = textFormat;
        this.scale = scale;
        this.animation = animation;
        this.replacement = replacement;
        this.shadow = shadow;
        this.viewRange = viewRange;
        this.viewMarge = viewMarge;
        this.backgroundColor = backgroundColor;
        this.seeThrough = seeThrough;

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
