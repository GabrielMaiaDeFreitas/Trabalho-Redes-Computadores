
package blockchain;

import java.util.ArrayList;

public class BlockchainTest {

    public static void main(String[] args) {

        // Cria uma cadeia de teste com dois blocos.
        Block bloco0 = new Block("Bloco original", "0");
        bloco0.mineBlock(NoobChain.difficulty);

        Block bloco1 = new Block("Segundo bloco", bloco0.hash);
        bloco1.mineBlock(NoobChain.difficulty);

        ArrayList<Block> cadeiaTeste = new ArrayList<>();
        cadeiaTeste.add(bloco0);
        cadeiaTeste.add(bloco1);

        System.out.println(
            "Cadeia original válida: "
            + NoobChain.isChainValid(cadeiaTeste)
        );

        // Simula adulteração: quebra o elo com o bloco anterior.
        bloco1.previousHash = "hash_adulterado";

        System.out.println(
            "Cadeia adulterada válida: "
            + NoobChain.isChainValid(cadeiaTeste)
        );
    }
}
