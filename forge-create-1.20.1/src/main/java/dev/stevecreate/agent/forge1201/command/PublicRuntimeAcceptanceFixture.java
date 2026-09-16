package dev.stevecreate.agent.forge1201.command;

import dev.stevecreate.agent.forge1201.acceptance.AcceptanceRuntimeGuard;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

/** Two-process packaged-JAR proof for the production public-alpha setup and backup path. */
public final class PublicRuntimeAcceptanceFixture {
    public static final String PHASE_PROPERTY =
            "steve_industrial.test.publicRuntimeAcceptancePhase";

    private PublicRuntimeAcceptanceFixture() {}

    public static void run(MinecraftServer server, String phase, Logger logger) {
        AcceptanceRuntimeGuard.requireDevelopmentRuntime("PublicRuntimeAcceptanceFixture");
        try {
            if (!"prepare".equals(phase) && !"verify".equals(phase)) {
                throw new IllegalArgumentException("Unknown public runtime acceptance phase: " + phase);
            }
            ServerLevel level = server.overworld();
            PublicAlphaRuntime.Result initial = PublicAlphaRuntime.resolve(level, false);
            if (!(initial instanceof PublicAlphaRuntime.Success success)) {
                throw new IllegalStateException("Public runtime pre-marker validation failed: "
                        + ((PublicAlphaRuntime.Failure) initial).code());
            }

            if ("prepare".equals(phase)) {
                PilotWorldMarkerSavedData data = PilotWorldMarkerSavedData.forLevel(level);
                if (data.marker().isPresent()) {
                    throw new IllegalStateException("Prepare phase requires a fresh unmarked world");
                }
                data.mark(new PilotWorldMarkerSavedData.Marker(
                        PilotWorldMarkerSavedData.SCHEMA,
                        success.authorization().worldIdentity(),
                        UUID.randomUUID().toString(),
                        "overworld-pilot-only",
                        "packaged-production-acceptance",
                        "fixture:public-runtime",
                        level.getGameTime()));
            }

            RecordingCommandSource recording = new RecordingCommandSource();
            CommandSourceStack source = server.createCommandSourceStack()
                    .withSource(recording)
                    .withLevel(level)
                    .withPermission(4);
            requireCommand(server, source, "/industrialagent setup validate", recording);

            if ("prepare".equals(phase)) {
                requireCommand(server, source, "/industrialagent backup prepare", recording);
                long requestCount;
                try (var requests = Files.list(success.authorization().backupRoot().resolve("requests"))) {
                    requestCount = requests.filter(Files::isRegularFile).count();
                }
                if (requestCount != 1) {
                    throw new IllegalStateException("Expected exactly one portable backup request, found "
                            + requestCount);
                }
                if (!server.saveEverything(false, true, true)) {
                    throw new IllegalStateException("Server rejected the explicit world save");
                }
                logger.info("PUBLIC_RUNTIME_PREPARE PASS schema={} marker=true requestCount=1 "
                                + "gameDirSource=process backupRootSource=config repositoryDependency=false "
                                + "worldMutation=false paths=redacted",
                        success.authorization().schemaVersion());
            } else {
                requireCommand(server, source, "/industrialagent backup verify", recording);
                PortableBackupService.Verified verified = PortableBackupService.verify(level);
                PilotDeploymentCommand.verifyPublicBackupCoreForAcceptance(level);
                logger.info("PUBLIC_RUNTIME_VERIFY PASS identity={} files={} bytes={} "
                                + "sourcePrePostEqual={} backupHash=true restoreDrill=true "
                                + "worldBinding=true coreBackupVerifier=true "
                                + "repositoryDependency=false paths=redacted",
                        verified.backupIdentity(), verified.entries().size(), verified.totalBytes(),
                        verified.sourcePreFingerprint().equals(verified.sourcePostFingerprint()));
            }
            server.halt(false);
        } catch (Exception failure) {
            logger.error("PUBLIC_RUNTIME_ACCEPTANCE FAIL phase={} detail={}", phase,
                    failure.getMessage(), failure);
            server.halt(false);
            throw new IllegalStateException("Public runtime acceptance failed", failure);
        }
    }

    private static void requireCommand(
            MinecraftServer server,
            CommandSourceStack source,
            String command,
            RecordingCommandSource recording) {
        int before = recording.messages().size();
        int result = server.getCommands().performPrefixedCommand(source, command);
        List<String> messages = recording.messages().subList(before, recording.messages().size());
        if (result != 1) {
            throw new IllegalStateException("Command failed: " + command + " messages=" + messages);
        }
    }

    private static final class RecordingCommandSource implements CommandSource {
        private final List<String> messages = new ArrayList<>();

        @Override
        public void sendSystemMessage(Component message) {
            messages.add(message.getString());
        }

        @Override
        public boolean acceptsSuccess() {
            return true;
        }

        @Override
        public boolean acceptsFailure() {
            return true;
        }

        @Override
        public boolean shouldInformAdmins() {
            return false;
        }

        private List<String> messages() {
            return messages;
        }
    }
}
