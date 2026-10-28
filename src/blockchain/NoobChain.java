
package blockchain;

import java.util.ArrayList;
import java.util.List;

/**
 * Blockchain local do nó.
 *
 * A cadeia é acessada por várias threads (uma por conexão, a thread de
 * sincronização e o console), por isso todo acesso passa por métodos
 * sincronizados em NoobChain.class e quem está fora recebe apenas cópias.
 */
public class NoobChain {

    // Resultado da tentativa de adotar uma cadeia recebida.
    public enum AdoptResult {
        ADOPTED("Cadeia adotada."),
        INVALID("Cadeia inválida."),
        NOT_LONGER("Tamanho menor ou igual à cadeia local.");

        private final String reason;

        AdoptResult(String reason) {
            this.reason = reason;
        }

        public String getReason() {
            return reason;
        }
    }

    // Lista que armazena a blockchain (acesso apenas pelos métodos abaixo).
    private static ArrayList<Block> blockchain = new ArrayList<>();

    // Número de zeros exigidos no início do hash.
    public static int difficulty = 3;

    public static void main(String[] args) {
        initializeBlockchain();

        System.out.println(
            "Blockchain is Valid: " + isChainValid()
        );
    }

    // Cria a blockchain inicial com três blocos.
    public static synchronized void initializeBlockchain() {
        blockchain.clear();

        blockchain.add(new Block("Hi im the first block", "0"));

        System.out.println("Trying to Mine block 1...");
        blockchain.get(0).mineBlock(difficulty);

        blockchain.add(new Block(
            "Yo im the second bloc vk",
            blockchain.get(blockchain.size() - 1).hash
        ));

        System.out.println("Trying to Mine block 2...");
        blockchain.get(1).mineBlock(difficulty);

        blockchain.add(new Block(
            "Hey im the third block",
            blockchain.get(blockchain.size() - 1).hash
        ));

        System.out.println("Trying to Mine block 3...");
        blockchain.get(2).mineBlock(difficulty);

        System.out.println(
            "\nBlockchain is Valid: " + isChainValid(blockchain)
        );
    }

    // Devolve uma cópia da cadeia local (segura para enviar pela rede).
    public static synchronized ArrayList<Block> getBlockchain() {
        return new ArrayList<>(blockchain);
    }

    public static synchronized int size() {
        return blockchain.size();
    }

    // Substitui a cadeia local sem validar (usado pelos testes).
    public static synchronized void setBlockchain(List<Block> chain) {
        blockchain = new ArrayList<>(chain);
    }

    public static synchronized void clear() {
        blockchain.clear();
    }

    // Valida a blockchain local.
    public static synchronized Boolean isChainValid() {
        return isChainValid(blockchain);
    }

    // Valida uma cadeia recebida ou a cadeia local.
    public static boolean isChainValid(List<Block> chain) {

        if (chain == null || chain.isEmpty()) {
            return false;
        }

        if (difficulty < 0) {
            return false;
        }

        String target = new String(
            new char[difficulty]
        ).replace('\0', '0');

        for (int i = 0; i < chain.size(); i++) {

            Block currentBlock = chain.get(i);

            // Confere se o bloco e os hashes existem.
            if (currentBlock == null
                    || currentBlock.hash == null
                    || currentBlock.previousHash == null) {
                return false;
            }

            // Confere se o hash corresponde aos dados do bloco.
            String calculatedHash = currentBlock.calculateHash();

            if (!currentBlock.hash.equals(calculatedHash)) {
                return false;
            }

            // Confere a prova de trabalho de todos os blocos.
            if (!currentBlock.hash.startsWith(target)) {
                return false;
            }

            if (i == 0) {
                // O bloco inicial deve apontar para "0".
                if (!"0".equals(currentBlock.previousHash)) {
                    return false;
                }
            } else {
                Block previousBlock = chain.get(i - 1);

                if (previousBlock == null
                        || previousBlock.hash == null) {
                    return false;
                }

                // Confere a ligação com o bloco anterior.
                if (!currentBlock.previousHash.equals(
                        previousBlock.hash)) {
                    return false;
                }
            }
        }

        return true;
    }

    /**
     * Regra de consenso (Fluxo B): adota a cadeia recebida apenas se ela
     * for válida E maior que a local. A verificação e a troca acontecem
     * sob o mesmo lock, então duas threads não conseguem adotar cadeias
     * ao mesmo tempo.
     */
    public static synchronized AdoptResult tryAdopt(List<Block> received) {

        if (received == null || !isChainValid(received)) {
            System.out.println("Cadeia recebida inválida.");
            return AdoptResult.INVALID;
        }

        if (received.size() <= blockchain.size()) {
            System.out.println(
                "Cadeia recebida não é mais longa ("
                + received.size() + " <= " + blockchain.size() + ")."
            );
            return AdoptResult.NOT_LONGER;
        }

        // Cria uma cópia da lista recebida.
        blockchain = new ArrayList<>(received);

        System.out.println(
            "Nova blockchain adotada: "
            + blockchain.size() + " blocos."
        );

        return AdoptResult.ADOPTED;
    }

    // Versão booleana de tryAdopt (mantida para os testes).
    public static boolean adoptBlockchain(List<Block> received) {
        return tryAdopt(received) == AdoptResult.ADOPTED;
    }

    /**
     * Cria e minera um novo bloco no topo da cadeia local.
     * Não faz broadcast: quem chama decide se propaga (NodeServer).
     *
     * A mineração acontece fora do lock para não bloquear o nó. Se a
     * cadeia mudar enquanto minera (outro nó propagou um bloco), o bloco
     * é minerado de novo sobre o novo topo.
     */
    public static boolean mineNewBlock(String data) {

        if (data == null || data.trim().isEmpty()) {
            System.out.println(
                "Não é possível minerar um bloco sem dados."
            );
            return false;
        }

        while (true) {
            Block previousBlock;

            synchronized (NoobChain.class) {
                if (blockchain.isEmpty()) {
                    System.out.println(
                        "Não é possível minerar: blockchain vazia."
                    );
                    return false;
                }

                previousBlock = blockchain.get(blockchain.size() - 1);
            }

            Block newBlock = new Block(data, previousBlock.hash);

            System.out.println("Tentando minerar novo bloco...");
            newBlock.mineBlock(difficulty);

            synchronized (NoobChain.class) {
                Block top = blockchain.get(blockchain.size() - 1);

                if (top.hash.equals(previousBlock.hash)) {
                    blockchain.add(newBlock);

                    System.out.println(
                        "Novo bloco adicionado. Total: "
                        + blockchain.size()
                    );
                    return true;
                }
            }

            System.out.println(
                "A cadeia mudou durante a mineração; "
                + "minerando novamente sobre o novo topo."
            );
        }
    }
}
