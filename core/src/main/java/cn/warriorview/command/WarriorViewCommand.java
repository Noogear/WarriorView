package cn.warriorview.command;

import cn.warriorview.api.WarriorView;
import cn.warriorview.api.WarriorViewAPI;
import cn.warriorview.configFile.MessageConfig;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import gloomlib.script.core.handler.ActionNodeHandler;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.LifecycleEventManager;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.plugin.Plugin;

import java.util.Collection;
import java.util.Map;

import static cn.warriorview.configFile.MessageConfig.of;

/**
 * Paper Lifecycle API 命令注册。
 *
 * <p>注册 {@code /warriorview} 主命令及其子命令：</p>
 * <ul>
 *   <li>{@code reload [all|animations|indicators|formatters|scripts]} — 重载配置</li>
 *   <li>{@code info} — 显示插件概览</li>
 *   <li>{@code list actions} — 列出已注册的脚本动作</li>
 *   <li>{@code list mappings} — 列出已加载的事件映射脚本</li>
 *   <li>{@code list indicators} — 列出已加载的指示器标签</li>
 *   <li>{@code list animations} — 列出已加载的动画定义</li>
 * </ul>
 */
public final class WarriorViewCommand {

    static final String PERM_RELOAD = "warriorview.reload";
    static final String PERM_LIST   = "warriorview.list";
    static final String PERM_INFO   = "warriorview.info";

    private WarriorViewCommand() {}

    /**
     * 通过 Paper Lifecycle API 注册命令。在 {@code onEnable()} 之前调用。
     */
    @SuppressWarnings("UnstableApiUsage")
    public static void register(Plugin plugin) {
        LifecycleEventManager<Plugin> manager = plugin.getLifecycleManager();
        manager.registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            Commands commands = event.registrar();
            commands.register(buildCommand(), "WarriorView 管理命令",
                    java.util.List.of("wv", "sv"));
        });
    }

    @SuppressWarnings("UnstableApiUsage")
    private static LiteralCommandNode<CommandSourceStack> buildCommand() {
        return Commands.literal("warriorview")
                .requires(src -> src.getSender().hasPermission(PERM_RELOAD)
                        || src.getSender().hasPermission(PERM_LIST)
                        || src.getSender().hasPermission(PERM_INFO))

                // ── reload ──────────────────────────────────────────
                .then(Commands.literal("reload")
                        .requires(src -> src.getSender().hasPermission(PERM_RELOAD))
                        .then(Commands.literal("all").executes(ctx -> {
                            reload(ctx.getSource(), "all");
                            return Command.SINGLE_SUCCESS;
                        }))
                        .then(Commands.literal("animations").executes(ctx -> {
                            reload(ctx.getSource(), "animations");
                            return Command.SINGLE_SUCCESS;
                        }))
                        .then(Commands.literal("indicators").executes(ctx -> {
                            reload(ctx.getSource(), "indicators");
                            return Command.SINGLE_SUCCESS;
                        }))
                        .then(Commands.literal("formatters").executes(ctx -> {
                            reload(ctx.getSource(), "formatters");
                            return Command.SINGLE_SUCCESS;
                        }))
                        .then(Commands.literal("scripts").executes(ctx -> {
                            reload(ctx.getSource(), "scripts");
                            return Command.SINGLE_SUCCESS;
                        }))
                        .executes(ctx -> {
                            reload(ctx.getSource(), "all");
                            return Command.SINGLE_SUCCESS;
                        })
                )

                // ── list ────────────────────────────────────────────
                .then(Commands.literal("list")
                        .requires(src -> src.getSender().hasPermission(PERM_LIST))
                        .executes(ctx -> {
                            MessageConfig msg = ((cn.warriorview.Main) WarriorViewAPI.getProvider()).getMessageConfig();
                            ctx.getSource().getSender().sendMessage(msg.format(msg.list.usage));
                            return Command.SINGLE_SUCCESS;
                        })
                        .then(Commands.literal("actions").executes(ctx -> {
                            listActions(ctx.getSource());
                            return Command.SINGLE_SUCCESS;
                        }))
                        .then(Commands.literal("mappings").executes(ctx -> {
                            listMappings(ctx.getSource());
                            return Command.SINGLE_SUCCESS;
                        }))
                        .then(Commands.literal("indicators").executes(ctx -> {
                            listIndicators(ctx.getSource());
                            return Command.SINGLE_SUCCESS;
                        }))
                        .then(Commands.literal("animations").executes(ctx -> {
                            listAnimations(ctx.getSource());
                            return Command.SINGLE_SUCCESS;
                        }))
                )

                // ── info ────────────────────────────────────────────
                .then(Commands.literal("info")
                        .requires(src -> src.getSender().hasPermission(PERM_INFO))
                        .executes(ctx -> {
                    showInfo(ctx.getSource());
                    return Command.SINGLE_SUCCESS;
                }))

                // ── 无子命令时显示 info ─────────────────────────
                .executes(ctx -> {
                    if (ctx.getSource().getSender().hasPermission(PERM_INFO)) {
                        showInfo(ctx.getSource());
                    }
                    return Command.SINGLE_SUCCESS;
                })
                .build();
    }

    // ── 命令执行逻辑 ──────────────────────────────────────────────────────

    @SuppressWarnings("UnstableApiUsage")
    private static void reload(CommandSourceStack source, String target) {
        WarriorView provider = WarriorViewAPI.getProvider();
        MessageConfig msg = ((cn.warriorview.Main) provider).getMessageConfig();
        long start = System.currentTimeMillis();

        if ("all".equals(target)) {
            msg.smartReload();
            Map<String, Boolean> changes = provider.smartReloadAll();
            long elapsed = System.currentTimeMillis() - start;

            source.getSender().sendMessage(msg.prefixed(msg.reload.smartComplete, of("elapsed", elapsed)));

            changes.forEach((module, changed) -> source.getSender().sendMessage(
                    msg.format(changed ? msg.reload.moduleUpdated : msg.reload.moduleUnchanged,
                            of("module", module))));
            return;
        }

        switch (target) {
            case "animations"  -> provider.getAnimationManager().reload();
            case "indicators"  -> provider.getIndicatorManager().reload();
            case "formatters"  -> { provider.getNumberFormatManager().reload(); provider.getCharReplaceManager().reload(); }
            case "scripts"     -> provider.getScriptManager().reloadMappings();
            default            -> provider.reloadAll();
        }

        long elapsed = System.currentTimeMillis() - start;
        source.getSender().sendMessage(msg.prefixed(msg.reload.complete,
                of("target", target), of("elapsed", elapsed)));
    }

    @SuppressWarnings("UnstableApiUsage")
    private static void listActions(CommandSourceStack source) {
        MessageConfig msg = ((cn.warriorview.Main) WarriorViewAPI.getProvider()).getMessageConfig();
        var actions = ActionNodeHandler.registry().all();
        source.getSender().sendMessage(msg.prefixed(msg.list.actionsHeader, of("count", actions.size())));
        actions.keySet().stream().sorted().forEach(name ->
                source.getSender().sendMessage(msg.format(msg.list.entry, of("name", name))));
    }

    @SuppressWarnings("UnstableApiUsage")
    private static void listMappings(CommandSourceStack source) {
        MessageConfig msg = ((cn.warriorview.Main) WarriorViewAPI.getProvider()).getMessageConfig();
        Collection<String> ids = WarriorViewAPI.getProvider().getScriptManager().getMappingIds();
        source.getSender().sendMessage(msg.prefixed(msg.list.mappingsHeader, of("count", ids.size())));
        ids.stream().sorted().forEach(id ->
                source.getSender().sendMessage(msg.format(msg.list.entry, of("name", id))));
    }

    @SuppressWarnings("UnstableApiUsage")
    private static void listIndicators(CommandSourceStack source) {
        MessageConfig msg = ((cn.warriorview.Main) WarriorViewAPI.getProvider()).getMessageConfig();
        Collection<String> tags = WarriorViewAPI.getProvider().getIndicatorManager().getTags();
        source.getSender().sendMessage(msg.prefixed(msg.list.indicatorsHeader, of("count", tags.size())));
        tags.stream().sorted().forEach(tag ->
                source.getSender().sendMessage(msg.format(msg.list.entry, of("name", tag))));
    }

    @SuppressWarnings("UnstableApiUsage")
    private static void listAnimations(CommandSourceStack source) {
        MessageConfig msg = ((cn.warriorview.Main) WarriorViewAPI.getProvider()).getMessageConfig();
        Collection<String> names = WarriorViewAPI.getProvider().getAnimationManager().getAnimationNames();
        source.getSender().sendMessage(msg.prefixed(msg.list.animationsHeader, of("count", names.size())));
        names.stream().sorted().forEach(name ->
                source.getSender().sendMessage(msg.format(msg.list.entry, of("name", name))));
    }

    @SuppressWarnings("UnstableApiUsage")
    private static void showInfo(CommandSourceStack source) {
        MessageConfig msg = ((cn.warriorview.Main) WarriorViewAPI.getProvider()).getMessageConfig();
        var provider = WarriorViewAPI.getProvider();
        var animMgr = provider.getAnimationManager();
        var scriptMgr = provider.getScriptManager();

        source.getSender().sendMessage(msg.format(""));
        source.getSender().sendMessage(msg.format(msg.info.header, of("version", provider.getVersion())));
        source.getSender().sendMessage(msg.format(msg.info.separator));
        source.getSender().sendMessage(msg.format(msg.info.animations, of("count", animMgr.getAnimationNames().size())));
        source.getSender().sendMessage(msg.format(msg.info.indicators, of("count", provider.getIndicatorManager().getTags().size())));
        source.getSender().sendMessage(msg.format(msg.info.mappings, of("count", scriptMgr.getMappingIds().size())));
        source.getSender().sendMessage(msg.format(msg.info.actions, of("count", ActionNodeHandler.registry().all().size())));
        source.getSender().sendMessage(msg.format(msg.info.numberFormats, of("count", provider.getNumberFormatManager().getRuleNames().size())));
        source.getSender().sendMessage(msg.format(msg.info.charReplaces, of("count", provider.getCharReplaceManager().getRuleNames().size())));
        source.getSender().sendMessage(msg.format(""));
    }
}
