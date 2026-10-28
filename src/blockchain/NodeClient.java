package blockchain;

import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.util.ArrayList;

public class NodeClient {

    public static void main(String[] args) {
        String host = "localhost";
        int port = 5000;
        String operation = "ALL";
        String data = "Novo bloco";

        if (args.length > 0) {
            host = args[0];
        }
        if (args.length > 1) {
            port = Integer.parseInt(args[1]);
        }
        if (args.length > 2) {
            operation = args[2].toUpperCase();
        }
        if (args.length > 3) {
            data = args[3];
        }

        // O handshake é feito antes das operações normais.
        try {
            Message helloResponse = sendRequest(
                host,
                port,
                new Message("HELLO", "V1.0-Node-" + port)
            );

            if ("REJECT".equals(helloResponse.getType())) {
                System.out.println(
                    "Apresentação rejeitada: " + helloResponse.getData()
                );
                return;
            }

            if (!"HELLO".equals(helloResponse.getType())) {
                System.out.println(
                    "Resposta inesperada ao HELLO: "
                    + helloResponse.getType()
                );
                return;
            }

            System.out.println(
                "Apresentação confirmada: " + helloResponse.getData()
            );
        } catch (Exception e) {
            System.out.println(
                "Erro na apresentação ao nó: " + e.getMessage()
            );
            return;
        }

        if ("SYNC".equals(operation)) {
            requestSync(host, port);
            return;
        }

        if ("MINE".equals(operation)) {
            requestMine(host, port, data);
            return;
        }

        NoobChain.initializeBlockchain();
        //NodeNetwork.addPeer(host + ":" + port);

        if ("ALL".equals(operation) || "BLOCKCHAIN".equals(operation)) {
            requestBlockchain(host, port);
        }

        if ("ALL".equals(operation)
                || "PEERS".equals(operation)
                || "GET_PEERS".equals(operation)) {
            requestPeers(host, port);
        }
    }

    private static Message sendRequest(
            String host, int port, Message request) throws Exception {
        try (
            Socket socket = new Socket(host, port);
            ObjectOutputStream out =
                new ObjectOutputStream(socket.getOutputStream())
        ) {
            socket.setSoTimeout(5000);
            out.flush();

            try (ObjectInputStream in =
                    new ObjectInputStream(socket.getInputStream())) {
                out.writeObject(request);
                out.flush();

                Object response = in.readObject();
                if (!(response instanceof Message)) {
                    throw new IllegalStateException(
                        "O servidor respondeu com um objeto inesperado."
                    );
                }
                return (Message) response;
            }
        }
    }

    private static boolean reportReject(Message response, String operation) {
        if ("REJECT".equals(response.getType())) {
            System.out.println(
                operation + " rejeitado: " + response.getData()
            );
            return true;
        }
        return false;
    }

    private static void requestMine(
            String host, int port, String data) {
        try {
            System.out.println(
                "\nA solicitar mineração ao nó " + host + ":" + port
            );

            Message response = sendRequest(
                host, port, new Message("MINE", data)
            );

            if (reportReject(response, "Pedido de mineração")) {
                return;
            }

            if ("MINE_COMPLETE".equals(response.getType())) {
                System.out.println(
                    "O servidor concluiu o pedido de mineração."
                );
            } else {
                System.out.println(
                    "Resposta inesperada: " + response.getType()
                );
            }
        } catch (Exception e) {
            System.out.println(
                "Erro ao solicitar mineração: " + e.getMessage()
            );
        }
    }

    private static void requestBlockchain(String host, int port) {
        try {
            System.out.println(
                "\nA solicitar blockchain a " + host + ":" + port
            );

            Message response = sendRequest(
                host, port, new Message("GET_BLOCKCHAIN")
            );

            if (reportReject(response, "Pedido de blockchain")) {
                return;
            }

            if (!"BLOCKCHAIN_COPY".equals(response.getType())) {
                System.out.println(
                    "Resposta inesperada: " + response.getType()
                );
                return;
            }

            ArrayList<Block> received = response.getBlockchain();
            boolean valid = NoobChain.isChainValid(received);

            System.out.println("Blockchain recebida é válida: " + valid);

            if (valid) {
                boolean adopted = NoobChain.adoptBlockchain(received);
                System.out.println("Cadeia adotada: " + adopted);
            }

            if (received != null) {
                System.out.println("Recebidos " + received.size() + " blocos.");

                for (int i = 0; i < received.size(); i++) {
                    Block block = received.get(i);
                    System.out.println(
                        "Bloco " + i
                        + " | Hash: " + block.hash
                        + " | Previous hash: " + block.previousHash
                    );
                }
            }
        } catch (Exception e) {
            System.out.println(
                "Erro ao solicitar blockchain: " + e.getMessage()
            );
        }
    }

    private static void requestPeers(String host, int port) {
        try {
            System.out.println("\nA solicitar lista de pares...");

            Message response = sendRequest(
                host, port, new Message("GET_PEERS")
            );

            if (reportReject(response, "Pedido de peers")) {
                return;
            }

            if (!"PEERS_LIST".equals(response.getType())) {
                System.out.println(
                    "Resposta inesperada: " + response.getType()
                );
                return;
            }

            ArrayList<String> receivedPeers = response.getPeers();

            if (receivedPeers != null) {
                for (String peer : receivedPeers) {
                    NodeNetwork.addPeer(peer);
                }
            }

            System.out.println(
                "Pares recebidos: "
                + (receivedPeers == null ? 0 : receivedPeers.size())
            );

            System.out.println("Pares conhecidos pelo cliente:");
            for (String peer : NodeNetwork.getPeers()) {
                System.out.println("- " + peer);
            }
        } catch (Exception e) {
            System.out.println(
                "Erro ao solicitar pares: " + e.getMessage()
            );
        }
    }

    private static void requestSync(String host, int port) {
        try {
            System.out.println(
                "\nA solicitar sincronização ao nó " + host + ":" + port
            );

            Message response = sendRequest(
                host, port, new Message("SYNC")
            );

            if (reportReject(response, "Pedido de sincronização")) {
                return;
            }

            if ("SYNC_COMPLETE".equals(response.getType())) {
                System.out.println(
                    "Pedido de sincronização concluído pelo servidor."
                );
            } else {
                System.out.println(
                    "Resposta inesperada: " + response.getType()
                );
            }
        } catch (Exception e) {
            System.out.println(
                "Erro ao solicitar sincronização: " + e.getMessage()
            );
        }
    }
}
