
package blockchain;

import java.util.ArrayList;

public class AdoptionTest {

    public static void main(String[] args) {

        // Guarda a blockchain atual para restaurá-la no final.
        ArrayList<Block> blockchainOriginal =
                new ArrayList<>(NoobChain.blockchain);

        try {
            // Cria uma blockchain local de teste com 2 blocos.
            ArrayList<Block> local = new ArrayList<>();

            Block bloco0 = new Block("Bloco 0", "0");
            bloco0.mineBlock(NoobChain.difficulty);
            local.add(bloco0);

            Block bloco1 = new Block("Bloco 1", bloco0.hash);
            bloco1.mineBlock(NoobChain.difficulty);
            local.add(bloco1);

            NoobChain.blockchain = new ArrayList<>(local);

            System.out.println("Blockchain local: "
                    + NoobChain.blockchain.size() + " blocos.");

            // TESTE 1: cadeia válida, mas do mesmo tamanho.
            ArrayList<Block> mesmaTamanho =
                    new ArrayList<>(local);

            boolean resultado1 =
                    NoobChain.adoptBlockchain(mesmaTamanho);

            System.out.println(
                    "Teste 1 - Mesma quantidade rejeitada: "
                    + !resultado1
            );

            // TESTE 2: cadeia válida e maior.
            ArrayList<Block> maior =
                    new ArrayList<>(local);

            Block bloco2 = new Block("Bloco 2", bloco1.hash);
            bloco2.mineBlock(NoobChain.difficulty);
            maior.add(bloco2);

            boolean resultado2 =
                    NoobChain.adoptBlockchain(maior);

            System.out.println(
                    "Teste 2 - Cadeia maior aceita: "
                    + resultado2
            );

            // TESTE 3: cadeia adulterada.
            ArrayList<Block> adulterada =
                    new ArrayList<>(maior);

            adulterada.get(1).previousHash = "hash_invalido";

            boolean resultado3 =
                    NoobChain.adoptBlockchain(adulterada);

            System.out.println(
                    "Teste 3 - Cadeia inválida rejeitada: "
                    + !resultado3
            );

        } finally {
            // Restaura a referência original da blockchain.
            NoobChain.blockchain =
                    new ArrayList<>(blockchainOriginal);
        }
    }
}
