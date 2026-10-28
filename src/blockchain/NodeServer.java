package blockchain;

import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;

public class NodeServer {

    public static void main(String[] args) {
        int port = 5000;
        boolean seed = false;

        if (args.length > 0) {
            port = Integer.parseInt(args[0]);
        }

        final int serverPort = port;

        if (args.length > 1 && "SEED".equalsIgnoreCase(args[1])) {
            seed = true;
        }

        // Apenas o seed cria a blockchain inicial.
        if (seed) {
            NoobChain.initializeBlockchain();

            Block fourthBlock = new Block(
                "Sou o quarto bloco",
                NoobChain.blockchain.get(NoobChain.blockchain.size() - 1).hash
            );

            System.out.println("Tentando minerar o bloco 4...");
            fourthBlock.mineBlock(NoobChain.difficulty);
            NoobChain.blockchain.add(fourthBlock);
        } else {
            // Os outros nós começam vazios e tentam sincronizar com os peers.
            NoobChain.blockchain.clear();
        }

        // Registra os peers configurados nos argumentos.
        for (int i = 1; i < args.length; i++) {
            if (!"SEED".equalsIgnoreCase(args[i])) {
                NodeNetwork.addPeer(args[i]);
            }
        }

        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("Nó aguardando conexões na porta " + port);

            if (!seed && args.length > 1) {
                String seedAddress = args[1];

                System.out.println(
                    "A registar este nó no seed: " + seedAddress
                );

                registerWithSeed(serverPort, seedAddress);
                startPeerRefreshThread(serverPort, seedAddress);
            }

            if (!seed) {
                Thread syncThread = new Thread(
                    () -> synchronizeWithPeers(serverPort)
                );
                syncThread.setDaemon(true);
                syncThread.start();
            }

            while (true) {
                try (Socket socket = serverSocket.accept()) {
                    socket.setSoTimeout(10000);

                    System.out.println(
                        "Conexão recebida de: " + socket.getInetAddress()
                    );

                    try (
                        ObjectOutputStream out =
                            new ObjectOutputStream(socket.getOutputStream());
                        ObjectInputStream in =
                            new ObjectInputStream(socket.getInputStream())
                    ) {
                        Object object = in.readObject();

                        if (!(object instanceof Message)) {
                            sendReject(out, "Objeto recebido não é uma mensagem BCP.");
                            continue;
                        }

                        Message request = (Message) object;

                        if (request.getType() == null) {
                            sendReject(out, "Tipo de mensagem ausente.");
                            continue;
                        }

                        String type = request.getType();

                        if ("HELLO".equals(type)) {
                            handleHello(request, out);

                        } else if ("MINE".equals(type)) {
                            String data = request.getData();

                            if (data == null || data.trim().isEmpty()) {
                                sendReject(out, "Dados do bloco não podem estar vazios.");
                                continue;
                            }

                            NoobChain.mineNewBlock(data);
                            out.writeObject(new Message("MINE_COMPLETE"));
                            out.flush();

                        } else if ("GET_BLOCKCHAIN".equals(type)) {
                            sendBlockchain(out);

                        } else if ("GET_PEERS".equals(type)) {
                            Message response = new Message(
                                "PEERS_LIST",
                                NodeNetwork.getPeers(),
                                true
                            );
                            out.writeObject(response);
                            out.flush();

                        } else if ("PEERS_LIST".equals(type)) {
                            ArrayList<String> receivedPeers = request.getPeers();

                            if (receivedPeers == null) {
                                sendReject(out, "Lista de peers ausente.");
                                continue;
                            }

                            for (String peer : receivedPeers) {
                                NodeNetwork.addPeer(peer);
                            }

                            out.writeObject(new Message("PEERS_LIST_RECEIVED"));
                            out.flush();

                        } else if ("BLOCKCHAIN_COPY".equals(type)) {
                            handleBlockchainCopy(request, out);

                        } else if ("SYNC".equals(type)) {
                            System.out.println("Pedido de sincronização recebido.");
                            synchronizeWithPeers(port);
                            out.writeObject(new Message("SYNC_COMPLETE"));
                            out.flush();

                        } else {
                            sendReject(out, "Tipo de mensagem desconhecido: " + type);
                        }
                    }
                } catch (Exception e) {
                    System.out.println(
                        "Erro ao processar conexão: " + e.getMessage()
                    );
                }
            }
        } catch (Exception e) {
            System.out.println(
                "Erro ao iniciar o servidor: " + e.getMessage()
            );
        }
    }

    private static void handleHello(
            Message request, ObjectOutputStream out) throws Exception {

        String nodeId = request.getData();

        if (nodeId == null || nodeId.trim().isEmpty()) {
            sendReject(out, "Identificação do nó ausente no HELLO.");
            return;
        }

        System.out.println("HELLO recebido de: " + nodeId);
        out.writeObject(new Message("HELLO", "ACK: " + nodeId));
        out.flush();
    }

    private static void handleBlockchainCopy(
            Message request, ObjectOutputStream out) throws Exception {

        ArrayList<Block> received = request.getBlockchain();

        if (received == null || !NoobChain.isChainValid(received)) {
            sendReject(out, "Blockchain inválida.");
            return;
        }

        if (received.size() <= NoobChain.blockchain.size()) {
            sendReject(out, "Tamanho menor ou igual à cadeia local.");
            return;
        }

        boolean adopted = NoobChain.adoptBlockchain(received);

        if (adopted) {
            System.out.println(
                "Blockchain recebida e adotada: "
                + NoobChain.blockchain.size() + " blocos."
            );
            out.writeObject(new Message("BLOCKCHAIN_RECEIVED"));
        } else {
            sendReject(out, "A cadeia não pôde ser adotada.");
        }

        out.flush();
    }

    private static void sendReject(
            ObjectOutputStream out, String reason) throws Exception {
        out.writeObject(new Message("REJECT", reason));
        out.flush();
    }

    private static void sendBlockchain(ObjectOutputStream out)
            throws Exception {
        ArrayList<Block> copy = new ArrayList<>(NoobChain.blockchain);
        out.writeObject(new Message("BLOCKCHAIN_COPY", copy));
        out.flush();

        System.out.println("Blockchain enviada: " + copy.size() + " blocos.");
    }

    private static void synchronizeWithPeers(int ownPort) {
        ArrayList<String> peers = NodeNetwork.getPeers();

        for (String peer : peers) {
            try {
                String[] parts = peer.split(":");

                if (parts.length != 2) {
                    System.out.println("Endereço de par inválido: " + peer);
                    continue;
                }

                String host = parts[0].trim();
                int peerPort = Integer.parseInt(parts[1].trim());

                if (peerPort == ownPort
                        && ("localhost".equals(host)
                        || "127.0.0.1".equals(host))) {
                    continue;
                }

                System.out.println("A solicitar blockchain a " + peer);

                try (
                    Socket socket = new Socket(host, peerPort);
                    ObjectOutputStream out =
                        new ObjectOutputStream(socket.getOutputStream())
                ) {
                    socket.setSoTimeout(5000);
                    out.flush();

                    try (ObjectInputStream in =
                            new ObjectInputStream(socket.getInputStream())) {

                        out.writeObject(new Message("GET_BLOCKCHAIN"));
                        out.flush();

                        Object obj = in.readObject();

                        if (!(obj instanceof Message)) {
                            System.out.println("Resposta inválida de " + peer);
                            continue;
                        }

                        Message response = (Message) obj;

                        if ("REJECT".equals(response.getType())) {
                            System.out.println(
                                "Pedido rejeitado por " + peer + ": "
                                + response.getData()
                            );
                            continue;
                        }

                        if (!"BLOCKCHAIN_COPY".equals(response.getType())) {
                            System.out.println("Resposta inesperada de " + peer);
                            continue;
                        }

                        ArrayList<Block> received = response.getBlockchain();

                        if (!NoobChain.isChainValid(received)) {
                            System.out.println(
                                "Blockchain inválida recebida de " + peer
                            );
                            continue;
                        }

                        if (NoobChain.adoptBlockchain(received)) {
                            System.out.println(
                                "Blockchain de " + peer + " adotada com sucesso."
                            );
                        } else {
                            System.out.println(
                                "Cadeia de " + peer
                                + " não adotada. Tamanho local: "
                                + NoobChain.blockchain.size()
                                + "; tamanho recebido: "
                                + (received == null ? 0 : received.size())
                            );
                        }
                    }
                }
            } catch (Exception e) {
                System.out.println(
                    "Erro ao sincronizar com " + peer + ": " + e.getMessage()
                );
            }
        }
    }

    public static void broadcastBlockchain() {
        ArrayList<String> peers = NodeNetwork.getPeers();

        for (String peer : peers) {
            try {
                String[] parts = peer.split(":");

                if (parts.length != 2) {
                    System.out.println("Endereço inválido: " + peer);
                    continue;
                }

                String host = parts[0].trim();
                int peerPort = Integer.parseInt(parts[1].trim());
                ArrayList<Block> copy = new ArrayList<>(NoobChain.blockchain);

                System.out.println(
                    "Enviando blockchain para " + peer
                    + " (" + copy.size() + " blocos)..."
                );

                try (Socket socket = new Socket(host, peerPort)) {
                    socket.setSoTimeout(5000);

                    try (
                        ObjectOutputStream out =
                            new ObjectOutputStream(socket.getOutputStream());
                        ObjectInputStream in =
                            new ObjectInputStream(socket.getInputStream())
                    ) {
                        out.flush();
                        out.writeObject(new Message("BLOCKCHAIN_COPY", copy));
                        out.flush();

                        Object responseObject = in.readObject();

                        if (responseObject instanceof Message) {
                            Message response = (Message) responseObject;

                            if ("REJECT".equals(response.getType())) {
                                System.out.println(
                                    "Blockchain rejeitada por " + peer + ": "
                                    + response.getData()
                                );
                            } else {
                                System.out.println(
                                    "Resposta de " + peer + ": "
                                    + response.getType()
                                );
                            }
                        } else {
                            System.out.println(
                                "Resposta inesperada de " + peer
                            );
                        }
                    }
                }
            } catch (Exception e) {
                System.out.println(
                    "Erro ao enviar blockchain para "
                    + peer + ": " + e.getMessage()
                );
            }
        }
    }
    
    private static String[] parsePeerAddress(String address) {
        String[] parts = address.split(":");

        if (parts.length != 2) {
            throw new IllegalArgumentException(
                "Endereço de par inválido: " + address
            );
        }

        return new String[] {
            parts[0].trim(),
            parts[1].trim()
        };
    }

    private static void registerWithSeed(
            int ownPort, String seedAddress) {

        try {
            String[] seedParts = parsePeerAddress(seedAddress);
            String seedHost = seedParts[0];
            int seedPort = Integer.parseInt(seedParts[1]);

            try (Socket socket = new Socket(seedHost, seedPort)) {
                socket.setSoTimeout(5000);

                String ownHost = socket.getLocalAddress().getHostAddress();
                String ownAddress = ownHost + ":" + ownPort;

                ArrayList<String> registration = new ArrayList<>();
                registration.add(ownAddress);

                try (
                    ObjectOutputStream out =
                        new ObjectOutputStream(socket.getOutputStream())
                ) {
                    out.flush();

                    try (
                        ObjectInputStream in =
                            new ObjectInputStream(socket.getInputStream())
                    ) {
                        out.writeObject(
                            new Message("PEERS_LIST", registration, true)
                        );
                        out.flush();

                        Object response = in.readObject();

                        if (response instanceof Message
                                && "PEERS_LIST_RECEIVED".equals(
                                    ((Message) response).getType())) {

                            System.out.println(
                                "Registo confirmado pelo seed: " + ownAddress
                            );
                        } else {
                            System.out.println(
                                "Resposta inesperada ao registo no seed."
                            );
                            return;
                        }
                    }
                }
            }

            refreshPeersFromSeed(ownPort, seedAddress);

        } catch (Exception e) {
            System.out.println(
                "Erro ao registar no seed: " + e.getMessage()
            );
        }
    }

    private static void refreshPeersFromSeed(
            int ownPort, String seedAddress) {

        try {
            String[] seedParts = parsePeerAddress(seedAddress);
            String seedHost = seedParts[0];
            int seedPort = Integer.parseInt(seedParts[1]);

            try (Socket socket = new Socket(seedHost, seedPort)) {
                socket.setSoTimeout(5000);

                String ownHost = socket.getLocalAddress().getHostAddress();
                String ownAddress = ownHost + ":" + ownPort;

                try (
                    ObjectOutputStream out =
                        new ObjectOutputStream(socket.getOutputStream())
                ) {
                    out.flush();

                    try (
                        ObjectInputStream in =
                            new ObjectInputStream(socket.getInputStream())
                    ) {
                        out.writeObject(new Message("GET_PEERS"));
                        out.flush();

                        Object response = in.readObject();

                        if (!(response instanceof Message)) {
                            System.out.println(
                                "Resposta inválida ao consultar o seed."
                            );
                            return;
                        }

                        Message message = (Message) response;

                        if (!"PEERS_LIST".equals(message.getType())) {
                            System.out.println(
                                "Resposta inesperada do seed: "
                                + message.getType()
                            );
                            return;
                        }

                        ArrayList<String> peers = message.getPeers();
                        
                        java.util.Set<String> paresAntes =
                        		new java.util.HashSet<>(NodeNetwork.getPeers());
                        
                        if (peers != null) {
                            for (String peer : peers) {
                                if (peer == null || peer.equals(ownAddress)) {
                                    continue;
                                }

                                NodeNetwork.addPeer(peer);
                            }
                        }


						java.util.Set<String> paresDepois =
						    new java.util.HashSet<>(NodeNetwork.getPeers());
						
						if (!paresAntes.equals(paresDepois)) {
						    System.out.println(
						        "Lista de pares alterada. Total local: "
						        + paresDepois.size()
						    );
						}
;
                    }
                }
            }

        } catch (Exception e) {
            System.out.println(
                "Erro ao atualizar lista de pares: " + e.getMessage()
            );
        }
    }

    private static void startPeerRefreshThread(
            int ownPort, String seedAddress) {

        Thread refreshThread = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(5000);
                    refreshPeersFromSeed(ownPort, seedAddress);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        });

        refreshThread.setDaemon(true);
        refreshThread.start();
    }
}
