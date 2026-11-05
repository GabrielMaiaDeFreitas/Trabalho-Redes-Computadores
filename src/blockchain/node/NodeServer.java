package blockchain.node;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import blockchain.core.Block;
import blockchain.core.NoobChain;
import blockchain.network.MessageType;
import blockchain.network.NetworkConfig;
import blockchain.network.NetworkMessage;
import blockchain.network.NodeNetwork;
import blockchain.network.PeerConnection;

public class NodeServer {

    private static final int IDLE_TIMEOUT_MS = 10 * 60 * 1000;

    private static final Pattern NODE_ID = Pattern.compile("Node-(\\d{1,5})");

    private static final ExecutorService connectionPool =
        Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable);
            thread.setDaemon(true);
            return thread;
        });

    private static String seedAddress =
        NetworkConfig.SEED_HOST + ":" + NetworkConfig.SEED_PORT;

    private static PeerConnection current;

    public static void main(String[] args) {
        int port = NetworkConfig.SEED_PORT;

        if (args.length > 0) {
            port = Integer.parseInt(args[0]);
        }

        NodeNetwork.setOwnPort(port);

        boolean seed = args.length > 1 && "SEED".equalsIgnoreCase(args[1]);

        if (!seed && args.length > 1) {
            seedAddress = args[1];
        }

        if (seed) {
            NoobChain.initializeBlockchain();
            System.out.println("Tentando minerar o bloco 4...");
            NoobChain.mineNewBlock("Sou o quarto bloco");
        } else {
            NoobChain.clear();
        }

        ServerSocket serverSocket;

        try {
            serverSocket = new ServerSocket(port);
        } catch (IOException e) {
            System.out.println(
                "Erro ao iniciar o servidor na porta " + port
                + ": " + e.getMessage()
            );
            return;
        }

        System.out.println(
            "Nó " + NodeNetwork.getNodeId()
            + " aguardando conexões na porta " + port
            + (seed ? " (SEED)" : " | seed: " + seedAddress)
        );

        Thread acceptThread = new Thread(
            () -> acceptLoop(serverSocket), "accept-" + port
        );
        acceptThread.start();

        runConsole();

        try {
            acceptThread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void acceptLoop(ServerSocket serverSocket) {
        while (!serverSocket.isClosed()) {
            try {
                Socket socket = serverSocket.accept();
                connectionPool.submit(() -> handleConnection(socket));
            } catch (IOException e) {
                if (!serverSocket.isClosed()) {
                    System.out.println(
                        "Erro ao aceitar conexão: " + e.getMessage()
                    );
                }
            }
        }
    }

    private static void handleConnection(Socket socket) {
        String peer = socket.getInetAddress().getHostAddress()
            + ":" + socket.getPort();

        System.out.println("Conexão TCP recebida de " + peer);

        try (Socket s = socket) {
            s.setSoTimeout(IDLE_TIMEOUT_MS);

            ObjectOutputStream out =
                new ObjectOutputStream(s.getOutputStream());
            out.flush();
            ObjectInputStream in =
                new ObjectInputStream(s.getInputStream());

            boolean handshakeDone = false;

            while (true) {
                Object object;

                try {
                    object = in.readObject();
                } catch (EOFException e) {
                    System.out.println("Conexão com " + peer + " encerrada.");
                    break;
                } catch (SocketTimeoutException e) {
                    System.out.println(
                        "Conexão de " + peer + " encerrada por inatividade."
                    );
                    break;
                }

                if (!(object instanceof NetworkMessage)) {
                    sendReject(out, peer, "Objeto recebido não é uma mensagem BCP.");
                    break;
                }

                NetworkMessage request = (NetworkMessage) object;

                if (request.getType() == null) {
                    sendReject(out, peer, "Tipo de mensagem ausente.");
                    break;
                }

                PeerConnection.log(false, peer, request);

                if (!handshakeDone) {
                    if (request.getType() != MessageType.HELLO) {
                        sendReject(out, peer,
                            "Handshake HELLO obrigatório antes de "
                            + request.getType() + "."
                        );
                        break;
                    }

                    String identified = handleHello(request, out, s, peer);

                    if (identified == null) {
                        break;
                    }

                    peer = identified;
                    handshakeDone = true;
                    continue;
                }

                handleMessage(request, out, peer);
            }

        } catch (Exception e) {
            System.out.println(
                "Erro ao processar conexão de " + peer + ": "
                + e.getMessage()
            );
        }
    }

    private static String handleHello(
            NetworkMessage request, ObjectOutputStream out,
            Socket socket, String peer) throws IOException {

        String hello = request.getPayloadAsString();
        String prefix = PeerConnection.PROTOCOL_VERSION + "-";

        if (hello == null || hello.trim().isEmpty()) {
            sendReject(out, peer, "Identificação do nó ausente no HELLO.");
            return null;
        }

        if (!hello.startsWith(prefix) || hello.length() == prefix.length()) {
            sendReject(out, peer,
                "Versão de protocolo incompatível: \"" + hello
                + "\". Esperado: " + prefix + "<id do nó>."
            );
            return null;
        }

        String nodeId = hello.substring(prefix.length());
        String listenAddress = null;

        Matcher matcher = NODE_ID.matcher(nodeId);
        if (matcher.matches()) {
            listenAddress = socket.getInetAddress().getHostAddress()
                + ":" + matcher.group(1);
        }

        send(out, peer, new NetworkMessage(
            MessageType.ACK,
            PeerConnection.PROTOCOL_VERSION + "-" + NodeNetwork.getNodeId()
        ));

        System.out.println(
            "Handshake confirmado com " + nodeId
            + (listenAddress == null ? "" : " (escuta em " + listenAddress + ")")
        );

        if (listenAddress != null) {
            NodeNetwork.addPeer(listenAddress);
            return listenAddress;
        }

        return nodeId;
    }

    private static void handleMessage(
            NetworkMessage request, ObjectOutputStream out, String peer)
            throws IOException {

        switch (request.getType()) {

            case HELLO:
                send(out, peer, new NetworkMessage(
                    MessageType.ACK,
                    PeerConnection.PROTOCOL_VERSION + "-"
                    + NodeNetwork.getNodeId()
                ));
                break;

            case GET_PEERS: {
                ArrayList<String> others = NodeNetwork.getPeers();
                others.remove(peer);
                send(out, peer, new NetworkMessage(
                    MessageType.PEERS_LIST, others
                ));
                System.out.println(
                    "[Fluxo A - passo 5] PEERS_LIST enviada a " + peer
                    + " com " + others.size() + " endereço(s)."
                );
                break;
            }

            case GET_BLOCKCHAIN: {
                ArrayList<Block> copy = NoobChain.getBlockchain();
                send(out, peer, new NetworkMessage(
                    MessageType.BLOCKCHAIN_COPY, copy
                ));
                System.out.println(
                    "[Fluxo B - passo 2] BLOCKCHAIN_COPY enviada a " + peer
                    + " (" + copy.size() + " blocos)."
                );
                break;
            }

            case BLOCKCHAIN_COPY: {
                ArrayList<Block> received = request.getPayloadAsBlockchain();
                validateAndAdopt("Fluxo C - passo 3", peer, received, null, out);
                break;
            }

            case REJECT:
            case ACK:
                break;

            default:
                sendReject(out, peer, "Mensagem " + request.getType()
                    + " não é esperada pelo servidor.");
        }
    }

    private static void validateAndAdopt(
            String step, String peer, ArrayList<Block> received,
            PeerConnection connection, ObjectOutputStream out)
            throws IOException {

        int localSize = NoobChain.size();
        boolean valid = NoobChain.isChainValid(received);
        int receivedSize = received == null ? 0 : received.size();

        System.out.println(
            "[" + step + "] Validação isChainValid() da cadeia de " + peer
            + ": " + (valid ? "válida" : "INVÁLIDA")
        );

        if (valid) {
            System.out.println(
                "[" + step + "] Regra de consenso: recebida " + receivedSize
                + " blocos " + (receivedSize > localSize ? ">" : "<=")
                + " local " + localSize + " blocos"
            );
        }

        NoobChain.AdoptResult result = NoobChain.tryAdopt(received);

        NetworkMessage reply;

        if (result == NoobChain.AdoptResult.ADOPTED) {
            System.out.println(
                "[" + step + "] Cadeia local substituída pela recebida ("
                + NoobChain.size() + " blocos)."
            );
            reply = new NetworkMessage(MessageType.ACK, result.getReason());
        } else {
            System.out.println(
                "[" + step + "] Cadeia descartada: " + result.getReason()
            );
            reply = new NetworkMessage(MessageType.REJECT, result.getReason());
        }

        if (connection != null) {
            connection.send(reply);
        } else {
            send(out, peer, reply);
        }
    }

    private static void send(
            ObjectOutputStream out, String peer, NetworkMessage message)
            throws IOException {
        PeerConnection.log(true, peer, message);
        out.writeObject(message);
        out.reset();
        out.flush();
    }

    private static void sendReject(
            ObjectOutputStream out, String peer, String reason)
            throws IOException {
        send(out, peer, new NetworkMessage(MessageType.REJECT, reason));
    }

    private static void cmdConnect(String address) {
        if (address.isEmpty()) {
            address = seedAddress;
        }

        closeCurrent();

        try {
            current = PeerConnection.connect(address);
            System.out.println(
                "[Fluxo A - passo 1] Conexão TCP estabelecida com " + address
                + " (porta local " + current.getLocalPort() + ")."
                + " Próximo passo: hello"
            );
        } catch (Exception e) {
            current = null;
            System.out.println(
                "[Fluxo A - passo 1] Falha ao conectar a " + address
                + ": " + e.getMessage()
            );
        }
    }

    private static void cmdHello() {
        if (!requireConnection()) {
            return;
        }

        try {
            System.out.println("[Fluxo A - passo 2] Enviando HELLO...");
            current.hello(NodeNetwork.getNodeId());
            System.out.println(
                "[Fluxo A - passo 3] Handshake confirmado por "
                + current.getRemoteNode() + ". Próximo passo: get_peers"
            );
            NodeNetwork.addPeer(current.getAddress());
        } catch (Exception e) {
            connectionFailed("[Fluxo A - passo 3]", e);
        }
    }

    private static void cmdGetPeers() {
        if (!requireConnection()) {
            return;
        }

        try {
            System.out.println("[Fluxo A - passo 4] Enviando GET_PEERS...");
            NetworkMessage response = current.request(
                new NetworkMessage(MessageType.GET_PEERS)
            );

            if (response.getType() != MessageType.PEERS_LIST) {
                System.out.println(
                    "[Fluxo A - passo 5] Resposta diferente de PEERS_LIST: "
                    + response.getType()
                );
                checkClosedAfterReject(response);
                return;
            }

            ArrayList<String> received = response.getPayloadAsPeers();

            if (received == null) {
                System.out.println(
                    "[Fluxo A - passo 5] PEERS_LIST com conteúdo inválido."
                );
                return;
            }

            System.out.println(
                "[Fluxo A - passo 5] PEERS_LIST recebida com "
                + received.size() + " endereço(s): " + received
            );

            int before = NodeNetwork.getPeers().size();
            for (String peer : received) {
                NodeNetwork.addPeer(peer);
            }
            int added = NodeNetwork.getPeers().size() - before;

            System.out.println(
                "[Fluxo A - passo 6] " + added + " endereço(s) novo(s)"
                + " adicionado(s). Lista de descoberta: "
                + NodeNetwork.getPeers()
            );

        } catch (Exception e) {
            connectionFailed("[Fluxo A - passo 5]", e);
        }
    }

    private static void cmdGetBlockchain() {
        if (!requireConnection()) {
            return;
        }

        try {
            System.out.println("[Fluxo B - passo 1] Enviando GET_BLOCKCHAIN...");
            NetworkMessage response = current.request(
                new NetworkMessage(MessageType.GET_BLOCKCHAIN)
            );

            if (response.getType() != MessageType.BLOCKCHAIN_COPY) {
                System.out.println(
                    "[Fluxo B - passo 2] Resposta diferente de BLOCKCHAIN_COPY: "
                    + response.getType()
                );
                checkClosedAfterReject(response);
                return;
            }

            ArrayList<Block> received = response.getPayloadAsBlockchain();

            System.out.println(
                "[Fluxo B - passo 2] BLOCKCHAIN_COPY recebida de "
                + current.getAddress() + " ("
                + (received == null ? 0 : received.size()) + " blocos)."
            );

            validateAndAdopt(
                "Fluxo B - passo 3", current.getAddress(), received, current, null
            );

        } catch (Exception e) {
            connectionFailed("[Fluxo B]", e);
        }
    }

    private static void cmdMine(String data) {
        if (data.isEmpty()) {
            System.out.println("Uso: minerar <dados do bloco>");
            return;
        }

        if (NoobChain.mineNewBlock(data)) {
            System.out.println(
                "[Fluxo C - passo 1] Proof of Work concluído e bloco inserido"
                + " na lista local (" + NoobChain.size() + " blocos)."
                + " Próximo passo: broadcast"
            );
        }
    }

    private static void cmdBroadcast() {
        ArrayList<Block> copy = NoobChain.getBlockchain();
        ArrayList<String> peers = NodeNetwork.getPeers();

        if (copy.isEmpty()) {
            System.out.println("Cadeia local vazia: nada para transmitir.");
            return;
        }

        if (peers.isEmpty()) {
            System.out.println(
                "Lista de peers vazia: faça o Fluxo A antes (conectar, hello, get_peers)."
            );
            return;
        }

        System.out.println(
            "[Fluxo C - passo 2] Transmitindo BLOCKCHAIN_COPY ("
            + copy.size() + " blocos) para " + peers.size() + " peer(s): "
            + peers
        );

        for (String peer : peers) {
            if (NodeNetwork.isSelf(peer)) {
                continue;
            }

            try (PeerConnection connection =
                    PeerConnection.open(peer, NodeNetwork.getNodeId())) {

                NetworkMessage response = connection.request(
                    new NetworkMessage(MessageType.BLOCKCHAIN_COPY, copy)
                );

                System.out.println(
                    "[Fluxo C - passo 3] " + peer + " respondeu "
                    + response.getType()
                    + (response.getPayloadAsString() == null
                        ? "" : ": " + response.getPayloadAsString())
                );

            } catch (Exception e) {
                System.out.println(
                    "[Fluxo C - passo 2] Erro ao enviar para "
                    + peer + ": " + e.getMessage()
                );
            }
        }
    }

    private static void cmdDisconnect() {
        if (current == null) {
            System.out.println("Nenhuma conexão aberta.");
            return;
        }

        String address = current.getAddress();
        closeCurrent();
        System.out.println("Conexão com " + address + " encerrada.");
    }

    private static boolean requireConnection() {
        if (current == null) {
            System.out.println(
                "Nenhuma conexão aberta. Use primeiro: conectar [ip:porta]"
            );
            return false;
        }
        return true;
    }

    private static void checkClosedAfterReject(NetworkMessage response) {
        if (response.getType() == MessageType.REJECT
                && current != null && !current.isHandshakeDone()) {
            System.out.println(
                "O peer encerrou a conexão. Recomece com: conectar e hello"
            );
            closeCurrent();
        }
    }

    private static void connectionFailed(String step, Exception e) {
        System.out.println(step + " Falha: " + e.getMessage());
        System.out.println("Conexão encerrada. Recomece com: conectar");
        closeCurrent();
    }

    private static void closeCurrent() {
        if (current != null) {
            current.close();
            current = null;
        }
    }

    private static void runConsole() {
        printHelp();

        BufferedReader reader =
            new BufferedReader(new InputStreamReader(System.in));

        while (true) {
            String line;

            try {
                line = reader.readLine();
            } catch (IOException e) {
                return;
            }

            if (line == null) {
                return;
            }

            line = line.trim();

            if (line.isEmpty()) {
                continue;
            }

            String[] parts = line.split("\\s+", 2);
            String command = parts[0].toLowerCase();
            String argument = parts.length > 1 ? parts[1].trim() : "";

            switch (command) {
                case "conectar":
                    cmdConnect(argument);
                    break;

                case "hello":
                    cmdHello();
                    break;

                case "get_peers":
                case "getpeers":
                    cmdGetPeers();
                    break;

                case "get_blockchain":
                case "getblockchain":
                    cmdGetBlockchain();
                    break;

                case "desconectar":
                    cmdDisconnect();
                    break;

                case "minerar":
                    cmdMine(argument);
                    break;

                case "broadcast":
                    cmdBroadcast();
                    break;

                case "cadeia":
                    printChain();
                    break;

                case "peers":
                    ArrayList<String> peers = NodeNetwork.getPeers();
                    System.out.println("Pares conhecidos (" + peers.size() + "):");
                    for (String peer : peers) {
                        System.out.println("- " + peer);
                    }
                    break;

                case "ajuda":
                    printHelp();
                    break;

                case "sair":
                    System.out.println("Encerrando o nó.");
                    closeCurrent();
                    System.exit(0);
                    break;

                default:
                    System.out.println(
                        "Comando desconhecido: " + command
                        + " (digite \"ajuda\")"
                    );
            }
        }
    }

    private static void printChain() {
        ArrayList<Block> chain = NoobChain.getBlockchain();

        System.out.println(
            "Blockchain local: " + chain.size() + " blocos"
            + " | válida: " + NoobChain.isChainValid(chain)
        );

        for (int i = 0; i < chain.size(); i++) {
            Block block = chain.get(i);
            System.out.println(
                "Bloco " + i
                + " | Hash: " + block.hash
                + " | Previous hash: " + block.previousHash
            );
        }
    }

    private static void printHelp() {
        System.out.println(
            "\nComandos:\n"
            + "  Fluxo A: conectar [ip:porta] | hello | get_peers\n"
            + "  Fluxo B: get_blockchain   (usa a conexão aberta)\n"
            + "  Fluxo C: minerar <dados> | broadcast\n"
            + "  Outros : peers | cadeia | desconectar | ajuda | sair\n"
            + "  (sem ip:porta, \"conectar\" usa o seed: " + seedAddress + ")\n"
        );
    }
}
