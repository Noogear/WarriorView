package cn.warriorView.View.Category;

import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;

public interface IDamageDisplay {

    void spawn(EntityDamageEvent e, double damage);

    void spawn(EntityDamageByEntityEvent e, double damage);

}
