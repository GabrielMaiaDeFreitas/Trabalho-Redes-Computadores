package blockchain.network;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import blockchain.core.Block;

public class NetworkMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    private final MessageType type;
    @SuppressWarnings("serial")
    private final Object payload;

    public NetworkMessage(MessageType type, Object payload) {
        this.type = type;
        this.payload = payload;
    }

    public NetworkMessage(MessageType type) {
        this(type, null);
    }

    public MessageType getType() {
        return type;
    }

    public Object getPayload() {
        return payload;
    }

    public String getPayloadAsString() {
        return payload instanceof String ? (String) payload : null;
    }

    public ArrayList<Block> getPayloadAsBlockchain() {
        if (!(payload instanceof List)) {
            return null;
        }

        ArrayList<Block> chain = new ArrayList<>();
        for (Object item : (List<?>) payload) {
            if (!(item instanceof Block)) {
                return null;
            }
            chain.add((Block) item);
        }
        return chain;
    }

    public ArrayList<String> getPayloadAsPeers() {
        if (!(payload instanceof List)) {
            return null;
        }

        ArrayList<String> peers = new ArrayList<>();
        for (Object item : (List<?>) payload) {
            if (!(item instanceof String)) {
                return null;
            }
            peers.add((String) item);
        }
        return peers;
    }

    public String describePayload() {
        if (payload == null) {
            return "null";
        }

        if (payload instanceof String) {
            return "\"" + payload + "\"";
        }

        ArrayList<Block> chain = type == MessageType.BLOCKCHAIN_COPY
            ? getPayloadAsBlockchain() : null;

        if (chain != null) {
            String top = chain.isEmpty() || chain.get(chain.size() - 1).hash == null
                ? "-" : chain.get(chain.size() - 1).hash.substring(0, 12) + "...";
            return "ArrayList<Block> com " + chain.size()
                + " blocos (topo: " + top + ")";
        }

        return payload.toString();
    }

    @Override
    public String toString() {
        return type + (payload instanceof String ? " (" + payload + ")" : "");
    }
}
