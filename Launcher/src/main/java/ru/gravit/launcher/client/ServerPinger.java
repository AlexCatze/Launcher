package ru.gravit.launcher.client;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonObject;

import ru.gravit.launcher.LauncherAPI;
import ru.gravit.utils.helper.IOHelper;
import ru.gravit.utils.helper.LogHelper;
import ru.gravit.utils.helper.VerifyHelper;
import ru.gravit.launcher.serialize.HInput;
import ru.gravit.launcher.serialize.HOutput;
import ru.gravit.launcher.profiles.ClientProfile;

public final class ServerPinger {
    public static final class Result {
        @LauncherAPI
        public final int onlinePlayers;
        @LauncherAPI
        public final int maxPlayers;
        @LauncherAPI
        public final String raw;

        public Result(int onlinePlayers, int maxPlayers, String raw) {
            this.onlinePlayers = VerifyHelper.verifyInt(onlinePlayers,
                    VerifyHelper.NOT_NEGATIVE, "onlinePlayers can't be < 0");
            this.maxPlayers = VerifyHelper.verifyInt(maxPlayers,
                    VerifyHelper.NOT_NEGATIVE, "maxPlayers can't be < 0");
            this.raw = raw;
        }

        @LauncherAPI
        public boolean isOverfilled() {
            return onlinePlayers >= maxPlayers;
        }
    }

    // Constants
    private static final String LEGACY_PING_HOST_MAGIC = "§1";
    private static final String LEGACY_PING_HOST_CHANNEL = "MC|PingHost";
    private static final Pattern LEGACY_PING_HOST_DELIMETER = Pattern.compile("\0", Pattern.LITERAL);

    private static final int PACKET_LENGTH = 65535;

    private static String readUTF16String(HInput input) throws IOException {
        int length = input.readUnsignedShort() << 1;
        byte[] encoded = input.readByteArray(-length);
        return new String(encoded, StandardCharsets.UTF_16BE);
    }

    private static void writeUTF16String(HOutput output, String s) throws IOException {
        output.writeShort((short) s.length());
        output.stream.write(s.getBytes(StandardCharsets.UTF_16BE));
    }

    // Instance
    private final InetSocketAddress address;
    private final ClientProfile.Version version;
    // Cache
    private final Object cacheLock = new Object();

    private Result cache = null;

    private Exception cacheException = null;

    private Instant cacheTime = null;

    @LauncherAPI
    public ServerPinger(InetSocketAddress address, ClientProfile.Version version) {
        this.address = Objects.requireNonNull(address, "address");
        this.version = Objects.requireNonNull(version, "version");
    }

    private Result doPing() throws IOException {
        try (Socket socket = IOHelper.newSocket()) {
            socket.connect(IOHelper.resolve(address), IOHelper.SOCKET_TIMEOUT);
            try (HInput input = new HInput(socket.getInputStream());
                 HOutput output = new HOutput(socket.getOutputStream())) {
                return version.compareTo(ClientProfile.Version.MC172) >= 0 ? modernPing(input, output) : legacyPing(input, output, version.compareTo(ClientProfile.Version.MC164) >= 0);
            }
        }
    }

    private Result legacyPing(HInput input, HOutput output, boolean mc16) throws IOException {
        output.writeUnsignedByte(0xFE); // 254 packet ID, Server list ping
        output.writeUnsignedByte(0x01); // Server ping payload
        if (mc16) {
            output.writeUnsignedByte(0xFA); // 250 packet ID, Custom payload
            writeUTF16String(output, LEGACY_PING_HOST_CHANNEL); // Custom payload name

            // Prepare custom payload packet
            byte[] customPayloadPacket;
            try (ByteArrayOutputStream packetArray = IOHelper.newByteArrayOutput()) {
                try (HOutput packetOutput = new HOutput(packetArray)) {
                    packetOutput.writeUnsignedByte(version.protocol); // Protocol version
                    writeUTF16String(packetOutput, address.getHostString()); // Server address
                    packetOutput.writeInt(address.getPort()); // Server port
                }
                customPayloadPacket = packetArray.toByteArray();
            }

            // Write custom payload packet
            output.writeShort((short) customPayloadPacket.length);
            output.stream.write(customPayloadPacket);
        }
        output.flush();

        // Raed kick (response) packet
        int kickPacketID = input.readUnsignedByte();
        if (kickPacketID != 0xFF)
            throw new IOException("Illegal kick packet ID: " + kickPacketID);

        // Read and parse response
        String response = readUTF16String(input);
        LogHelper.debug("Ping response (legacy): '%s'", response);
        String[] splitted = LEGACY_PING_HOST_DELIMETER.split(response);
        if (splitted.length != 6)
            throw new IOException("Tokens count mismatch");

        // Verify all parts
        String magic = splitted[0];
        if (!magic.equals(LEGACY_PING_HOST_MAGIC))
            throw new IOException("Magic string mismatch: " + magic);
        int protocol = Integer.parseInt(splitted[1]);
        if (protocol != version.protocol)
            throw new IOException("Protocol mismatch: " + protocol);
        String clientVersion = splitted[2];
        if (!clientVersion.equals(version.name))
            throw new IOException(String.format("Version mismatch: '%s'", clientVersion));
        int onlinePlayers = VerifyHelper.verifyInt(Integer.parseInt(splitted[4]),
                VerifyHelper.NOT_NEGATIVE, "onlinePlayers can't be < 0");
        int maxPlayers = VerifyHelper.verifyInt(Integer.parseInt(splitted[5]),
                VerifyHelper.NOT_NEGATIVE, "maxPlayers can't be < 0");

        // Return ping status
        return new Result(onlinePlayers, maxPlayers, response);
    }

    private Result modernPing(HInput input, HOutput output) throws IOException {
        // Prepare handshake packet
        byte[] handshakePacket;
        try (ByteArrayOutputStream packetArray = IOHelper.newByteArrayOutput()) {
            try (HOutput packetOutput = new HOutput(packetArray)) {
                packetOutput.writeVarInt(0x0); // Handshake packet ID
                packetOutput.writeVarInt(version.protocol); // Protocol version
                packetOutput.writeString(address.getHostString(), 0); // Server address
                packetOutput.writeShort((short) address.getPort()); // Server port
                packetOutput.writeVarInt(0x1); // Next state - status
            }
            handshakePacket = packetArray.toByteArray();
        }

        // Write handshake packet
        output.writeByteArray(handshakePacket, PACKET_LENGTH);

        // Request status packet
        output.writeVarInt(1); // Status packet size (single byte)
        output.writeVarInt(0x0); // Status packet ID
        output.flush();

        // ab is a dirty fix for some servers (noticed KCauldron 1.7.10)
        int ab = 0;
        while (ab <= 0)
            ab = IOHelper.verifyLength(input.readVarInt(), PACKET_LENGTH);

        // Read outer status response packet ID
        String response;
        byte[] statusPacket = input.readByteArray(-ab);
        try (HInput packetInput = new HInput(statusPacket)) {
            int statusPacketID = packetInput.readVarInt();
            if (statusPacketID != 0x0)
                throw new IOException("Illegal status packet ID: " + statusPacketID);
            response = packetInput.readString(PACKET_LENGTH);
            LogHelper.debug("Ping response (modern): '%s'", response);
        }

        // Parse JSON response
        JsonObject object = Json.parse(response).asObject();
        JsonObject playersObject = object.get("players").asObject();
        int online = playersObject.get("online").asInt();
        int max = playersObject.get("max").asInt();

        // Return ping status
        return new Result(online, max, response);
    }

    @LauncherAPI
    public Result ping() throws IOException {
        Instant now = Instant.now();
        synchronized (cacheLock) {
            // Update ping cache
            if (cacheTime == null || Duration.between(now, cacheTime).toMillis() >= IOHelper.SOCKET_TIMEOUT) {
                cacheTime = now;
                try {
                    cache = doPing();
                    cacheException = null;
                } catch (IOException | IllegalArgumentException /* Protocol error */ e) {
                    cache = null;
                    cacheException = e;
                }
            }

            // Verify is result available
            if (cache == null) {
                if (cacheException instanceof IOException)
                    throw (IOException) cacheException;
                if (cacheException instanceof IllegalArgumentException)
                    throw (IllegalArgumentException) cacheException;
                cacheException = new IOException("Unavailable");
                throw (IOException) cacheException;
            }

            // We're done
            return cache;
        }
    }
}
