package org.geyser.extension.bmenus;

import org.geysermc.event.subscribe.Subscribe;
import org.geysermc.geyser.api.event.bedrock.ClientEmoteEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserPostInitializeEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserShutdownEvent;
import org.geysermc.geyser.api.extension.Extension;

public class BMenusExtension implements Extension {

    private MenuManager menuManager;

    @Subscribe
    public void onPostInitialize(GeyserPostInitializeEvent event) {
        menuManager = new MenuManager(this);
        menuManager.loadConfig();
    }

    @Subscribe
    public void onClientEmote(ClientEmoteEvent event) {
        event.setCancelled(true);
        if (menuManager != null) {
            menuManager.openMenu(event.connection(), "main");
        }
    }

    @Subscribe
    public void onShutdown(GeyserShutdownEvent event) {
        if (menuManager != null) {
            menuManager.shutdown();
        }
    }
}