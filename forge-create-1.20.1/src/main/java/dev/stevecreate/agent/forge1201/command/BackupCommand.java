package dev.stevecreate.agent.forge1201.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/** Player-facing portable backup request and verification commands. */
public final class BackupCommand {
    private BackupCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("backup")
                .then(Commands.literal("status").executes(context -> status(context.getSource())))
                .then(Commands.literal("prepare").requires(source -> source.hasPermission(4))
                        .executes(context -> prepare(context.getSource())))
                .then(Commands.literal("verify").executes(context -> verify(context.getSource())))
                .then(Commands.literal("list").executes(context -> list(context.getSource())))
                .then(Commands.literal("explain").executes(context -> explain(context.getSource())));
    }

    private static int status(CommandSourceStack source) {
        try {
            PortableBackupService.Verified verified = PortableBackupService.verify(source.getLevel());
            source.sendSuccess(() -> Component.literal("Backup status VERIFIED identity="
                    + verified.backupIdentity() + " files=" + verified.entries().size()
                    + " bytes=" + verified.totalBytes() + " restoreDrill=true stale=false paths=redacted"), false);
            return 1;
        } catch (Exception failure) {
            source.sendFailure(Component.literal("Backup status NOT_READY code=" + failure.getMessage()
                    + " safeNextStep=/industrialagent backup prepare"));
            return 0;
        }
    }

    private static int prepare(CommandSourceStack source) {
        try {
            PortableBackupService.Prepared prepared = PortableBackupService.prepare(source.getLevel());
            source.sendSuccess(() -> Component.literal("Backup request PENDING id=" + prepared.requestId()
                    + " requestFile=" + prepared.requestFile() + " next=save_and_fully_close_Minecraft_then_run_"
                    + "tools/Complete-IndustrialAgentBackup.ps1_-RequestFile_<requestFile>"
                    + " worldMutation=false automaticUpload=false"), true);
            return 1;
        } catch (Exception failure) {
            source.sendFailure(Component.literal("Backup prepare refused code=" + failure.getMessage()));
            return 0;
        }
    }

    private static int verify(CommandSourceStack source) {
        try {
            PortableBackupService.Verified verified = PortableBackupService.verify(source.getLevel());
            source.sendSuccess(() -> Component.literal("Backup verify PASS identity="
                    + verified.backupIdentity() + " manifest=" + verified.manifestHash()
                    + " sourcePrePostEqual=" + verified.sourcePreFingerprint().equals(
                            verified.sourcePostFingerprint())
                    + " backupHash=true restoreDrill=true worldBinding=true paths=redacted"), false);
            return 1;
        } catch (Exception failure) {
            source.sendFailure(Component.literal("Backup verify FAIL code=" + failure.getMessage()
                    + " safeNextStep=do_not_start_pilot"));
            return 0;
        }
    }

    private static int list(CommandSourceStack source) {
        try {
            var backups = PortableBackupService.list(source.getLevel());
            source.sendSuccess(() -> Component.literal("Backup list count=" + backups.size()
                    + " records=" + backups + " maxShown=32 paths=redacted"), false);
            return 1;
        } catch (Exception failure) {
            source.sendFailure(Component.literal("Backup list refused code=" + failure.getMessage()));
            return 0;
        }
    }

    private static int explain(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("Backup flow: prepare -> save and fully close Minecraft -> "
                + "run bundled offline helper -> restart -> verify. The helper writes one new immutable backup, "
                + "SHA-256 manifest, source pre/post fingerprints, completed marker and restore drill; no upload."), false);
        return 1;
    }
}
