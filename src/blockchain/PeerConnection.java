package blockchain;

import java.io.Closeable;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Lado cliente de uma conexão BCP.
 *
 * Abre o socket TCP com timeout, faz o handshake HELLO/ACK e mantém a
 * conexão aberta para as próximas mensagens (Fluxo A da especificação).
 */
public class PeerConnection implements Closeable {

    public static final String PROTOCOL_VERSION = "V1.0";

    // Tempo máximo para estabelecer a conexão TCP.
    public static final int CONNECT_TIMEOUT_MS = 3000;

    // Tempo máximo esperando uma resposta.
    public static final int READ_TIMEOUT_MS = 10000;

    private final String address;
    private final Socket socket;
    private final ObjectOutputStream out;
    private final ObjectInputStream in;
    private String remoteNode;

    private PeerConnection(String address, Socket socket) throws IOException {
        this.address = address;
        this.socket = socket;
        this.out = new ObjectOutputStream(socket.getOutputStream());
        this.out.flush();
        this.in = new ObjectInputStream(socket.getInputStream());
    }

    /**
     * Conecta a "host:porta" e faz o handshake.
     * Lança IOException se a conexão falhar ou o HELLO for rejeitado.
     */
    public static PeerConnection open(String address, String nodeId)
            throws IOException {

        String[] parts = NodeNetwork.parseAddress(address);
        String host = parts[0];
        int port = Integer.parseInt(parts[1]);

        Socket socket = new Socket();
        PeerConnection connection = null;

        try {
            socket.connect(
                new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS
            );
            socket.setSoTimeout(READ_TIMEOUT_MS);

            connection = new PeerConnection(address, socket);
            connection.handshake(nodeId);
            return connection;

        } catch (IOException e) {
            if (connection != null) {
                connection.close();
            } else {
                socket.close();
            }
            throw e;
        }
    }

    private void handshake(String nodeId) throws IOException {
        NetworkMessage response = request(new NetworkMessage(
            MessageType.HELLO, PROTOCOL_VERSION + "-" + nodeId
        ));

        if (response.getType() != MessageType.ACK) {
            throw new IOException(
                "Handshake recusado por " + address + ": "
                + response.getPayloadAsString()
            );
        }

        remoteNode = response.getPayloadAsString();
    }

    /**
     * Imprime uma mensagem do protocolo no formato:
     *   [BCP] enviado  -> <peer> | <TIPO> | <payload>
     *   [BCP] recebido <- <peer> | <TIPO> | <payload>
     * Usado tanto pelo lado cliente (esta classe) quanto pelo servidor.
     */
    public static void log(boolean sent, String peer, NetworkMessage message) {
        System.out.println(
            "[BCP] " + (sent ? "enviado  -> " : "recebido <- ") + peer
            + " | " + message.getType()
            + " | " + message.describePayload()
        );
    }

    // Envia uma mensagem sem esperar resposta (ex.: REJECT, ACK).
    public void send(NetworkMessage message) throws IOException {
        log(true, address, message);
        out.writeObject(message);
        // reset() impede que o stream reaproveite referências de objetos
        // já enviados nesta conexão (o que enviaria dados desatualizados).
        out.reset();
        out.flush();
    }

    // Lê a próxima mensagem do peer.
    public NetworkMessage receive() throws IOException {
        try {
            Object object = in.readObject();

            if (!(object instanceof NetworkMessage)) {
                throw new IOException(
                    "Objeto recebido de " + address
                    + " não é uma NetworkMessage."
                );
            }

            NetworkMessage message = (NetworkMessage) object;

            if (message.getType() == null) {
                throw new IOException(
                    "Mensagem sem tipo recebida de " + address
                );
            }

            log(false, address, message);
            return message;

        } catch (ClassNotFoundException e) {
            throw new IOException("Classe desconhecida recebida: "
                + e.getMessage(), e);
        }
    }

    // Envia uma mensagem e espera a resposta.
    public NetworkMessage request(NetworkMessage message) throws IOException {
        send(message);
        return receive();
    }

    // Endereço IP local usado nesta conexão (o IP do nó na rede).
    public String getLocalHost() {
        return socket.getLocalAddress().getHostAddress();
    }

    public String getAddress() {
        return address;
    }

    // Identificação devolvida pelo peer no ACK do handshake.
    public String getRemoteNode() {
        return remoteNode;
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Nada a fazer: a conexão já está sendo encerrada.
        }
    }
}
