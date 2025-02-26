package cn.warriorView.View.Category;

import cn.warriorView.Object.Animation.IAnimation;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;

public interface IDamageDisplay {

    void spawn(EntityDamageEvent e, double damage);

    void spawn(EntityDamageByEntityEvent e, double damage);

    IAnimation animation();

}
