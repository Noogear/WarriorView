package cn.strikeView.View;

import cn.strikeView.Util.EntityUtil;
import com.github.retrooper.packetevents.protocol.world.Location;
import org.bukkit.entity.Display;

public class ViewDisplay {
    
    private final Display.Billboard billboard;

    public ViewDisplay() {

        this.billboard = Display.Billboard.CENTER;

    }


    public void spawn(Location loc) {
        int entityId = EntityUtil.getAutoEntityId();




    }

}
