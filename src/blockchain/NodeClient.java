package blockchain;

import java.util.ArrayList;

/**
 * Cliente de linha de comando para inspecionar um nó.
 *
 * Uso: NodeClient [host] [porta] [ALL | BLOCKCHAIN | PEERS]
 *
 * Todas as operações usam uma única conexão, como no Fluxo A:
 * HELLO -> ACK -> GET_BLOCKCHAIN -> BLOCKCHAIN_COPY -> GET_PEERS -> PEERS_LIST.
 *
 * Para minerar ou forçar sincronização, use os comandos do console do
 * próprio nó (NodeServer): "minerar <dados>" e "sync".
 */
public class NodeClient {

    public static void main(String[] args) {
        String host = args.length > 0 ? args[0] : "localhost";
        int port = args.length > 1
            ? Integer.parseInt(args[1]) : NetworkConfig.SEED_PORT;
        String operation = args.length > 2 ? args[2].toUpperCase() : "ALL";

        if (!"ALL".equals(operation)
                && !"BLOCKCHAIN".equals(operation)
                && !"PEERS".equals(operation)
                && !"GET_PEERS".equals(operation)) {
            System.out.println(
                "Operação desconhecida: " + operation
                + ". Use ALL, BLOCKCHAIN ou PEERS."
                + " Para minerar, use \"minerar <dados>\" no console do nó."
            );
            return;
        }

        String address = host + ":" + port;

        try (PeerConnection connection =
                PeerConnection.open(address, "Client")) {

            System.out.println(
                "Handshake confirmado por " + connection.getRemoteNode()
            );

            if ("ALL".equals(operation) || "BLOCKCHAIN".equals(operation)) {
                requestBlockchain(connection);
            }

            if (!"BLOCKCHAIN".equals(operation)) {
                requestPeers(connection);
            }

        } catch (Exception e) {
            System.out.println(
                "Erro na comunicação com " + address + ": " + e.getMessage()
            );
        }
    }

    private static void requestBlockchain(PeerConnection connection)
            throws Exception {

        System.out.println(
            "\nSolicitando blockchain a " + connection.getAddress()
        );

        NetworkMessage response = connection.request(
            new NetworkMessage(MessageType.GET_BLOCKCHAIN)
        );

        if (response.getType() == MessageType.REJECT) {
            System.out.println(
                "Pedido de blockchain rejeitado: "
                + response.getPayloadAsString()
            );
            return;
        }

        if (response.getType() != MessageType.BLOCKCHAIN_COPY) {
            System.out.println("Resposta inesperada: " + response.getType());
            return;
        }

        ArrayList<Block> received = response.getPayloadAsBlockchain();
        NoobChain.AdoptResult result = NoobChain.tryAdopt(received);

        System.out.println("Resultado da validação: " + result.getReason());

        // Fluxo B: confirma com ACK ou devolve REJECT com o motivo.
        if (result == NoobChain.AdoptResult.ADOPTED) {
            connection.send(new NetworkMessage(
                MessageType.ACK, result.getReason()
            ));
        } else {
            connection.send(new NetworkMessage(
                MessageType.REJECT, result.getReason()
            ));
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
    }

    private static void requestPeers(PeerConnection connection)
            throws Exception {

        System.out.println("\nSolicitando lista de pares...");

        NetworkMessage response = connection.request(
            new NetworkMessage(MessageType.GET_PEERS)
        );

        if (response.getType() == MessageType.REJECT) {
            System.out.println(
                "Pedido de peers rejeitado: " + response.getPayloadAsString()
            );
            return;
        }

        if (response.getType() != MessageType.PEERS_LIST) {
            System.out.println("Resposta inesperada: " + response.getType());
            return;
        }

        ArrayList<String> receivedPeers = response.getPayloadAsPeers();

        if (receivedPeers == null) {
            System.out.println("Lista de peers inválida.");
            return;
        }

        for (String peer : receivedPeers) {
            NodeNetwork.addPeer(peer);
        }

        System.out.println("Pares recebidos: " + receivedPeers.size());
        System.out.println("Pares conhecidos pelo cliente:");

        for (String peer : NodeNetwork.getPeers()) {
            System.out.println("- " + peer);
        }
    }
}
