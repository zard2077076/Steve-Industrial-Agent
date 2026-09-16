package dev.stevecreate.agent.forge1201.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.stevecreate.agent.core.diagnostic.FactoryMaintenanceApproval;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** Player-owned three-step maintenance review, approval and execution transport. */
public final class FactoryMaintenanceCommand {
    private FactoryMaintenanceCommand() {}

    public static void attach(LiteralArgumentBuilder<CommandSourceStack> playerRoot) {
        playerRoot.then(Commands.literal("maintenance")
                .then(Commands.literal("propose").executes(context ->
                        propose(context.getSource())))
                .then(Commands.literal("status").executes(context ->
                        status(context.getSource())))
                .then(Commands.literal("approve").executes(context ->
                        approve(context.getSource())))
                .then(Commands.literal("dismiss").executes(context ->
                        dismiss(context.getSource())))
                .then(Commands.literal("execute").executes(context ->
                        execute(context.getSource()))));
    }

    private static int propose(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) return refused(source, "维护提案只允许玩家查看");
        var result = FactoryMaintenanceService.proposeOwnedMetalPressPower(player);
        if (!result.success()) return refused(source, "无法生成维护提案：" + result.code());
        var proposal = result.proposal().orElseThrow();
        source.sendSuccess(() -> Component.literal("维护提案（尚未授权）\n"
                + "故障：" + proposal.faultCode() + " · 证据：" + proposal.evidenceCode() + "\n"
                + "动作：仅恢复本订单 Metal Press 的蓝冰/岩浆热源两格\n"
                + "不会扫描附近箱子、不会触碰物品栏、不会重接线\n"
                + "如确认范围，请执行 /steveagent maintenance approve"), false);
        return 1;
    }

    private static int status(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) return refused(source, "维护状态只允许玩家查看");
        var proposal = FactoryMaintenanceService.currentProposal(player).orElse(null);
        if (proposal == null) return refused(source, "当前没有待处理维护提案");
        var approval = dev.stevecreate.agent.forge1201.industrial
                .FactoryMaintenanceApprovalSavedData.forLevel(player.serverLevel())
                .ledger().approvals().get(proposal.proposalId());
        String state = approval == null ? "AWAITING_EXPLICIT_APPROVAL" : approval.state().name();
        var execution = FactoryMaintenanceService.currentExecution(player).orElse(null);
        String executionState = execution == null ? "NOT_STARTED" : execution.state().name();
        String next = execution == null ? (approval == null
                ? "/steveagent maintenance approve"
                : "/steveagent maintenance execute")
                : execution.state() == dev.stevecreate.agent.forge1201.industrial
                        .FactoryMaintenanceExecutionSavedData.State.PREPARED
                        ? "/steveagent maintenance execute（继续未完成动作）"
                        : "等待或再次执行以完成验证";
        source.sendSuccess(() -> Component.literal("维护提案状态：" + state
                + "\n执行事务：" + executionState
                + "\n证据：" + proposal.evidenceCode()
                + " · 到期 tick：" + proposal.expiresAtTick()
                + "\n下一步：" + next), false);
        return 1;
    }

    private static int approve(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) return refused(source, "维护批准只允许玩家操作");
        var result = FactoryMaintenanceService.approveCurrent(player);
        if (!result.success()) return refused(source, "维护批准被拒绝：" + result.code());
        FactoryMaintenanceApproval approval = result.approval().orElseThrow();
        source.sendSuccess(() -> Component.literal("维护批准已记录，但尚未改变世界\n"
                + "状态：" + approval.state()
                + " · 单次有效 · 到期 tick：" + approval.expiresAtTick() + "\n"
                + "执行前会重新诊断；确认现在执行请输入 /steveagent maintenance execute"), false);
        return 1;
    }

    private static int dismiss(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) return refused(source, "维护提案撤销只允许玩家操作");
        var result = FactoryMaintenanceService.dismissCurrent(player);
        if (!result.success()) return refused(source, result.code()
                .equals("MAINTENANCE_EXECUTION_IN_PROGRESS")
                ? "维护执行已开始，不能撤销；请用 status 查看后执行恢复"
                : "当前没有待撤销维护提案");
        source.sendSuccess(() -> Component.literal("维护提案已撤销；世界和物品均未改变"), false);
        return 1;
    }

    private static int execute(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) return refused(source, "维护执行只允许玩家操作");
        var result = FactoryMaintenanceService.executeCurrent(player);
        if (!result.success()) return refused(source, "维护执行被拒绝：" + result.code());
        source.sendSuccess(() -> Component.literal("维护已完成\n"
                + "实际修改：" + result.worldMutations() + " 个订单热源格\n"
                + "重新诊断：" + result.postDiagnosis().orElseThrow().status()
                + " · 一次性批准已消费"), false);
        return 1;
    }

    private static int refused(CommandSourceStack source, String message) {
        source.sendFailure(Component.literal(message));
        return 0;
    }
}
