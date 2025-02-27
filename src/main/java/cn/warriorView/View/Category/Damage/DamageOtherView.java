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

public class DamageOtherView implements IDamageDisplay {

    final boolean onlyPlayer;
    final Position position;
    final TextFormat textFormat;
    final IScale scale;
    final byte viewMarge;
    final List<IAnimation> animations;
    final int length;
    final Offset offset;
    final List<EntityData> basicSpawnData;

    public DamageOtherView(ViewParams params) {
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
        spawn((EntityDamageByEntityEvent) e, damage);
    }

    @Override
    public void spawn(EntityDamageByEntityEvent event, double damage) {
        LivingEntity attacker = (LivingEntity) event.getDamager();
        Player player = null;
        if (attacker instanceof Player p) {
            player = p;
        } else {
            if (this.onlyPlayer) return;
        }
        LivingEntity entity = (LivingEntity) event.getEntity();
        Location damageLocation;
        switch (this.position) {
            case DAMAGE:
                ViewUtil.spawnDisplay(animation(), viewMarge, textFormat, scale, entity, attacker, player, damage, offset, new ArrayList<>(basicSpawnData));
                return;
            case EYE:
                damageLocation = entity.getEyeLocation();
                break;
            default:
                damageLocation = entity.getLocation();
                break;
        }
        ViewUtil.spawnDisplay(animation(), viewMarge, textFormat, scale, damageLocation, player, damage, offset, new ArrayList<>(basicSpawnData));

    }

    @Override
    public IAnimation animation() {
        return animations.get(ThreadLocalRandom.current().nextInt(length));
    }

    public enum Position {
        EYE,
        FOOT,
        DAMAGE
    }

}
