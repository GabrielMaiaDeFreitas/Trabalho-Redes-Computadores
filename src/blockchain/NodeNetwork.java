
package blockchain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class NodeNetwork {

    private static final List<String> peers =
        Collections.synchronizedList(new ArrayList<>());

    public static void addPeer(String peer) {
        if (peer == null || peer.trim().isEmpty()) {
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
}
