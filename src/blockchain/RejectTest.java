
package blockchain;

import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;

public class RejectTest {
    public static void main(String[] args) {
        String host = args.length > 0 ? args[0] : "localhost";
        int port = args.length > 1
                ? Integer.parseInt(args[1]) : 5000;

        try (Socket socket = new Socket(host, port);
             ObjectOutputStream out =
                     new ObjectOutputStream(socket.getOutputStream());
             ObjectInputStream in =
                     new ObjectInputStream(socket.getInputStream())) {

            out.flush();

            Message request = new Message("TIPO_DESCONHECIDO");
            out.writeObject(request);
            out.flush();

            Object response = in.readObject();

            if (response instanceof Message) {
                Message message = (Message) response;

                System.out.println(
                        "Tipo de resposta: " + message.getType());
                System.out.println(
                        "Detalhe: " + message.getData());

                if ("REJECT".equals(message.getType())) {
                    System.out.println(
                            "TESTE PASSOU: mensagem rejeitada.");
                } else {
                    System.out.println(
                            "TESTE FALHOU: resposta inesperada.");
                }
            } else {
                System.out.println(
                        "TESTE FALHOU: resposta não reconhecida.");
            }

        } catch (Exception e) {
            System.out.println("Erro no teste: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
