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
 *   NodeServer <porta> SEED                -> nó semente (cria a cadeia)
 *   NodeServer <porta> [<seedHost:porta>]  -> nó comum
 *
 * O nó só escuta a porta ao iniciar: nada é enviado automaticamente.
 * Cada passo dos fluxos é disparado por um comando no console:
 *
 *   Fluxo A (bootstrap e descoberta)
 *     conectar [ip:porta]  -> passo 1: abre a conexão TCP (padrão: seed)
 *     hello                -> passos 2 e 3: HELLO e espera o ACK
 *     get_peers            -> passos 4, 5 e 6: GET_PEERS, PEERS_LIST, adiciona
 *
 *   Fluxo B (sincronização, na conexão aberta)
 *     get_blockchain       -> passos 1, 2 e 3: GET_BLOCKCHAIN, BLOCKCHAIN_COPY,
 *                             validação, consenso e REJECT/ACK
 *
 *   Fluxo C (novo bloco)
 *     minerar <dados>      -> passo 1: PoW e inserção na cadeia local
 *     broadcast            -> passos 2 e 3: BLOCKCHAIN_COPY a todos os peers
 *
 * O lado que recebe as mensagens responde sozinho (papel de servidor).
 * Toda mensagem enviada ou recebida é impressa com o prefixo [BCP].
 */
public class NodeServer {

    // Tempo máximo que uma conexão de entrada pode ficar sem mensagens.
    // É longo porque os passos são digitados à mão.
    private static final int IDLE_TIMEOUT_MS = 10 * 60 * 1000;

    // ID enviado no HELLO pelos nós: "Node-<porta de escuta>".
    private static final Pattern NODE_ID = Pattern.compile("Node-(\\d{1,5})");

    // Uma thread por conexão recebida.
    private static final ExecutorService connectionPool =
        Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable);
            thread.setDaemon(true);
            return thread;
        });

    // Endereço semente: gravado em NetworkConfig ou informado no argumento.
    private static String seedAddress =
        NetworkConfig.SEED_HOST + ":" + NetworkConfig.SEED_PORT;

    // Conexão de saída aberta pelo comando "conectar".
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

        // Apenas o seed cria a blockchain inicial.
        if (seed) {
            NoobChain.initializeBlockchain();
            System.out.println("Tentando minerar o bloco 4...");
            NoobChain.mineNewBlock("Sou o quarto bloco");
        } else {
            // Os outros nós começam vazios e sincronizam pelo Fluxo B.
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

        // Sem console (stdin fechado), o nó continua servindo.
        try {
            acceptThread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------
    // Papel de servidor (responde sozinho às mensagens recebidas)
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
     * Fluxo A, passo 3 (lado B): valida o HELLO (versão do protocolo e ID
     * do nó) e confirma com ACK. Devolve o nome usado para o peer nos logs
     * seguintes, ou null se o handshake foi rejeitado.
     *
     * Quando o ID tem o formato "Node-<porta>", o endereço de escuta do
     * peer é o IP de origem da conexão + essa porta, e ele passa a fazer
     * parte da lista de pares ativos devolvida em PEERS_LIST.
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
                // Handshake repetido: apenas confirma de novo.
                send(out, peer, new NetworkMessage(
                    MessageType.ACK,
                    PeerConnection.PROTOCOL_VERSION + "-"
                    + NodeNetwork.getNodeId()
                ));
                break;

            case GET_PEERS: {
                // Fluxo A, passo 5 (lado B): endereços dos OUTROS nós
                // conhecidos (sem o próprio solicitante).
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
                // Fluxo B, passo 2 (lado B): envia a cópia da cadeia local.
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
                // Fluxo C, passo 3 (peer receptor): valida a nova cadeia e
                // atualiza se for válida e maior; senão responde REJECT.
                ArrayList<Block> received = request.getPayloadAsBlockchain();
                validateAndAdopt("Fluxo C - passo 3", peer, received, null, out);
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

    /**
     * Validação de criptografia/integridade + regra de consenso, usada no
     * Fluxo B (passo 3) e no Fluxo C (passo 3). Imprime cada verificação e
     * responde ACK (adotada) ou REJECT (descartada) por "connection"
     * (lado cliente) ou por "out" (lado servidor).
     */
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

    // ------------------------------------------------------------------
    // Papel de cliente (cada passo é um comando do console)
    // ------------------------------------------------------------------

    // Fluxo A, passo 1: abre a conexão TCP (sem HELLO).
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

    // Fluxo A, passos 2 e 3: envia HELLO e espera o ACK.
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
            // O nó com quem o handshake foi feito também é um par conhecido.
            NodeNetwork.addPeer(current.getAddress());
        } catch (Exception e) {
            connectionFailed("[Fluxo A - passo 3]", e);
        }
    }

    // Fluxo A, passos 4, 5 e 6: GET_PEERS, PEERS_LIST e lista de descoberta.
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

    // Fluxo B: GET_BLOCKCHAIN, BLOCKCHAIN_COPY, validação, consenso e REJECT/ACK.
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

    // Fluxo C, passo 1: PoW do novo bloco e inserção na cadeia local.
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

    // Fluxo C, passo 2: BLOCKCHAIN_COPY para todos os peers da lista.
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

            // Cada envio usa uma conexão com handshake (HELLO/ACK), exigido
            // pelo servidor antes de qualquer outra mensagem.
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

    // O servidor encerra a conexão depois de um REJECT de handshake.
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

    // ------------------------------------------------------------------
    // Console
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
