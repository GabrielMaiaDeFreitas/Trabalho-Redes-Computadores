
package blockchain;

import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.util.ArrayList;

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

            // Enviar a cadeia ao servidor.
            try (Socket socket = new Socket(host, port);
                 ObjectOutputStream out =
                         new ObjectOutputStream(
                                 socket.getOutputStream())) {

                out.flush();

                try (ObjectInputStream in =
                             new ObjectInputStream(
                                     socket.getInputStream())) {

                    out.writeObject(
                            new Message("BLOCKCHAIN_COPY", testChain)
                    );
                    out.flush();

                    Object response = in.readObject();

                    if (!(response instanceof Message)) {
                        System.out.println(
                                "TESTE FALHOU: resposta não reconhecida."
                        );
                        return;
                    }

                    Message message = (Message) response;

                    System.out.println("\n--- Resposta do servidor ---");
                    System.out.println(
                            "Tipo de resposta: " + message.getType()
                    );
                    System.out.println(
                            "Detalhe: " + message.getData()
                    );

                    if ("REJECT".equals(message.getType())) {
                        String detail = message.getData();

                        if (detail != null
                                && (detail.toLowerCase().contains(
                                        "tamanho menor ou igual")
                                || detail.toLowerCase().contains(
                                        "não é mais longa")
                                || detail.toLowerCase().contains(
                                        "mais longa"))) {

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
            }

        } catch (Exception e) {
            System.out.println("Erro no teste: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
