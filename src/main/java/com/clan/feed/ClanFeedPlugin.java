package com.clan.feed;

import com.google.gson.Gson;
import com.google.inject.Inject;
import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.GameStateChanged;
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

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@PluginDescriptor(
    name = "Clan Feed",
    description = "Receives websocket messages and displays them in clan chat"
)
public class ClanFeedPlugin extends Plugin
{
    static final String CONFIG_NAME = "clanfeed";

    private static final String WS_SENDER = "WebSocket";
    private static final String WS_KEY_HEADER = "X-WS-Key";
    private static final int MAX_MESSAGE_LENGTH = 500;
    private static final long RECONNECT_DELAY_SECONDS = 5L;

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
    private final AtomicBoolean authFailed = new AtomicBoolean(false);
    private final AtomicInteger connectionGeneration = new AtomicInteger(0);

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
        authFailed.set(false);
        connectionGeneration.incrementAndGet();

        webSocketClient = httpClient.newBuilder()
            .pingInterval(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build();

        if (client.getGameState() == GameState.LOGGED_IN)
        {
            connectWebSocket(connectionGeneration.get());
        }
    }

    @Override
    protected void shutDown()
    {
        shuttingDown.set(true);
        reconnectScheduled.set(false);
        authFailed.set(false);
        connectionGeneration.incrementAndGet();

        disconnectWebSocket();

        webSocketClient = null;
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        if (event.getGameState() == GameState.LOGGED_IN)
        {
            if (webSocket == null)
            {
                int generation = connectionGeneration.incrementAndGet();
                reconnectScheduled.set(false);
                authFailed.set(false);

                connectWebSocket(generation);
            }

            return;
        }

        if (event.getGameState() == GameState.LOGIN_SCREEN && webSocket != null)
        {
            connectionGeneration.incrementAndGet();
            reconnectScheduled.set(false);

            disconnectWebSocket();
        }
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event)
    {
        if (!CONFIG_NAME.equals(event.getGroup()))
        {
            return;
        }

        if ("websocketUrl".equals(event.getKey()) || "websocketKey".equals(event.getKey()))
        {
            int generation = connectionGeneration.incrementAndGet();
            reconnectScheduled.set(false);
            authFailed.set(false);

            disconnectWebSocket();

            if (client.getGameState() == GameState.LOGGED_IN)
            {
                connectWebSocket(generation);
            }
        }
    }

    private void connectWebSocket(int generation)
    {
        if (shuttingDown.get())
        {
            return;
        }

        if (client.getGameState() != GameState.LOGGED_IN)
        {
            return;
        }

        if (authFailed.get())
        {
            log.warn("WebSocket authentication previously failed. Update the config to retry.");
            return;
        }

        String wsUrl = trimToNull(config.websocketUrl());
        String wsKey = trimToNull(config.websocketKey());

        if (wsUrl == null)
        {
            log.warn("WebSocket URL is missing");
            return;
        }

        if (wsKey == null)
        {
            log.warn("WebSocket key is missing");
            return;
        }

        if (webSocketClient == null)
        {
            return;
        }

        disconnectWebSocket();

        Request request;
        try
        {
            request = new Request.Builder()
                .url(wsUrl)
                .addHeader(WS_KEY_HEADER, wsKey)
                .build();
        }
        catch (IllegalArgumentException e)
        {
            log.warn("Invalid WebSocket URL: {}", wsUrl);
            return;
        }

        webSocket = webSocketClient.newWebSocket(request, new ClanFeedWebSocketListener(generation));
    }

    private void disconnectWebSocket()
    {
        WebSocket socket = this.webSocket;
        this.webSocket = null;

        if (socket != null)
        {
            try
            {
                socket.close(1000, "Plugin stopped");
            }
            catch (Exception e)
            {
                log.debug("Error while closing websocket", e);
            }
        }
    }

    private void closeStaleWebSocket(WebSocket webSocket)
    {
        try
        {
            webSocket.close(1000, "Stale websocket");
        }
        catch (Exception e)
        {
            log.debug("Error while closing stale websocket", e);
        }
    }

    private void scheduleReconnect(int generation)
    {
        if (shuttingDown.get())
        {
            return;
        }

        if (client.getGameState() != GameState.LOGGED_IN)
        {
            return;
        }

        if (authFailed.get())
        {
            return;
        }

        if (generation != connectionGeneration.get())
        {
            return;
        }

        String wsUrl = trimToNull(config.websocketUrl());
        String wsKey = trimToNull(config.websocketKey());

        if (wsUrl == null || wsKey == null)
        {
            return;
        }

        if (!reconnectScheduled.compareAndSet(false, true))
        {
            return;
        }

        executor.schedule(() ->
        {
            try
            {
                if (
                    !shuttingDown.get()
                        && !authFailed.get()
                        && generation == connectionGeneration.get()
                        && client.getGameState() == GameState.LOGGED_IN
                )
                {
                    connectWebSocket(generation);
                }
            }
            finally
            {
                reconnectScheduled.set(false);
            }
        }, RECONNECT_DELAY_SECONDS, TimeUnit.SECONDS);
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

    private static String trimToNull(String value)
    {
        if (value == null)
        {
            return null;
        }

        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private final class ClanFeedWebSocketListener extends WebSocketListener
    {
        private final int generation;

        private ClanFeedWebSocketListener(int generation)
        {
            this.generation = generation;
        }

        @Override
        public void onOpen(WebSocket webSocket, Response response)
        {
            if (generation != connectionGeneration.get())
            {
                closeStaleWebSocket(webSocket);
                return;
            }

            authFailed.set(false);
            reconnectScheduled.set(false);
            log.info("WebSocket connected");
        }

        @Override
        public void onMessage(WebSocket webSocket, String text)
        {
            if (generation != connectionGeneration.get())
            {
                closeStaleWebSocket(webSocket);
                return;
            }

            try
            {
                WebsocketMessagePayload payload = gson.fromJson(text, WebsocketMessagePayload.class);
                if (payload == null || payload.message == null)
                {
                    return;
                }

                String message = payload.message.trim();
                if (message.isEmpty())
                {
                    return;
                }

                if (message.length() > MAX_MESSAGE_LENGTH)
                {
                    log.warn("Dropping websocket message because it exceeds max length");
                    return;
                }

                postToClanChat(message);
            }
            catch (Exception e)
            {
                log.warn("WebSocket message parse error", e);
            }
        }

        @Override
        public void onClosed(WebSocket webSocket, int code, String reason)
        {
            if (ClanFeedPlugin.this.webSocket == webSocket)
            {
                ClanFeedPlugin.this.webSocket = null;

                if (!shuttingDown.get() && !authFailed.get())
                {
                    scheduleReconnect(generation);
                }
            }
        }

        @Override
        public void onFailure(WebSocket webSocket, Throwable t, Response response)
        {
            if (generation != connectionGeneration.get())
            {
                closeStaleWebSocket(webSocket);
                return;
            }

            boolean authenticationFailure =
                response != null && (response.code() == 401 || response.code() == 403);

            if (ClanFeedPlugin.this.webSocket == webSocket)
            {
                ClanFeedPlugin.this.webSocket = null;

                if (authenticationFailure)
                {
                    authFailed.set(true);
                    reconnectScheduled.set(false);
                    log.warn("WebSocket authentication failed. Check your websocket key.");
                    return;
                }

                if (!shuttingDown.get())
                {
                    scheduleReconnect(generation);
                }
            }
        }
    }

    private static final class WebsocketMessagePayload
    {
        private String message;
    }
}