package blockchain;

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

/**
 * Nó "servent" do protocolo BCP: servidor e cliente ao mesmo tempo.
 *
 * Uso:
 *   NodeServer <porta> SEED                      -> nó semente (cria a cadeia)
 *   NodeServer <porta> [<seedHost:porta>] [peers...]
 *
 * Sem endereço de seed, usa o seed gravado em NetworkConfig.
 * Depois de iniciar, o terminal aceita comandos (digite "ajuda").
 *
 * Fluxos da especificação:
 *   Fluxo A (bootstrap)    -> bootstrap(), executado ao iniciar o nó.
 *   Fluxo B (sincronização)-> synchronizeWithPeers(), ao iniciar e no comando "sync".
 *   Fluxo C (novo bloco)   -> broadcastBlockchain(), após o comando "minerar".
 *
 * Toda mensagem enviada ou recebida é impressa com o prefixo [BCP].
 */
public class NodeServer {

    // Tempo máximo que uma conexão de entrada pode ficar sem mensagens.
    private static final int IDLE_TIMEOUT_MS = 30000;

    // ID enviado no HELLO pelos nós: "Node-<porta de escuta>".
    private static final Pattern NODE_ID = Pattern.compile("Node-(\\d{1,5})");

    // Uma thread por conexão recebida.
    private static final ExecutorService connectionPool =
        Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable);
            thread.setDaemon(true);
            return thread;
        });

    public static void main(String[] args) {
        int port = NetworkConfig.SEED_PORT;

        if (args.length > 0) {
            port = Integer.parseInt(args[0]);
        }

        final int serverPort = port;
        NodeNetwork.setOwnPort(serverPort);

        boolean seed = args.length > 1 && "SEED".equalsIgnoreCase(args[1]);

        // Endereço do seed: argumento ou o valor gravado em NetworkConfig.
        String seedAddress = null;

        if (!seed) {
            seedAddress = args.length > 1
                ? args[1]
                : NetworkConfig.SEED_HOST + ":" + NetworkConfig.SEED_PORT;

            NodeNetwork.addPeer(seedAddress);
        }

        // Peers extras informados nos argumentos.
        for (int i = 2; i < args.length; i++) {
            NodeNetwork.addPeer(args[i]);
        }

        // Apenas o seed cria a blockchain inicial.
        if (seed) {
            NoobChain.initializeBlockchain();
            System.out.println("Tentando minerar o bloco 4...");
            NoobChain.mineNewBlock("Sou o quarto bloco");
        } else {
            // Os outros nós começam vazios e sincronizam com os peers.
            NoobChain.clear();
        }

        ServerSocket serverSocket;

        try {
            serverSocket = new ServerSocket(serverPort);
        } catch (IOException e) {
            System.out.println(
                "Erro ao iniciar o servidor na porta " + serverPort
                + ": " + e.getMessage()
            );
            return;
        }

        System.out.println(
            "Nó " + NodeNetwork.getNodeId()
            + " aguardando conexões na porta " + serverPort
            + (seed ? " (SEED)" : "")
        );

        Thread acceptThread = new Thread(
            () -> acceptLoop(serverSocket), "accept-" + serverPort
        );
        acceptThread.start();

        if (!seed) {
            final String seedFinal = seedAddress;

            Thread startupThread = new Thread(() -> {
                bootstrap(seedFinal);
                synchronizeWithPeers();
            }, "startup");
            startupThread.setDaemon(true);
            startupThread.start();
        }

        runConsole();

        // Sem console (stdin fechado), o nó continua servindo.
        try {
            acceptThread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------
    // Papel de servidor
    // ------------------------------------------------------------------

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

    /**
     * Atende uma conexão de entrada. A conexão fica aberta e pode
     * transportar várias mensagens; a primeira precisa ser HELLO.
     */
    private static void handleConnection(Socket socket) {
        // Antes do HELLO o peer é identificado pelo IP e porta TCP de origem.
        String peer = socket.getInetAddress().getHostAddress()
            + ":" + socket.getPort();

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
                    break; // O peer fechou a conexão.
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

                // Handshake obrigatório antes de qualquer outra mensagem.
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

    /**
     * Valida o HELLO (versão do protocolo e ID do nó) e responde ACK.
     * Devolve o nome usado para o peer nos logs seguintes, ou null se o
     * handshake foi rejeitado.
     *
     * Quando o ID tem o formato "Node-<porta>", o endereço de escuta do
     * peer é o IP de origem da conexão + essa porta, e ele é adicionado à
     * lista de peers conhecidos. É assim que nós antigos descobrem os nós
     * que entram depois na rede.
     */
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
            "Handshake concluído com " + nodeId
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
                // Handshake repetido: apenas confirma de novo.
                send(out, peer, new NetworkMessage(
                    MessageType.ACK,
                    PeerConnection.PROTOCOL_VERSION + "-"
                    + NodeNetwork.getNodeId()
                ));
                break;

            case GET_PEERS: {
                // Fluxo A, passo 5: responde com os endereços dos OUTROS
                // nós conhecidos (sem o próprio solicitante).
                ArrayList<String> others = NodeNetwork.getPeers();
                others.remove(peer);
                send(out, peer, new NetworkMessage(
                    MessageType.PEERS_LIST, others
                ));
                break;
            }

            case GET_BLOCKCHAIN:
                // Fluxo B, passo 2: envia a cópia da cadeia local.
                send(out, peer, new NetworkMessage(
                    MessageType.BLOCKCHAIN_COPY, NoobChain.getBlockchain()
                ));
                break;

            case BLOCKCHAIN_COPY: {
                // Fluxo C, passo 3: valida a cadeia anunciada e adota se
                // for válida e maior; senão responde REJECT com o motivo.
                ArrayList<Block> received = request.getPayloadAsBlockchain();
                NoobChain.AdoptResult result = NoobChain.tryAdopt(received);

                if (result == NoobChain.AdoptResult.ADOPTED) {
                    System.out.println(
                        "Fluxo C: cadeia de " + peer + " adotada ("
                        + NoobChain.size() + " blocos)."
                    );
                    send(out, peer, new NetworkMessage(
                        MessageType.ACK, result.getReason()
                    ));
                } else {
                    System.out.println(
                        "Fluxo C: cadeia de " + peer + " descartada ("
                        + result.getReason() + ")"
                    );
                    sendReject(out, peer, result.getReason());
                }
                break;
            }

            case REJECT:
            case ACK:
                // Resposta final do peer (ex.: fim do Fluxo B); já foi
                // impressa no log [BCP] e não recebe resposta.
                break;

            default:
                // PEERS_LIST é enviada apenas pelo servidor.
                sendReject(out, peer, "Mensagem " + request.getType()
                    + " não é esperada pelo servidor.");
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

    // ------------------------------------------------------------------
    // Papel de cliente
    // ------------------------------------------------------------------

    /**
     * Fluxo A: conecta ao seed, faz HELLO/ACK, pede GET_PEERS e adiciona
     * os endereços da PEERS_LIST à lista de descoberta.
     */
    public static void bootstrap(String seedAddress) {
        System.out.println(
            "\n=== Fluxo A: bootstrap e descoberta de peers via "
            + seedAddress + " ==="
        );

        try (PeerConnection connection =
                PeerConnection.open(seedAddress, NodeNetwork.getNodeId())) {

            NetworkMessage response = connection.request(
                new NetworkMessage(MessageType.GET_PEERS)
            );

            if (response.getType() != MessageType.PEERS_LIST) {
                System.out.println(
                    "Fluxo A: resposta inesperada de " + seedAddress
                    + ": " + response.getType()
                );
                return;
            }

            ArrayList<String> received = response.getPayloadAsPeers();

            if (received == null) {
                System.out.println(
                    "Fluxo A: lista de peers inválida recebida de " + seedAddress
                );
                return;
            }

            for (String peer : received) {
                NodeNetwork.addPeer(peer);
            }

            System.out.println(
                "Fluxo A concluído: " + received.size()
                + " endereço(s) recebido(s). Peers conhecidos: "
                + NodeNetwork.getPeers()
            );

        } catch (Exception e) {
            System.out.println(
                "Fluxo A: erro ao contatar o seed " + seedAddress + ": "
                + e.getMessage()
            );
        }
    }

    /**
     * Fluxo B: pede a blockchain a cada peer e aplica a regra da maior
     * cadeia válida. Se a cadeia for inválida ou menor/igual, descarta e
     * envia REJECT; se for adotada, confirma com ACK.
     */
    public static void synchronizeWithPeers() {
        for (String peer : NodeNetwork.getPeers()) {
            if (NodeNetwork.isSelf(peer)) {
                continue;
            }

            System.out.println(
                "\n=== Fluxo B: sincronização de estado com " + peer + " ==="
            );

            try (PeerConnection connection =
                    PeerConnection.open(peer, NodeNetwork.getNodeId())) {

                NetworkMessage response = connection.request(
                    new NetworkMessage(MessageType.GET_BLOCKCHAIN)
                );

                if (response.getType() != MessageType.BLOCKCHAIN_COPY) {
                    System.out.println(
                        "Fluxo B: resposta inesperada de " + peer + ": "
                        + response.getType()
                    );
                    continue;
                }

                ArrayList<Block> received = response.getPayloadAsBlockchain();
                NoobChain.AdoptResult result = NoobChain.tryAdopt(received);

                if (result == NoobChain.AdoptResult.ADOPTED) {
                    System.out.println(
                        "Fluxo B: cadeia de " + peer + " adotada ("
                        + NoobChain.size() + " blocos)."
                    );
                    connection.send(new NetworkMessage(
                        MessageType.ACK, result.getReason()
                    ));
                } else {
                    System.out.println(
                        "Fluxo B: cadeia de " + peer + " descartada ("
                        + result.getReason() + ") Local: "
                        + NoobChain.size() + " blocos; recebida: "
                        + (received == null ? 0 : received.size()) + " blocos."
                    );
                    connection.send(new NetworkMessage(
                        MessageType.REJECT, result.getReason()
                    ));
                }

            } catch (Exception e) {
                System.out.println(
                    "Fluxo B: erro ao sincronizar com " + peer + ": "
                    + e.getMessage()
                );
            }
        }
    }

    /**
     * Fluxo C: envia a cadeia local (com o bloco recém-minerado) a todos
     * os peers conhecidos.
     */
    public static void broadcastBlockchain() {
        ArrayList<Block> copy = NoobChain.getBlockchain();
        ArrayList<String> peers = NodeNetwork.getPeers();

        System.out.println(
            "\n=== Fluxo C: anúncio do novo bloco para " + peers.size()
            + " peer(s) (" + copy.size() + " blocos) ==="
        );

        for (String peer : peers) {
            if (NodeNetwork.isSelf(peer)) {
                continue;
            }

            try (PeerConnection connection =
                    PeerConnection.open(peer, NodeNetwork.getNodeId())) {

                connection.request(
                    new NetworkMessage(MessageType.BLOCKCHAIN_COPY, copy)
                );

            } catch (Exception e) {
                System.out.println(
                    "Fluxo C: erro ao enviar blockchain para "
                    + peer + ": " + e.getMessage()
                );
            }
        }

        System.out.println("Fluxo C concluído.");
    }

    // ------------------------------------------------------------------
    // Console local (comandos que não fazem parte do protocolo)
    // ------------------------------------------------------------------

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
                return; // Sem console disponível.
            }

            line = line.trim();

            if (line.isEmpty()) {
                continue;
            }

            String[] parts = line.split("\\s+", 2);
            String command = parts[0].toLowerCase();
            String argument = parts.length > 1 ? parts[1] : "";

            switch (command) {
                case "minerar":
                case "mine":
                    if (argument.isEmpty()) {
                        System.out.println("Uso: minerar <dados do bloco>");
                    } else if (NoobChain.mineNewBlock(argument)) {
                        broadcastBlockchain();
                    }
                    break;

                case "cadeia":
                case "chain":
                    printChain();
                    break;

                case "peers":
                    ArrayList<String> peers = NodeNetwork.getPeers();
                    System.out.println("Pares conhecidos (" + peers.size() + "):");
                    for (String peer : peers) {
                        System.out.println("- " + peer);
                    }
                    break;

                case "sync":
                    synchronizeWithPeers();
                    break;

                case "ajuda":
                case "help":
                    printHelp();
                    break;

                case "sair":
                case "exit":
                    System.out.println("Encerrando o nó.");
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
            "\nComandos: minerar <dados> | cadeia | peers | sync | ajuda | sair\n"
        );
    }
}
