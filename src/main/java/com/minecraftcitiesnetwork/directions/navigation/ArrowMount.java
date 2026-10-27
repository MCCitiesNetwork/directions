package com.minecraftcitiesnetwork.directions.navigation;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityPositionSync;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRelativeMove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRelativeMoveAndRotation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRotation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityVelocity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;

/**
 * Makes a player's arrow ride them, as far as that player's own client knows.
 *
 * <p>The client draws a passenger at its own predicted position every frame, so a riding arrow
 * keeps up with sprinting, jumping and stopping without any lag. Only the owner's client is told:
 * on the server the arrow parts stay ordinary entities, because a server-side passenger would stop
 * the player from being teleported to another world.
 *
 * <p>Packet callbacks run on network threads, so all state here is concurrent.
 */
final class ArrowMount implements PacketListener {
    /** Arrow entity IDs, by the UUID of the player they ride. */
    private final Map<UUID, int[]> arrowsByOwner = new ConcurrentHashMap<>();
    /** The owner's real passengers, as the server last reported them, so ours are added and never replace them. */
    private final Map<UUID, int[]> realPassengersByOwner = new ConcurrentHashMap<>();
    private PacketListenerCommon registration;

    void register() {
        registration = PacketEvents.getAPI().getEventManager().registerListener(this, PacketListenerPriority.NORMAL);
    }

    void unregister() {
        if (registration != null) {
            PacketEvents.getAPI().getEventManager().unregisterListener(registration);
            registration = null;
        }
        arrowsByOwner.clear();
        realPassengersByOwner.clear();
    }

    /**
     * Records {@code arrow} as riding {@code owner}. Call before the owner's client is sent the entity:
     * the owner's client is told it rides as soon as the entity reaches them.
     */
    void add(Player owner, Entity arrow) {
        realPassengersByOwner.put(owner.getUniqueId(), owner.getPassengers().stream().mapToInt(Entity::getEntityId).toArray());
        arrowsByOwner.merge(owner.getUniqueId(), new int[]{arrow.getEntityId()},
                (existing, added) -> IntStream.concat(Arrays.stream(existing), Arrays.stream(added)).toArray());
    }

    /** Drops the record of {@code arrow}, for when it is removed. */
    void remove(UUID ownerId, Entity arrow) {
        arrowsByOwner.computeIfPresent(ownerId, (id, existing) -> {
            int[] kept = Arrays.stream(existing).filter(entityId -> entityId != arrow.getEntityId()).toArray();
            return kept.length == 0 ? null : kept;
        });
        if (!arrowsByOwner.containsKey(ownerId)) {
            realPassengersByOwner.remove(ownerId);
        }
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        User user = event.getUser();
        if (user == null || user.getUUID() == null) {
            return;
        }
        int[] arrows = arrowsByOwner.get(user.getUUID());
        if (arrows == null) {
            return;
        }
        int moved = movedEntityId(event);
        if (moved != -1) {
            // The client applies a rider's position packets mid-frame before putting it back on its
            // vehicle, which would flash the arrow at its server position for a moment.
            if (contains(arrows, moved)) {
                event.setCancelled(true);
            }
        } else if (event.getPacketType() == PacketType.Play.Server.SPAWN_ENTITY) {
            int spawned = new WrapperPlayServerSpawnEntity(event).getEntityId();
            if (contains(arrows, spawned)) {
                // The client must know the entity before it can be told the entity rides.
                event.getTasksAfterSend().add(() -> sendPassengers(user, user.getEntityId()));
            }
        } else if (event.getPacketType() == PacketType.Play.Server.SET_PASSENGERS) {
            WrapperPlayServerSetPassengers packet = new WrapperPlayServerSetPassengers(event);
            if (packet.getEntityId() != user.getEntityId()) {
                return;
            }
            int[] real = Arrays.stream(packet.getPassengers()).filter(id -> !contains(arrows, id)).toArray();
            realPassengersByOwner.put(user.getUUID(), real);
            packet.setPassengers(withArrows(real, arrows));
            event.markForReEncode(true);
        }
    }

    /** The entity a movement packet moves, or -1 for any other packet. */
    private static int movedEntityId(PacketSendEvent event) {
        PacketTypeCommon type = event.getPacketType();
        if (type == PacketType.Play.Server.ENTITY_TELEPORT) {
            return new WrapperPlayServerEntityTeleport(event).getEntityId();
        } else if (type == PacketType.Play.Server.ENTITY_POSITION_SYNC) {
            return new WrapperPlayServerEntityPositionSync(event).getId();
        } else if (type == PacketType.Play.Server.ENTITY_RELATIVE_MOVE) {
            return new WrapperPlayServerEntityRelativeMove(event).getEntityId();
        } else if (type == PacketType.Play.Server.ENTITY_RELATIVE_MOVE_AND_ROTATION) {
            return new WrapperPlayServerEntityRelativeMoveAndRotation(event).getEntityId();
        } else if (type == PacketType.Play.Server.ENTITY_ROTATION) {
            return new WrapperPlayServerEntityRotation(event).getEntityId();
        } else if (type == PacketType.Play.Server.ENTITY_VELOCITY) {
            return new WrapperPlayServerEntityVelocity(event).getEntityId();
        }
        return -1;
    }

    private void sendPassengers(User user, int ownerEntityId) {
        int[] arrows = arrowsByOwner.get(user.getUUID());
        if (arrows == null) {
            return;
        }
        int[] real = realPassengersByOwner.getOrDefault(user.getUUID(), new int[0]);
        user.sendPacketSilently(new WrapperPlayServerSetPassengers(ownerEntityId, withArrows(real, arrows)));
    }

    private static int[] withArrows(int[] real, int[] arrows) {
        return IntStream.concat(Arrays.stream(real), Arrays.stream(arrows)).toArray();
    }

    private static boolean contains(int[] ids, int id) {
        for (int candidate : ids) {
            if (candidate == id) {
                return true;
            }
        }
        return false;
    }
}
