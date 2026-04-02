package com.clan.feed;

import com.google.gson.Gson;
import com.google.inject.Inject;
import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@PluginDescriptor(
    name = "Clan Feed",
    description = "Receives websocket messages and displays them in clan chat"
)
public class ClanFeedPlugin extends Plugin
{
    static final String CONFIG_NAME = "clanfeed";
    private static final String WS_SENDER = "WebSocket";

    @Inject
    private ClanFeedConfig config;

    @Inject
    private Client client;

    @Inject
    private ClientThread clientThread;

    @Inject
    private Gson gson;

    @Inject
    private OkHttpClient httpClient;

    @Inject
    private ScheduledExecutorService executor;

    private OkHttpClient webSocketClient;
    private WebSocket webSocket;
    private final AtomicBoolean reconnectScheduled = new AtomicBoolean(false);
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);

    @Provides
    ClanFeedConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(ClanFeedConfig.class);
    }

    @Override
    protected void startUp()
    {
        shuttingDown.set(false);
        reconnectScheduled.set(false);

        webSocketClient = httpClient.newBuilder()
            .pingInterval(30, TimeUnit.SECONDS)
            .build();

        connectWebSocket();
    }

    @Override
    protected void shutDown()
    {
        shuttingDown.set(true);
        reconnectScheduled.set(false);

        disconnectWebSocket();

        if (webSocketClient != null)
        {
            webSocketClient.dispatcher().executorService().shutdown();
            webSocketClient.connectionPool().evictAll();
            webSocketClient = null;
        }
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event)
    {
        if (!CONFIG_NAME.equals(event.getGroup()))
        {
            return;
        }

        if ("websocketUrl".equals(event.getKey()))
        {
            disconnectWebSocket();
            connectWebSocket();
        }
    }

    private void connectWebSocket()
    {
        if (shuttingDown.get())
        {
            return;
        }

        String wsUrl = config.websocketUrl();
        if (wsUrl == null || wsUrl.trim().isEmpty())
        {
            return;
        }

        if (webSocketClient == null)
        {
            return;
        }

        disconnectWebSocket();

        Request request = new Request.Builder()
            .url(wsUrl.trim())
            .build();

        webSocket = webSocketClient.newWebSocket(request, new WebSocketListener()
        {
            @Override
            public void onOpen(@NotNull WebSocket webSocket, @NotNull Response response)
            {
                reconnectScheduled.set(false);
                log.info("WebSocket connected");
            }

            @Override
            public void onMessage(@NotNull WebSocket webSocket, @NotNull String text)
            {
                try
                {
                    WebsocketMessagePayload payload = gson.fromJson(text, WebsocketMessagePayload.class);
                    if (payload != null && payload.message != null)
                    {
                        String message = payload.message.trim();
                        if (!message.isEmpty())
                        {
                            postToClanChat(message);
                        }
                    }
                }
                catch (Exception e)
                {
                    log.warn("WebSocket message parse error", e);
                }
            }

            @Override
            public void onClosed(@NotNull WebSocket webSocket, int code, @NotNull String reason)
            {
                log.warn("WebSocket disconnected");
                if (ClanFeedPlugin.this.webSocket == webSocket)
                {
                    ClanFeedPlugin.this.webSocket = null;
                }
                scheduleReconnect();
            }

            @Override
            public void onFailure(@NotNull WebSocket webSocket, @NotNull Throwable t, @Nullable Response response)
            {
                log.warn("WebSocket failure", t);
                if (ClanFeedPlugin.this.webSocket == webSocket)
                {
                    ClanFeedPlugin.this.webSocket = null;
                }
                scheduleReconnect();
            }
        });
    }

    private void disconnectWebSocket()
    {
        if (webSocket != null)
        {
            try
            {
                webSocket.close(1000, "Plugin stopped");
            }
            catch (Exception ignored)
            {
            }
            finally
            {
                webSocket = null;
            }
        }
    }

    private void scheduleReconnect()
    {
        if (shuttingDown.get())
        {
            return;
        }

        String wsUrl = config.websocketUrl();
        if (wsUrl == null || wsUrl.trim().isEmpty())
        {
            return;
        }

        if (!reconnectScheduled.compareAndSet(false, true))
        {
            return;
        }

        log.info("Reconnecting websocket in 5 seconds");

        executor.schedule(() ->
        {
            try
            {
                if (!shuttingDown.get())
                {
                    connectWebSocket();
                }
            }
            finally
            {
                reconnectScheduled.set(false);
            }
        }, 5, TimeUnit.SECONDS);
    }

    private void postToClanChat(String message)
    {
        clientThread.invoke(() ->
            client.addChatMessage(
                ChatMessageType.CLAN_MESSAGE,
                "",
                message,
                WS_SENDER
            )
        );
    }

    private static class WebsocketMessagePayload
    {
        String message;
    }
}