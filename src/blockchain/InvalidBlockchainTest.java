
package blockchain;

import java.util.ArrayList;

/**
 * Envia a um nó uma cadeia adulterada e espera REJECT.
 * Uso: InvalidBlockchainTest [host] [porta]
 */
public class InvalidBlockchainTest {
    public static void main(String[] args) {
        String host = args.length > 0 ? args[0] : "localhost";
        int port = args.length > 1
                ? Integer.parseInt(args[1]) : 5000;

        try {
            // Criar uma cadeia de teste válida.
            ArrayList<Block> invalidChain = new ArrayList<>();

            Block block0 = new Block("Bloco de teste 0", "0");
            block0.mineBlock(NoobChain.difficulty);
            invalidChain.add(block0);

            Block block1 = new Block(
                    "Bloco de teste 1", block0.hash);
            block1.mineBlock(NoobChain.difficulty);
            invalidChain.add(block1);

            // Adulterar os dados do bloco sem recalcular o hash.
            java.lang.reflect.Field dataField =
                    Block.class.getDeclaredField("data");
            dataField.setAccessible(true);
            dataField.set(block1, "Dados adulterados");

            System.out.println(
                    "Cadeia de teste válida? "
                    + NoobChain.isChainValid(invalidChain));

            try (PeerConnection connection = PeerConnection.open(
                    host + ":" + port, "InvalidBlockchainTest")) {

                NetworkMessage message = connection.request(
                        new NetworkMessage(
                                MessageType.BLOCKCHAIN_COPY, invalidChain));

                System.out.println(
                        "Tipo de resposta: " + message.getType());
                System.out.println(
                        "Detalhe: " + message.getPayloadAsString());

                if (message.getType() == MessageType.REJECT) {
                    System.out.println(
                            "TESTE PASSOU: cadeia inválida rejeitada.");
                } else {
                    System.out.println(
                            "TESTE FALHOU: resposta inesperada.");
                }
            }
        } catch (Exception e) {
            System.out.println("Erro no teste: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
