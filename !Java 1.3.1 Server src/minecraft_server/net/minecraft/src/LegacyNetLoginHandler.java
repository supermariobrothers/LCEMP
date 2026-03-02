package net.minecraft.src;

import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.util.Iterator;
import java.util.logging.Logger;
import net.minecraft.server.MinecraftServer;

/**
 * Handles the login handshake for Legacy Console Edition (LCE) clients
 * connecting to the standalone server.
 *
 * The Legacy protocol uses a two-step handshake:
 *   1. PreLoginPacket (id=2): netcodeVersion short (495) + username + UGC data
 *   2. LoginPacket   (id=1): clientVersion int (39)    + player data
 *
 * After the handshake succeeds the handler creates a {@link TcpConnection} and
 * {@link NetServerHandler} that share the same socket so that in-game packets
 * are processed normally by the existing server infrastructure.
 */
public class LegacyNetLoginHandler implements Runnable
{
    public static Logger logger = Logger.getLogger("Minecraft");

    /** Netcode version sent in the Legacy PreLoginPacket. */
    private static final int LEGACY_NETCODE_VERSION = 495;

    /** Protocol version embedded inside the Legacy LoginPacket (matches Java 1.3.1). */
    private static final int LEGACY_PROTOCOL_VERSION = 39;

    private final MinecraftServer mcServer;
    private final Socket socket;
    private final InputStream inputStream;
    private final String connectionId;

    /**
     * Creates a handler and immediately starts the login sequence on a daemon thread.
     *
     * @param server      the running MinecraftServer instance
     * @param socket      the accepted client socket
     * @param inputStream the socket input stream, possibly wrapped in a PushbackInputStream
     * @param id          a human-readable connection identifier used for logging
     */
    public LegacyNetLoginHandler(MinecraftServer server, Socket socket, InputStream inputStream, String id)
    {
        this.mcServer = server;
        this.socket = socket;
        this.inputStream = inputStream;
        this.connectionId = id;

        Thread t = new Thread(this, id + " legacy-login");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public void run()
    {
        try
        {
            doLogin();
        }
        catch (Exception e)
        {
            logger.warning("Legacy login error for " + connectionId + ": " + e.getMessage());
            try { socket.close(); } catch (IOException ex) { }
        }
    }

    // -------------------------------------------------------------------------
    // Login sequence
    // -------------------------------------------------------------------------

    private void doLogin() throws IOException
    {
        DataInputStream dis = new DataInputStream(inputStream);
        // Use a separate DataOutputStream for the handshake so we can flush
        // before handing the socket off to TcpConnection.
        DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), 5120));

        // ---- Phase 1: Read Legacy PreLoginPacket (id = 2) -------------------
        int packetId = dis.read();

        if (packetId == -1)
        {
            return; // connection closed before first packet
        }

        if (packetId != 2)
        {
            sendDisconnect(dos, "Expected PreLoginPacket (id 2), got id " + packetId);
            return;
        }

        short netcodeVersion = dis.readShort();

        if (netcodeVersion != LEGACY_NETCODE_VERSION)
        {
            String msg = netcodeVersion > LEGACY_NETCODE_VERSION ? "Outdated server!" : "Outdated client!";
            sendDisconnect(dos, msg);
            return;
        }

        String loginKey = readString(dis);        // username/key sent by client
        byte friendsOnlyBits = dis.readByte();
        int ugcPlayersVersion = dis.readInt();
        int playerCount = dis.readByte() & 0xFF;

        for (int i = 0; i < playerCount; i++)
        {
            dis.readLong();                        // player XUID (ignored on dedicated server)
        }

        byte[] uniqueSaveName = new byte[14];
        dis.readFully(uniqueSaveName);
        int serverSettings = dis.readInt();
        /* byte hostIndex = */ dis.readByte();
        /* int texturePackId = */ dis.readInt();

        // ---- Phase 2: Send Legacy PreLoginPacket response (id = 2) ----------
        dos.write(2);                               // packet id
        dos.writeShort(LEGACY_NETCODE_VERSION);     // netcodeVersion
        writeString("-", dos);                      // loginKey = "-" (offline)
        dos.writeByte(0);                           // friendsOnlyBits
        dos.writeInt(0);                            // ugcPlayersVersion
        dos.writeByte(0);                           // playerCount (empty list)
        for (int i = 0; i < 14; i++) dos.writeByte(0); // uniqueSaveName (zeros)
        dos.writeInt(0);                            // serverSettings
        dos.writeByte(0);                           // hostIndex
        dos.writeInt(0);                            // texturePackId
        dos.flush();

        // ---- Phase 3: Read Legacy LoginPacket (id = 1) ----------------------
        packetId = dis.read();

        if (packetId != 1)
        {
            sendDisconnect(dos, "Expected LoginPacket (id 1), got id " + packetId);
            return;
        }

        int clientVersion = dis.readInt();

        if (clientVersion != LEGACY_PROTOCOL_VERSION)
        {
            String msg = clientVersion > LEGACY_PROTOCOL_VERSION ? "Outdated server!" : "Outdated client!";
            sendDisconnect(dos, msg);
            return;
        }

        String playerName = readString(dis);
        /* String levelTypeName = */ readString(dis);
        /* long seed = */ dis.readLong();
        /* int gameTypeId = */ dis.readInt();
        /* int dimension = */ dis.readByte();
        /* int mapHeight = */ dis.readByte() & 0xFF;
        /* int clientMaxPlayers = */ dis.readByte() & 0xFF;
        /* long offlineXuid = */ dis.readLong();
        /* long onlineXuid = */ dis.readLong();
        /* boolean friendsOnlyUGC = */ dis.readBoolean();
        /* int clientUgcVersion = */ dis.readInt();
        byte difficulty = dis.readByte();
        /* int multiplayerInstanceId = */ dis.readInt();
        /* byte playerIndex = */ dis.readByte();
        /* int skinId = */ dis.readInt();
        /* int capeId = */ dis.readInt();
        /* boolean isGuest = */ dis.readBoolean();
        /* boolean newSeaLevel = */ dis.readBoolean();
        /* int uiGamePrivileges = */ dis.readInt();
        // _LARGE_WORLDS fields (xzSize short + hellScale byte) are not expected
        // unless the client was compiled with _LARGE_WORLDS.

        logger.info("Legacy client login attempt: " + playerName + " (protocol " + clientVersion + ") from " + socket.getRemoteSocketAddress());

        // ---- Phase 4: Validate username and access --------------------------
        if (!playerName.equals(StringUtils.stripControlCodes(playerName)))
        {
            sendDisconnect(dos, "Invalid username!");
            return;
        }

        String banReason = mcServer.func_71203_ab().func_72399_a(socket.getRemoteSocketAddress(), playerName);

        if (banReason != null)
        {
            sendDisconnect(dos, banReason);
            return;
        }

        // ---- Phase 5: Create and configure the player entity ----------------
        EntityPlayerMP playerEntity = mcServer.func_71203_ab().func_72366_a(playerName);

        if (playerEntity == null)
        {
            sendDisconnect(dos, "Failed to create player entity");
            return;
        }

        mcServer.func_71203_ab().readPlayerDataFromFile(playerEntity);
        WorldServer world = mcServer.getWorldManager(playerEntity.dimension);
        playerEntity.setWorld(world);
        playerEntity.theItemInWorldManager.setWorld(world);
        ChunkCoordinates spawnPoint = world.getSpawnPoint();
        mcServer.func_71203_ab().func_72381_a(playerEntity, (EntityPlayerMP) null, world);

        logger.info(playerName + "[" + socket.getRemoteSocketAddress() + "] logged in (Legacy) with entity id " + playerEntity.entityId);

        // ---- Phase 6: Send Legacy LoginPacket response (id = 1) -------------
        // This must happen before TcpConnection is created so both output paths
        // do not interleave bytes on the socket's output stream.
        WorldInfo worldInfo = world.getWorldInfo();
        sendLegacyLoginResponse(dos, playerEntity, world, worldInfo);
        dos.flush();

        // ---- Phase 7: Hand off to TcpConnection / NetServerHandler ----------
        // All handshake bytes have been consumed from 'inputStream'.  Constructing
        // TcpConnection with the same InputStream ensures in-game packets are
        // read from the correct position in the stream.
        // Pass the server's key pair so TcpConnection is fully initialised even
        // though Legacy clients do not use encryption.
        TcpConnection tcpConn = new TcpConnection(socket, inputStream, connectionId, null, mcServer.getKeyPair().getPrivate());
        NetServerHandler netHandler = new NetServerHandler(mcServer, tcpConn, playerEntity);
        tcpConn.setNetHandler(netHandler);

        // ---- Phase 8: Send remaining initial game state packets -------------
        netHandler.sendPacket(new Packet6SpawnPosition(spawnPoint.posX, spawnPoint.posY, spawnPoint.posZ));
        netHandler.sendPacket(new Packet202PlayerAbilities(playerEntity.capabilities));
        mcServer.func_71203_ab().updateTimeAndWeather(playerEntity, world);
        mcServer.func_71203_ab().sendPacketToAllPlayers(new Packet3Chat("\u00a7e" + playerName + " joined the game."));
        mcServer.func_71203_ab().playerLoggedIn(playerEntity);
        netHandler.teleportTo(playerEntity.posX, playerEntity.posY, playerEntity.posZ, playerEntity.rotationYaw, playerEntity.rotationPitch);

        // Register with the server so the connection is ticked each server cycle.
        mcServer.func_71212_ac().addPlayer(netHandler);

        netHandler.sendPacket(new Packet4UpdateTime(world.getWorldTime()));

        Iterator potionIter = playerEntity.getActivePotionEffects().iterator();

        while (potionIter.hasNext())
        {
            PotionEffect effect = (PotionEffect) potionIter.next();
            netHandler.sendPacket(new Packet41EntityEffect(playerEntity.entityId, effect));
        }

        playerEntity.func_71116_b();
    }

    // -------------------------------------------------------------------------
    // Helper: build and send the Legacy LoginPacket (S -> C)
    // -------------------------------------------------------------------------

    private void sendLegacyLoginResponse(DataOutputStream dos, EntityPlayerMP player, WorldServer world, WorldInfo worldInfo) throws IOException
    {
        dos.write(1);                                                  // packet id = 1
        dos.writeInt(LEGACY_PROTOCOL_VERSION);                         // clientVersion = 39
        writeString(player.username, dos);                             // username
        String terrainTypeName = (worldInfo.getTerrainType() != null) ? worldInfo.getTerrainType().getWorldTypeName() : "DEFAULT";
        writeString(terrainTypeName, dos);                             // levelTypeName
        dos.writeLong(worldInfo.getSeed());                            // world seed
        dos.writeInt(player.theItemInWorldManager.getGameType().getID()); // gameType
        dos.writeByte(0);                                              // dimension (overworld)
        dos.writeByte((byte) world.getHeight());                       // mapHeight
        dos.writeByte((byte) mcServer.func_71203_ab().getMaxPlayers()); // maxPlayers
        dos.writeLong(-1L);                                            // offlineXuid = INVALID
        dos.writeLong(-1L);                                            // onlineXuid  = INVALID
        dos.writeBoolean(false);                                       // friendsOnlyUGC
        dos.writeInt(0);                                               // ugcPlayersVersion
        dos.writeByte(world.difficultySetting);                        // difficulty
        dos.writeInt(player.entityId);                                 // multiplayerInstanceId
        dos.writeByte(0);                                              // playerIndex
        dos.writeInt(0);                                               // skinId
        dos.writeInt(0);                                               // capeId
        dos.writeBoolean(false);                                       // isGuest
        dos.writeBoolean(true);                                        // newSeaLevel
        dos.writeInt(-1);                                              // uiGamePrivileges (all)
        // _LARGE_WORLDS fields are omitted; standard world size is assumed.
    }

    // -------------------------------------------------------------------------
    // Helper: send a Legacy disconnect / kick packet (id = 255)
    // -------------------------------------------------------------------------

    private static void sendDisconnect(DataOutputStream dos, String reason) throws IOException
    {
        dos.write(255);           // packet id = 255 (KickDisconnect)
        writeString(reason, dos);
        dos.flush();
    }

    // -------------------------------------------------------------------------
    // String helpers (UTF-16 big-endian, same as Java Packet.readString / writeString)
    // -------------------------------------------------------------------------

    /**
     * Reads a UTF-16 big-endian string prefixed by a 16-bit character count.
     * This matches the format used by both the Legacy C++ {@code Packet::readUtf} /
     * {@code writeUtf} and the Java server's {@code Packet.readString} /
     * {@code writeString}.
     */
    static String readString(DataInputStream dis) throws IOException
    {
        short length = dis.readShort();
        char[] chars = new char[length];

        for (int i = 0; i < length; i++)
        {
            chars[i] = dis.readChar(); // reads 2 bytes (big-endian)
        }

        return new String(chars);
    }

    /**
     * Writes a UTF-16 big-endian string prefixed by a 16-bit character count.
     */
    static void writeString(String s, DataOutputStream dos) throws IOException
    {
        dos.writeShort(s.length()); // char count
        dos.writeChars(s);          // 2 bytes per character
    }
}
