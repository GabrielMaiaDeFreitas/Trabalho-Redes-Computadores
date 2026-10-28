
package blockchain;

import java.util.ArrayList;

public class NoobChain {

    // Lista que armazena a blockchain.
    public static ArrayList<Block> blockchain = new ArrayList<>();

    // Número de zeros exigidos no início do hash.
    public static int difficulty = 3;

    public static void main(String[] args) {
        initializeBlockchain();

        System.out.println(
            "Blockchain is Valid: " + isChainValid()
        );
    }

    // Cria a blockchain inicial com três blocos.
    public static void initializeBlockchain() {
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
            "\nBlockchain is Valid: " + isChainValid()
        );
    }

    // Valida a blockchain local.
    public static Boolean isChainValid() {
        return isChainValid(blockchain);
    }

    // Valida uma cadeia recebida ou a cadeia local.
    public static boolean isChainValid(ArrayList<Block> chain) {

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

    // Adota uma cadeia apenas se for válida e maior.
    public static boolean adoptBlockchain(
            ArrayList<Block> received) {

        if (received == null) {
            System.out.println("Cadeia recebida nula.");
            return false;
        }

        if (!isChainValid(received)) {
            System.out.println("Cadeia recebida inválida.");
            return false;
        }

        if (received.size() <= blockchain.size()) {
            System.out.println(
                "Cadeia recebida não é mais longa."
            );
            return false;
        }

        // Cria uma cópia da lista recebida.
        blockchain = new ArrayList<>(received);

        System.out.println(
            "Nova blockchain adotada: "
            + blockchain.size() + " blocos."
        );

        return true;
    }

    // Cria, minera e propaga um novo bloco.
    public static void mineNewBlock(String data) {

        if (blockchain.isEmpty()) {
            System.out.println(
                "Não é possível minerar: blockchain vazia."
            );
            return;
        }

        if (data == null || data.trim().isEmpty()) {
            System.out.println(
                "Não é possível minerar um bloco sem dados."
            );
            return;
        }

        Block previousBlock =
            blockchain.get(blockchain.size() - 1);

        Block newBlock = new Block(
            data,
            previousBlock.hash
        );

        System.out.println("Tentando minerar novo bloco...");
        newBlock.mineBlock(difficulty);

        blockchain.add(newBlock);

        System.out.println(
            "Novo bloco adicionado. Total: "
            + blockchain.size()
        );

        NodeServer.broadcastBlockchain();
    }
}
