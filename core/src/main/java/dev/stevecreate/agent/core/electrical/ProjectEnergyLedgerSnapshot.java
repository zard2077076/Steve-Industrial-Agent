package dev.stevecreate.agent.core.electrical;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record ProjectEnergyLedgerSnapshot(
        Map<ResourceId, EnergyTransaction> transactions,
        List<String> journal,
        long generation,
        long lastTick) {
    public ProjectEnergyLedgerSnapshot {
        transactions = Map.copyOf(Objects.requireNonNull(transactions, "transactions"));
        journal = List.copyOf(Objects.requireNonNull(journal, "journal"));
        if (transactions.size() > 4_096 || journal.size() > 16_384
                || generation < 0 || lastTick < 0) {
            throw new IllegalArgumentException("energy snapshot is unbounded or invalid");
        }
        for (Map.Entry<ResourceId, EnergyTransaction> entry : transactions.entrySet()) {
            ResourceId id = entry.getKey();
            EnergyTransaction transaction = entry.getValue();
            if (!id.equals(transaction.transactionId()) || transaction.generation() > generation
                    || transaction.updatedTick() > lastTick) {
                throw new IllegalArgumentException("energy snapshot transaction identity is invalid");
            }
            List<String> expected = switch (transaction.state()) {
                case PREPARED -> List.of("PREPARED");
                case SETTLED -> List.of("PREPARED", "SETTLED");
                case RELEASED -> List.of("PREPARED", "RELEASED");
            };
            if (!events(journal, id).equals(expected)) {
                throw new IllegalArgumentException(
                        "energy snapshot journal differs from transaction state");
            }
        }
        rejectUnknownTransactions(journal, transactions);
    }

    private static List<String> events(List<String> journal, ResourceId id) {
        java.util.ArrayList<String> result = new java.util.ArrayList<>();
        for (String entry : journal) {
            String[] parts = entry.split("\\|", 4);
            if (parts.length != 4) throw new IllegalArgumentException("energy journal row is malformed");
            if (parts[3].equals(id.toString())) result.add(parts[2]);
        }
        return List.copyOf(result);
    }

    private static void rejectUnknownTransactions(
            List<String> journal, Map<ResourceId, EnergyTransaction> transactions) {
        for (String entry : journal) {
            String[] parts = entry.split("\\|", 4);
            if (parts.length != 4 || !transactions.containsKey(ResourceId.parse(parts[3]))) {
                throw new IllegalArgumentException("energy journal references an unknown transaction");
            }
        }
    }
}
