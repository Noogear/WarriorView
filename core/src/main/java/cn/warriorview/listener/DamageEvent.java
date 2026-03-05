package cn.warriorview.listener;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;

import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import com.github.retrooper.packetevents.util.Vector3d;

import cn.warriorview.util.LocationUtil;
import io.netty.util.internal.shaded.org.jctools.queues.MpscUnboundedArrayQueue;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class DamageEvent implements Listener {

    private final MpscUnboundedArrayQueue<DamageIndicator> eventQueue = new MpscUnboundedArrayQueue<>(2048);
    private final MpscUnboundedArrayQueue<Player> quitQueue = new MpscUnboundedArrayQueue<>(128);

    // ==========================================
    // 2. 异步引擎私有状态 (绝对单线程运行，0锁，0-GC)
    // ==========================================
    private final Int2ObjectOpenHashMap<ActiveIndicator> tickAccumulator = new Int2ObjectOpenHashMap<>();
    private final ObjectArrayList<ActiveIndicator> activeIndicators = new ObjectArrayList<>();
    // 核心：玩家私有信箱矩阵 (享元模式的终点)
    private final IdentityHashMap<Player, ObjectArrayList<Object>> playerMailboxes = new IdentityHashMap<>();
    private final double MAX_DISTANCE_SQ = 24 * 24;

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        Entity victim = e.getEntity();
        Entity attacker = e.getDamager();
        Set<Player> trackers = victim.getTrackedBy();
        if (trackers.isEmpty())
            return;
        Player[] preciseViewers = new Player[trackers.size()];
        int validCount = 0;
        double vX = victim.getX();
        double vY = victim.getY();
        double vZ = victim.getZ();

        for (Player p : trackers) {
            double dX = vX - p.getX();
            double dY = vY - p.getY();
            double dZ = vZ - p.getZ();
            double distanceSq = dX * dX + dY * dY + dZ * dZ;
            if (distanceSq <= MAX_DISTANCE_SQ) {
                preciseViewers[validCount++] = p;
            }
        }
        if (validCount == 0)
            return;

        double vW = victim.getWidth();
        double vH = victim.getHeight();
        boolean isLiving = attacker instanceof LivingEntity;
        double eyeX = attacker.getX();
        double eyeY = attacker.getY() + (isLiving ? ((LivingEntity) attacker).getEyeHeight() : 0);
        double eyeZ = attacker.getZ();
        float attackerYaw = attacker.getYaw();
        float attackerPitch = attacker.getPitch();
        eventQueue.relaxedOffer(new DamageIndicator(
                e.getFinalDamage(),
                isLiving,
                eyeX, eyeY, eyeZ,
                attackerYaw,
                attackerPitch,
                victim.getEntityId(),
                vX, vY, vZ,
                vW, vH,
                victim.getWorld().getUID(), preciseViewers, validCount));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        quitQueue.relaxedOffer(e.getPlayer());
    }

    private record DamageIndicator(
            double finalDamage,
            boolean isLivingAttacker,
            double aX, double aY, double aZ,
            float pitch, float yaw,
            int vId,
            double vX, double vY, double vZ,
            double vW, double vH,
            UUID worldUid,
            Player[] viewers, int viewerCount) {
    }

    private void engineTick() {
        // --- 阶段 0：生命周期与防泄漏深度清理 ---
        Player quitter;
        while ((quitter = quitQueue.poll()) != null) {
            playerMailboxes.remove(quitter); // 彻底释放登出玩家的信箱
        }

        // 🌟 0-GC 魔法：只清空信箱内部游标，保留 ObjectArrayList 内存空间！
        for (ObjectArrayList<Object> mailbox : playerMailboxes.values()) {
            mailbox.clear();
        }

        // --- 阶段 1：汲取伤害数据，本地无锁聚合 ---
        tickAccumulator.clear();
        DamageIndicator event;
        while ((event = eventQueue.poll()) != null) {
            ActiveIndicator indicator = tickAccumulator.get(event.vId);
            if (indicator == null) {
                // 0-GC 计算完美的防穿模坐标
                Vector3d startLoc = LocationUtil.getClampedHitLocation(
                        event.aX, event.aY, event.aZ,
                        event.vX, event.vY, event.vZ, event.vW, event.vH);

                indicator = new ActiveIndicator(
                        EntityIdGenerator.next(), event.finalDamage,
                        startLoc.x, startLoc.y, startLoc.z, event.worldUid, event.viewers, event.viewerCount);
                tickAccumulator.put(event.vId, indicator);
                activeIndicators.add(indicator);
            } else {
                indicator.damage += event.finalDamage; // 同 Tick 多次伤害极速累加
            }
        }

        // --- 阶段 2：享元计算与矩阵投递 (TextDisplay 降维渲染) ---
        for (int i = activeIndicators.size() - 1; i >= 0; i--) {
            ActiveIndicator particle = activeIndicators.get(i);

            // 🎬 渲染卸载：利用客户端插值，服务器只在第 0 帧和第 20 帧发包！
            ObjectArrayList<Object> framePackets = calculateFlyweightPackets(particle);

            if (particle.age >= 20) {
                activeIndicators.remove(i); // 寿命终结，安全移除
            } else {
                particle.age++;
            }

            // 🎯 矩阵投递：如果当前帧有包产生，则分发内存指针
            if (!framePackets.isEmpty()) {
                for (Player viewer : particle.viewers) {
                    // 🌟 异步安全校验：只调用线程安全的 isOnline()。
                    // 绝不调用 viewer.getLocation()，交由客户端自己的视距剔除机制来处理过远的包。
                    if (viewer.isOnline()) {
                        ObjectArrayList<Object> mailbox = playerMailboxes.get(viewer);
                        if (mailbox == null) {
                            mailbox = new ObjectArrayList<>();
                            playerMailboxes.put(viewer, mailbox);
                        }
                        mailbox.addAll(framePackets); // 仅仅传递内存指针，0 复制！
                    }
                }
            }
        }

        // --- 阶段 3：终极私有 Bundle 发送 ---
        for (Map.Entry<Player, ObjectArrayList<Object>> entry : playerMailboxes.entrySet()) {
            ObjectArrayList<Object> packets = entry.getValue();
            if (!packets.isEmpty()) {
                // 压缩打包：把该玩家眼前所有的生、老、病、死打包成一个超级 Bundle 发送
                sendPacket(entry.getKey(), new ClientboundBundlePacket(packets));
            }
        }
    }

    private static class ActiveIndicator {
        final int virtualId;
        double damage;
        final double startX, startY, startZ;
        final UUID worldUid;
        final Player[] viewers;
        final int viewerCount;
        int age = 0;

        ActiveIndicator(int virtualId, double damage, double x, double y, double z, UUID worldUid, Player[] viewers,
                int viewerCount) {
            this.virtualId = virtualId;
            this.damage = damage;
            this.startX = x;
            this.startY = y;
            this.startZ = z;
            this.worldUid = worldUid;
            this.viewers = viewers;
            this.viewerCount = viewerCount;
        }
    }
}
