package net.minecraft.src;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.util.logging.Logger;
import net.minecraft.server.MinecraftServer;

/**
 * Mirrors the LAN-advertising half of WinsockNetLayer (Windows64/Network/WinsockNetLayer.cpp)
 * so that a running Java server is discoverable by the Legacy Console Edition Win64 client.
 *
 * The C++ client (WinsockNetLayer::StartDiscovery / DiscoveryThreadProc) binds a UDP socket
 * to 0.0.0.0:25566 and waits for Win64LANBroadcast datagrams.  On each received packet it:
 *   1. Checks recvLen >= sizeof(Win64LANBroadcast) (84 bytes).
 *   2. Rejects the packet if broadcast->magic != WIN64_LAN_BROADCAST_MAGIC (0x4D434C4E).
 *   3. Adds or refreshes a Win64LANSession keyed by (senderIP, gamePort).
 *   4. Expires sessions not seen for > 5000 ms.
 *
 * Win64LANBroadcast struct layout (#pragma pack(push,1), all multi-byte fields little-endian
 * because the C++ code runs on x86 Windows):
 *
 *   Offset  Size  Field               Value / source
 *   ------  ----  ------------------  ------------------------------------------
 *      0      4   DWORD magic         WIN64_LAN_BROADCAST_MAGIC = 0x4D434C4E
 *      4      2   WORD  netVersion    VER_NETWORK = 495  (LEGACY_NETCODE_VERSION)
 *      6      2   WORD  gamePort      TCP port the server is listening on
 *      8     64   wchar_t hostName[32] server MOTD, UTF-16LE, zero-padded to 32 chars
 *     72      1   BYTE  playerCount   current online player count
 *     73      1   BYTE  maxPlayers    configured max players
 *     74      4   DWORD gameHostSettings  0 (no special host settings)
 *     78      4   DWORD texturePackParentId  0 (no texture pack)
 *     82      1   BYTE  subTexturePackId  0
 *     83      1   BYTE  isJoinable    1 (server is always joinable when running)
 *   Total: 84 bytes
 *
 * The server sends this packet to 255.255.255.255:25566 once per second, exactly mirroring
 * WinsockNetLayer::AdvertiseThreadProc's Sleep(1000) loop.
 */
public class LegacyLANBroadcastThread extends Thread
{
    private static final Logger logger = Logger.getLogger("Minecraft");

    /** WIN64_LAN_BROADCAST_MAGIC = 0x4D434C4E  ("MCLN" in ASCII). */
    static final int BROADCAST_MAGIC = 0x4D434C4E;

    /**
     * VER_NETWORK (BuildVer.h) = MINECRAFT_NET_VERSION = 495.
     * Must match LEGACY_NETCODE_VERSION checked in LegacyNetLoginHandler and
     * the value in Win64LANBroadcast.netVersion that the discovery listener reads.
     */
    static final short NET_VERSION = 495;

    /** WIN64_LAN_DISCOVERY_PORT: the UDP port the C++ discovery listener binds to. */
    static final int DISCOVERY_PORT = 25566;

    /** sizeof(Win64LANBroadcast) with #pragma pack(push,1). */
    static final int PACKET_SIZE = 84;

    /** Matches Sleep(1000) in AdvertiseThreadProc. */
    private static final long BROADCAST_INTERVAL_MS = 1000L;

    private final MinecraftServer server;
    private volatile boolean running = true;

    public LegacyLANBroadcastThread(MinecraftServer server)
    {
        super("Legacy LAN Broadcast Thread");
        this.server = server;
        setDaemon(true);
    }

    /** Gracefully stop the broadcast. The thread exits within one interval. */
    public void stopBroadcast()
    {
        running = false;
        interrupt();
    }

    @Override
    public void run()
    {
        logger.info("Legacy LAN: broadcasting on UDP port " + DISCOVERY_PORT);
        DatagramSocket sock = null;
        try
        {
            sock = new DatagramSocket();
            sock.setBroadcast(true);
            // Mirrors: broadcastAddr.sin_addr.s_addr = INADDR_BROADCAST
            InetAddress broadcastAddr = InetAddress.getByName("255.255.255.255");

            while (running)
            {
                try
                {
                    byte[] payload = buildPacket();
                    sock.send(new DatagramPacket(payload, payload.length, broadcastAddr, DISCOVERY_PORT));
                }
                catch (IOException e)
                {
                    if (running)
                    {
                        logger.warning("Legacy LAN: broadcast send failed: " + e.getMessage());
                    }
                }

                // Mirrors: Sleep(1000) in AdvertiseThreadProc
                try
                {
                    Thread.sleep(BROADCAST_INTERVAL_MS);
                }
                catch (InterruptedException e)
                {
                    break;
                }
            }
        }
        catch (IOException e)
        {
            logger.warning("Legacy LAN: socket error: " + e.getMessage());
        }
        finally
        {
            if (sock != null)
            {
                sock.close();
            }
        }
        logger.info("Legacy LAN: broadcast stopped.");
    }

    /**
     * Builds the 84-byte Win64LANBroadcast packet in LITTLE_ENDIAN byte order,
     * exactly matching the Win64LANBroadcast struct as it appears in memory on
     * a Windows x86 host.
     */
    byte[] buildPacket()
    {
        ByteBuffer buf = ByteBuffer.allocate(PACKET_SIZE);
        buf.order(ByteOrder.LITTLE_ENDIAN);  // Windows x86 is little-endian

        // DWORD magic = WIN64_LAN_BROADCAST_MAGIC
        buf.putInt(BROADCAST_MAGIC);

        // WORD netVersion = VER_NETWORK = 495
        buf.putShort(NET_VERSION);

        // WORD gamePort
        buf.putShort((short) (server.getServerPort() & 0xFFFF));

        // wchar_t hostName[32]  -- 32 UTF-16LE code units = 64 bytes, zero-padded
        // Mirrors: wcsncpy_s(s_advertiseData.hostName, 32, hostName, _TRUNCATE)
        String motd = server.getMotd();
        byte[] nameUtf16le = motd.getBytes(Charset.forName("UTF-16LE"));
        // cap at 32 wchar_t (64 bytes); keep surrogate pairs intact by rounding down to even
        int copyLen = Math.min(nameUtf16le.length, 64) & ~1;
        byte[] hostNameField = new byte[64];  // zero-initialised
        System.arraycopy(nameUtf16le, 0, hostNameField, 0, copyLen);
        buf.put(hostNameField);

        // BYTE playerCount  (func_71203_ab() returns the ServerConfigurationManager)
        ServerConfigurationManager scm = server.func_71203_ab();
        buf.put((byte) (scm != null ? scm.playersOnline() : 0));

        // BYTE maxPlayers
        buf.put((byte) server.getMaxPlayers());

        // DWORD gameHostSettings = 0
        buf.putInt(0);

        // DWORD texturePackParentId = 0
        buf.putInt(0);

        // BYTE subTexturePackId = 0
        buf.put((byte) 0);

        // BYTE isJoinable = 1
        // Mirrors UpdateAdvertiseJoinable(true): the dedicated server is always joinable.
        buf.put((byte) 1);

        return buf.array();
    }
}
