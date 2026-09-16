package dev.stevecreate.agent.core.fluid;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Append-only project fluid transaction ledger with exact millibucket conservation. */
public final class ProjectFluidLedger {
    private final Map<ResourceId, FluidTransaction> transactions = new LinkedHashMap<>();
    private final List<String> journal = new ArrayList<>();
    private long generation;
    private long lastTick;

    public synchronized FluidTransaction prepare(
            ResourceId transactionId,
            ResourceId projectId,
            ResourceId taskId,
            ResourceId sourceEndpointId,
            FluidIdentity identity,
            long amountMb,
            long tick) {
        requireTick(tick);
        FluidTransaction existing = transactions.get(transactionId);
        if (existing != null) {
            if (!existing.projectId().equals(projectId) || !existing.taskId().equals(taskId)
                    || !existing.sourceEndpointId().equals(sourceEndpointId)
                    || !existing.identity().equals(identity) || existing.amountMb() != amountMb) {
                throw new IllegalStateException("fluid transaction identity was reused");
            }
            return existing;
        }
        FluidTransaction created = new FluidTransaction(transactionId, projectId, taskId,
                sourceEndpointId, identity, amountMb, FluidTransactionState.PREPARED,
                ++generation, tick);
        transactions.put(transactionId, created);
        journal("PREPARED", transactionId, tick);
        lastTick = tick;
        return created;
    }

    public synchronized FluidTransaction advance(
            ResourceId transactionId,
            FluidTransactionState next,
            long tick) {
        requireTick(tick);
        FluidTransaction current = transactions.get(Objects.requireNonNull(
                transactionId, "transactionId"));
        if (current == null) throw new IllegalArgumentException("unknown fluid transaction");
        if (current.state() == next) return current;
        FluidTransaction updated = current.advance(next, ++generation, tick);
        transactions.put(transactionId, updated);
        journal(next.name(), transactionId, tick);
        lastTick = tick;
        return updated;
    }

    public synchronized BalanceReport balance(ResourceId projectId) {
        List<FluidTransaction> rows = transactions.values().stream()
                .filter(value -> value.projectId().equals(projectId)).toList();
        long withdrawn = rows.stream().filter(value -> switch (value.state()) {
            case WITHDRAWN, DELIVERED, CONSUMED, RETURN_PENDING, RETURNED -> true;
            default -> false;
        }).mapToLong(FluidTransaction::amountMb).sum();
        long consumed = rows.stream().filter(value -> value.state() == FluidTransactionState.CONSUMED)
                .mapToLong(FluidTransaction::amountMb).sum();
        long returned = rows.stream().filter(value -> value.state() == FluidTransactionState.RETURNED)
                .mapToLong(FluidTransaction::amountMb).sum();
        // RETURN_PENDING is deliberately NOT outstanding. Outstanding means fluid that is
        // somewhere it is supposed to be; RETURN_PENDING means it left the source, the
        // delivery diverged, and the reclaim did not finish — it is in neither tank and
        // nothing else in this ledger will move it.
        //
        // Counting it as outstanding is what made this method incapable of failing. The
        // four sets below used to partition the withdrawn set exactly, so the residual
        // was zero for every possible input and balanced() was true by arithmetic rather
        // than by evidence. Three executor tests rested their top assertion on it.
        long outstanding = rows.stream().filter(value -> value.state() == FluidTransactionState.WITHDRAWN
                        || value.state() == FluidTransactionState.DELIVERED)
                .mapToLong(FluidTransaction::amountMb).sum();
        // No clamp. A negative residual would mean more was consumed or returned than was
        // ever withdrawn, which is a state-machine violation, and hiding it behind
        // Math.max is how a ledger reports health across the exact failure it exists to
        // catch.
        long unaccounted = withdrawn - consumed - returned - outstanding;
        return new BalanceReport(withdrawn, consumed, returned, outstanding, unaccounted,
                unaccounted == 0, duplicates(rows, FluidTransactionState.WITHDRAWN),
                duplicates(rows, FluidTransactionState.RETURNED));
    }

    /**
     * How many times a state was journalled beyond the first, across this project.
     *
     * <p>The transition table is a DAG with three terminal states, so no transaction can
     * legally reach the same state twice and this should always be zero. That is the
     * reason to compute it rather than to assume it: it checks the recorded history
     * against the rule instead of trusting the rule, and it was previously a hard-coded
     * zero that no input could move.</p>
     */
    private int duplicates(List<FluidTransaction> rows, FluidTransactionState state) {
        Map<ResourceId, Integer> seen = new LinkedHashMap<>();
        for (String entry : journal) {
            String[] parts = entry.split("\\|", 4);
            if (parts.length < 4 || !parts[2].equals(state.name())) continue;
            ResourceId id = ResourceId.parse(parts[3]);
            // Per project: a second transaction reaching WITHDRAWN is not a duplicate,
            // which is what a naive count over the whole journal would report.
            if (rows.stream().noneMatch(row -> row.transactionId().equals(id))) continue;
            seen.merge(id, 1, Integer::sum);
        }
        return seen.values().stream().mapToInt(count -> Math.max(0, count - 1)).sum();
    }

    public synchronized Map<ResourceId, FluidTransaction> transactions() {
        return Map.copyOf(transactions);
    }
    public synchronized List<String> journal() { return List.copyOf(journal); }

    public synchronized ProjectFluidLedgerSnapshot snapshot() {
        return new ProjectFluidLedgerSnapshot(transactions, journal, generation, lastTick);
    }

    public static ProjectFluidLedger restore(ProjectFluidLedgerSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        ProjectFluidLedger ledger = new ProjectFluidLedger();
        ledger.transactions.putAll(snapshot.transactions());
        ledger.journal.addAll(snapshot.journal());
        ledger.generation = snapshot.generation();
        ledger.lastTick = snapshot.lastTick();
        for (FluidTransaction row : ledger.transactions.values()) {
            List<String> observed = ledger.events(row.transactionId());
            if (!validEvents(row.state(), observed)) {
                throw new IllegalArgumentException("fluid snapshot journal differs from transaction state");
            }
        }
        return ledger;
    }

    private void journal(String event, ResourceId id, long tick) {
        if (journal.size() >= 16_384) throw new IllegalStateException("fluid journal is full");
        journal.add(generation + "|" + tick + "|" + event + "|" + id);
    }

    private int eventCount(ResourceId id, String event) {
        int count = 0;
        for (String entry : journal) {
            String[] parts = entry.split("\\|", 4);
            if (parts.length == 4 && parts[2].equals(event)
                    && parts[3].equals(id.toString())) count++;
        }
        return count;
    }

    private List<String> events(ResourceId id) {
        ArrayList<String> events = new ArrayList<>();
        for (String entry : journal) {
            String[] parts = entry.split("\\|", 4);
            if (parts.length == 4 && parts[3].equals(id.toString())) events.add(parts[2]);
        }
        return List.copyOf(events);
    }

    private static boolean validEvents(FluidTransactionState state, List<String> observed) {
        return switch (state) {
            case PREPARED -> observed.equals(List.of("PREPARED"));
            case WITHDRAWN -> observed.equals(List.of("PREPARED", "WITHDRAWN"));
            case DELIVERED -> observed.equals(List.of("PREPARED", "WITHDRAWN", "DELIVERED"));
            case CONSUMED -> observed.equals(
                    List.of("PREPARED", "WITHDRAWN", "DELIVERED", "CONSUMED"));
            case RELEASED -> observed.equals(List.of("PREPARED", "RELEASED"));
            case RETURN_PENDING -> observed.equals(
                    List.of("PREPARED", "WITHDRAWN", "RETURN_PENDING"))
                    || observed.equals(List.of(
                            "PREPARED", "WITHDRAWN", "DELIVERED", "RETURN_PENDING"));
            case RETURNED -> observed.equals(
                    List.of("PREPARED", "WITHDRAWN", "RETURN_PENDING", "RETURNED"))
                    || observed.equals(List.of("PREPARED", "WITHDRAWN", "DELIVERED",
                            "RETURN_PENDING", "RETURNED"));
        };
    }

    private void requireTick(long tick) {
        if (tick < lastTick) throw new IllegalArgumentException("fluid ledger tick moved backwards");
    }

    public record BalanceReport(
            long withdrawnMb,
            long consumedMb,
            long returnedMb,
            long outstandingMb,
            long unaccountedMb,
            boolean balanced,
            int duplicateWithdrawals,
            int duplicateReturns) {}
}
