package cn.warriorView.View;

import cn.warriorView.Animation.Animation;
import cn.warriorView.Object.Scale;
import cn.warriorView.Object.Replacement;
import com.github.retrooper.packetevents.util.Vector3f;

public abstract class ViewDisplay {

    private final String textFormat;
    private final Replacement replacement;
    private final Scale scale;
    private final boolean shadow;
    private final float viewRange;
    private final byte viewMarge;
    private final int backgroundColor;
    private final boolean seeThrough;
    private final boolean onlyPlayer;
    private final Animation animation;

    protected ViewDisplay(ViewParams params) {
        this.textFormat = params.textFormat();
        this.replacement = params.replacement();
        this.scale = params.scale();
        this.shadow = params.shadow();
        this.viewRange = params.viewRange();
        this.viewMarge = params.viewMarge();
        this.backgroundColor = params.backgroundColor();
        this.seeThrough = params.seeThrough();
        this.onlyPlayer = params.onlyPlayer();
        this.animation = params.animation();
    }

    public boolean isOnlyPlayer() {
        return onlyPlayer;
    }

    public String getTextFormat() {
        return textFormat;
    }

    public Vector3f getScale() {
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
