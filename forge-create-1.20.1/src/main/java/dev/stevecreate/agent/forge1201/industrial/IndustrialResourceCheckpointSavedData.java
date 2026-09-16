package dev.stevecreate.agent.forge1201.industrial;

import dev.stevecreate.agent.core.electrical.EnergyTransaction;
import dev.stevecreate.agent.core.electrical.EnergyTransactionState;
import dev.stevecreate.agent.core.electrical.ProjectEnergyLedgerSnapshot;
import dev.stevecreate.agent.core.fluid.FluidIdentity;
import dev.stevecreate.agent.core.fluid.FluidTransaction;
import dev.stevecreate.agent.core.fluid.FluidTransactionState;
import dev.stevecreate.agent.core.fluid.ProjectFluidLedgerSnapshot;
import dev.stevecreate.agent.core.industrial.EntityLogisticsSettlementV1;
import dev.stevecreate.agent.core.industrial.IndustrialResourceBindingV1;
import dev.stevecreate.agent.core.industrial.IndustrialResourceCheckpointV1;
import dev.stevecreate.agent.core.model.ResourceId;
import dev.stevecreate.agent.core.resource.GenericResourceType;
import dev.stevecreate.agent.core.warehouse.WarehouseAllocation;
import dev.stevecreate.agent.core.warehouse.WarehouseReservationRequest;
import dev.stevecreate.agent.core.warehouse.WarehouseReservationSnapshot;
import dev.stevecreate.agent.core.warehouse.WarehouseReservationStatus;
import dev.stevecreate.agent.core.warehouse.WarehouseResourceKey;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/** Durable IPO-03 selectors and ledgers; no live capability or runtime reference is persisted. */
public final class IndustrialResourceCheckpointSavedData extends SavedData {
    public static final String DATA_NAME = "steve_industrial_resource_checkpoints";
    public static final int SCHEMA = 2;
    private final Map<ResourceId, IndustrialResourceCheckpointV1> checkpoints =
            new LinkedHashMap<>();
    private final Map<ResourceId, IndustrialResourceBindingV1> bindings =
            new LinkedHashMap<>();

    public static IndustrialResourceCheckpointSavedData forLevel(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(
                IndustrialResourceCheckpointSavedData::load,
                IndustrialResourceCheckpointSavedData::new, DATA_NAME);
    }

    public static IndustrialResourceCheckpointSavedData load(CompoundTag root) {
        int schema = root.getInt("Schema");
        if (schema < 0 || schema > SCHEMA) {
            throw new IllegalStateException("Unsupported industrial resource schema " + schema);
        }
        IndustrialResourceCheckpointSavedData data = new IndustrialResourceCheckpointSavedData();
        for (Tag raw : root.getList("Checkpoints", Tag.TAG_COMPOUND)) {
            try {
                IndustrialResourceCheckpointV1 checkpoint = decode((CompoundTag) raw);
                data.checkpoints.put(checkpoint.projectId(), checkpoint);
            } catch (IllegalArgumentException | IllegalStateException ignored) {
                // Malformed resource state never recreates withdrawal or network authority.
            }
        }
        List<ResourceId> persistedBindingProjects = new ArrayList<>();
        for (Tag raw : root.getList("Bindings", Tag.TAG_COMPOUND)) {
            CompoundTag row = (CompoundTag) raw;
            try {
                ResourceId persistedProject = ResourceId.parse(row.getString("Project"));
                persistedBindingProjects.add(persistedProject);
                IndustrialResourceBindingV1 binding = IndustrialResourceBindingNbt.decode(row);
                data.bindings.put(binding.projectId(), binding);
            } catch (IllegalArgumentException | IllegalStateException ignored) {
                // A malformed immutable selector never becomes reload authority.
            }
        }
        for (ResourceId project : persistedBindingProjects) {
            IndustrialResourceBindingV1 binding = data.bindings.get(project);
            IndustrialResourceCheckpointV1 checkpoint = data.checkpoints.get(project);
            if (binding == null || checkpoint == null
                    || !binding.fingerprint().equals(checkpoint.bindingFingerprint())) {
                data.bindings.remove(project);
                data.checkpoints.remove(project);
            }
        }
        return data;
    }

    public Optional<IndustrialResourceCheckpointV1> checkpoint(ResourceId projectId) {
        return Optional.ofNullable(checkpoints.get(Objects.requireNonNull(projectId, "projectId")));
    }

    public Map<ResourceId, IndustrialResourceCheckpointV1> checkpoints() {
        return Map.copyOf(checkpoints);
    }

    public Optional<IndustrialResourceBindingV1> binding(ResourceId projectId) {
        return Optional.ofNullable(bindings.get(Objects.requireNonNull(projectId, "projectId")));
    }

    /** Persists immutable selectors and transactional state atomically. */
    public void put(
            IndustrialResourceBindingV1 binding,
            IndustrialResourceCheckpointV1 checkpoint) {
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(checkpoint, "checkpoint");
        if (!binding.projectId().equals(checkpoint.projectId())
                || !binding.fingerprint().equals(checkpoint.bindingFingerprint())) {
            throw new IllegalArgumentException("resource binding differs from checkpoint");
        }
        bindings.put(binding.projectId(), binding);
        checkpoints.put(checkpoint.projectId(), checkpoint);
        setDirty();
    }

    public void put(IndustrialResourceCheckpointV1 checkpoint) {
        checkpoints.put(Objects.requireNonNull(checkpoint, "checkpoint").projectId(), checkpoint);
        setDirty();
    }

    public void remove(ResourceId projectId) {
        Objects.requireNonNull(projectId, "projectId");
        boolean checkpointRemoved = checkpoints.remove(projectId) != null;
        boolean bindingRemoved = bindings.remove(projectId) != null;
        if (checkpointRemoved || bindingRemoved) setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag root) {
        root.putInt("Schema", SCHEMA);
        ListTag rows = new ListTag();
        checkpoints.values().stream().sorted(Comparator.comparing(
                        value -> value.projectId().toString()))
                .map(IndustrialResourceCheckpointSavedData::encode).forEach(rows::add);
        root.put("Checkpoints", rows);
        ListTag bindingRows = new ListTag();
        bindings.values().stream().sorted(Comparator.comparing(
                        value -> value.projectId().toString()))
                .map(IndustrialResourceBindingNbt::encode).forEach(bindingRows::add);
        root.put("Bindings", bindingRows);
        return root;
    }

    private static CompoundTag encode(IndustrialResourceCheckpointV1 checkpoint) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Project", checkpoint.projectId().toString());
        tag.putString("Binding", checkpoint.bindingFingerprint());
        tag.putLong("SavedTick", checkpoint.savedTick());
        tag.put("Warehouse", encode(checkpoint.warehouse()));
        tag.put("Energy", encode(checkpoint.energy()));
        tag.put("Fluid", encode(checkpoint.fluid()));
        checkpoint.logisticsSettlement().ifPresent(value -> tag.put("Logistics", encode(value)));
        return tag;
    }

    private static IndustrialResourceCheckpointV1 decode(CompoundTag tag) {
        return new IndustrialResourceCheckpointV1(
                ResourceId.parse(tag.getString("Project")), tag.getString("Binding"),
                decodeWarehouse(tag.getCompound("Warehouse")),
                decodeEnergy(tag.getCompound("Energy")), decodeFluid(tag.getCompound("Fluid")),
                tag.contains("Logistics", Tag.TAG_COMPOUND)
                        ? Optional.of(decodeLogistics(tag.getCompound("Logistics")))
                        : Optional.empty(),
                tag.getLong("SavedTick"));
    }

    private static CompoundTag encode(WarehouseReservationSnapshot snapshot) {
        CompoundTag tag = baseSnapshot(snapshot.graphFingerprint(), snapshot.generation(),
                snapshot.lastEpochMillis(), snapshot.journal());
        ListTag requests = new ListTag();
        snapshot.requests().values().stream().sorted(Comparator.comparing(
                        value -> value.requestId().toString()))
                .map(IndustrialResourceCheckpointSavedData::encode).forEach(requests::add);
        tag.put("Requests", requests);
        ListTag allocations = new ListTag();
        snapshot.allocations().values().stream().sorted(Comparator.comparing(
                        value -> value.allocationId().toString()))
                .map(IndustrialResourceCheckpointSavedData::encode).forEach(allocations::add);
        tag.put("Allocations", allocations);
        return tag;
    }

    private static WarehouseReservationSnapshot decodeWarehouse(CompoundTag tag) {
        LinkedHashMap<ResourceId, WarehouseReservationRequest> requests = new LinkedHashMap<>();
        for (Tag raw : tag.getList("Requests", Tag.TAG_COMPOUND)) {
            WarehouseReservationRequest request = decodeRequest((CompoundTag) raw);
            if (requests.putIfAbsent(request.requestId(), request) != null) {
                throw new IllegalArgumentException("duplicate warehouse request in checkpoint");
            }
        }
        LinkedHashMap<ResourceId, WarehouseAllocation> allocations = new LinkedHashMap<>();
        for (Tag raw : tag.getList("Allocations", Tag.TAG_COMPOUND)) {
            WarehouseAllocation allocation = decodeAllocation((CompoundTag) raw);
            if (allocations.putIfAbsent(allocation.allocationId(), allocation) != null) {
                throw new IllegalArgumentException("duplicate warehouse allocation in checkpoint");
            }
        }
        return new WarehouseReservationSnapshot(tag.getString("Graph"), requests, allocations,
                strings(tag, "Journal"), tag.getLong("Generation"), tag.getLong("Last"));
    }

    private static CompoundTag encode(WarehouseReservationRequest request) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Request", request.requestId().toString());
        tag.putString("Project", request.projectId().toString());
        tag.putString("Owner", request.ownerId().toString());
        tag.put("Resource", encode(request.resource()));
        tag.putLong("Quantity", request.quantity());
        putIds(tag, "Endpoints", request.allowedEndpointIds());
        tag.putLong("Expires", request.expiresAtEpochMillis());
        tag.putInt("Priority", request.priority());
        return tag;
    }

    private static WarehouseReservationRequest decodeRequest(CompoundTag tag) {
        return new WarehouseReservationRequest(ResourceId.parse(tag.getString("Request")),
                ResourceId.parse(tag.getString("Project")),
                ResourceId.parse(tag.getString("Owner")),
                decodeResource(tag.getCompound("Resource")), tag.getLong("Quantity"),
                ids(tag, "Endpoints"), tag.getLong("Expires"), tag.getInt("Priority"));
    }

    private static CompoundTag encode(WarehouseAllocation allocation) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Allocation", allocation.allocationId().toString());
        tag.putString("Request", allocation.requestId().toString());
        tag.putString("Project", allocation.projectId().toString());
        tag.putString("Endpoint", allocation.endpointId().toString());
        tag.put("Resource", encode(allocation.resource()));
        tag.putLong("Quantity", allocation.quantity());
        tag.putString("Status", allocation.status().name());
        tag.putLong("Generation", allocation.generation());
        tag.putLong("Updated", allocation.updatedAtEpochMillis());
        return tag;
    }

    private static WarehouseAllocation decodeAllocation(CompoundTag tag) {
        return new WarehouseAllocation(ResourceId.parse(tag.getString("Allocation")),
                ResourceId.parse(tag.getString("Request")),
                ResourceId.parse(tag.getString("Project")),
                ResourceId.parse(tag.getString("Endpoint")),
                decodeResource(tag.getCompound("Resource")), tag.getLong("Quantity"),
                WarehouseReservationStatus.valueOf(tag.getString("Status")),
                tag.getLong("Generation"), tag.getLong("Updated"));
    }

    private static CompoundTag encode(ProjectEnergyLedgerSnapshot snapshot) {
        CompoundTag tag = baseSnapshot("", snapshot.generation(), snapshot.lastTick(),
                snapshot.journal());
        ListTag rows = new ListTag();
        snapshot.transactions().values().stream().sorted(Comparator.comparing(
                        value -> value.transactionId().toString()))
                .map(IndustrialResourceCheckpointSavedData::encode).forEach(rows::add);
        tag.put("Transactions", rows);
        return tag;
    }

    private static ProjectEnergyLedgerSnapshot decodeEnergy(CompoundTag tag) {
        LinkedHashMap<ResourceId, EnergyTransaction> rows = new LinkedHashMap<>();
        for (Tag raw : tag.getList("Transactions", Tag.TAG_COMPOUND)) {
            EnergyTransaction transaction = decodeEnergyTransaction((CompoundTag) raw);
            if (rows.putIfAbsent(transaction.transactionId(), transaction) != null) {
                throw new IllegalArgumentException("duplicate energy transaction in checkpoint");
            }
        }
        return new ProjectEnergyLedgerSnapshot(rows, strings(tag, "Journal"),
                tag.getLong("Generation"), tag.getLong("Last"));
    }

    private static CompoundTag encode(EnergyTransaction transaction) {
        CompoundTag tag = transactionBase(transaction.transactionId(), transaction.projectId(),
                transaction.taskId(), transaction.sourceEndpointId(), transaction.generation(),
                transaction.updatedTick());
        tag.putString("Resource", transaction.energyResource().toString());
        tag.putLong("Amount", transaction.amountFe());
        tag.putString("State", transaction.state().name());
        return tag;
    }

    private static EnergyTransaction decodeEnergyTransaction(CompoundTag tag) {
        return new EnergyTransaction(id(tag, "Transaction"), id(tag, "Project"), id(tag, "Task"),
                id(tag, "Source"), id(tag, "Resource"), tag.getLong("Amount"),
                EnergyTransactionState.valueOf(tag.getString("State")),
                tag.getLong("Generation"), tag.getLong("Updated"));
    }

    private static CompoundTag encode(ProjectFluidLedgerSnapshot snapshot) {
        CompoundTag tag = baseSnapshot("", snapshot.generation(), snapshot.lastTick(),
                snapshot.journal());
        ListTag rows = new ListTag();
        snapshot.transactions().values().stream().sorted(Comparator.comparing(
                        value -> value.transactionId().toString()))
                .map(IndustrialResourceCheckpointSavedData::encode).forEach(rows::add);
        tag.put("Transactions", rows);
        return tag;
    }

    private static ProjectFluidLedgerSnapshot decodeFluid(CompoundTag tag) {
        LinkedHashMap<ResourceId, FluidTransaction> rows = new LinkedHashMap<>();
        for (Tag raw : tag.getList("Transactions", Tag.TAG_COMPOUND)) {
            FluidTransaction transaction = decodeFluidTransaction((CompoundTag) raw);
            if (rows.putIfAbsent(transaction.transactionId(), transaction) != null) {
                throw new IllegalArgumentException("duplicate fluid transaction in checkpoint");
            }
        }
        return new ProjectFluidLedgerSnapshot(rows, strings(tag, "Journal"),
                tag.getLong("Generation"), tag.getLong("Last"));
    }

    private static CompoundTag encode(FluidTransaction transaction) {
        CompoundTag tag = transactionBase(transaction.transactionId(), transaction.projectId(),
                transaction.taskId(), transaction.sourceEndpointId(), transaction.generation(),
                transaction.updatedTick());
        tag.putString("Fluid", transaction.identity().fluidId().toString());
        tag.putString("Components", transaction.identity().componentSha256());
        tag.putLong("Amount", transaction.amountMb());
        tag.putString("State", transaction.state().name());
        return tag;
    }

    private static FluidTransaction decodeFluidTransaction(CompoundTag tag) {
        return new FluidTransaction(id(tag, "Transaction"), id(tag, "Project"), id(tag, "Task"),
                id(tag, "Source"), new FluidIdentity(id(tag, "Fluid"), tag.getString("Components")),
                tag.getLong("Amount"), FluidTransactionState.valueOf(tag.getString("State")),
                tag.getLong("Generation"), tag.getLong("Updated"));
    }

    private static CompoundTag encode(EntityLogisticsSettlementV1 settlement) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Session", settlement.sessionId().toString());
        tag.putString("Graph", settlement.taskGraphFingerprint());
        putIds(tag, "Assignments", settlement.completedAssignmentIds());
        tag.putInt("Duplicates", settlement.duplicateCompletions());
        tag.putLong("Unaccounted", settlement.unaccountedCarriedItems());
        tag.putLong("Private", settlement.privateItemsTouched());
        return tag;
    }

    private static EntityLogisticsSettlementV1 decodeLogistics(CompoundTag tag) {
        return new EntityLogisticsSettlementV1(id(tag, "Session"), tag.getString("Graph"),
                ids(tag, "Assignments"), tag.getInt("Duplicates"),
                tag.getLong("Unaccounted"), tag.getLong("Private"));
    }

    private static CompoundTag encode(WarehouseResourceKey resource) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Type", resource.resourceType().serializedName());
        tag.putString("Id", resource.resourceId().toString());
        tag.putString("Components", resource.componentSha256());
        return tag;
    }

    private static WarehouseResourceKey decodeResource(CompoundTag tag) {
        return new WarehouseResourceKey(GenericResourceType.fromSerializedName(
                tag.getString("Type")), id(tag, "Id"), tag.getString("Components"));
    }

    private static CompoundTag baseSnapshot(
            String graph, long generation, long last, List<String> journal) {
        CompoundTag tag = new CompoundTag();
        if (!graph.isEmpty()) tag.putString("Graph", graph);
        tag.putLong("Generation", generation);
        tag.putLong("Last", last);
        putStrings(tag, "Journal", journal);
        return tag;
    }

    private static CompoundTag transactionBase(
            ResourceId transaction, ResourceId project, ResourceId task, ResourceId source,
            long generation, long updated) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Transaction", transaction.toString());
        tag.putString("Project", project.toString());
        tag.putString("Task", task.toString());
        tag.putString("Source", source.toString());
        tag.putLong("Generation", generation);
        tag.putLong("Updated", updated);
        return tag;
    }

    private static ResourceId id(CompoundTag tag, String key) {
        return ResourceId.parse(tag.getString(key));
    }

    private static void putIds(CompoundTag tag, String key, List<ResourceId> values) {
        ListTag rows = new ListTag();
        values.forEach(value -> rows.add(StringTag.valueOf(value.toString())));
        tag.put(key, rows);
    }

    private static List<ResourceId> ids(CompoundTag tag, String key) {
        return tag.getList(key, Tag.TAG_STRING).stream()
                .map(Tag::getAsString).map(ResourceId::parse).toList();
    }

    private static void putStrings(CompoundTag tag, String key, List<String> values) {
        ListTag rows = new ListTag();
        values.forEach(value -> rows.add(StringTag.valueOf(value)));
        tag.put(key, rows);
    }

    private static List<String> strings(CompoundTag tag, String key) {
        return tag.getList(key, Tag.TAG_STRING).stream().map(Tag::getAsString).toList();
    }
}
