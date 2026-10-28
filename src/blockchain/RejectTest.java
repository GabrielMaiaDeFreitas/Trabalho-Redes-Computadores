
package blockchain;

import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;

/**
 * Verifica as rejeições do handshake:
 *   1. Mensagem enviada antes do HELLO -> REJECT.
 *   2. HELLO com versão de protocolo errada -> REJECT.
 *   3. HELLO correto -> ACK.
 * Uso: RejectTest [host] [porta]
 */
public class RejectTest {

    public static void main(String[] args) {
        String host = args.length > 0 ? args[0] : "localhost";
        int port = args.length > 1
                ? Integer.parseInt(args[1]) : 5000;

        check(host, port,
                "Teste 1 - GET_BLOCKCHAIN sem HELLO",
                new NetworkMessage(MessageType.GET_BLOCKCHAIN),
                MessageType.REJECT);

        check(host, port,
                "Teste 2 - HELLO com versão errada",
                new NetworkMessage(MessageType.HELLO, "V9.9-RejectTest"),
                MessageType.REJECT);

        check(host, port,
                "Teste 3 - HELLO correto",
                new NetworkMessage(MessageType.HELLO,
                        PeerConnection.PROTOCOL_VERSION + "-RejectTest"),
                MessageType.ACK);
    }

    // Envia uma única mensagem numa conexão nova e confere o tipo da resposta.
    private static void check(String host, int port, String name,
            NetworkMessage request, MessageType expected) {

        try (Socket socket = new Socket(host, port)) {
            socket.setSoTimeout(5000);

            ObjectOutputStream out =
                    new ObjectOutputStream(socket.getOutputStream());
            out.flush();
            ObjectInputStream in =
                    new ObjectInputStream(socket.getInputStream());

            out.writeObject(request);
            out.flush();

            Object response = in.readObject();

            if (!(response instanceof NetworkMessage)) {
                System.out.println(name + ": FALHOU (resposta não reconhecida)");
                return;
            }

            NetworkMessage message = (NetworkMessage) response;

            System.out.println(
                    name + ": " + (message.getType() == expected
                            ? "PASSOU" : "FALHOU")
                    + " | resposta: " + message);

        } catch (Exception e) {
            System.out.println(name + ": erro - " + e.getMessage());
        }
    }
}
