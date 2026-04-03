package com.clan.feed;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(ClanFeedPlugin.CONFIG_NAME)
public interface ClanFeedConfig extends Config
{
    @ConfigItem(
        keyName = "websocketUrl",
        name = "WebSocket URL",
        description = "WebSocket URL to receive published messages from"
    )
    default String websocketUrl()
    {
        return "";
    }

    @Secret
    @ConfigItem(
        keyName = "websocketKey",
        name = "WebSocket key",
        description = "Authentication key used when connecting to the websocket"
    )
    default String websocketKey()
    {
        return "";
    }
}
}