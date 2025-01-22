package cn.warriorView.Util;

import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;

public class RegistryUtil {
    public static EntityDamageEvent.DamageCause toDamageCause(String string) {
        try {
            return EntityDamageEvent.DamageCause.valueOf(string.toLowerCase());
        } catch (Exception e) {
            return null;
        }
    }

    public static EntityRegainHealthEvent.RegainReason toRegainReason(String string) {
        try {
            return EntityRegainHealthEvent.RegainReason.valueOf(string.toLowerCase());
        } catch (Exception e) {
            return null;
        }
    }

}
