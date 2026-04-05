package com.clan.feed;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class ClanFeedTest
{
  public static void main(String[] args) throws Exception {
        ExternalPluginManager.loadBuiltin(ClanFeedPlugin.class);
        RuneLite.main(args);
    }
}

