package cn.warriorView.view.category;

import cn.warriorView.object.animation.IAnimation;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;

public interface IDamageDisplay {

    void spawn(EntityDamageEvent e, double damage);

    void spawn(EntityDamageByEntityEvent e, double damage);

    IAnimation animation();

}
