package dev.stevecreate.agent.core.survey;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Fixed-token parser over registered identities; performs no filesystem or world operation. */
public final class FormalSurveyCommandParser {
    private final Set<String> registeredWorlds;
    private final Set<ResourceId> registeredPreviewTargets;

    public FormalSurveyCommandParser(Set<String> registeredWorlds, Set<ResourceId> registeredPreviewTargets) {
        this.registeredWorlds = Set.copyOf(registeredWorlds);
        this.registeredPreviewTargets = Set.copyOf(registeredPreviewTargets);
        if (this.registeredWorlds.size() > 256 || this.registeredPreviewTargets.size() > 64
                || this.registeredWorlds.stream().anyMatch(world -> !world.matches("world:[0-9a-f]{64}"))) {
            throw new IllegalArgumentException("formal command registry is invalid or unbounded");
        }
    }

    public FormalSurveyCommandPlan parse(List<String> input) {
        List<String> tokens = List.copyOf(input);
        if (tokens.size() < 3 || tokens.size() > 6 || tokens.stream().anyMatch(this::unsafeToken)
                || !tokens.get(0).equals("survey") || !tokens.get(1).equals("formal")) {
            throw failure(FormalSurveyFailureCode.FORMAL_WORLD_PATH_OUTSIDE_ALLOWED_ROOT,
                    "Only fixed survey formal commands without paths are accepted");
        }
        return switch (tokens.get(2)) {
            case "list-worlds" -> {
                requireSize(tokens, 3);
                yield plan(FormalSurveyCommand.LIST_WORLDS, null, null, 0);
            }
            case "inspect" -> worldCommand(tokens, FormalSurveyCommand.INSPECT);
            case "infrastructure" -> worldCommand(tokens, FormalSurveyCommand.INFRASTRUCTURE);
            case "candidates" -> worldCommand(tokens, FormalSurveyCommand.CANDIDATES);
            case "preview" -> preview(tokens);
            default -> throw failure(FormalSurveyFailureCode.FORMAL_WORLD_NOT_SELECTED,
                    "Unknown formal survey command");
        };
    }

    private FormalSurveyCommandPlan worldCommand(List<String> tokens, FormalSurveyCommand command) {
        requireSize(tokens, 4);
        return plan(command, registeredWorld(tokens.get(3)), null, 0);
    }

    private FormalSurveyCommandPlan preview(List<String> tokens) {
        requireSize(tokens, 6);
        String world = registeredWorld(tokens.get(3));
        ResourceId target;
        try {
            target = ResourceId.parse(tokens.get(4));
        } catch (RuntimeException exception) {
            throw failure(FormalSurveyFailureCode.CANDIDATE_ZONE_NOT_FOUND, "Preview target is invalid");
        }
        if (!registeredPreviewTargets.contains(target)) {
            throw failure(FormalSurveyFailureCode.CANDIDATE_ZONE_NOT_FOUND, "Preview target is not registered");
        }
        long quantity;
        try {
            quantity = Long.parseLong(tokens.get(5));
        } catch (NumberFormatException exception) {
            throw failure(FormalSurveyFailureCode.FORMAL_WORLD_DRY_RUN_ONLY, "Quantity is invalid");
        }
        if (quantity < 1 || quantity > 1_000_000) {
            throw failure(FormalSurveyFailureCode.FORMAL_WORLD_DRY_RUN_ONLY, "Quantity is outside the bound");
        }
        return plan(FormalSurveyCommand.PREVIEW, world, target, quantity);
    }

    private String registeredWorld(String world) {
        if (!registeredWorlds.contains(world)) {
            throw failure(FormalSurveyFailureCode.FORMAL_WORLD_NOT_SELECTED, "World identity is not registered");
        }
        return world;
    }

    private FormalSurveyCommandPlan plan(
            FormalSurveyCommand command, String world, ResourceId target, long quantity) {
        String root = world == null ? "world-index" : world.substring("world:".length());
        return new FormalSurveyCommandPlan(command, Optional.ofNullable(world), Optional.ofNullable(target), quantity,
                "formal-survey/" + root + "/" + command.name().toLowerCase().replace('_', '-') + ".json",
                world != null, false, false, false, false, false, false, false);
    }

    private boolean unsafeToken(String token) {
        return token == null || token.isBlank() || token.length() > 512 || token.contains("/")
                || token.contains("\\") || token.contains("..") || token.matches("^[A-Za-z]:.*");
    }

    private static void requireSize(List<String> tokens, int size) {
        if (tokens.size() != size) {
            throw failure(FormalSurveyFailureCode.FORMAL_WORLD_NOT_SELECTED,
                    "Formal survey command has the wrong argument count");
        }
    }

    private static FormalSurveyCommandException failure(FormalSurveyFailureCode code, String message) {
        return new FormalSurveyCommandException(code, message);
    }
}
