package ru.gravit.launchserver.websocket.json.auth;

import io.netty.channel.ChannelHandlerContext;
import ru.gravit.launcher.events.request.ErrorRequestEvent;
import ru.gravit.launcher.events.request.ProfilesRequestEvent;
import ru.gravit.launchserver.LaunchServer;
import ru.gravit.launchserver.socket.Client;
import ru.gravit.launchserver.websocket.json.SimpleResponse;

public class ProfilesResponse extends SimpleResponse {
    @Override
    public String getType() {
        return "profiles";
    }

    @Override
    public void execute(ChannelHandlerContext ctx, Client client) {
        if (!client.checkSign && !client.isAuth) {
            service.sendObject(ctx, new ErrorRequestEvent("Access denied"));
            return;
        }
        sendResult(new ProfilesRequestEvent(LaunchServer.server.getProfiles()));
    }
}
