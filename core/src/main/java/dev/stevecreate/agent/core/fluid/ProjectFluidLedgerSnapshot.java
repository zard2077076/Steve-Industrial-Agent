package dev.stevecreate.agent.core.fluid;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record ProjectFluidLedgerSnapshot(
        Map<ResourceId, FluidTransaction> transactions,
        List<String> journal,
        long generation,
        long lastTick) {
    public ProjectFluidLedgerSnapshot {
        transactions = Map.copyOf(Objects.requireNonNull(transactions, "transactions"));
        journal = List.copyOf(Objects.requireNonNull(journal, "journal"));
        if (transactions.size() > 4_096 || journal.size() > 16_384
                || generation < 0 || lastTick < 0) {
            throw new IllegalArgumentException("fluid snapshot is unbounded or invalid");
        }
        for (Map.Entry<ResourceId, FluidTransaction> entry : transactions.entrySet()) {
            ResourceId id = entry.getKey();
            FluidTransaction transaction = entry.getValue();
            if (!id.equals(transaction.transactionId()) || transaction.generation() > generation
                    || transaction.updatedTick() > lastTick) {
                throw new IllegalArgumentException("fluid snapshot transaction identity is invalid");
            }
            if (!validEvents(transaction.state(), events(journal, id))) {
                throw new IllegalArgumentException(
                        "fluid snapshot journal differs from transaction state");
            }
        }
        rejectUnknownTransactions(journal, transactions);
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

    private static List<String> events(List<String> journal, ResourceId id) {
        java.util.ArrayList<String> result = new java.util.ArrayList<>();
        for (String entry : journal) {
            String[] parts = entry.split("\\|", 4);
            if (parts.length != 4) throw new IllegalArgumentException("fluid journal row is malformed");
            if (parts[3].equals(id.toString())) result.add(parts[2]);
        }
        return List.copyOf(result);
    }

    private static void rejectUnknownTransactions(
            List<String> journal, Map<ResourceId, FluidTransaction> transactions) {
        for (String entry : journal) {
            String[] parts = entry.split("\\|", 4);
            if (parts.length != 4 || !transactions.containsKey(ResourceId.parse(parts[3]))) {
                throw new IllegalArgumentException("fluid journal references an unknown transaction");
            }
        }
    }
}
