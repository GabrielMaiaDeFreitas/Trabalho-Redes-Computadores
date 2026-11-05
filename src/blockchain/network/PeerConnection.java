package blockchain.network;

import java.io.Closeable;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

public class PeerConnection implements Closeable {

    public static final String PROTOCOL_VERSION = "V1.0";

    public static final int CONNECT_TIMEOUT_MS = 3000;

    public static final int READ_TIMEOUT_MS = 10000;

    private final String address;
    private final Socket socket;
    private final ObjectOutputStream out;
    private final ObjectInputStream in;
    private String remoteNode;
    private boolean handshakeDone = false;

    private PeerConnection(String address, Socket socket) throws IOException {
        this.address = address;
        this.socket = socket;
        this.out = new ObjectOutputStream(socket.getOutputStream());
        this.out.flush();
        this.in = new ObjectInputStream(socket.getInputStream());
    }

    public static PeerConnection open(String address, String nodeId)
            throws IOException {

        PeerConnection connection = connect(address);

        try {
            connection.hello(nodeId);
            return connection;
        } catch (IOException e) {
            connection.close();
            throw e;
        }
    }

    public static PeerConnection connect(String address) throws IOException {

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

    public void hello(String nodeId) throws IOException {
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
        handshakeDone = true;
    }

    public boolean isHandshakeDone() {
        return handshakeDone;
    }

    public int getLocalPort() {
        return socket.getLocalPort();
    }

    public static void log(boolean sent, String peer, NetworkMessage message) {
        System.out.println(
            "[BCP] " + (sent ? "enviado  -> " : "recebido <- ") + peer
            + " | " + message.getType()
            + " | " + message.describePayload()
        );
    }

    public void send(NetworkMessage message) throws IOException {
        log(true, address, message);
        out.writeObject(message);
        out.reset();
        out.flush();
    }

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

    public NetworkMessage request(NetworkMessage message) throws IOException {
        send(message);
        return receive();
    }

    public String getLocalHost() {
        return socket.getLocalAddress().getHostAddress();
    }

    public String getAddress() {
        return address;
    }

    public String getRemoteNode() {
        return remoteNode;
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
