package ru.gravit.launcher.events.request;

import ru.gravit.launcher.LauncherNetworkAPI;
import ru.gravit.launcher.events.RequestEvent;
import ru.gravit.utils.event.EventInterface;

import java.util.UUID;

public class EchoRequestEvent extends RequestEvent implements EventInterface {
    private static final UUID uuid = UUID.fromString("0a1f820f-7cd5-47a5-ae0e-17492e0e1fe1");
    @LauncherNetworkAPI
    public String echo;

    public EchoRequestEvent(String echo) {
        this.echo = echo;
    }

    @Override
    public String getType() {
        return "echo";
    }

    @Override
    public UUID getUUID() {
        return uuid;
    }
}
