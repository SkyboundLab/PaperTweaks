/*
 * GNU General Public License v3
 *
 * PaperTweaks, a performant replacement for the VanillaTweaks datapacks.
 *
 * Copyright (C) 2021-2026 Machine_Maker
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package me.machinemaker.papertweaks.modules;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import me.machinemaker.papertweaks.annotations.ModuleInfo;
import me.machinemaker.papertweaks.cloud.dispatchers.CommandDispatcher;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.plugin.java.JavaPlugin;
import org.incendo.cloud.paper.PaperCommandManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression test: on Paper 26.3, removing a command while the server is
 * stopping crashes with RejectedExecutionException, because the command
 * executor is already shut down by then.
 */
@SuppressWarnings("unchecked")
class ShutdownUnregisterTest {

    @ModuleCommand.Info(value = "testcmd", descriptionKey = "test.description")
    static final class TestCommand extends ModuleCommand {

        @Override
        protected void registerCommands() {
        }
    }

    static final class TestLifecycle extends ModuleLifecycle {

        TestLifecycle(final JavaPlugin plugin, final Set<ModuleCommand> commands) {
            super(plugin, commands, Set.of(), Set.of(), Set.of());
        }
    }

    @Test
    void unregisterSwallowsRejectedExecutionAndTriesRemainingRoots() throws Exception {
        final TestCommand command = new TestCommand();
        rootCommands(command).addAll(List.of("root-one", "root-two"));
        final PaperCommandManager<CommandDispatcher> manager = mock(PaperCommandManager.class);
        doThrow(new RejectedExecutionException("executor shut down")).when(manager).deleteRootCommand("root-one");

        assertDoesNotThrow(() -> command.unregisterCommands(manager));

        verify(manager).deleteRootCommand("root-one");
        verify(manager).deleteRootCommand("root-two");
    }

    @Test
    void disableSkipsUnregisterOnShutdownButNotOtherwise() throws Exception {
        final Server mockServer = mock(Server.class);
        when(mockServer.getOnlinePlayers()).thenReturn(List.of());

        final Field serverField = Bukkit.class.getDeclaredField("server");
        serverField.setAccessible(true);
        if (serverField.get(null) == null) {
            serverField.set(null, mockServer);
        }

        final JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getServer()).thenReturn(mockServer);

        final TestCommand command = new TestCommand();
        rootCommands(command).add("root-one");

        final PaperCommandManager<CommandDispatcher> manager = mock(PaperCommandManager.class);
        final TestLifecycle lifecycle = new TestLifecycle(plugin, Set.of(command));
        setField(lifecycle, "commandManager", manager);

        final ModuleInfo moduleInfo = mock(ModuleInfo.class);
        when(moduleInfo.name()).thenReturn("Test");
        setField(lifecycle, "moduleInfo", moduleInfo);

        lifecycle.disable(true, true);
        verify(manager, never()).deleteRootCommand(anyString());
        assertEquals(ModuleState.DISABLED, lifecycle.getState());

        lifecycle.disable(true, false);
        verify(manager).deleteRootCommand("root-one");
        assertEquals(ModuleState.DISABLED, lifecycle.getState());
    }

    private static Set<String> rootCommands(final ModuleCommand command) throws Exception {
        final Field field = ModuleCommand.class.getDeclaredField("rootCommands");
        field.setAccessible(true);
        return (Set<String>) field.get(command);
    }

    private static void setField(final Object target, final String name, final Object value) throws Exception {
        final Field field = ModuleLifecycle.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
