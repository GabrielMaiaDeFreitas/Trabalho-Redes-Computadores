package blockchain;

import java.io.Serializable;
import java.util.ArrayList;

public class Message implements Serializable {

    private static final long serialVersionUID = 1L;

    private String type;
    private ArrayList<Block> blockchain;
    private ArrayList<String> peers;
    private String data;

    // Mensagem simples, sem dados adicionais
    public Message(String type) {
        this.type = type;
        this.blockchain = null;
        this.peers = null;
        this.data = null;
    }

    // Mensagem que transporta a blockchain
    public Message(String type, ArrayList<Block> blockchain) {
        this.type = type;
        this.blockchain = blockchain;
        this.peers = null;
        this.data = null;
    }

    // Mensagem que transporta a lista de nós
    public Message(String type, ArrayList<String> peers, boolean peerMessage) {
        this.type = type;
        this.blockchain = null;
        this.peers = peers;
        this.data = null;
    }

    // Mensagem que transporta o texto a ser minerado
    public Message(String type, String data) {
        this.type = type;
        this.blockchain = null;
        this.peers = null;
        this.data = data;
    }

    public String getType() {
        return type;
    }

    public ArrayList<Block> getBlockchain() {
        return blockchain;
    }

    public ArrayList<String> getPeers() {
        return peers;
    }

    public String getData() {
        return data;
    }
}
