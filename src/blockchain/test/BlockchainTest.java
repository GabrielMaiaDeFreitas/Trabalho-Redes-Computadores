
package blockchain.test;

import java.util.ArrayList;

import blockchain.core.Block;
import blockchain.core.NoobChain;

public class BlockchainTest {

    public static void main(String[] args) {

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

        bloco1.previousHash = "hash_adulterado";

        System.out.println(
            "Cadeia adulterada válida: "
            + NoobChain.isChainValid(cadeiaTeste)
        );
    }
}
