package blockchain;

/**
 * Operações do protocolo BCP (seção 4.2 da especificação).
 */
public enum MessageType {

    // Handshake inicial: payload String "V1.0-<id do nó>".
    HELLO,

    // Pede a lista de peers conhecidos: payload null.
    GET_PEERS,

    // Lista de peers no formato "IP:Porta": payload List<String>.
    PEERS_LIST,

    // Pede a cópia completa da blockchain: payload null.
    GET_BLOCKCHAIN,

    // Cópia da blockchain (resposta ou broadcast): payload ArrayList<Block>.
    BLOCKCHAIN_COPY,

    // Rejeição de mensagem ou cadeia: payload String com o motivo.
    REJECT,

    // Confirmação de que a operação deu certo: payload String opcional.
    ACK
}
