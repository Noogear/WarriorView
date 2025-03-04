package cn.warriorView.view.category.damage;

import cn.warriorView.object.Offset;
import cn.warriorView.object.animation.IAnimation;
import cn.warriorView.object.format.TextFormat;
import cn.warriorView.object.scale.IScale;
import cn.warriorView.util.ViewUtil;
import cn.warriorView.view.ViewParams;
import cn.warriorView.view.category.IDamageDisplay;
import cn.warriorView.view.meta.MetaFactory;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public class DamageView implements IDamageDisplay {

    final TextFormat textFormat;
    final IScale scale;
    final byte viewMarge;
    final boolean onlyPlayer;
    final List<IAnimation> animations;
    final int length;
    final Position position;
    final Offset offset;
    final List<EntityData> basicSpawnData;

    public DamageView(ViewParams params) {
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

    @Override
    public void spawn(EntityDamageEvent e, double damage) {
        LivingEntity entity = (LivingEntity) e.getEntity();
        Player player = null;
        if (entity instanceof Player p) {
            player = p;
        } else {
            if (this.onlyPlayer) return;
        }
        ViewUtil.spawnDisplay(animation(), viewMarge, textFormat, scale, this.position.getLocation(entity), player, damage, offset, new ArrayList<>(basicSpawnData));
    }

    @Override
    public void spawn(EntityDamageByEntityEvent e, double damage) {
        spawn((EntityDamageEvent) e, damage);
    }

    @Override
    public IAnimation animation() {
        return animations.get(ThreadLocalRandom.current().nextInt(length));
    }

    public enum Position {
        EYE {
            @Override
            public Location getLocation(LivingEntity entity) {
                return entity.getEyeLocation();
            }
        },
        FOOT {
            @Override
            public Location getLocation(LivingEntity entity) {
                return entity.getLocation();
            }
        };

        public abstract Location getLocation(LivingEntity entity);
    }
}
