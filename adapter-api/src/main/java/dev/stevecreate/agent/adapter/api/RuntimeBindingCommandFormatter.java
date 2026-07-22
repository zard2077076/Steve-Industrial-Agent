package dev.stevecreate.agent.adapter.api;

import dev.stevecreate.agent.core.binding.BindingFailure;
import dev.stevecreate.agent.core.binding.BindingResult;
import dev.stevecreate.agent.core.binding.BoundMachineNode;
import dev.stevecreate.agent.core.binding.VerifiedImplementationBoundPlan;
import java.util.List;
import java.util.Objects;

/** Deterministic read-only presentation of implementation binding and its full trace. */
public final class RuntimeBindingCommandFormatter {
    public RuntimePlanningCommandReport format(BindingResult result) {
        Objects.requireNonNull(result, "result");
        if (result instanceof BindingResult.Failure failed) {
            return failure(failed.failure());
        }
        return success(((BindingResult.Success) result).plan());
    }

    private static RuntimePlanningCommandReport success(
            VerifiedImplementationBoundPlan plan) {
        List<String> nodes = plan.graph().boundProcessNodes().values().stream()
                .map(RuntimeBindingCommandFormatter::node).toList();
        String headline = "Binding verified plan=" + plan.id()
                + " nodes=" + nodes.size()
                + " verification=PASS fingerprint=" + plan.graph().runtimeFingerprint();
        String detail = "implementations=" + bracket(nodes)
                + " checks=" + bracket(plan.evidence().keySet().stream()
                        .map(Enum::name).toList());
        String trace = "trace=" + bracket(plan.graph().bindingTrace());
        String structured = "runtimeBinding={status=PASS,plan=" + plan.id()
                + ",logicalPlan=" + plan.graph().logicalPlan().id()
                + ",nodes=" + bracket(nodes)
                + ",verificationChecks=" + bracket(plan.evidence().keySet().stream()
                        .map(Enum::name).toList())
                + ",fingerprint=" + plan.graph().runtimeFingerprint()
                + ",catalogGeneration=" + plan.graph().catalogReloadGeneration()
                + ",layoutAuthority=false,executionAuthority=false,worldMutation=false,"
                + trace + "}";
        return new RuntimePlanningCommandReport(
                true, 1, List.of(headline, detail, trace), structured);
    }

    private static RuntimePlanningCommandReport failure(BindingFailure failure) {
        String node = failure.logicalNodeId().map(Object::toString).orElse("none");
        String capability = failure.capabilityId().map(Object::toString).orElse("none");
        String recipe = failure.recipeId().map(Object::toString).orElse("none");
        String adapter = failure.adapterId().map(Object::toString).orElse("none");
        String trace = bracket(failure.trace());
        String line = "Binding failed code=" + failure.code()
                + " stage=" + failure.stage()
                + " node=" + node
                + " capability=" + capability
                + " recipe=" + recipe
                + " candidates=" + bracket(failure.candidateImplementationIds().stream()
                        .map(Object::toString).toList())
                + " adapter=" + adapter
                + " fingerprint=" + failure.runtimeFingerprint()
                + " constraint=" + quote(failure.constraint())
                + " reason=" + quote(failure.detail())
                + " next=" + quote(failure.safeNextStep())
                + " trace=" + trace;
        String structured = "runtimeBinding={status=FAIL,code=" + failure.code()
                + ",stage=" + failure.stage()
                + ",node=" + node
                + ",capability=" + capability
                + ",recipe=" + recipe
                + ",candidates=" + bracket(failure.candidateImplementationIds().stream()
                        .map(Object::toString).toList())
                + ",adapter=" + adapter
                + ",fingerprint=" + failure.runtimeFingerprint()
                + ",constraint=" + quote(failure.constraint())
                + ",detail=" + quote(failure.detail())
                + ",userIntervention=" + failure.userInterventionRequired()
                + ",safeNextStep=" + quote(failure.safeNextStep())
                + ",trace=" + trace + "}";
        return new RuntimePlanningCommandReport(false, 0, List.of(line), structured);
    }

    private static String node(BoundMachineNode node) {
        return "node=" + node.logicalNodeId()
                + ":recipe=" + node.recipeId()
                + ":implementation=" + node.implementationId()
                + ":adapter=" + node.adapterId()
                + ":executions=" + node.quantityConversion().executions()
                + ":ingredients=" + node.recipeInputs().stream()
                        .map(value -> value.ingredientKind() + "/" + value.selectedResource()
                                + "/" + quote(value.ingredientIdentity()))
                        .toList()
                + ":ports=" + node.logicalToImplementationPorts()
                + ":score=" + node.selectionScore().total();
    }

    private static String bracket(List<String> values) {
        return "[" + String.join(",", values) + "]";
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
