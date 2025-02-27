package cn.warriorView.view.category.regain;

import cn.warriorView.object.Offset;
import cn.warriorView.object.animation.IAnimation;
import cn.warriorView.object.format.TextFormat;
import cn.warriorView.object.scale.IScale;
import cn.warriorView.util.ViewUtil;
import cn.warriorView.view.ViewParams;
import cn.warriorView.view.meta.MetaFactory;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityRegainHealthEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public class RegainView {
    final boolean onlyPlayer;
    final Position position;
    final int length;
    private final TextFormat textFormat;
    private final IScale scale;
    private final byte viewMarge;
    private final List<IAnimation> animations;
    private final Offset offset;
    private final List<EntityData> basicSpawnData;

    public RegainView(ViewParams params) {
        this.textFormat = params.textFormat();
        this.scale = params.scale();
        this.viewMarge = params.viewMarge();
        this.onlyPlayer = params.onlyPlayer();
        this.animations = params.animations();
        this.length = animations.size();
        this.position = Position.valueOf(params.position().toUpperCase());
        this.offset = params.offset();
        this.basicSpawnData = MetaFactory.basicCreate(params);
    }

    public void spawn(EntityRegainHealthEvent event) {
        double regain = event.getAmount();
        if (regain <= 0.01) return;
        LivingEntity entity = (LivingEntity) event.getEntity();
        Player player = null;
        if (entity instanceof Player p) {
            player = p;
        } else {
            if (this.onlyPlayer) return;
        }
        Location regainLocation = (this.position == Position.EYE) ? entity.getEyeLocation() : entity.getLocation();
        ViewUtil.spawnDisplay(animation(), viewMarge, textFormat, scale, regainLocation, player, regain, offset, new ArrayList<>(basicSpawnData));

    }

    private IAnimation animation() {
        return animations.get(ThreadLocalRandom.current().nextInt(length));
    }

    public enum Position {
        EYE,
        FOOT
    }
}
