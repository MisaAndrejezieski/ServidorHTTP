import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ServidorHTTP {
    private static final int PORTA = 8080;
    private static final String DIRETORIO_PUBLICO = "./public";
    private static final Map<String, String> TIPOS_MIME = new HashMap<>();

    private static final Map<String, Map<String, Object>> SESSOES = new ConcurrentHashMap<>();
    private static final Map<String, Long> SESSOES_EXPIRACAO = new ConcurrentHashMap<>();
    private static final long TEMPO_EXPIRACAO_SESSAO = 30 * 60 * 1000;

    private static final Map<String, WebSocketConnection> WEBSOCKETS = new ConcurrentHashMap<>();
    private static int websocketIdCounter = 0;

    static {
        TIPOS_MIME.put("html", "text/html");
        TIPOS_MIME.put("css", "text/css");
        TIPOS_MIME.put("js", "application/javascript");
        TIPOS_MIME.put("png", "image/png");
        TIPOS_MIME.put("jpg", "image/jpeg");
        TIPOS_MIME.put("jpeg", "image/jpeg");
        TIPOS_MIME.put("gif", "image/gif");
        TIPOS_MIME.put("json", "application/json");
        TIPOS_MIME.put("txt", "text/plain");
        TIPOS_MIME.put("ico", "image/x-icon");
        TIPOS_MIME.put("xml", "application/xml");
        TIPOS_MIME.put("pdf", "application/pdf");
        TIPOS_MIME.put("zip", "application/zip");
    }

    public static void main(String[] args) {
        criarDiretorioPublico();

        ExecutorService threadPool = Executors.newCachedThreadPool();

        try (ServerSocket servidor = new ServerSocket(PORTA)) {
            System.out.println("🚀 Servidor HTTP/WS rodando em http://localhost:" + PORTA);
            System.out.println("📁 Servindo arquivos de: " + new File(DIRETORIO_PUBLICO).getAbsolutePath());
            System.out.println("🧵 Multi-thread com pool dinâmico");
            System.out.println("🍪 Cookies e Sessões ativos");
            System.out.println("🔌 WebSockets disponíveis em: ws://localhost:" + PORTA + "/ws");
            System.out.println("📄 Páginas: / (home) | /projetos | /contato | /ws (chat)");
            System.out.println("⏹️  Pressione CTRL+C para parar\n");

            Thread cleaner = new Thread(() -> {
                while (true) {
                    try {
                        Thread.sleep(60000);
                        limparSessoesExpiradas();
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            });
            cleaner.setDaemon(true);
            cleaner.start();

            while (true) {
                Socket cliente = servidor.accept();
                threadPool.execute(() -> {
                    try {
                        handleConnection(cliente);
                    } catch (IOException e) {
                        System.err.println("❌ Erro: " + e.getMessage());
                    } finally {
                        try {
                            cliente.close();
                        } catch (IOException e) {}
                    }
                });
            }
        } catch (IOException e) {
            System.err.println("❌ Não foi possível iniciar o servidor na porta " + PORTA);
            e.printStackTrace();
        } finally {
            threadPool.shutdown();
        }
    }

    // ==================== HANDLE CONNECTION ====================
    private static void handleConnection(Socket cliente) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(cliente.getInputStream()));
        OutputStream out = cliente.getOutputStream();

        String linha = in.readLine();
        if (linha == null || linha.isEmpty()) return;

        System.out.println("📥 " + linha);

        String[] partes = linha.split(" ");
        if (partes.length < 3) return;

        String metodo = partes[0];
        String caminho = partes[1];
        String[] caminhoParts = caminho.split("\\?");
        String caminhoBase = caminhoParts[0];

        Map<String, String> cabecalhos = new HashMap<>();
        while ((linha = in.readLine()) != null && !linha.isEmpty()) {
            String[] chaveValor = linha.split(": ", 2);
            if (chaveValor.length == 2) {
                cabecalhos.put(chaveValor[0], chaveValor[1]);
            }
        }

        // ============ VERIFICA WEBSOCKET PRIMEIRO ============
        if (cabecalhos.containsKey("Upgrade") && cabecalhos.get("Upgrade").equalsIgnoreCase("websocket")) {
            System.out.println("🔌 Handshake WebSocket detectado!");
            handleWebSocket(cliente, cabecalhos);
            return;
        }

        StringBuilder corpo = new StringBuilder();
        if (cabecalhos.containsKey("Content-Length")) {
            int contentLength = Integer.parseInt(cabecalhos.get("Content-Length"));
            for (int i = 0; i < contentLength; i++) {
                corpo.append((char) in.read());
            }
        }

        Map<String, String> cookies = parseCookies(cabecalhos.getOrDefault("Cookie", ""));

        String sessionId = cookies.get("SESSION_ID");
        Map<String, Object> sessao = getSession(sessionId);
        if (sessao == null) {
            sessionId = gerarSessionId();
            sessao = new ConcurrentHashMap<>();
            SESSOES.put(sessionId, sessao);
            SESSOES_EXPIRACAO.put(sessionId, System.currentTimeMillis() + TEMPO_EXPIRACAO_SESSAO);
        }

        SESSOES_EXPIRACAO.put(sessionId, System.currentTimeMillis() + TEMPO_EXPIRACAO_SESSAO);

        // ============ ROTEAMENTO ============
        String response;
        if (caminhoBase.equals("/") || caminhoBase.equals("/index.html")) {
            response = servirArquivo("/index.html");
        } else if (caminhoBase.startsWith("/api/")) {
            response = processarAPI(metodo, caminho, corpo.toString(), sessao);
        } else if (caminhoBase.equals("/status")) {
            response = enviarStatus();
        } else if (caminhoBase.equals("/sessao")) {
            response = mostrarSessao(sessao);
        } else if (caminhoBase.equals("/ws")) {
            response = servirArquivo("/websocket.html");
        } else if (caminhoBase.equals("/projetos")) {
            response = servirArquivo("/projetos.html");
        } else if (caminhoBase.equals("/contato")) {
            response = servirArquivo("/contato.html");
        } else {
            response = servirArquivo(caminhoBase);
        }

        if (response != null && !response.isEmpty() && !response.contains("Set-Cookie")) {
            String cookieHeader = "Set-Cookie: SESSION_ID=" + sessionId + "; Path=/; HttpOnly\r\n";
            int headerEnd = response.indexOf("\r\n\r\n");
            if (headerEnd > 0) {
                response = response.substring(0, headerEnd) + "\r\n" + cookieHeader + response.substring(headerEnd);
            }
        }

        if (response != null) {
            out.write(response.getBytes());
            out.flush();
        }
    }

    // ==================== COOKIES ====================
    private static Map<String, String> parseCookies(String cookieStr) {
        Map<String, String> cookies = new HashMap<>();
        if (cookieStr == null || cookieStr.isEmpty()) return cookies;
        for (String cookie : cookieStr.split("; ")) {
            String[] parts = cookie.split("=", 2);
            if (parts.length == 2) {
                cookies.put(parts[0], parts[1]);
            }
        }
        return cookies;
    }

    // ==================== SESSÕES ====================
    private static String gerarSessionId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static Map<String, Object> getSession(String sessionId) {
        if (sessionId == null) return null;
        Map<String, Object> sessao = SESSOES.get(sessionId);
        if (sessao != null) {
            Long expiracao = SESSOES_EXPIRACAO.get(sessionId);
            if (expiracao != null && expiracao > System.currentTimeMillis()) {
                return sessao;
            } else {
                SESSOES.remove(sessionId);
                SESSOES_EXPIRACAO.remove(sessionId);
            }
        }
        return null;
    }

    private static void limparSessoesExpiradas() {
        long agora = System.currentTimeMillis();
        for (Map.Entry<String, Long> entry : SESSOES_EXPIRACAO.entrySet()) {
            if (entry.getValue() < agora) {
                SESSOES.remove(entry.getKey());
                SESSOES_EXPIRACAO.remove(entry.getKey());
            }
        }
    }

    // ==================== WEB SOCKETS ====================

    private static String gerarAcceptWebSocket(String key) {
        try {
            String guid = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
            String concat = key + guid;
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(concat.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            return "";
        }
    }

    private static void handleWebSocket(Socket cliente, Map<String, String> cabecalhos) throws IOException {
        String key = cabecalhos.get("Sec-WebSocket-Key");
        if (key == null) {
            System.err.println("❌ WebSocket: Sec-WebSocket-Key não encontrada");
            return;
        }

        System.out.println("🔑 WebSocket Key: " + key);

        String accept = gerarAcceptWebSocket(key);
        
        String response = "HTTP/1.1 101 Switching Protocols\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: " + accept + "\r\n" +
                "\r\n";

        cliente.getOutputStream().write(response.getBytes());
        cliente.getOutputStream().flush();
        
        System.out.println("✅ WebSocket handshake concluído!");

        String wsId = "ws-" + (++websocketIdCounter);
        WebSocketConnection ws = new WebSocketConnection(cliente, wsId);
        WEBSOCKETS.put(wsId, ws);

        System.out.println("🔌 WebSocket conectado: " + wsId + " (Total: " + WEBSOCKETS.size() + ")");

        ws.sendMessage("🌸 Bem-vindo ao chat neon, " + ws.getNome() + "!");
        broadcast("🌸 " + ws.getNome() + " entrou no chat!");

        try {
            while (true) {
                String mensagem = ws.readMessage();
                if (mensagem == null) break;

                System.out.println("📨 WebSocket " + wsId + ": " + mensagem);

                if (mensagem.startsWith("/")) {
                    String[] cmd = mensagem.split(" ", 2);
                    switch (cmd[0].toLowerCase()) {
                        case "/nick":
                            if (cmd.length > 1) {
                                ws.setNome(cmd[1]);
                                ws.sendMessage("✅ Nome alterado para: " + cmd[1]);
                            }
                            break;
                        case "/ping":
                            ws.sendMessage("🏓 pong");
                            break;
                        case "/sair":
                            ws.sendMessage("👋 Saindo...");
                            WEBSOCKETS.remove(wsId);
                            broadcast("🌙 " + ws.getNome() + " saiu do chat.");
                            ws.close();
                            return;
                        default:
                            ws.sendMessage("❌ Comandos: /nick, /ping, /sair");
                    }
                } else {
                    broadcast("💬 " + ws.getNome() + ": " + mensagem);
                }
            }
        } catch (IOException e) {
            System.err.println("⚠️ WebSocket " + wsId + " desconectado");
        } finally {
            WEBSOCKETS.remove(wsId);
            broadcast("🌙 " + ws.getNome() + " saiu do chat.");
            ws.close();
            System.out.println("🔌 WebSocket desconectado: " + wsId + " (Total: " + WEBSOCKETS.size() + ")");
        }
    }

    private static void broadcast(String mensagem) {
        for (WebSocketConnection ws : WEBSOCKETS.values()) {
            try {
                ws.sendMessage(mensagem);
            } catch (IOException e) {}
        }
    }

    static class WebSocketConnection {
        private Socket socket;
        private InputStream in;
        private OutputStream out;
        private String nome;

        public WebSocketConnection(Socket socket, String id) throws IOException {
            this.socket = socket;
            this.in = socket.getInputStream();
            this.out = socket.getOutputStream();
            this.nome = "Anônimo-" + id.substring(0, 6);
        }

        public void setNome(String nome) {
            this.nome = nome;
        }

        public String getNome() {
            return nome;
        }

        public void sendMessage(String mensagem) throws IOException {
            byte[] dados = mensagem.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            int tamanho = dados.length;

            byte[] frame = new byte[2 + tamanho];
            frame[0] = (byte) 0x81;
            if (tamanho <= 125) {
                frame[1] = (byte) tamanho;
                System.arraycopy(dados, 0, frame, 2, tamanho);
            } else if (tamanho <= 65535) {
                frame[1] = (byte) 126;
                frame = new byte[4 + tamanho];
                frame[0] = (byte) 0x81;
                frame[1] = (byte) 126;
                frame[2] = (byte) ((tamanho >> 8) & 0xFF);
                frame[3] = (byte) (tamanho & 0xFF);
                System.arraycopy(dados, 0, frame, 4, tamanho);
            } else {
                frame[1] = (byte) 127;
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

        public String readMessage() throws IOException {
            try {
                int b1 = in.read();
                if (b1 == -1) return null;
                
                if ((b1 & 0x0F) == 0x08) {
                    return null;
                }
                
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
                    int lidos = in.read(mascaraBytes);
                    if (lidos < 4) return null;
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
                
                return new String(dados, 0, lidos, java.nio.charset.StandardCharsets.UTF_8);
            } catch (SocketException e) {
                return null;
            }
        }

        public void close() {
            try {
                socket.close();
            } catch (IOException e) {}
        }
    }

    // ==================== SERVE ARQUIVOS ====================
    private static String servirArquivo(String caminho) throws IOException {
        File arquivo = new File(DIRETORIO_PUBLICO + caminho);

        if (!arquivo.exists() || arquivo.isDirectory()) {
            return enviarErro(404, "Arquivo não encontrado");
        }

        String extensao = "";
        int idxPonto = caminho.lastIndexOf('.');
        if (idxPonto > 0) {
            extensao = caminho.substring(idxPonto + 1).toLowerCase();
        }
        String mimeType = TIPOS_MIME.getOrDefault(extensao, "application/octet-stream");

        byte[] conteudo = Files.readAllBytes(arquivo.toPath());

        return "HTTP/1.1 200 OK\r\n" +
                "Content-Type: " + mimeType + "\r\n" +
                "Content-Length: " + conteudo.length + "\r\n" +
                "Server: JavaHTTPServer/3.0\r\n" +
                "Date: " + new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z").format(new Date()) + "\r\n" +
                "Connection: close\r\n" +
                "\r\n" +
                new String(conteudo);
    }

    // ==================== API ====================
    private static String processarAPI(String metodo, String caminho, String corpo,
                                        Map<String, Object> sessao) throws IOException {
        String resposta = "";
        int status = 200;

        if (caminho.startsWith("/api/hello")) {
            String nome = extrairParametro(caminho, "nome");
            if (nome == null) nome = "Mundo";
            sessao.put("ultimoNome", nome);
            sessao.put("ultimaVisita", new Date().toString());
            resposta = "{\"mensagem\": \"Olá, " + nome + "!\", \"timestamp\": \"" + new Date() + "\"}";
        } else if (metodo.equals("POST") && caminho.equals("/api/echo")) {
            resposta = "{\"recebido\": " + corpo + "}";
        } else if (caminho.equals("/api/status")) {
            resposta = "{\"status\": \"online\", \"versao\": \"3.0\", \"websockets\": " + WEBSOCKETS.size() + "}";
        } else if (caminho.equals("/api/hora")) {
            resposta = "{\"hora\": \"" + new SimpleDateFormat("HH:mm:ss").format(new Date()) + "\"}";
        } else if (caminho.equals("/api/data")) {
            resposta = "{\"data\": \"" + new SimpleDateFormat("dd/MM/yyyy").format(new Date()) + "\"}";
        } else if (metodo.equals("POST") && caminho.equals("/api/contato")) {
            sessao.put("contato", corpo);
            resposta = "{\"mensagem\": \"Contato recebido com sucesso!\", \"dados\": " + corpo + "}";
        } else if (caminho.equals("/api/sessao")) {
            resposta = "{\"sessao\": " + mapToJson(sessao) + "}";
        } else if (caminho.equals("/api/contador")) {
            Integer contador = (Integer) sessao.getOrDefault("contador", 0);
            contador++;
            sessao.put("contador", contador);
            resposta = "{\"contador\": " + contador + "}";
        } else if (caminho.equals("/api/websockets")) {
            resposta = "{\"total\": " + WEBSOCKETS.size() + ", \"ids\": " + WEBSOCKETS.keySet() + "}";
        } else {
            status = 404;
            resposta = "{\"erro\": \"Endpoint não encontrado\"}";
        }

        return "HTTP/1.1 " + status + " " + (status == 200 ? "OK" : "Not Found") + "\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: " + resposta.length() + "\r\n" +
                "Server: JavaHTTPServer/3.0\r\n" +
                "Connection: close\r\n" +
                "\r\n" +
                resposta;
    }

    private static String mapToJson(Map<String, Object> map) {
        if (map == null || map.isEmpty()) return "{}";
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (!first) sb.append(", ");
            first = false;
            sb.append("\"").append(entry.getKey()).append("\": ");
            Object value = entry.getValue();
            if (value instanceof String) {
                sb.append("\"").append(value).append("\"");
            } else if (value instanceof Number) {
                sb.append(value);
            } else if (value instanceof Boolean) {
                sb.append(value);
            } else if (value instanceof Date) {
                sb.append("\"").append(value).append("\"");
            } else {
                sb.append("\"").append(value).append("\"");
            }
        }
        sb.append("}");
        return sb.toString();
    }

    // ==================== STATUS ====================
    private static String enviarStatus() {
        return "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/plain\r\n" +
                "Content-Length: 14\r\n" +
                "Server: JavaHTTPServer/3.0\r\n" +
                "Connection: close\r\n" +
                "\r\n" +
                "Servidor OK ✓";
    }

    // ==================== MOSTRAR SESSÃO ====================
    private static String mostrarSessao(Map<String, Object> sessao) {
        String html = "<html><body><h1>Sessão Atual</h1><pre>" + sessao + "</pre></body></html>";
        return "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/html\r\n" +
                "Content-Length: " + html.length() + "\r\n" +
                "Server: JavaHTTPServer/3.0\r\n" +
                "Connection: close\r\n" +
                "\r\n" +
                html;
    }

    // ==================== ERROS ====================
    private static String enviarErro(int codigo, String mensagem) {
        String resposta = "<html><body><h1>" + codigo + " " + mensagem + "</h1><p>Servidor Java HTTP</p></body></html>";
        return "HTTP/1.1 " + codigo + " " + mensagem + "\r\n" +
                "Content-Type: text/html\r\n" +
                "Content-Length: " + resposta.length() + "\r\n" +
                "Server: JavaHTTPServer/3.0\r\n" +
                "Connection: close\r\n" +
                "\r\n" +
                resposta;
    }

    // ==================== UTILITÁRIOS ====================
    private static String extrairParametro(String caminho, String parametro) {
        int idx = caminho.indexOf('?');
        if (idx == -1) return null;
        String query = caminho.substring(idx + 1);
        for (String par : query.split("&")) {
            String[] chaveValor = par.split("=");
            if (chaveValor.length == 2 && chaveValor[0].equals(parametro)) {
                return chaveValor[1];
            }
        }
        return null;
    }

    private static void criarDiretorioPublico() {
        File dir = new File(DIRETORIO_PUBLICO);
        if (!dir.exists()) {
            dir.mkdirs();
            System.out.println("📁 Diretório 'public' criado.");
        }

        criarArquivoIndex();
        criarArquivoProjetos();
        criarArquivoContato();
        criarArquivoWebSocket();
        criarArquivoStyle();
        criarArquivoScript();
        criarArquivoExemplo();
    }

    private static void criarArquivoIndex() {
        File file = new File(DIRETORIO_PUBLICO + "/index.html");
        if (!file.exists()) {
            try (FileWriter fw = new FileWriter(file)) {
                fw.write("""
                <!DOCTYPE html>
                <html lang="pt-br">
                <head>
                    <meta charset="UTF-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    <title>Misael Andrejezieski | Portfólio</title>
                    <link rel="stylesheet" href="style.css">
                </head>
                <body>
                    <canvas id="particles"></canvas>

                    <nav class="navbar">
                        <div class="nav-container">
                            <div class="nav-brand">
                                <span class="logo">🚀</span>
                                <span class="glitch-text" data-text="Misael">Misael</span>
                            </div>
                            <ul class="nav-menu">
                                <li><a href="/" class="active">Home</a></li>
                                <li><a href="/projetos">Projetos</a></li>
                                <li><a href="/contato">Contato</a></li>
                                <li><a href="/ws">💬 Chat</a></li>
                            </ul>
                            <div class="nav-status">
                                <span class="status-dot online"></span>
                                <span class="neon-text">#Online</span>
                            </div>
                        </div>
                    </nav>

                    <main>
                        <section class="hero">
                            <div class="hero-content">
                                <div class="hero-badge">
                                    <span class="neon-badge">✦ Transformo café em código ☕ → 💻</span>
                                </div>
                                <h1 class="hero-title">
                                    <span class="neon-text-pink">Misael</span>
                                    <span class="neon-text-blue">Andrejezieski</span>
                                </h1>
                                <h2 class="hero-subtitle">
                                    <span class="neon-text-cyan">Programador</span>
                                    <span class="neon-text-purple">|</span>
                                    <span class="neon-text-green">Front-end</span>
                                    <span class="neon-text-purple">|</span>
                                    <span class="neon-text-yellow">Café</span>
                                </h2>
                                <p class="hero-description">
                                    Formado em <strong>Análise e Desenvolvimento de Sistemas</strong> pela <strong>Unicesumar</strong>.<br>
                                    Faço sites que carregam rápido, funcionam bem e ainda conto piadas ruins no processo.<br>
                                    <span style="color: var(--neon-pink);">💻 "Transformo café em código"</span>
                                </p>
                                <div class="hero-buttons">
                                    <a href="/projetos" class="btn neon-btn-primary">✦ Ver Projetos</a>
                                    <a href="https://misaandrejezieski.github.io/Misa/" target="_blank" class="btn neon-btn-secondary">☕ Meu Site</a>
                                </div>
                            </div>
                            <div class="hero-illustration">
                                <div class="neon-orb pink"></div>
                                <div class="neon-orb cyan"></div>
                                <div class="neon-orb purple"></div>
                            </div>
                        </section>

                        <section class="social-links">
                            <h2 class="neon-subtitle">🌐 Onde me encontrar</h2>
                            <div class="social-grid">
                                <a href="https://misaandrejezieski.github.io/Misa/" target="_blank" class="social-card neon-card">
                                    <span class="social-icon">☕</span>
                                    <span class="social-name">Meu Site</span>
                                    <span class="social-desc">Transformo café em código</span>
                                </a>
                                <a href="https://github.com/MisaAndrejezieski" target="_blank" class="social-card neon-card">
                                    <span class="social-icon">🐙</span>
                                    <span class="social-name">GitHub</span>
                                    <span class="social-desc">Meus repositórios</span>
                                </a>
                                <a href="https://www.linkedin.com/in/misael-andrejezieski-b4996720a/" target="_blank" class="social-card neon-card">
                                    <span class="social-icon">🔗</span>
                                    <span class="social-name">LinkedIn</span>
                                    <span class="social-desc">Perfil profissional</span>
                                </a>
                                <a href="https://www.instagram.com/misaelandrejezieski/" target="_blank" class="social-card neon-card">
                                    <span class="social-icon">📸</span>
                                    <span class="social-name">Instagram</span>
                                    <span class="social-desc">@misaelandrejezieski</span>
                                </a>
                                <a href="https://www.facebook.com/profile.php?id=100034358779961" target="_blank" class="social-card neon-card">
                                    <span class="social-icon">👍</span>
                                    <span class="social-name">Facebook</span>
                                    <span class="social-desc">Misa Misa</span>
                                </a>
                            </div>
                        </section>

                        <section class="skills">
                            <h2 class="neon-subtitle">⚡ Tecnologias</h2>
                            <div class="skills-grid">
                                <span class="skill-tag neon-tag">Java</span>
                                <span class="skill-tag neon-tag">C#</span>
                                <span class="skill-tag neon-tag">.NET</span>
                                <span class="skill-tag neon-tag">JavaScript</span>
                                <span class="skill-tag neon-tag">HTML + CSS</span>
                                <span class="skill-tag neon-tag">SQL</span>
                                <span class="skill-tag neon-tag">Git</span>
                                <span class="skill-tag neon-tag">Linux</span>
                            </div>
                        </section>

                        <section class="projetos-destaque">
                            <h2 class="neon-subtitle">🚀 Projetos em Destaque</h2>
                            <div class="destaque-grid">
                                <a href="https://misaandrejezieski.github.io/Cotton-Candy-Kabukicho/" target="_blank" class="destaque-card neon-card">
                                    <span class="destaque-icon">🍸</span>
                                    <h3>Cotton Candy Kabukicho</h3>
                                    <p>Landing page neon para clube privê</p>
                                </a>
                                <a href="https://misaandrejezieski.github.io/NekoLamen/" target="_blank" class="destaque-card neon-card">
                                    <span class="destaque-icon">🍜</span>
                                    <h3>NekoLamen</h3>
                                    <p>Delivery de yakisoba artesanal</p>
                                </a>
                                <a href="https://misaandrejezieski.github.io/NeonOn/" target="_blank" class="destaque-card neon-card">
                                    <span class="destaque-icon">▶️</span>
                                    <h3>NeonOn</h3>
                                    <p>Player de mídia com tema neon</p>
                                </a>
                                <a href="https://misaandrejezieski.github.io/Copa-2026/" target="_blank" class="destaque-card neon-card">
                                    <span class="destaque-icon">⚽</span>
                                    <h3>Copa 2026</h3>
                                    <p>Álbum de figurinhas interativo</p>
                                </a>
                            </div>
                            <div style="text-align: center; margin-top: 20px;">
                                <a href="/projetos" class="btn neon-btn-secondary">✦ Ver todos os projetos</a>
                            </div>
                        </section>

                        <section class="highlights">
                            <h2 class="neon-subtitle">🌟 Destaques</h2>
                            <div class="highlights-grid">
                                <div class="highlight-card neon-card">
                                    <span class="highlight-icon">🚀</span>
                                    <h3>Servidor HTTP + WebSocket</h3>
                                    <p>Construído em Java puro, com sessões, cookies e chat em tempo real.</p>
                                </div>
                                <div class="highlight-card neon-card">
                                    <span class="highlight-icon">🧠</span>
                                    <h3>Interpretador Brainfuck</h3>
                                    <p>Linguagem esotérica Turing-completa implementada do zero.</p>
                                </div>
                                <div class="highlight-card neon-card">
                                    <span class="highlight-icon">🌀</span>
                                    <h3>Labirinto 3D</h3>
                                    <p>Geração procedural e pathfinding com A* em Java.</p>
                                </div>
                            </div>
                        </section>

                        <footer class="neon-footer">
                            <p>✦ Misael Andrejezieski ✦ 2026</p>
                            <div class="footer-links">
                                <a href="https://misaandrejezieski.github.io/Misa/" target="_blank" class="neon-link-footer">☕ Meu Site</a>
                                <a href="https://github.com/MisaAndrejezieski" target="_blank" class="neon-link-footer">🐙 GitHub</a>
                                <a href="https://www.linkedin.com/in/misael-andrejezieski-b4996720a/" target="_blank" class="neon-link-footer">🔗 LinkedIn</a>
                                <a href="https://www.instagram.com/misaelandrejezieski/" target="_blank" class="neon-link-footer">📸 Instagram</a>
                                <a href="https://www.facebook.com/profile.php?id=100034358779961" target="_blank" class="neon-link-footer">👍 Facebook</a>
                            </div>
                        </footer>
                    </main>

                    <script src="script.js"></script>
                </body>
                </html>
                """);
                System.out.println("📄 index.html criado em ./public/");
            } catch (IOException e) {
                System.err.println("⚠️ Erro ao criar index.html: " + e.getMessage());
            }
        }
    }

    private static void criarArquivoProjetos() {
        File file = new File(DIRETORIO_PUBLICO + "/projetos.html");
        if (!file.exists()) {
            try (FileWriter fw = new FileWriter(file)) {
                fw.write("""
                <!DOCTYPE html>
                <html lang="pt-br">
                <head>
                    <meta charset="UTF-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    <title>Projetos | Misael Andrejezieski</title>
                    <link rel="stylesheet" href="style.css">
                </head>
                <body>
                    <canvas id="particles"></canvas>

                    <nav class="navbar">
                        <div class="nav-container">
                            <div class="nav-brand">
                                <span class="logo">🚀</span>
                                <span class="glitch-text" data-text="Misael">Misael</span>
                            </div>
                            <ul class="nav-menu">
                                <li><a href="/">Home</a></li>
                                <li><a href="/projetos" class="active">Projetos</a></li>
                                <li><a href="/contato">Contato</a></li>
                                <li><a href="/ws">💬 Chat</a></li>
                            </ul>
                            <div class="nav-status">
                                <span class="status-dot online"></span>
                                <span class="neon-text">#Online</span>
                            </div>
                        </div>
                    </nav>

                    <main>
                        <section style="text-align: center; padding: 20px 0;">
                            <h1 class="neon-title" style="font-size: 2.8em; margin-bottom: 10px;">✦ Meus Projetos</h1>
                            <p style="color: #c8bdd0; font-size: 1.1em;">Uma seleção do que tenho criado</p>
                        </section>

                        <div class="projetos-grid">
                            <div class="projeto-card neon-card" style="border-color: #ff8fab;">
                                <div class="projeto-icon">🍸</div>
                                <h3 style="color: #ff8fab;">Cotton Candy Kabukicho</h3>
                                <p>Landing page para um clube privê temático de Tóquio. Design neon com drinks exclusivos e galeria.</p>
                                <div class="projeto-tags">
                                    <span>HTML/CSS</span>
                                    <span>Neon</span>
                                    <span>Landing Page</span>
                                </div>
                                <a href="https://misaandrejezieski.github.io/Cotton-Candy-Kabukicho/" target="_blank" class="projeto-link">🌐 Ver projeto →</a>
                            </div>

                            <div class="projeto-card neon-card" style="border-color: #89b4fa;">
                                <div class="projeto-icon">⬇️</div>
                                <h3 style="color: #89b4fa;">Site BaixarYou</h3>
                                <p>Plataforma para download de conteúdos. Interface limpa e funcional.</p>
                                <div class="projeto-tags">
                                    <span>HTML/CSS</span>
                                    <span>Download</span>
                                    <span>UI</span>
                                </div>
                                <a href="https://misaandrejezieski.github.io/Site-BaixarYou/" target="_blank" class="projeto-link">🌐 Ver projeto →</a>
                            </div>

                            <div class="projeto-card neon-card" style="border-color: #a6e3a1;">
                                <div class="projeto-icon">🍜</div>
                                <h3 style="color: #a6e3a1;">NekoLamen</h3>
                                <p>Site para delivery de yakisoba artesanal. Cardápio completo com preços e opções de retirada.</p>
                                <div class="projeto-tags">
                                    <span>HTML/CSS</span>
                                    <span>Delivery</span>
                                    <span>Cardápio</span>
                                </div>
                                <a href="https://misaandrejezieski.github.io/NekoLamen/" target="_blank" class="projeto-link">🌐 Ver projeto →</a>
                            </div>

                            <div class="projeto-card neon-card" style="border-color: #cba6f7;">
                                <div class="projeto-icon">▶️</div>
                                <h3 style="color: #cba6f7;">NeonOn</h3>
                                <p>Player de vídeos e imagens com interface neon. Arraste e solte arquivos para reprodução.</p>
                                <div class="projeto-tags">
                                    <span>JS</span>
                                    <span>Player</span>
                                    <span>Neon</span>
                                </div>
                                <a href="https://misaandrejezieski.github.io/NeonOn/" target="_blank" class="projeto-link">🌐 Ver projeto →</a>
                            </div>

                            <div class="projeto-card neon-card" style="border-color: #f9e2af;">
                                <div class="projeto-icon">🔧</div>
                                <h3 style="color: #f9e2af;">Auto PES V2</h3>
                                <p>Projeto de automação de processos. (Atualmente em desenvolvimento)</p>
                                <div class="projeto-tags">
                                    <span>Vercel</span>
                                    <span>Em breve</span>
                                </div>
                                <a href="https://auto-pes-v2-d3jlomtlc-misael-andrejezieskis-projects.vercel.app/" target="_blank" class="projeto-link">🌐 Ver projeto →</a>
                            </div>

                            <div class="projeto-card neon-card" style="border-color: #ff8fab;">
                                <div class="projeto-icon">🎰</div>
                                <h3 style="color: #ff8fab;">Slot Madruga</h3>
                                <p>Slot machine com tema anime. Sistema de créditos e giros.</p>
                                <div class="projeto-tags">
                                    <span>JS</span>
                                    <span>Jogo</span>
                                    <span>Anime</span>
                                </div>
                                <a href="https://misaandrejezieski.github.io/SlotMadruga/" target="_blank" class="projeto-link">🌐 Ver projeto →</a>
                            </div>

                            <div class="projeto-card neon-card" style="border-color: #89b4fa;">
                                <div class="projeto-icon">✨</div>
                                <h3 style="color: #89b4fa;">Site Bonito</h3>
                                <p>Site com efeitos especiais e design clean. Exploração de estilos visuais.</p>
                                <div class="projeto-tags">
                                    <span>HTML/CSS</span>
                                    <span>Design</span>
                                    <span>Efeitos</span>
                                </div>
                                <a href="https://misaandrejezieski.github.io/Site-Bonito/" target="_blank" class="projeto-link">🌐 Ver projeto →</a>
                            </div>

                            <div class="projeto-card neon-card" style="border-color: #a6e3a1;">
                                <div class="projeto-icon">🧘</div>
                                <h3 style="color: #a6e3a1;">TRIBB US Carambei</h3>
                                <p>Comunidade focada em meditação e bem-estar. Respiração consciente e harmonia.</p>
                                <div class="projeto-tags">
                                    <span>HTML/CSS</span>
                                    <span>Meditação</span>
                                    <span>Bem-estar</span>
                                </div>
                                <a href="https://misaandrejezieski.github.io/TRIBB-US-Carambei/" target="_blank" class="projeto-link">🌐 Ver projeto →</a>
                            </div>

                            <div class="projeto-card neon-card" style="border-color: #f9e2af;">
                                <div class="projeto-icon">⚽</div>
                                <h3 style="color: #f9e2af;">Copa 2026</h3>
                                <p>Álbum de figurinhas interativo para a Copa do Mundo. Colecione os times!</p>
                                <div class="projeto-tags">
                                    <span>HTML/CSS</span>
                                    <span>Album</span>
                                    <span>Futebol</span>
                                </div>
                                <a href="https://misaandrejezieski.github.io/Copa-2026/" target="_blank" class="projeto-link">🌐 Ver projeto →</a>
                            </div>

                            <div class="projeto-card neon-card" style="border-color: #cba6f7;">
                                <div class="projeto-icon">🍮</div>
                                <h3 style="color: #cba6f7;">PudimFlow</h3>
                                <p>Projeto em desenvolvimento na Vercel. (Em breve)</p>
                                <div class="projeto-tags">
                                    <span>Vercel</span>
                                    <span>Em breve</span>
                                </div>
                                <a href="https://pudimflow-o5e2dnl0n-misael-andrejezieskis-projects.vercel.app/" target="_blank" class="projeto-link">🌐 Ver projeto →</a>
                            </div>
                        </div>

                        <div style="text-align: center; margin: 30px 0;">
                            <p style="color: #9a8aa2;">📦 Total de projetos: <strong style="color: var(--neon-pink);">10</strong></p>
                        </div>

                        <footer class="neon-footer">
                            <p>✦ Misael Andrejezieski ✦ 2026</p>
                            <div class="footer-links">
                                <a href="https://misaandrejezieski.github.io/Misa/" target="_blank" class="neon-link-footer">☕ Meu Site</a>
                                <a href="https://github.com/MisaAndrejezieski" target="_blank" class="neon-link-footer">🐙 GitHub</a>
                                <a href="https://www.linkedin.com/in/misael-andrejezieski-b4996720a/" target="_blank" class="neon-link-footer">🔗 LinkedIn</a>
                                <a href="https://www.instagram.com/misaelandrejezieski/" target="_blank" class="neon-link-footer">📸 Instagram</a>
                                <a href="https://www.facebook.com/profile.php?id=100034358779961" target="_blank" class="neon-link-footer">👍 Facebook</a>
                            </div>
                        </footer>
                    </main>

                    <script src="script.js"></script>
                </body>
                </html>
                """);
                System.out.println("📄 projetos.html criado em ./public/");
            } catch (IOException e) {
                System.err.println("⚠️ Erro ao criar projetos.html: " + e.getMessage());
            }
        }
    }

    private static void criarArquivoContato() {
        File file = new File(DIRETORIO_PUBLICO + "/contato.html");
        if (!file.exists()) {
            try (FileWriter fw = new FileWriter(file)) {
                fw.write("""
                <!DOCTYPE html>
                <html lang="pt-br">
                <head>
                    <meta charset="UTF-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    <title>Contato | Misael Andrejezieski</title>
                    <link rel="stylesheet" href="style.css">
                </head>
                <body>
                    <canvas id="particles"></canvas>

                    <nav class="navbar">
                        <div class="nav-container">
                            <div class="nav-brand">
                                <span class="logo">🚀</span>
                                <span class="glitch-text" data-text="Misael">Misael</span>
                            </div>
                            <ul class="nav-menu">
                                <li><a href="/">Home</a></li>
                                <li><a href="/projetos">Projetos</a></li>
                                <li><a href="/contato" class="active">Contato</a></li>
                                <li><a href="/ws">💬 Chat</a></li>
                            </ul>
                            <div class="nav-status">
                                <span class="status-dot online"></span>
                                <span class="neon-text">#Online</span>
                            </div>
                        </div>
                    </nav>

                    <main>
                        <section style="text-align: center; padding: 20px 0;">
                            <h1 class="neon-title" style="font-size: 2.8em; margin-bottom: 10px;">✉ Contato</h1>
                            <p style="color: #c8bdd0; font-size: 1.1em;">Vamos conversar! Me envie uma mensagem</p>
                        </section>

                        <div class="contato-container neon-card">
                            <form class="contato-form" onsubmit="enviarContato(event)">
                                <div class="form-group">
                                    <label for="nome">Nome</label>
                                    <input type="text" id="nome" placeholder="Seu nome" required>
                                </div>
                                <div class="form-group">
                                    <label for="email">E-mail</label>
                                    <input type="email" id="email" placeholder="seu@email.com" required>
                                </div>
                                <div class="form-group">
                                    <label for="assunto">Assunto</label>
                                    <input type="text" id="assunto" placeholder="Assunto da mensagem" required>
                                </div>
                                <div class="form-group">
                                    <label for="mensagem">Mensagem</label>
                                    <textarea id="mensagem" placeholder="Sua mensagem..." required></textarea>
                                </div>
                                <button type="submit" class="btn neon-btn-primary">✦ Enviar mensagem</button>
                            </form>
                        </div>

                        <div style="text-align: center; margin: 30px 0;">
                            <p style="color: #9a8aa2; font-size: 1.1em; margin-bottom: 15px;">🌐 Me encontre em todas as redes</p>
                            <div style="display: flex; gap: 15px; justify-content: center; flex-wrap: wrap;">
                                <a href="https://misaandrejezieski.github.io/Misa/" target="_blank" class="neon-link-footer" style="font-size: 1.1em;">☕ Meu Site</a>
                                <a href="https://github.com/MisaAndrejezieski" target="_blank" class="neon-link-footer" style="font-size: 1.1em;">🐙 GitHub</a>
                                <a href="https://www.linkedin.com/in/misael-andrejezieski-b4996720a/" target="_blank" class="neon-link-footer" style="font-size: 1.1em;">🔗 LinkedIn</a>
                                <a href="https://www.instagram.com/misaelandrejezieski/" target="_blank" class="neon-link-footer" style="font-size: 1.1em;">📸 Instagram</a>
                                <a href="https://www.facebook.com/profile.php?id=100034358779961" target="_blank" class="neon-link-footer" style="font-size: 1.1em;">👍 Facebook</a>
                            </div>
                        </div>

                        <footer class="neon-footer">
                            <p>✦ Misael Andrejezieski ✦ 2026</p>
                            <div class="footer-links">
                                <a href="https://misaandrejezieski.github.io/Misa/" target="_blank" class="neon-link-footer">☕ Meu Site</a>
                                <a href="https://github.com/MisaAndrejezieski" target="_blank" class="neon-link-footer">🐙 GitHub</a>
                                <a href="https://www.linkedin.com/in/misael-andrejezieski-b4996720a/" target="_blank" class="neon-link-footer">🔗 LinkedIn</a>
                                <a href="https://www.instagram.com/misaelandrejezieski/" target="_blank" class="neon-link-footer">📸 Instagram</a>
                                <a href="https://www.facebook.com/profile.php?id=100034358779961" target="_blank" class="neon-link-footer">👍 Facebook</a>
                            </div>
                        </footer>
                    </main>

                    <script src="script.js"></script>
                </body>
                </html>
                """);
                System.out.println("📄 contato.html criado em ./public/");
            } catch (IOException e) {
                System.err.println("⚠️ Erro ao criar contato.html: " + e.getMessage());
            }
        }
    }

    private static void criarArquivoWebSocket() {
        File file = new File(DIRETORIO_PUBLICO + "/websocket.html");
        if (!file.exists()) {
            try (FileWriter fw = new FileWriter(file)) {
                fw.write("""
                <!DOCTYPE html>
                <html lang="pt-br">
                <head>
                    <meta charset="UTF-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    <title>Chat Neon | Misael Andrejezieski</title>
                    <link rel="stylesheet" href="style.css">
                    <style>
                        .chat-page {
                            max-width: 800px;
                            margin: 30px auto;
                            padding: 0 20px;
                            position: relative;
                            z-index: 1;
                        }
                        .chat-page .chat-header {
                            text-align: center;
                            margin-bottom: 30px;
                        }
                        .chat-page .chat-header h1 {
                            font-size: 2.8em;
                            margin-bottom: 5px;
                        }
                        .chat-page .chat-header p {
                            color: #9a8aa2;
                            font-size: 1.1em;
                        }
                        .chat-container {
                            background: var(--bg-card);
                            backdrop-filter: blur(10px);
                            border: 1px solid var(--border-card);
                            border-radius: 20px;
                            overflow: hidden;
                            box-shadow: 0 0 40px rgba(255, 143, 171, 0.05);
                        }
                        .chat-status-bar {
                            display: flex;
                            align-items: center;
                            justify-content: space-between;
                            padding: 14px 24px;
                            border-bottom: 1px solid var(--border-card);
                            background: rgba(0, 0, 0, 0.2);
                        }
                        .chat-status-bar .status-info {
                            display: flex;
                            align-items: center;
                            gap: 12px;
                            font-weight: 500;
                        }
                        .chat-status-bar .status-dot {
                            width: 10px;
                            height: 10px;
                            border-radius: 50%;
                            display: inline-block;
                        }
                        .chat-status-bar .status-dot.online {
                            background: var(--neon-green);
                            box-shadow: 0 0 20px var(--neon-green-glow);
                            animation: pulse 1.5s ease-in-out infinite;
                        }
                        .chat-status-bar .status-dot.offline {
                            background: #ff0044;
                            box-shadow: 0 0 20px rgba(255, 0, 68, 0.3);
                        }
                        .chat-status-bar .status-text {
                            color: #c8bdd0;
                            font-size: 0.95em;
                        }
                        .chat-status-bar .status-text.online {
                            color: var(--neon-green);
                            text-shadow: 0 0 10px var(--neon-green-glow);
                        }
                        .chat-status-bar .status-text.offline {
                            color: #ff0044;
                        }
                        .chat-status-bar .user-info {
                            color: var(--neon-purple);
                            font-size: 0.9em;
                            text-shadow: 0 0 10px var(--neon-purple-glow);
                        }
                        #chat-messages {
                            height: 420px;
                            overflow-y: auto;
                            padding: 20px 24px;
                            background: rgba(0, 0, 0, 0.15);
                            display: flex;
                            flex-direction: column;
                            gap: 4px;
                        }
                        #chat-messages::-webkit-scrollbar {
                            width: 5px;
                        }
                        #chat-messages::-webkit-scrollbar-track {
                            background: rgba(255, 143, 171, 0.05);
                        }
                        #chat-messages::-webkit-scrollbar-thumb {
                            background: var(--neon-pink);
                            border-radius: 10px;
                            box-shadow: 0 0 20px var(--neon-pink-glow);
                        }
                        .msg {
                            padding: 8px 16px;
                            border-radius: 14px;
                            animation: fadeIn 0.25s ease;
                            max-width: 82%;
                            word-wrap: break-word;
                            font-size: 0.95em;
                            line-height: 1.5;
                        }
                        .msg.sistema {
                            background: rgba(203, 166, 247, 0.15);
                            color: var(--neon-purple);
                            border: 1px solid rgba(203, 166, 247, 0.15);
                            align-self: center;
                            max-width: 90%;
                            text-shadow: 0 0 10px var(--neon-purple-glow);
                            font-size: 0.85em;
                            padding: 6px 18px;
                            border-radius: 30px;
                        }
                        .msg.usuario {
                            background: rgba(255, 143, 171, 0.15);
                            color: var(--neon-pink);
                            border: 1px solid rgba(255, 143, 171, 0.15);
                            align-self: flex-end;
                            margin-left: auto;
                            text-shadow: 0 0 10px var(--neon-pink-glow);
                        }
                        .msg.outro {
                            background: rgba(137, 180, 250, 0.10);
                            color: var(--neon-blue);
                            border: 1px solid rgba(137, 180, 250, 0.10);
                            align-self: flex-start;
                            text-shadow: 0 0 10px var(--neon-blue-glow);
                        }
                        .chat-input-area {
                            display: flex;
                            gap: 12px;
                            padding: 16px 24px;
                            border-top: 1px solid var(--border-card);
                            background: rgba(0, 0, 0, 0.15);
                        }
                        .chat-input-area input {
                            flex: 1;
                            padding: 12px 20px;
                            border: 1px solid var(--border-card);
                            border-radius: 30px;
                            font-size: 1em;
                            background: rgba(0, 0, 0, 0.3);
                            color: #e0d6e8;
                            outline: none;
                            transition: all 0.3s;
                        }
                        .chat-input-area input:focus {
                            border-color: var(--neon-pink);
                            box-shadow: 0 0 30px var(--neon-pink-glow);
                        }
                        .chat-input-area input::placeholder {
                            color: #6a5a72;
                        }
                        .chat-input-area input:disabled {
                            opacity: 0.4;
                            cursor: not-allowed;
                        }
                        .chat-input-area button {
                            padding: 12px 30px;
                            border: none;
                            border-radius: 30px;
                            font-weight: 600;
                            font-size: 1em;
                            cursor: pointer;
                            transition: all 0.3s;
                            background: var(--neon-pink);
                            color: #0f0a12;
                            box-shadow: 0 0 30px var(--neon-pink-glow);
                        }
                        .chat-input-area button:hover:not(:disabled) {
                            transform: scale(1.03);
                            box-shadow: 0 0 50px var(--neon-pink-glow);
                        }
                        .chat-input-area button:disabled {
                            opacity: 0.4;
                            cursor: not-allowed;
                            transform: none;
                        }
                        .chat-commands {
                            padding: 10px 24px;
                            border-top: 1px solid rgba(255, 143, 171, 0.05);
                            font-size: 0.8em;
                            color: #6a5a72;
                            background: rgba(0, 0, 0, 0.1);
                            display: flex;
                            flex-wrap: wrap;
                            gap: 12px;
                            justify-content: center;
                        }
                        .chat-commands code {
                            background: rgba(255, 143, 171, 0.08);
                            color: var(--neon-pink);
                            padding: 2px 12px;
                            border-radius: 12px;
                            font-size: 0.85em;
                            border: 1px solid rgba(255, 143, 171, 0.08);
                        }
                        .chat-commands .cmd-label {
                            color: #6a5a72;
                        }
                        .chat-footer {
                            text-align: center;
                            padding: 20px;
                            margin-top: 20px;
                        }
                        .chat-footer a {
                            color: var(--neon-purple);
                            text-decoration: none;
                            text-shadow: 0 0 10px var(--neon-purple-glow);
                            transition: all 0.3s;
                        }
                        .chat-footer a:hover {
                            color: var(--neon-pink);
                            text-shadow: 0 0 30px var(--neon-pink-glow);
                        }
                        @keyframes fadeIn {
                            from { opacity: 0; transform: translateY(6px); }
                            to { opacity: 1; transform: translateY(0); }
                        }
                        @media (max-width: 600px) {
                            .chat-page .chat-header h1 { font-size: 2em; }
                            #chat-messages { height: 300px; padding: 14px; }
                            .chat-input-area { flex-direction: column; }
                            .chat-input-area button { width: 100%; }
                            .chat-commands { flex-direction: column; align-items: center; }
                        }
                    </style>
                </head>
                <body>
                    <canvas id="particles"></canvas>

                    <nav class="navbar">
                        <div class="nav-container">
                            <div class="nav-brand">
                                <span class="logo">🚀</span>
                                <span class="glitch-text" data-text="Misael">Misael</span>
                            </div>
                            <ul class="nav-menu">
                                <li><a href="/">Home</a></li>
                                <li><a href="/projetos">Projetos</a></li>
                                <li><a href="/contato">Contato</a></li>
                                <li><a href="/ws" class="active">💬 Chat</a></li>
                            </ul>
                            <div class="nav-status">
                                <span class="status-dot online"></span>
                                <span class="neon-text">#Online</span>
                            </div>
                        </div>
                    </nav>

                    <main class="chat-page">
                        <div class="chat-header">
                            <h1 class="neon-title" style="font-size: 2.5em;">💬 Chat Neon</h1>
                            <p>Conecte-se e converse em tempo real com WebSocket</p>
                        </div>

                        <div class="chat-container">
                            <div class="chat-status-bar">
                                <div class="status-info">
                                    <span id="chat-status-dot" class="status-dot offline"></span>
                                    <span id="chat-status-text" class="status-text offline">Desconectado</span>
                                </div>
                                <div class="user-info">
                                    <span id="chat-user-name">👤 Anônimo</span>
                                </div>
                            </div>

                            <div id="chat-messages"></div>

                            <div class="chat-input-area">
                                <input type="text" id="chat-input" placeholder="⚡ Digite sua mensagem..." disabled>
                                <button id="chat-send" disabled>✨ Enviar</button>
                            </div>

                            <div class="chat-commands">
                                <span class="cmd-label">💡 Comandos:</span>
                                <code>/nick [nome]</code>
                                <code>/ping</code>
                                <code>/sair</code>
                            </div>
                        </div>

                        <div class="chat-footer">
                            <a href="/">← Voltar para Home</a>
                        </div>

                        <footer class="neon-footer">
                            <p>✦ Misael Andrejezieski ✦ 2026</p>
                            <div class="footer-links">
                                <a href="https://misaandrejezieski.github.io/Misa/" target="_blank" class="neon-link-footer">☕ Meu Site</a>
                                <a href="https://github.com/MisaAndrejezieski" target="_blank" class="neon-link-footer">🐙 GitHub</a>
                                <a href="https://www.linkedin.com/in/misael-andrejezieski-b4996720a/" target="_blank" class="neon-link-footer">🔗 LinkedIn</a>
                                <a href="https://www.instagram.com/misaelandrejezieski/" target="_blank" class="neon-link-footer">📸 Instagram</a>
                                <a href="https://www.facebook.com/profile.php?id=100034358779961" target="_blank" class="neon-link-footer">👍 Facebook</a>
                            </div>
                        </footer>
                    </main>

                    <script src="script.js"></script>
                </body>
                </html>
                """);
                System.out.println("📄 websocket.html criado em ./public/");
            } catch (IOException e) {
                System.err.println("⚠️ Erro ao criar websocket.html: " + e.getMessage());
            }
        }
    }

    private static void criarArquivoStyle() {
        File file = new File(DIRETORIO_PUBLICO + "/style.css");
        if (!file.exists()) {
            try (FileWriter fw = new FileWriter(file)) {
                fw.write("""
                /* ========== RESET ========== */
                * {
                    margin: 0;
                    padding: 0;
                    box-sizing: border-box;
                }

                body {
                    font-family: 'Segoe UI', 'Courier New', monospace;
                    background: #0f0a12;
                    color: #e0d6e8;
                    min-height: 100vh;
                    overflow-x: hidden;
                    padding-top: 70px;
                }

                /* ========== FUNDO COM PARTÍCULAS ========== */
                #particles {
                    position: fixed;
                    top: 0;
                    left: 0;
                    width: 100%;
                    height: 100%;
                    z-index: 0;
                    pointer-events: none;
                }

                /* ========== CORES NEON PASTEL ========== */
                :root {
                    --neon-pink: #ff8fab;
                    --neon-pink-glow: rgba(255, 143, 171, 0.3);
                    --neon-blue: #89b4fa;
                    --neon-blue-glow: rgba(137, 180, 250, 0.3);
                    --neon-cyan: #79e0e0;
                    --neon-cyan-glow: rgba(121, 224, 224, 0.3);
                    --neon-green: #a6e3a1;
                    --neon-green-glow: rgba(166, 227, 161, 0.3);
                    --neon-purple: #cba6f7;
                    --neon-purple-glow: rgba(203, 166, 247, 0.3);
                    --neon-yellow: #f9e2af;
                    --neon-yellow-glow: rgba(249, 226, 175, 0.3);
                    --bg-card: rgba(30, 22, 38, 0.85);
                    --border-card: rgba(255, 143, 171, 0.2);
                }

                /* ========== TEXTO GLITCH ========== */
                .glitch-text {
                    position: relative;
                    color: var(--neon-pink);
                    text-shadow: 0 0 20px var(--neon-pink-glow);
                    font-weight: 800;
                }

                .glitch-text::before,
                .glitch-text::after {
                    content: attr(data-text);
                    position: absolute;
                    top: 0;
                    left: 0;
                    width: 100%;
                    height: 100%;
                    opacity: 0;
                }

                .glitch-text:hover::before {
                    animation: glitch 0.25s infinite;
                    color: var(--neon-cyan);
                    opacity: 1;
                }

                .glitch-text:hover::after {
                    animation: glitch 0.25s infinite reverse;
                    color: var(--neon-purple);
                    opacity: 1;
                }

                @keyframes glitch {
                    0% { transform: translate(1px, -1px); }
                    25% { transform: translate(-1px, 1px); }
                    50% { transform: translate(1px, 1px); }
                    75% { transform: translate(-1px, -1px); }
                    100% { transform: translate(0px, 0px); }
                }

                /* ========== TEXTOS NEON ========== */
                .neon-text { color: #00ff88; text-shadow: 0 0 10px #00ff88, 0 0 20px #00ff88; }
                .neon-text-pink { color: var(--neon-pink); text-shadow: 0 0 20px var(--neon-pink-glow); }
                .neon-text-blue { color: var(--neon-blue); text-shadow: 0 0 20px var(--neon-blue-glow); }
                .neon-text-cyan { color: var(--neon-cyan); text-shadow: 0 0 20px var(--neon-cyan-glow); }
                .neon-text-green { color: var(--neon-green); text-shadow: 0 0 20px var(--neon-green-glow); }
                .neon-text-purple { color: var(--neon-purple); text-shadow: 0 0 20px var(--neon-purple-glow); }
                .neon-text-yellow { color: var(--neon-yellow); text-shadow: 0 0 20px var(--neon-yellow-glow); }

                .neon-title {
                    color: #ff00ff;
                    font-size: 2.5em;
                    text-shadow: 0 0 10px #ff00ff, 0 0 20px #ff00ff, 0 0 40px #ff00ff;
                    animation: neonPulse 2s ease-in-out infinite;
                }

                .neon-subtitle {
                    color: var(--neon-purple);
                    font-size: 2em;
                    font-weight: 700;
                    text-shadow: 0 0 30px var(--neon-purple-glow);
                    margin-bottom: 30px;
                    text-align: center;
                }

                @keyframes neonPulse {
                    0%, 100% { text-shadow: 0 0 10px #ff00ff, 0 0 20px #ff00ff, 0 0 40px #ff00ff; }
                    50% { text-shadow: 0 0 20px #ff00ff, 0 0 40px #ff00ff, 0 0 80px #ff00ff; }
                }

                /* ========== BADGE ========== */
                .neon-badge {
                    display: inline-block;
                    background: rgba(166, 227, 161, 0.12);
                    color: var(--neon-green);
                    padding: 6px 18px;
                    border-radius: 30px;
                    border: 1px solid var(--neon-green);
                    font-size: 0.85em;
                    font-weight: 600;
                    text-shadow: 0 0 10px var(--neon-green-glow);
                    box-shadow: 0 0 20px var(--neon-green-glow);
                    animation: pulseBadge 2s ease-in-out infinite;
                }

                @keyframes pulseBadge {
                    0%, 100% { box-shadow: 0 0 20px var(--neon-green-glow); }
                    50% { box-shadow: 0 0 40px var(--neon-green-glow), 0 0 60px var(--neon-green-glow); }
                }

                /* ========== NAVBAR ========== */
                .navbar {
                    position: fixed;
                    top: 0;
                    left: 0;
                    right: 0;
                    background: rgba(15, 10, 18, 0.92);
                    backdrop-filter: blur(20px);
                    border-bottom: 1px solid var(--border-card);
                    z-index: 1000;
                    padding: 0 20px;
                    height: 65px;
                    display: flex;
                    align-items: center;
                }

                .nav-container {
                    max-width: 1200px;
                    width: 100%;
                    margin: 0 auto;
                    display: flex;
                    align-items: center;
                    justify-content: space-between;
                }

                .nav-brand {
                    display: flex;
                    align-items: center;
                    gap: 10px;
                    font-size: 1.2em;
                    font-weight: 800;
                }

                .nav-brand .logo {
                    font-size: 1.5em;
                    animation: float 3s ease-in-out infinite;
                }

                @keyframes float {
                    0%, 100% { transform: translateY(0); }
                    50% { transform: translateY(-4px); }
                }

                .nav-menu {
                    display: flex;
                    list-style: none;
                    gap: 15px;
                }

                .nav-menu a {
                    color: #c0b0c8;
                    text-decoration: none;
                    padding: 8px 18px;
                    border-radius: 30px;
                    transition: all 0.3s;
                    font-weight: 500;
                    font-size: 0.95em;
                }

                .nav-menu a:hover {
                    color: var(--neon-pink);
                    background: var(--neon-pink-glow);
                    text-shadow: 0 0 20px var(--neon-pink-glow);
                }

                .nav-menu a.active {
                    color: var(--neon-pink);
                    background: var(--neon-pink-glow);
                    box-shadow: 0 0 30px var(--neon-pink-glow);
                }

                .nav-status {
                    display: flex;
                    align-items: center;
                    gap: 8px;
                    font-size: 0.85em;
                }

                .status-dot {
                    display: inline-block;
                    width: 10px;
                    height: 10px;
                    border-radius: 50%;
                    background: var(--neon-green);
                    box-shadow: 0 0 20px var(--neon-green-glow);
                    animation: pulse 1.5s ease-in-out infinite;
                }

                @keyframes pulse {
                    0%, 100% { opacity: 1; transform: scale(1); }
                    50% { opacity: 0.6; transform: scale(0.8); }
                }

                /* ========== HERO ========== */
                .hero {
                    display: grid;
                    grid-template-columns: 1fr 1fr;
                    gap: 50px;
                    align-items: center;
                    max-width: 1200px;
                    margin: 40px auto 60px;
                    padding: 0 20px;
                    position: relative;
                    z-index: 1;
                    min-height: 70vh;
                }

                @media (max-width: 900px) {
                    .hero {
                        grid-template-columns: 1fr;
                        text-align: center;
                    }
                }

                .hero-content {
                    display: flex;
                    flex-direction: column;
                    gap: 20px;
                }

                .hero-badge {
                    margin-bottom: 10px;
                }

                .hero-title {
                    font-size: 3.2em;
                    font-weight: 800;
                    line-height: 1.1;
                }

                .hero-title span {
                    display: inline-block;
                }

                .hero-subtitle {
                    font-size: 1.4em;
                    font-weight: 400;
                    display: flex;
                    gap: 12px;
                    flex-wrap: wrap;
                }

                @media (max-width: 900px) {
                    .hero-subtitle {
                        justify-content: center;
                    }
                }

                .hero-description {
                    font-size: 1.15em;
                    line-height: 1.7;
                    color: #c8bdd0;
                    max-width: 500px;
                }

                @media (max-width: 900px) {
                    .hero-description {
                        max-width: 100%;
                    }
                }

                .hero-description strong {
                    color: var(--neon-pink);
                    text-shadow: 0 0 15px var(--neon-pink-glow);
                }

                .hero-buttons {
                    display: flex;
                    gap: 15px;
                    flex-wrap: wrap;
                    margin-top: 10px;
                }

                @media (max-width: 900px) {
                    .hero-buttons {
                        justify-content: center;
                    }
                }

                /* ========== BOTÕES ========== */
                .btn {
                    padding: 12px 30px;
                    border-radius: 50px;
                    font-weight: 600;
                    font-size: 1em;
                    text-decoration: none;
                    transition: all 0.3s;
                    border: none;
                    cursor: pointer;
                    display: inline-flex;
                    align-items: center;
                    gap: 8px;
                }

                .neon-btn-primary {
                    background: var(--neon-pink);
                    color: #0f0a12;
                    box-shadow: 0 0 30px var(--neon-pink-glow);
                }

                .neon-btn-primary:hover {
                    transform: scale(1.05);
                    box-shadow: 0 0 50px var(--neon-pink-glow), 0 0 80px var(--neon-pink-glow);
                }

                .neon-btn-secondary {
                    background: transparent;
                    color: var(--neon-cyan);
                    border: 2px solid var(--neon-cyan);
                    box-shadow: 0 0 20px var(--neon-cyan-glow);
                }

                .neon-btn-secondary:hover {
                    background: var(--neon-cyan);
                    color: #0f0a12;
                    transform: scale(1.05);
                    box-shadow: 0 0 40px var(--neon-cyan-glow);
                }

                /* ========== ESFERAS DECORATIVAS ========== */
                .hero-illustration {
                    position: relative;
                    display: flex;
                    justify-content: center;
                    align-items: center;
                    height: 400px;
                }

                .neon-orb {
                    position: absolute;
                    border-radius: 50%;
                    filter: blur(60px);
                    opacity: 0.6;
                    animation: orbFloat 6s ease-in-out infinite;
                }

                .neon-orb.pink {
                    width: 250px;
                    height: 250px;
                    background: var(--neon-pink);
                    animation-delay: 0s;
                }

                .neon-orb.cyan {
                    width: 180px;
                    height: 180px;
                    background: var(--neon-cyan);
                    animation-delay: 2s;
                    top: 50px;
                    right: 20px;
                }

                .neon-orb.purple {
                    width: 150px;
                    height: 150px;
                    background: var(--neon-purple);
                    animation-delay: 4s;
                    bottom: 50px;
                    left: 20px;
                }

                @keyframes orbFloat {
                    0%, 100% { transform: translate(0, 0) scale(1); }
                    33% { transform: translate(20px, -30px) scale(1.1); }
                    66% { transform: translate(-20px, 20px) scale(0.9); }
                }

                /* ========== SOCIAL LINKS ========== */
                .social-links {
                    max-width: 1200px;
                    margin: 60px auto;
                    padding: 0 20px;
                    position: relative;
                    z-index: 1;
                }

                .social-grid {
                    display: grid;
                    grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
                    gap: 20px;
                }

                .social-card {
                    display: flex;
                    flex-direction: column;
                    align-items: center;
                    padding: 25px 20px;
                    border-radius: 20px;
                    text-decoration: none;
                    transition: all 0.3s;
                    text-align: center;
                    background: var(--bg-card);
                    backdrop-filter: blur(10px);
                    border: 1px solid var(--border-card);
                }

                .social-card:hover {
                    transform: translateY(-5px);
                    border-color: var(--neon-pink);
                    box-shadow: 0 0 40px var(--neon-pink-glow);
                }

                .social-icon {
                    font-size: 2.5em;
                    margin-bottom: 10px;
                }

                .social-name {
                    color: var(--neon-cyan);
                    font-weight: 600;
                    font-size: 1.1em;
                    text-shadow: 0 0 15px var(--neon-cyan-glow);
                }

                .social-desc {
                    color: #9a8aa2;
                    font-size: 0.85em;
                    margin-top: 4px;
                }

                /* ========== SKILLS ========== */
                .skills {
                    max-width: 1200px;
                    margin: 60px auto;
                    padding: 0 20px;
                    position: relative;
                    z-index: 1;
                }

                .skills-grid {
                    display: flex;
                    flex-wrap: wrap;
                    justify-content: center;
                    gap: 12px;
                }

                .neon-tag {
                    background: rgba(203, 166, 247, 0.08);
                    color: var(--neon-purple);
                    padding: 8px 22px;
                    border-radius: 30px;
                    border: 1px solid rgba(203, 166, 247, 0.2);
                    font-weight: 500;
                    font-size: 0.95em;
                    transition: all 0.3s;
                    text-shadow: 0 0 10px var(--neon-purple-glow);
                }

                .neon-tag:hover {
                    background: rgba(203, 166, 247, 0.15);
                    box-shadow: 0 0 30px var(--neon-purple-glow);
                    transform: translateY(-2px);
                }

                /* ========== DESTAQUES HOME ========== */
                .projetos-destaque {
                    max-width: 1200px;
                    margin: 60px auto;
                    padding: 0 20px;
                    position: relative;
                    z-index: 1;
                }

                .destaque-grid {
                    display: grid;
                    grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
                    gap: 20px;
                    margin-top: 20px;
                }

                .destaque-card {
                    padding: 24px;
                    border-radius: 16px;
                    text-decoration: none;
                    text-align: center;
                    transition: all 0.3s;
                    background: var(--bg-card);
                    backdrop-filter: blur(10px);
                    border: 1px solid var(--border-card);
                }

                .destaque-card:hover {
                    transform: translateY(-4px);
                    border-color: var(--neon-pink);
                    box-shadow: 0 0 40px var(--neon-pink-glow);
                }

                .destaque-icon {
                    font-size: 2.2em;
                    display: block;
                    margin-bottom: 10px;
                }

                .destaque-card h3 {
                    color: var(--neon-cyan);
                    font-size: 1.1em;
                    text-shadow: 0 0 15px var(--neon-cyan-glow);
                    margin-bottom: 6px;
                }

                .destaque-card p {
                    color: #9a8aa2;
                    font-size: 0.85em;
                }

                /* ========== HIGHLIGHTS ========== */
                .highlights {
                    max-width: 1200px;
                    margin: 60px auto;
                    padding: 0 20px;
                    position: relative;
                    z-index: 1;
                }

                .highlights-grid {
                    display: grid;
                    grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
                    gap: 25px;
                }

                .highlight-card {
                    padding: 30px;
                    text-align: center;
                    transition: all 0.3s;
                    border-radius: 20px;
                    background: var(--bg-card);
                    backdrop-filter: blur(10px);
                    border: 1px solid var(--border-card);
                }

                .highlight-card:hover {
                    transform: translateY(-5px);
                    border-color: var(--neon-pink);
                    box-shadow: 0 0 40px var(--neon-pink-glow);
                }

                .highlight-icon {
                    font-size: 2.8em;
                    display: block;
                    margin-bottom: 15px;
                }

                .highlight-card h3 {
                    color: var(--neon-cyan);
                    font-size: 1.2em;
                    text-shadow: 0 0 15px var(--neon-cyan-glow);
                    margin-bottom: 10px;
                }

                .highlight-card p {
                    color: #c8bdd0;
                    font-size: 0.95em;
                    line-height: 1.6;
                }

                /* ========== PROJETOS ========== */
                .projetos-grid {
                    display: grid;
                    grid-template-columns: repeat(auto-fit, minmax(300px, 1fr));
                    gap: 25px;
                    max-width: 1200px;
                    margin: 40px auto;
                    padding: 0 20px;
                    position: relative;
                    z-index: 1;
                }

                .projeto-card {
                    padding: 28px;
                    border-radius: 20px;
                    transition: all 0.4s ease;
                    text-align: left;
                    background: var(--bg-card);
                    backdrop-filter: blur(10px);
                    border: 1px solid var(--border-card);
                }

                .projeto-card:hover {
                    transform: translateY(-6px) scale(1.01);
                    box-shadow: 0 0 50px rgba(255, 143, 171, 0.15);
                }

                .projeto-card .projeto-icon {
                    font-size: 2.8em;
                    margin-bottom: 15px;
                    display: block;
                }

                .projeto-card h3 {
                    font-size: 1.4em;
                    margin-bottom: 10px;
                    text-shadow: 0 0 20px currentColor;
                }

                .projeto-card p {
                    color: #c8bdd0;
                    line-height: 1.6;
                    margin-bottom: 15px;
                    font-size: 0.95em;
                }

                .projeto-card .projeto-tags {
                    display: flex;
                    flex-wrap: wrap;
                    gap: 8px;
                    margin-bottom: 18px;
                }

                .projeto-card .projeto-tags span {
                    background: rgba(137, 180, 250, 0.08);
                    color: var(--neon-blue);
                    padding: 4px 14px;
                    border-radius: 20px;
                    font-size: 0.75em;
                    border: 1px solid rgba(137, 180, 250, 0.12);
                    font-weight: 500;
                }

                .projeto-card .projeto-link {
                    display: inline-flex;
                    align-items: center;
                    gap: 6px;
                    color: var(--neon-cyan);
                    text-decoration: none;
                    font-weight: 600;
                    transition: all 0.3s;
                    text-shadow: 0 0 10px var(--neon-cyan-glow);
                    font-size: 0.95em;
                    border-bottom: 1px solid transparent;
                }

                .projeto-card .projeto-link:hover {
                    color: var(--neon-pink);
                    text-shadow: 0 0 30px var(--neon-pink-glow);
                    border-bottom-color: var(--neon-pink);
                }

                /* ========== CONTATO ========== */
                .contato-container {
                    max-width: 600px;
                    margin: 40px auto;
                    padding: 40px;
                    border-radius: 20px;
                    background: var(--bg-card);
                    backdrop-filter: blur(10px);
                    border: 1px solid var(--border-card);
                    position: relative;
                    z-index: 1;
                }

                .contato-form .form-group {
                    margin-bottom: 20px;
                }

                .contato-form label {
                    display: block;
                    color: var(--neon-purple);
                    font-weight: 500;
                    margin-bottom: 6px;
                    text-shadow: 0 0 10px var(--neon-purple-glow);
                }

                .contato-form input,
                .contato-form textarea {
                    width: 100%;
                    padding: 12px 16px;
                    border-radius: 12px;
                    border: 1px solid var(--border-card);
                    background: rgba(30, 22, 38, 0.6);
                    color: #e0d6e8;
                    font-size: 1em;
                    transition: all 0.3s;
                    outline: none;
                }

                .contato-form input:focus,
                .contato-form textarea:focus {
                    border-color: var(--neon-pink);
                    box-shadow: 0 0 30px var(--neon-pink-glow);
                }

                .contato-form textarea {
                    resize: vertical;
                    min-height: 120px;
                }

                .contato-form .btn {
                    width: 100%;
                    justify-content: center;
                }

                /* ========== FOOTER ========== */
                .neon-footer {
                    max-width: 1200px;
                    margin: 60px auto 30px;
                    padding: 30px 20px;
                    text-align: center;
                    border-top: 1px solid var(--border-card);
                    position: relative;
                    z-index: 1;
                }

                .neon-footer p {
                    color: #9a8aa2;
                    font-size: 0.95em;
                    font-weight: 400;
                }

                .footer-links {
                    display: flex;
                    justify-content: center;
                    gap: 25px;
                    margin-top: 12px;
                    flex-wrap: wrap;
                }

                .neon-link-footer {
                    color: var(--neon-purple);
                    text-decoration: none;
                    font-size: 0.9em;
                    transition: all 0.3s;
                    text-shadow: 0 0 10px var(--neon-purple-glow);
                }

                .neon-link-footer:hover {
                    color: var(--neon-pink);
                    text-shadow: 0 0 30px var(--neon-pink-glow);
                }

                /* ========== RESPONSIVO ========== */
                @media (max-width: 768px) {
                    .nav-menu {
                        display: none;
                    }

                    .hero-title {
                        font-size: 2.4em;
                    }

                    .hero-subtitle {
                        font-size: 1.1em;
                    }

                    .highlights-grid {
                        grid-template-columns: 1fr;
                    }

                    .projetos-grid {
                        grid-template-columns: 1fr;
                    }

                    .contato-container {
                        padding: 20px;
                    }

                    .destaque-grid {
                        grid-template-columns: 1fr 1fr;
                    }
                }

                @media (max-width: 480px) {
                    .destaque-grid {
                        grid-template-columns: 1fr;
                    }

                    .social-grid {
                        grid-template-columns: 1fr 1fr;
                    }
                }
                """);
                System.out.println("📄 style.css criado em ./public/");
            } catch (IOException e) {
                System.err.println("⚠️ Erro ao criar style.css: " + e.getMessage());
            }
        }
    }

    private static void criarArquivoScript() {
        File file = new File(DIRETORIO_PUBLICO + "/script.js");
        if (!file.exists()) {
            try (FileWriter fw = new FileWriter(file)) {
                fw.write("""
                // ============ PARTÍCULAS DE FUNDO ============
                const canvas = document.getElementById('particles');
                const ctx = canvas.getContext('2d');

                canvas.width = window.innerWidth;
                canvas.height = window.innerHeight;

                const particles = [];
                const particleCount = 80;

                const cores = [
                    'rgba(255, 143, 171, ',
                    'rgba(137, 180, 250, ',
                    'rgba(121, 224, 224, ',
                    'rgba(203, 166, 247, ',
                    'rgba(249, 226, 175, '
                ];

                class Particle {
                    constructor() {
                        this.x = Math.random() * canvas.width;
                        this.y = Math.random() * canvas.height;
                        this.size = Math.random() * 2.5 + 1;
                        this.speedX = (Math.random() - 0.5) * 0.3;
                        this.speedY = (Math.random() - 0.5) * 0.3;
                        this.color = cores[Math.floor(Math.random() * cores.length)];
                        this.opacity = Math.random() * 0.4 + 0.1;
                    }

                    update() {
                        this.x += this.speedX;
                        this.y += this.speedY;

                        if (this.x > canvas.width || this.x < 0) this.speedX *= -1;
                        if (this.y > canvas.height || this.y < 0) this.speedY *= -1;
                    }

                    draw() {
                        ctx.beginPath();
                        ctx.arc(this.x, this.y, this.size, 0, Math.PI * 2);
                        ctx.fillStyle = this.color + this.opacity + ')';
                        ctx.shadowColor = this.color + '0.3)';
                        ctx.shadowBlur = 20;
                        ctx.fill();
                        ctx.shadowBlur = 0;
                    }
                }

                for (let i = 0; i < particleCount; i++) {
                    particles.push(new Particle());
                }

                function animateParticles() {
                    ctx.clearRect(0, 0, canvas.width, canvas.height);
                    particles.forEach(p => {
                        p.update();
                        p.draw();
                    });

                    for (let i = 0; i < particles.length; i++) {
                        for (let j = i + 1; j < particles.length; j++) {
                            const dx = particles[i].x - particles[j].x;
                            const dy = particles[i].y - particles[j].y;
                            const dist = Math.sqrt(dx * dx + dy * dy);
                            if (dist < 150) {
                                ctx.beginPath();
                                ctx.strokeStyle = 'rgba(255, 143, 171, 0.06)';
                                ctx.lineWidth = 0.5;
                                ctx.moveTo(particles[i].x, particles[i].y);
                                ctx.lineTo(particles[j].x, particles[j].y);
                                ctx.stroke();
                            }
                        }
                    }

                    requestAnimationFrame(animateParticles);
                }

                animateParticles();

                window.addEventListener('resize', () => {
                    canvas.width = window.innerWidth;
                    canvas.height = window.innerHeight;
                });

                // ============ NAVEGAÇÃO ============
                document.querySelectorAll('.nav-menu a').forEach(link => {
                    link.addEventListener('click', function(e) {
                        document.querySelectorAll('.nav-menu a').forEach(a => a.classList.remove('active'));
                        this.classList.add('active');
                    });
                });

                // ============ STATUS DO SERVIDOR ============
                function atualizarStatus() {
                    fetch('/api/status')
                        .then(r => r.json())
                        .then(data => {
                            const online = data.status === 'online';
                            const dot = document.querySelector('.status-dot');
                            if (dot) {
                                dot.className = 'status-dot ' + (online ? 'online' : 'offline');
                            }
                            const statusText = document.querySelector('.neon-text');
                            if (statusText) {
                                statusText.textContent = online ? '#Online' : '#Offline';
                            }
                        })
                        .catch(() => {
                            const dot = document.querySelector('.status-dot');
                            if (dot) dot.className = 'status-dot offline';
                            const statusText = document.querySelector('.neon-text');
                            if (statusText) statusText.textContent = '#Offline';
                        });
                }

                setInterval(atualizarStatus, 5000);
                atualizarStatus();

                // ============ SESSÃO ============
                function carregarSessao() {
                    fetch('/api/sessao')
                        .then(r => r.json())
                        .then(data => {
                            const sessao = data.sessao || {};
                            const sessionId = document.getElementById('session-id');
                            const sessionData = document.getElementById('session-data-content');
                            const sessionExpira = document.getElementById('session-expira');
                            const sessionIdDash = document.getElementById('session-id-dash');

                            if (sessionId) sessionId.textContent = sessao.SESSION_ID || 'N/A';
                            if (sessionIdDash) sessionIdDash.textContent = sessao.SESSION_ID ? 'Ativa' : 'Nenhuma';
                            if (sessionData) sessionData.textContent = JSON.stringify(sessao, null, 2);
                            if (sessionExpira) sessionExpira.textContent = sessao.expira || 'N/A';
                        })
                        .catch(() => {
                            const sessionData = document.getElementById('session-data-content');
                            if (sessionData) sessionData.textContent = 'Erro ao carregar sessão';
                        });
                }

                function limparSessao() {
                    if (confirm('Tem certeza que deseja limpar a sessão?')) {
                        document.cookie = 'SESSION_ID=; expires=Thu, 01 Jan 1970 00:00:00 UTC; path=/;';
                        alert('Sessão limpa! Recarregando a página...');
                        location.reload();
                    }
                }

                function testarSessao(tipo, valor) {
                    let url = '/api/';
                    if (tipo === 'contador') {
                        url = '/api/contador';
                        fetch(url)
                            .then(r => r.json())
                            .then(data => {
                                const contador = document.getElementById('contador-dash');
                                if (contador) contador.textContent = data.contador || 0;
                                carregarSessao();
                            })
                            .catch(() => alert('Erro ao incrementar contador'));
                    } else if (tipo === 'nome') {
                        if (!valor) return;
                        url = '/api/hello?nome=' + encodeURIComponent(valor);
                        fetch(url)
                            .then(() => {
                                carregarSessao();
                                alert('Nome salvo na sessão!');
                            })
                            .catch(() => alert('Erro ao salvar nome'));
                    }
                }

                function atualizarContador() {
                    fetch('/api/contador')
                        .then(r => r.json())
                        .then(data => {
                            const contador = document.getElementById('contador-dash');
                            if (contador) contador.textContent = data.contador || 0;
                        })
                        .catch(() => {});
                }

                if (document.getElementById('session-id')) {
                    carregarSessao();
                }
                if (document.getElementById('contador-dash')) {
                    atualizarContador();
                    setInterval(atualizarContador, 3000);
                }
                setInterval(carregarSessao, 10000);

                // ============ CHAT WEBSOCKET ============
                let ws = null;
                let wsConectado = false;
                let nomeUsuario = 'Anônimo';

                function conectarWebSocket() {
                    if (!document.getElementById('chat-messages')) return;

                    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
                    ws = new WebSocket(protocol + '//' + window.location.host + '/ws');

                    ws.onopen = function() {
                        wsConectado = true;
                        const dot = document.getElementById('chat-status-dot');
                        const text = document.getElementById('chat-status-text');
                        const input = document.getElementById('chat-input');
                        const sendBtn = document.getElementById('chat-send');

                        if (dot) { dot.className = 'status-dot online'; }
                        if (text) { text.textContent = 'Conectado'; text.className = 'status-text online'; }
                        if (input) { input.disabled = false; input.focus(); }
                        if (sendBtn) { sendBtn.disabled = false; }

                        adicionarMensagemChat('🔗 Conectado ao servidor!', 'sistema');
                    };

                    ws.onmessage = function(evento) {
                        adicionarMensagemChat(evento.data, 'outro');
                    };

                    ws.onclose = function() {
                        wsConectado = false;
                        const dot = document.getElementById('chat-status-dot');
                        const text = document.getElementById('chat-status-text');
                        const input = document.getElementById('chat-input');
                        const sendBtn = document.getElementById('chat-send');

                        if (dot) { dot.className = 'status-dot offline'; }
                        if (text) { text.textContent = 'Desconectado'; text.className = 'status-text offline'; }
                        if (input) { input.disabled = true; }
                        if (sendBtn) { sendBtn.disabled = true; }

                        adicionarMensagemChat('🔌 Desconectado. Tentando reconectar em 3s...', 'sistema');
                        setTimeout(conectarWebSocket, 3000);
                    };

                    ws.onerror = function() {
                        adicionarMensagemChat('❌ Erro na conexão.', 'sistema');
                    };
                }

                function adicionarMensagemChat(texto, classe = 'usuario') {
                    const container = document.getElementById('chat-messages');
                    if (!container) return;

                    const div = document.createElement('div');
                    div.className = 'msg ' + classe;

                    if (classe === 'usuario') {
                        div.textContent = '👤 ' + texto;
                    } else if (classe === 'outro') {
                        div.textContent = texto;
                    } else {
                        div.textContent = texto;
                    }

                    container.appendChild(div);
                    container.scrollTop = container.scrollHeight;
                }

                function enviarMensagemChat() {
                    const input = document.getElementById('chat-input');
                    if (!input) return;

                    const texto = input.value.trim();
                    if (!texto || !ws || ws.readyState !== WebSocket.OPEN) return;

                    ws.send(texto);
                    adicionarMensagemChat(texto, 'usuario');
                    input.value = '';
                    input.focus();
                }

                const chatInput = document.getElementById('chat-input');
                const chatSend = document.getElementById('chat-send');

                if (chatInput) {
                    chatInput.addEventListener('keydown', function(e) {
                        if (e.key === 'Enter') enviarMensagemChat();
                    });
                }
                if (chatSend) {
                    chatSend.addEventListener('click', enviarMensagemChat);
                }

                if (document.getElementById('chat-messages')) {
                    conectarWebSocket();
                }

                // ============ API TESTER ============
                function testarAPI() {
                    const method = document.getElementById('api-method');
                    const endpointInput = document.getElementById('api-endpoint');
                    const bodyInput = document.getElementById('api-body');
                    const responseDiv = document.getElementById('api-response-content');
                    const statusDiv = document.getElementById('api-response-status');

                    if (!method || !endpointInput || !responseDiv) return;

                    const methodValue = method.value;
                    let endpoint = endpointInput.value;
                    const body = bodyInput ? bodyInput.value : '';

                    if (methodValue === 'GET' && body) {
                        try {
                            const obj = JSON.parse(body);
                            const params = new URLSearchParams(obj);
                            endpoint += (endpoint.includes('?') ? '&' : '?') + params.toString();
                        } catch (e) {}
                    }

                    responseDiv.textContent = '⏳ Carregando...';
                    if (statusDiv) {
                        statusDiv.textContent = '';
                        statusDiv.className = 'response-status';
                    }

                    const options = {
                        method: methodValue,
                        headers: { 'Content-Type': 'application/json' }
                    };

                    if (methodValue !== 'GET' && body) {
                        options.body = body;
                    }

                    fetch(endpoint, options)
                        .then(r => {
                            if (statusDiv) {
                                const status = r.status + ' ' + r.statusText;
                                statusDiv.textContent = '📊 ' + status;
                                statusDiv.className = 'response-status ' + (r.ok ? 'success' : 'error');
                            }
                            return r.text();
                        })
                        .then(data => {
                            try {
                                const json = JSON.parse(data);
                                responseDiv.textContent = JSON.stringify(json, null, 2);
                            } catch (e) {
                                responseDiv.textContent = data || '✅ Requisição bem-sucedida (sem corpo)';
                            }
                        })
                        .catch(err => {
                            responseDiv.textContent = '❌ Erro: ' + err.message;
                            if (statusDiv) {
                                statusDiv.textContent = '❌ Erro na requisição';
                                statusDiv.className = 'response-status error';
                            }
                        });
                }

                const apiMethod = document.getElementById('api-method');
                if (apiMethod) {
                    apiMethod.addEventListener('change', function() {
                        const bodyGroup = document.getElementById('api-body-group');
                        if (bodyGroup) {
                            if (this.value === 'GET') {
                                bodyGroup.style.display = 'none';
                            } else {
                                bodyGroup.style.display = 'block';
                            }
                        }
                    });
                }

                // ============ CONTATO ============
                function enviarContato(event) {
                    event.preventDefault();

                    const nome = document.getElementById('nome');
                    const email = document.getElementById('email');
                    const assunto = document.getElementById('assunto');
                    const mensagem = document.getElementById('mensagem');

                    if (!nome || !email || !assunto || !mensagem) return;

                    const btn = event.target.querySelector('.btn');
                    if (!btn) return;

                    const textoOriginal = btn.textContent;
                    btn.textContent = '⏳ Enviando...';
                    btn.disabled = true;

                    fetch('/api/contato', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({
                            nome: nome.value,
                            email: email.value,
                            assunto: assunto.value,
                            mensagem: mensagem.value
                        })
                    })
                    .then(r => r.json())
                    .then(data => {
                        btn.textContent = '✅ Enviado!';
                        btn.style.background = '#a6e3a1';
                        btn.style.color = '#0f0a12';
                        btn.style.boxShadow = '0 0 40px rgba(166, 227, 161, 0.5)';

                        event.target.reset();

                        setTimeout(() => {
                            btn.textContent = textoOriginal;
                            btn.style.background = '';
                            btn.style.color = '';
                            btn.style.boxShadow = '';
                            btn.disabled = false;
                        }, 3000);
                    })
                    .catch(() => {
                        btn.textContent = '❌ Erro';
                        setTimeout(() => {
                            btn.textContent = textoOriginal;
                            btn.disabled = false;
                        }, 3000);
                    });
                }
                """);
                System.out.println("📄 script.js criado em ./public/");
            } catch (IOException e) {
                System.err.println("⚠️ Erro ao criar script.js: " + e.getMessage());
            }
        }
    }

    private static void criarArquivoExemplo() {
        File file = new File(DIRETORIO_PUBLICO + "/exemplo.txt");
        if (!file.exists()) {
            try (FileWriter fw = new FileWriter(file)) {
                fw.write("""
                Servidor HTTP/WebSocket em Java
                ================================

                Versão: 3.0
                Data: """ + new Date() + """

                Portfólio de Misael Andrejezieski

                Páginas:
                - / (Home)
                - /projetos
                - /contato
                - /ws (Chat WebSocket)

                Links:
                - https://misaandrejezieski.github.io/Misa/
                - https://github.com/MisaAndrejezieski
                - https://www.linkedin.com/in/misael-andrejezieski-b4996720a/
                - https://www.instagram.com/misaelandrejezieski/
                - https://www.facebook.com/profile.php?id=100034358779961
                """);
            } catch (IOException e) {
                System.err.println("⚠️ Erro ao criar exemplo.txt: " + e.getMessage());
            }
        }
    }
}