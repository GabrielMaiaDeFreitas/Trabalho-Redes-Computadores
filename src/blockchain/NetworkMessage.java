package blockchain;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Pacote do protocolo BCP (seção 4.1 da especificação).
 * Toda mensagem trafegada na rede é uma instância desta classe.
 */
public class NetworkMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    private final MessageType type;
    // Tipo Object conforme a especificação; na prática é sempre String,
    // ArrayList<String>, ArrayList<Block> ou null (todos serializáveis).
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

    // Devolve o payload como texto (HELLO, REJECT, ACK) ou null.
    public String getPayloadAsString() {
        return payload instanceof String ? (String) payload : null;
    }

    // Devolve o payload como blockchain (BLOCKCHAIN_COPY) ou null se o
    // conteúdo não for uma lista de blocos.
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

    // Devolve o payload como lista de peers (PEERS_LIST) ou null se o
    // conteúdo não for uma lista de strings.
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

    // Resumo do payload para o log do protocolo.
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
