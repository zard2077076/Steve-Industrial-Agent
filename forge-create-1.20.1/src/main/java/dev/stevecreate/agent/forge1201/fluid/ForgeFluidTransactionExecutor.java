package dev.stevecreate.agent.forge1201.fluid;

import dev.stevecreate.agent.core.fluid.FluidIdentity;
import dev.stevecreate.agent.core.fluid.FluidTransactionState;
import dev.stevecreate.agent.core.fluid.ProjectFluidLedger;
import dev.stevecreate.agent.core.model.ResourceId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.registries.ForgeRegistries;

/** Exact simulate-first drain/fill transaction over two explicitly authorized Forge endpoints. */
public final class ForgeFluidTransactionExecutor {
    private ForgeFluidTransactionExecutor() {}

    public static Result transfer(
            ServerLevel level,
            BlockPos sourcePosition,
            Direction sourceFace,
            BlockPos destinationPosition,
            Direction destinationFace,
            ProjectFluidLedger ledger,
            ResourceId transactionId,
            ResourceId projectId,
            ResourceId taskId,
            ResourceId sourceEndpointId,
            FluidStack requested,
            long tick) {
        Objects.requireNonNull(level, "level");
        if (!level.getServer().isSameThread()) return Result.refused("WRONG_THREAD");
        if (sourcePosition.equals(destinationPosition)) return Result.refused("ENDPOINTS_IDENTICAL");
        if (!level.hasChunkAt(sourcePosition) || !level.hasChunkAt(destinationPosition)) {
            return Result.refused("CHUNK_NOT_LOADED");
        }
        BlockEntity sourceEntity = level.getBlockEntity(sourcePosition);
        BlockEntity destinationEntity = level.getBlockEntity(destinationPosition);
        if (sourceEntity == null || destinationEntity == null) {
            return Result.refused("FLUID_ENDPOINT_MISSING");
        }
        var source = sourceEntity.getCapability(ForgeCapabilities.FLUID_HANDLER, sourceFace).resolve();
        var destination = destinationEntity.getCapability(
                ForgeCapabilities.FLUID_HANDLER, destinationFace).resolve();
        if (source.isEmpty() || destination.isEmpty()) {
            return Result.refused("FLUID_CAPABILITY_MISSING");
        }
        return transfer(source.get(), destination.get(), ledger, transactionId, projectId,
                taskId, sourceEndpointId, requested, tick);
    }

    public static Result transfer(
            IFluidHandler source,
            IFluidHandler destination,
            ProjectFluidLedger ledger,
            ResourceId transactionId,
            ResourceId projectId,
            ResourceId taskId,
            ResourceId sourceEndpointId,
            FluidStack requested,
            long tick) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(ledger, "ledger");
        Objects.requireNonNull(requested, "requested");
        if (requested.isEmpty() || requested.getAmount() < 1) {
            return Result.refused("FLUID_REQUEST_EMPTY");
        }
        FluidPacket packet = new FluidPacket(identity(requested), requested.getAmount());
        return transfer(new HandlerEndpoint(source, requested),
                new HandlerEndpoint(destination, requested), ledger, transactionId, projectId,
                taskId, sourceEndpointId, packet, tick);
    }

    static Result transfer(
            Endpoint source,
            Endpoint destination,
            ProjectFluidLedger ledger,
            ResourceId transactionId,
            ResourceId projectId,
            ResourceId taskId,
            ResourceId sourceEndpointId,
            FluidPacket requested,
            long tick) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(ledger, "ledger");
        Objects.requireNonNull(requested, "requested");
        var transaction = ledger.prepare(transactionId, projectId, taskId, sourceEndpointId,
                requested.identity(), requested.amountMb(), tick);
        if (transaction.state() != FluidTransactionState.PREPARED) {
            return new Result(false, "TRANSACTION_ALREADY_ADVANCED", transaction.state(),
                    0, ledger.balance(projectId).balanced());
        }
        FluidPacket simulatedDrain = source.drain(requested, true);
        if (!simulatedDrain.equals(requested)) {
            ledger.advance(transactionId, FluidTransactionState.RELEASED, tick);
            return result(ledger, projectId, "SOURCE_FLUID_CHANGED", 0, transactionId);
        }
        int simulatedFill = destination.fill(requested, true);
        if (simulatedFill != requested.amountMb()) {
            ledger.advance(transactionId, FluidTransactionState.RELEASED, tick);
            return result(ledger, projectId, "DESTINATION_CAPACITY_INSUFFICIENT", 0,
                    transactionId);
        }
        FluidPacket withdrawn = source.drain(requested, false);
        if (!withdrawn.equals(requested)) {
            if (withdrawn.amountMb() > 0) source.fill(withdrawn, false);
            ledger.advance(transactionId, FluidTransactionState.RELEASED, tick);
            return result(ledger, projectId, "WITHDRAWAL_DIVERGED", 0, transactionId);
        }
        ledger.advance(transactionId, FluidTransactionState.WITHDRAWN, tick);
        int delivered = destination.fill(withdrawn, false);
        if (delivered != withdrawn.amountMb()) {
            FluidPacket reclaimed = delivered == 0
                    ? new FluidPacket(withdrawn.identity(), 0)
                    : destination.drain(new FluidPacket(withdrawn.identity(), delivered), false);
            FluidPacket returnPacket = new FluidPacket(withdrawn.identity(),
                    withdrawn.amountMb() - delivered + reclaimed.amountMb());
            int returned = source.fill(returnPacket, false);
            ledger.advance(transactionId, FluidTransactionState.RETURN_PENDING, tick);
            if (returned == withdrawn.amountMb()) {
                ledger.advance(transactionId, FluidTransactionState.RETURNED, tick);
                return result(ledger, projectId, "DELIVERY_DIVERGED_RETURNED", 0,
                        transactionId);
            }
            return result(ledger, projectId, "FLUID_RETURN_PENDING", delivered, transactionId);
        }
        ledger.advance(transactionId, FluidTransactionState.DELIVERED, tick);
        return result(ledger, projectId, "DELIVERED", delivered, transactionId);
    }

    public static FluidIdentity identity(FluidStack stack) {
        ResourceLocation id = ForgeRegistries.FLUIDS.getKey(stack.getFluid());
        if (id == null || stack.isEmpty()) throw new IllegalArgumentException("fluid is unregistered");
        String components = stack.hasTag() ? stack.getTag().toString() : "";
        return new FluidIdentity(ResourceId.parse(id.toString()), sha256(components));
    }

    interface Endpoint {
        FluidPacket drain(FluidPacket requested, boolean simulate);
        int fill(FluidPacket offered, boolean simulate);
    }

    record FluidPacket(FluidIdentity identity, int amountMb) {
        FluidPacket {
            Objects.requireNonNull(identity, "identity");
            if (amountMb < 0) throw new IllegalArgumentException("negative fluid packet");
        }
    }

    private static final class HandlerEndpoint implements Endpoint {
        private final IFluidHandler handler;
        private final FluidStack template;

        private HandlerEndpoint(IFluidHandler handler, FluidStack template) {
            this.handler = handler;
            this.template = template.copy();
        }

        @Override
        public FluidPacket drain(FluidPacket requested, boolean simulate) {
            FluidStack query = template.copy();
            query.setAmount(requested.amountMb());
            FluidStack drained = handler.drain(query, simulate
                    ? IFluidHandler.FluidAction.SIMULATE : IFluidHandler.FluidAction.EXECUTE);
            if (drained.isEmpty()) return new FluidPacket(requested.identity(), 0);
            FluidIdentity actual = identity(drained);
            return new FluidPacket(actual, drained.getAmount());
        }

        @Override
        public int fill(FluidPacket offered, boolean simulate) {
            if (offered.amountMb() == 0) return 0;
            if (!offered.identity().equals(identity(template))) return 0;
            FluidStack stack = template.copy();
            stack.setAmount(offered.amountMb());
            return handler.fill(stack, simulate
                    ? IFluidHandler.FluidAction.SIMULATE : IFluidHandler.FluidAction.EXECUTE);
        }
    }

    private static Result result(
            ProjectFluidLedger ledger,
            ResourceId projectId,
            String code,
            int amount,
            ResourceId transactionId) {
        return new Result("DELIVERED".equals(code), code,
                ledger.transactions().get(transactionId).state(), amount,
                ledger.balance(projectId).balanced());
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    public record Result(
            boolean success,
            String code,
            FluidTransactionState state,
            int deliveredMb,
            boolean ledgerBalanced) {
        public Result {
            Objects.requireNonNull(code, "code");
            if (deliveredMb < 0) throw new IllegalArgumentException("negative fluid delivery");
        }
        private static Result refused(String code) {
            return new Result(false, code, null, 0, true);
        }
    }
}
