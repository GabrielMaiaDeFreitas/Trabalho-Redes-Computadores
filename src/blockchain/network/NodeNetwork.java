package blockchain.network;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.List;

public class NodeNetwork {

    private static final List<String> peers = new ArrayList<>();

    private static volatile int ownPort = -1;

    private static volatile String nodeId = "Client";

    public static void setOwnPort(int port) {
        ownPort = port;
        nodeId = "Node-" + port;
    }

    public static String getNodeId() {
        return nodeId;
    }

    public static void addPeer(String peer) {
        if (peer == null || peer.trim().isEmpty()) {
            return;
        }

        peer = peer.trim();

        try {
            parseAddress(peer);
        } catch (IllegalArgumentException e) {
            System.out.println(e.getMessage());
            return;
        }

        if (isSelf(peer)) {
            return;
        }

        synchronized (peers) {
            if (!peers.contains(peer)) {
                peers.add(peer);
                System.out.println("Novo par conhecido: " + peer);
            }
        }
    }

    public static ArrayList<String> getPeers() {
        synchronized (peers) {
            return new ArrayList<>(peers);
        }
    }

    public static String[] parseAddress(String address) {
        if (address == null) {
            throw new IllegalArgumentException("Endereço de par ausente.");
        }

        String[] parts = address.trim().split(":");

        if (parts.length != 2 || parts[0].trim().isEmpty()) {
            throw new IllegalArgumentException(
                "Endereço de par inválido: " + address
            );
        }

        try {
            int port = Integer.parseInt(parts[1].trim());
            if (port < 1 || port > 65535) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                "Porta inválida no endereço: " + address
            );
        }

        return new String[] { parts[0].trim(), parts[1].trim() };
    }

    public static boolean isSelf(String address) {
        if (ownPort < 0) {
            return false;
        }

        try {
            String[] parts = parseAddress(address);

            if (Integer.parseInt(parts[1]) != ownPort) {
                return false;
            }

            InetAddress host = InetAddress.getByName(parts[0]);
            return host.isLoopbackAddress()
                || host.isAnyLocalAddress()
                || NetworkInterface.getByInetAddress(host) != null;

        } catch (Exception e) {
            return false;
        }
    }
}
