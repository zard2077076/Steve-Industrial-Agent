package dev.stevecreate.agent.core.diagnostic;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/** Deterministic classification only; it never receives a world or an executor. */
public final class FactoryHealthAnalyzer {
    public FactoryHealthReport analyze(FactoryHealthSnapshot snapshot) {
        List<FactoryDiagnosis> findings = new ArrayList<>();
        EnumSet<FactoryHealthCategory> unknown = EnumSet.noneOf(FactoryHealthCategory.class);
        for (FactoryHealthObservation observation : snapshot.observations()) {
            if (observation.state() == FactoryObservationState.UNKNOWN) {
                unknown.add(observation.category());
            } else if (observation.state() == FactoryObservationState.FAULT) {
                findings.add(diagnosis(observation));
            }
        }
        FactoryHealthStatus status = !findings.isEmpty() ? FactoryHealthStatus.FAULTED
                : !unknown.isEmpty() ? FactoryHealthStatus.INCONCLUSIVE
                : FactoryHealthStatus.HEALTHY;
        return new FactoryHealthReport(snapshot.subjectId(), snapshot.observedTick(), status,
                findings, unknown, snapshot.observations(), 0, false);
    }

    private static FactoryDiagnosis diagnosis(FactoryHealthObservation observation) {
        FactoryFaultCode code;
        FactoryRecommendedAction action;
        switch (observation.category()) {
            case MATERIAL_SUPPLY -> {
                code = FactoryFaultCode.MATERIAL_SHORTAGE;
                action = FactoryRecommendedAction.RESTOCK_BOUND_SOURCES;
            }
            case OUTPUT_CAPACITY -> {
                code = FactoryFaultCode.OUTPUT_CAPACITY_EXHAUSTED;
                action = FactoryRecommendedAction.FREE_OUTPUT_CAPACITY;
            }
            case LOGISTICS_ROUTE -> {
                code = FactoryFaultCode.LOGISTICS_ROUTE_BLOCKED;
                action = FactoryRecommendedAction.INSPECT_APPROVED_ROUTE;
            }
            case ENERGY_SUPPLY -> {
                code = FactoryFaultCode.ENERGY_INSUFFICIENT;
                action = FactoryRecommendedAction.RESTORE_POWER;
            }
            case STRUCTURE_INTEGRITY -> {
                code = FactoryFaultCode.STRUCTURE_OR_ORIENTATION_MISMATCH;
                action = FactoryRecommendedAction.REVALIDATE_STRUCTURE;
            }
            case RESERVATION_INTEGRITY -> {
                code = FactoryFaultCode.RESERVATION_DRIFT;
                action = FactoryRecommendedAction.RESELECT_MATERIAL_SOURCE;
            }
            default -> throw new IllegalStateException("unmapped factory health category");
        }
        return new FactoryDiagnosis(code, observation.category(), Severity.ERROR, action,
                observation.source(), observation.evidenceCode(), observation.metrics(),
                observation.resources(), observation.detail());
    }
}
