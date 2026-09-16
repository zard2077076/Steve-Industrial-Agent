package dev.stevecreate.agent.forge1201.entity;

import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * A passive, server-driven construction worker with a player-shaped client renderer.
 *
 * <p>The entity owns no goals, targeting or task authority. The bounded executor remains the
 * only source of movement and work decisions; this type provides persistent identity, visible
 * equipment and smoothly interpolated movement for that already-authorized path.</p>
 */
public final class ConstructionBotEntity extends PathfinderMob {
    public static final double MOVEMENT_PER_TICK = 0.20D;
    private static final String ROLE_TAG = "SteveIndustrialRole";
    private static final EntityDataAccessor<Integer> ROLE =
            SynchedEntityData.defineId(ConstructionBotEntity.class, EntityDataSerializers.INT);

    public ConstructionBotEntity(
            EntityType<? extends ConstructionBotEntity> entityType,
            Level level) {
        super(entityType, level);
        setNoAi(true);
        setNoGravity(true);
        setInvulnerable(true);
        setPersistenceRequired();
        setCanPickUpLoot(false);
    }

    @Override
    protected void registerGoals() {
        // Deliberately empty: only the bounded server executor may move or operate this entity.
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        entityData.define(ROLE, Role.LOGISTICS.serializedId());
    }

    public Role role() {
        return Role.fromSerializedId(entityData.get(ROLE));
    }

    public void setRole(Role role) {
        entityData.set(ROLE, Objects.requireNonNull(role, "role").serializedId());
    }

    /**
     * Advances at most one fifth of a block toward the center of one prevalidated adjacent cell.
     *
     * <p>The old implementation measured only horizontal distance and snapped the Y
     * coordinate to the destination on the first tick.  That made a legal stair path
     * look like a teleport and, more importantly, allowed a stale path to skip the
     * physical step.  The path planner now admits only one-block stair transitions and
     * this method follows the full three-dimensional segment between those nodes.</p>
     *
     * @return {@code true} only after the exact destination center has been reached
     */
    public boolean advanceToward(BlockPos destination) {
        Objects.requireNonNull(destination, "destination");
        Vec3 target = Vec3.atBottomCenterOf(destination);
        Vec3 remaining = target.subtract(position());
        double distance = remaining.length();
        if (distance <= MOVEMENT_PER_TICK) {
            setPos(target);
            setDeltaMovement(Vec3.ZERO);
            return true;
        }
        double scale = MOVEMENT_PER_TICK / distance;
        double stepX = remaining.x * scale;
        double stepY = remaining.y * scale;
        double stepZ = remaining.z * scale;
        face(stepX, stepZ);
        setPos(getX() + stepX, getY() + stepY, getZ() + stepZ);
        setDeltaMovement(Vec3.ZERO);
        return false;
    }

    public void face(BlockPos target) {
        Objects.requireNonNull(target, "target");
        face(target.getX() + 0.5D - getX(), target.getZ() + 0.5D - getZ());
    }

    private void face(double deltaX, double deltaZ) {
        if (Math.abs(deltaX) < 1.0E-7D && Math.abs(deltaZ) < 1.0E-7D) {
            return;
        }
        float yaw = (float) (Math.toDegrees(Math.atan2(deltaZ, deltaX)) - 90.0D);
        setYRot(yaw);
        setYHeadRot(yaw);
        setYBodyRot(yaw);
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt(ROLE_TAG, role().serializedId());
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        setRole(Role.fromSerializedId(tag.getInt(ROLE_TAG)));
        setNoAi(true);
        setNoGravity(true);
        setInvulnerable(true);
        setPersistenceRequired();
    }

    public enum Role {
        LOGISTICS(0, "logistics"),
        BUILDER_INSPECTOR(1, "builder_inspector");

        private final int serializedId;
        private final String serializedName;

        Role(int serializedId, String serializedName) {
            this.serializedId = serializedId;
            this.serializedName = serializedName;
        }

        public int serializedId() {
            return serializedId;
        }

        public String serializedName() {
            return serializedName;
        }

        public static Role fromSerializedId(int value) {
            return value == BUILDER_INSPECTOR.serializedId
                    ? BUILDER_INSPECTOR : LOGISTICS;
        }
    }
}
