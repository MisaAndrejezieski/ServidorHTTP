import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ServidorWebSocket {
    private static final int PORTA_WS = 8081;
    private static final Map<String, WebSocketClient> clientes = new ConcurrentHashMap<>();

    public static void main(String[] args) {
        System.out.println("🔌 Servidor WebSocket rodando na porta " + PORTA_WS);
        System.out.println("📡 Conecte-se em: ws://localhost:" + PORTA_WS);
        System.out.println("⏹️  Pressione CTRL+C para parar\n");

        try (ServerSocket server = new ServerSocket(PORTA_WS)) {
            while (true) {
                Socket cliente = server.accept();
                new Thread(() -> handleClient(cliente)).start();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void handleClient(Socket socket) {
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            OutputStream out = socket.getOutputStream();

            // Lê a primeira linha da requisição
            String linha = in.readLine();
            if (linha == null || !linha.contains("GET")) {
                socket.close();
                return;
            }

            // Lê os cabeçalhos
            Map<String, String> headers = new HashMap<>();
            while ((linha = in.readLine()) != null && !linha.isEmpty()) {
                if (linha.contains(":")) {
                    String[] parts = linha.split(": ", 2);
                    headers.put(parts[0], parts[1]);
                }
            }

            // Verifica se é WebSocket
            String upgrade = headers.get("Upgrade");
            if (upgrade == null || !upgrade.equalsIgnoreCase("websocket")) {
                socket.close();
                return;
            }

            String key = headers.get("Sec-WebSocket-Key");
            if (key == null) {
                socket.close();
                return;
            }

            // Calcula o accept
            String accept = calcularAccept(key);

            // Resposta de handshake
            String response = "HTTP/1.1 101 Switching Protocols\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Accept: " + accept + "\r\n" +
                    "\r\n";

            out.write(response.getBytes());
            out.flush();

            String id = "ws-" + UUID.randomUUID().toString().substring(0, 6);
            WebSocketClient clienteWS = new WebSocketClient(socket, id);
            clientes.put(id, clienteWS);

            System.out.println("✅ Cliente conectado: " + id + " (Total: " + clientes.size() + ")");

            // Envia mensagem de boas-vindas
            clienteWS.enviarMensagem("🌸 Bem-vindo ao chat! ID: " + id);

            // Loop de leitura
            while (true) {
                String mensagem = clienteWS.lerMensagem();
                if (mensagem == null) break;

                System.out.println("📨 " + id + ": " + mensagem);

                // Comandos
                if (mensagem.startsWith("/")) {
                    String[] cmd = mensagem.split(" ", 2);
                    switch (cmd[0].toLowerCase()) {
                        case "/nick":
                            if (cmd.length > 1) {
                                clienteWS.nome = cmd[1];
                                clienteWS.enviarMensagem("✅ Nome alterado para: " + cmd[1]);
                            }
                            break;
                        case "/ping":
                            clienteWS.enviarMensagem("🏓 pong");
                            break;
                        case "/sair":
                            clienteWS.enviarMensagem("👋 Saindo...");
                            clientes.remove(id);
                            broadcast("🌙 " + clienteWS.nome + " saiu do chat.");
                            clienteWS.fechar();
                            return;
                        default:
                            clienteWS.enviarMensagem("❌ Comandos: /nick, /ping, /sair");
                    }
                } else {
                    broadcast("💬 " + clienteWS.nome + ": " + mensagem);
                }
            }

        } catch (IOException e) {
            System.err.println("⚠️ Cliente desconectado");
        } finally {
            try {
                socket.close();
            } catch (IOException e) {}
        }
    }

    private static void broadcast(String mensagem) {
        for (WebSocketClient cliente : clientes.values()) {
            try {
                cliente.enviarMensagem(mensagem);
            } catch (IOException e) {}
        }
    }

    private static String calcularAccept(String key) {
        try {
            String guid = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
            String concat = key + guid;
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(concat.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (Exception e) {
            return "";
        }
    }

    static class WebSocketClient {
        private Socket socket;
        private InputStream in;
        private OutputStream out;
        public String id;
        public String nome;

        public WebSocketClient(Socket socket, String id) throws IOException {
            this.socket = socket;
            this.in = socket.getInputStream();
            this.out = socket.getOutputStream();
            this.id = id;
            this.nome = "Anônimo-" + id;
        }

        public void enviarMensagem(String mensagem) throws IOException {
            byte[] dados = mensagem.getBytes(StandardCharsets.UTF_8);
            int tamanho = dados.length;

            byte[] frame;
            if (tamanho <= 125) {
                frame = new byte[2 + tamanho];
                frame[0] = (byte) 0x81;
                frame[1] = (byte) tamanho;
                System.arraycopy(dados, 0, frame, 2, tamanho);
            } else if (tamanho <= 65535) {
                frame = new byte[4 + tamanho];
                frame[0] = (byte) 0x81;
                frame[1] = (byte) 126;
                frame[2] = (byte) ((tamanho >> 8) & 0xFF);
                frame[3] = (byte) (tamanho & 0xFF);
                System.arraycopy(dados, 0, frame, 4, tamanho);
            } else {
                frame = new byte[10 + tamanho];
                frame[0] = (byte) 0x81;
                frame[1] = (byte) 127;
                for (int i = 0; i < 8; i++) {
                    frame[2 + i] = (byte) ((tamanho >> (8 * (7 - i))) & 0xFF);
                }
                System.arraycopy(dados, 0, frame, 10, tamanho);
            }

            out.write(frame);
            out.flush();
        }

        public String lerMensagem() throws IOException {
            try {
                int b1 = in.read();
                if (b1 == -1) return null;
                if ((b1 & 0x0F) == 0x08) return null;

                int b2 = in.read();
                if (b2 == -1) return null;

                boolean mascara = (b2 & 0x80) != 0;
                int tamanho = b2 & 0x7F;

                if (tamanho == 126) {
                    tamanho = (in.read() << 8) | in.read();
                } else if (tamanho == 127) {
                    tamanho = 0;
                    for (int i = 0; i < 8; i++) {
                        tamanho = (tamanho << 8) | in.read();
                    }
                }

                byte[] mascaraBytes = null;
                if (mascara) {
                    mascaraBytes = new byte[4];
                    in.read(mascaraBytes);
                }

                byte[] dados = new byte[tamanho];
                int lidos = 0;
                while (lidos < tamanho) {
                    int r = in.read(dados, lidos, tamanho - lidos);
                    if (r == -1) break;
                    lidos += r;
                }

                if (mascara && mascaraBytes != null) {
                    for (int i = 0; i < dados.length; i++) {
                        dados[i] ^= mascaraBytes[i % 4];
                    }
                }

                return new String(dados, 0, lidos, StandardCharsets.UTF_8);
            } catch (SocketException e) {
                return null;
            }
        }

        public void fechar() {
            try {
                socket.close();
            } catch (IOException e) {}
        }
    }
}