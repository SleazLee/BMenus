package org.geyser.extension.bmenus;

import org.geysermc.event.subscribe.Subscribe;
import org.geysermc.geyser.api.event.bedrock.action.BedrockActionEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserPostInitializeEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserShutdownEvent;
import org.geysermc.geyser.api.extension.Extension;
import org.geysermc.geyser.api.player.GeyserPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class BMenusExtension implements Extension {

    private final MenuManager menuManager = new MenuManager(this);
    private final Map<UUID, Long> lastInventoryOpen = new HashMap<>();

    @Subscribe
    public void onPostInitialize(GeyserPostInitializeEvent event) {
        menuManager.loadConfig();
    }

    @Subscribe
    public void onBedrockAction(BedrockActionEvent event) {
        if (event.action() == BedrockActionEvent.Action.OPEN_INVENTORY) {
            GeyserPlayer player = event.player();
            long now = System.currentTimeMillis();
            long last = lastInventoryOpen.getOrDefault(player.uuid(), 0L);
            if (now - last < 400) {
                menuManager.openMenu(player, "main");
            }
            lastInventoryOpen.put(player.uuid(), now);
        }
    }

    @Subscribe
    public void onShutdown(GeyserShutdownEvent event) {
        menuManager.shutdown();
    }
}
