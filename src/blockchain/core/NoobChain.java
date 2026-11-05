
package blockchain.core;

import java.util.ArrayList;
import java.util.List;

public class NoobChain {

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

    private static ArrayList<Block> blockchain = new ArrayList<>();

    public static int difficulty = 3;

    public static void main(String[] args) {
        initializeBlockchain();

        System.out.println(
            "Blockchain is Valid: " + isChainValid()
        );
    }

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

    public static synchronized ArrayList<Block> getBlockchain() {
        return new ArrayList<>(blockchain);
    }

    public static synchronized int size() {
        return blockchain.size();
    }

    public static synchronized void setBlockchain(List<Block> chain) {
        blockchain = new ArrayList<>(chain);
    }

    public static synchronized void clear() {
        blockchain.clear();
    }

    public static synchronized Boolean isChainValid() {
        return isChainValid(blockchain);
    }

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

            if (currentBlock == null
                    || currentBlock.hash == null
                    || currentBlock.previousHash == null) {
                return false;
            }

            String calculatedHash = currentBlock.calculateHash();

            if (!currentBlock.hash.equals(calculatedHash)) {
                return false;
            }

            if (!currentBlock.hash.startsWith(target)) {
                return false;
            }

            if (i == 0) {
                if (!"0".equals(currentBlock.previousHash)) {
                    return false;
                }
            } else {
                Block previousBlock = chain.get(i - 1);

                if (previousBlock == null
                        || previousBlock.hash == null) {
                    return false;
                }

                if (!currentBlock.previousHash.equals(
                        previousBlock.hash)) {
                    return false;
                }
            }
        }

        return true;
    }

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

        blockchain = new ArrayList<>(received);

        System.out.println(
            "Nova blockchain adotada: "
            + blockchain.size() + " blocos."
        );

        return AdoptResult.ADOPTED;
    }

    public static boolean adoptBlockchain(List<Block> received) {
        return tryAdopt(received) == AdoptResult.ADOPTED;
    }

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
