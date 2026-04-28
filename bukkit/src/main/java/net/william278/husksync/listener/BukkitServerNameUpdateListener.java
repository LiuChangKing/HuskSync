/*
 * This file is part of HuskSync, licensed under the Apache License 2.0.
 *
 *  Copyright (c) William278 <will27528@gmail.com>
 *  Copyright (c) contributors
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package net.william278.husksync.listener;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteStreams;
import net.william278.husksync.BukkitHuskSync;
import net.william278.husksync.config.Server;
import net.william278.husksync.util.Task;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.Optional;
import java.util.logging.Level;

public class BukkitServerNameUpdateListener implements Listener, PluginMessageListener {

    private static final String CHANNEL = "BungeeCord";
    private static final String SUBCHANNEL_GET_SERVER = "GetServer";
    private static final long QUERY_INTERVAL_TICKS = 40L;

    private final BukkitHuskSync plugin;
    private volatile boolean fetched;
    private Task.Sync pollingTask;

    public BukkitServerNameUpdateListener(@NotNull BukkitHuskSync plugin) {
        this.plugin = plugin;
    }

    public void onEnable() {
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, CHANNEL, this);
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        startPollingTask();
        requestServerName();
    }

    public void onDisable() {
        if (pollingTask != null) {
            pollingTask.cancel();
            pollingTask = null;
        }
        HandlerList.unregisterAll(this);
        plugin.getServer().getMessenger().unregisterIncomingPluginChannel(plugin, CHANNEL, this);
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, CHANNEL);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerJoin(@NotNull PlayerJoinEvent event) {
        if (fetched) {
            return;
        }
        startPollingTask();
        requestServerName();
    }

    @Override
    public void onPluginMessageReceived(@NotNull String channel, @NotNull Player player, byte[] message) {
        if (!CHANNEL.equals(channel) || fetched) {
            return;
        }
        try {
            final ByteArrayDataInput input = ByteStreams.newDataInput(message);
            final String subChannel = input.readUTF();
            if (!SUBCHANNEL_GET_SERVER.equals(subChannel)) {
                return;
            }

            final String serverName = input.readUTF().trim();
            if (serverName.isBlank()) {
                return;
            }
            fetched = true;
            if (pollingTask != null) {
                pollingTask.cancel();
                pollingTask = null;
            }

            if (!serverName.equals(plugin.getServerName())) {
                plugin.saveServer(Server.of(serverName));
                plugin.log(Level.INFO, String.format("Updated server.yml name to \"%s\" via proxy query", serverName));
            } else {
                plugin.debug(String.format("Confirmed server.yml name \"%s\" via proxy query", serverName));
            }
        } catch (Throwable e) {
            plugin.log(Level.WARNING, "Failed to parse proxy server ID response from BungeeCord", e);
        }
    }

    private void startPollingTask() {
        if (fetched || pollingTask != null) {
            return;
        }
        pollingTask = plugin.runSyncDelayed(() -> {
            pollingTask = null;
            requestServerName();
            startPollingTask();
        }, null, QUERY_INTERVAL_TICKS);
    }

    private void requestServerName() {
        if (fetched) {
            return;
        }
        final Optional<? extends Player> player = plugin.getServer().getOnlinePlayers().stream().findFirst();
        if (player.isEmpty()) {
            return;
        }
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeUTF(SUBCHANNEL_GET_SERVER);
            player.get().sendPluginMessage(plugin, CHANNEL, bytes.toByteArray());
        } catch (Throwable e) {
            plugin.log(Level.WARNING, "Failed to request server ID from BungeeCord", e);
        }
    }

}
