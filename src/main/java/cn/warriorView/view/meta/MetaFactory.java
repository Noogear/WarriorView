package cn.warriorView.view.meta;

import cn.warriorView.view.ViewParams;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;

import java.util.ArrayList;
import java.util.List;

public class MetaFactory {

    public static List<EntityData> basicCreate(ViewParams params) {
        List<EntityData> data = new ArrayList<>();
        data.add(TextDisplayMeta.billboardConstraints(TextDisplayMeta.BillboardConstraints.CENTER));
        data.add(TextDisplayMeta.positionRotationInterpolationDuration(params.teleportDuration()));
        data.add(TextDisplayMeta.transformationInterpolationDuration(10));
        data.add(TextDisplayMeta.textOpacity(params.textOpacity()));
        data.add(TextDisplayMeta.viewRange(params.viewRange()));
        data.add(TextDisplayMeta.backgroundColor(params.backgroundColor()));
        data.add(TextDisplayMeta.maskBitGroup(params.shadow(), params.seeThrough(), false, false, false));
        return data;
    }
}
