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
    private final AtomicInteger connectionGeneration = new AtomicInteger(0);

    @Provides
    ClanFeedConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(ClanFeedConfig.class);
    }

    @Override
    protected void startUp()
    {
        log.info("ClanFeedPlugin starting up");

        shuttingDown.set(false);
        reconnectScheduled.set(false);
        connectionGeneration.incrementAndGet();

        webSocketClient = httpClient.newBuilder()
            .pingInterval(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // Disable read timeout for websocket connections
            .build();

        connectWebSocket(connectionGeneration.get());
    }

    @Override
    protected void shutDown()
    {
        log.info("ClanFeedPlugin shutting down");

        shuttingDown.set(true);
        reconnectScheduled.set(false);
        connectionGeneration.incrementAndGet();

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

        if ("websocketUrl".equals(event.getKey()) || "websocketKey".equals(event.getKey()))
        {
            log.info("WebSocket config changed, reconnecting");

            int generation = connectionGeneration.incrementAndGet();
            reconnectScheduled.set(false);

            disconnectWebSocket();
            connectWebSocket(generation);
        }
    }

    private void connectWebSocket(int generation)
    {
        log.info("connectWebSocket() called");

        if (shuttingDown.get())
        {
            log.info("Not connecting websocket because plugin is shutting down");
            return;
        }

        String wsUrl = trimToNull(config.websocketUrl());
        String wsKey = trimToNull(config.websocketKey());

        log.info("Configured websocketUrl='{}'", wsUrl);
        log.info("Configured websocketKey present={}", wsKey != null);

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
            log.warn("WebSocket client not initialised");
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
            log.warn("Invalid WebSocket URL: {}", wsUrl, e);
            return;
        }

        log.info("Connecting websocket to {}", wsUrl);

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

    private void scheduleReconnect(int generation)
    {
        if (shuttingDown.get())
        {
            return;
        }

        if (generation != connectionGeneration.get())
        {
            log.debug("Skipping reconnect schedule for stale websocket generation {}", generation);
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

        log.info("Reconnecting websocket in {} seconds", RECONNECT_DELAY_SECONDS);

        executor.schedule(() ->
        {
            try
            {
                if (shuttingDown.get())
                {
                    return;
                }

                if (generation != connectionGeneration.get())
                {
                    log.debug("Skipping stale scheduled reconnect for generation {}", generation);
                    return;
                }

                connectWebSocket(generation);
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
        public void onOpen(@NotNull WebSocket webSocket, @NotNull Response response)
        {
            if (generation != connectionGeneration.get())
            {
                log.debug("Ignoring onOpen for stale websocket generation {}", generation);
                try
                {
                    webSocket.close(1000, "Stale websocket");
                }
                catch (Exception e)
                {
                    log.debug("Error closing stale websocket on open", e);
                }
                return;
            }

            reconnectScheduled.set(false);
            log.info("WebSocket connected");
        }

        @Override
        public void onMessage(@NotNull WebSocket webSocket, @NotNull String text)
        {
            if (generation != connectionGeneration.get())
            {
                log.debug("Ignoring message from stale websocket generation {}", generation);
                return;
            }

            log.debug("WebSocket message received, length={}", text.length());

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
                    log.warn(
                        "Dropping websocket message because it exceeds max length ({} > {})",
                        message.length(),
                        MAX_MESSAGE_LENGTH
                    );
                    return;
                }

                postToClanChat(message);
            }
            catch (Exception e)
            {
                log.warn("WebSocket message parse error, raw length={}", text.length(), e);
            }
        }

        @Override
        public void onClosing(@NotNull WebSocket webSocket, int code, @NotNull String reason)
        {
            log.info("WebSocket closing code={} reason={}", code, reason);
        }

        @Override
        public void onClosed(@NotNull WebSocket webSocket, int code, @NotNull String reason)
        {
            log.warn("WebSocket closed code={} reason={}", code, reason);

            if (ClanFeedPlugin.this.webSocket == webSocket)
            {
                ClanFeedPlugin.this.webSocket = null;

                if (!shuttingDown.get())
                {
                    scheduleReconnect(generation);
                }
            }
            else
            {
                log.debug("Ignoring onClosed for non-current websocket");
            }
        }

        @Override
        public void onFailure(@NotNull WebSocket webSocket, @NotNull Throwable t, @Nullable Response response)
        {
            if (response != null)
            {
                log.warn(
                    "WebSocket failure code={} message={}",
                    response.code(),
                    response.message(),
                    t
                );
            }
            else
            {
                log.warn("WebSocket failure without HTTP response", t);
            }

            if (ClanFeedPlugin.this.webSocket == webSocket)
            {
                ClanFeedPlugin.this.webSocket = null;

                if (!shuttingDown.get())
                {
                    scheduleReconnect(generation);
                }
            }
            else
            {
                log.debug("Ignoring onFailure for non-current websocket");
            }
        }
    }

    private static final class WebsocketMessagePayload
    {
        private String message;
    }
}