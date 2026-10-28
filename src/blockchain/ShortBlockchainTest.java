
package blockchain;

import java.util.ArrayList;

/**
 * Envia a um nó uma cadeia válida, mas não maior que a dele, e espera
 * REJECT pela regra de tamanho.
 * Uso: ShortBlockchainTest [host] [porta]
 */
public class ShortBlockchainTest {

    public static void main(String[] args) {

        String host = args.length > 0 ? args[0] : "localhost";
        int port = args.length > 1
                ? Integer.parseInt(args[1]) : 5000;

        try {
            // Criar uma cadeia de teste válida com quatro blocos.
            ArrayList<Block> testChain = new ArrayList<>();

            Block previous = new Block("Bloco de teste 0", "0");
            previous.mineBlock(NoobChain.difficulty);
            testChain.add(previous);

            for (int i = 1; i < 4; i++) {
                Block next = new Block(
                        "Bloco de teste " + i,
                        previous.hash
                );

                next.mineBlock(NoobChain.difficulty);
                testChain.add(next);
                previous = next;
            }

            // Diagnóstico da cadeia.
            System.out.println("\n--- Diagnóstico da cadeia ---");
            System.out.println(
                    "Dificuldade configurada: " + NoobChain.difficulty
            );

            System.out.println(
                    "Número de blocos: " + testChain.size()
            );

            System.out.println(
                    "Cadeia de teste válida: "
                    + NoobChain.isChainValid(testChain)
            );

            for (int i = 0; i < testChain.size(); i++) {
                Block current = testChain.get(i);

                System.out.println(
                        "Bloco " + i
                        + " | Hash correto: "
                        + current.hash.equals(current.calculateHash())
                        + " | Dificuldade correta: "
                        + current.hash.startsWith(
                                "0".repeat(NoobChain.difficulty))
                );

                if (i == 0) {
                    System.out.println(
                            "Bloco 0 | previousHash correto: "
                            + "0".equals(current.previousHash)
                    );
                } else {
                    Block previousBlock = testChain.get(i - 1);

                    System.out.println(
                            "Bloco " + i
                            + " | Ligação ao bloco anterior correta: "
                            + current.previousHash.equals(
                                    previousBlock.hash)
                    );
                }
            }

            // Não enviar a cadeia se for inválida.
            if (!NoobChain.isChainValid(testChain)) {
                System.out.println(
                        "TESTE INTERROMPIDO: cadeia inválida."
                );
                return;
            }

            // Enviar a cadeia ao nó (com handshake).
            try (PeerConnection connection = PeerConnection.open(
                    host + ":" + port, "ShortBlockchainTest")) {

                NetworkMessage message = connection.request(
                        new NetworkMessage(
                                MessageType.BLOCKCHAIN_COPY, testChain)
                );

                System.out.println("\n--- Resposta do servidor ---");
                System.out.println(
                        "Tipo de resposta: " + message.getType()
                );
                System.out.println(
                        "Detalhe: " + message.getPayloadAsString()
                );

                if (message.getType() == MessageType.REJECT) {
                    String detail = message.getPayloadAsString();

                    if (detail != null
                            && detail.toLowerCase().contains(
                                    "tamanho menor ou igual")) {

                        System.out.println(
                                "TESTE PASSOU: cadeia válida "
                                + "rejeitada pela regra de tamanho."
                        );

                    } else {
                        System.out.println(
                                "REJEIÇÃO RECEBIDA, mas o motivo "
                                + "precisa de confirmação."
                        );
                    }

                } else {
                    System.out.println(
                            "TESTE FALHOU: esperava-se REJECT."
                    );
                }
            }

        } catch (Exception e) {
            System.out.println("Erro no teste: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
