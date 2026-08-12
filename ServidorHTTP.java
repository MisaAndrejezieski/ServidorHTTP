import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
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
            System.out.println("📄 Páginas: / (home) | /projetos | /contato");
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

        StringBuilder corpo = new StringBuilder();
        if (cabecalhos.containsKey("Content-Length")) {
            int contentLength = Integer.parseInt(cabecalhos.get("Content-Length"));
            for (int i = 0; i < contentLength; i++) {
                corpo.append((char) in.read());
            }
        }

        if (cabecalhos.containsKey("Upgrade") && cabecalhos.get("Upgrade").equalsIgnoreCase("websocket")) {
            handleWebSocket(cliente, cabecalhos);
            return;
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
    private static void handleWebSocket(Socket cliente, Map<String, String> cabecalhos) throws IOException {
        String key = cabecalhos.get("Sec-WebSocket-Key");
        if (key == null) return;

        String accept = base64Encode(sha1(key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"));
        String response = "HTTP/1.1 101 Switching Protocols\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: " + accept + "\r\n" +
                "\r\n";

        cliente.getOutputStream().write(response.getBytes());
        cliente.getOutputStream().flush();

        String wsId = "ws-" + (++websocketIdCounter);
        WebSocketConnection ws = new WebSocketConnection(cliente, wsId);
        WEBSOCKETS.put(wsId, ws);

        System.out.println("🔌 WebSocket conectado: " + wsId + " (Total: " + WEBSOCKETS.size() + ")");

        ws.sendMessage("✦ Bem-vindo ao chat neon, " + ws.getNome() + "!");
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

    private static String sha1(String input) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return new String(Base64.getEncoder().encode(digest), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.security.NoSuchAlgorithmException e) {
            return "";
        }
    }

    private static String base64Encode(String input) {
        return input;
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
            in.read(dados);

            if (mascara && mascaraBytes != null) {
                for (int i = 0; i < dados.length; i++) {
                    dados[i] ^= mascaraBytes[i % 4];
                }
            }

            return new String(dados, java.nio.charset.StandardCharsets.UTF_8);
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

        // Cria os arquivos HTML se não existirem
        criarArquivoIndex();
        criarArquivoProjetos();
        criarArquivoContato();
        criarArquivoWebSocket();
        criarArquivoExemplo();
    }

    private static void criarArquivoIndex() {
        File index = new File(DIRETORIO_PUBLICO + "/index.html");
        if (!index.exists()) {
            try (FileWriter fw = new FileWriter(index)) {
                fw.write("""
                <!DOCTYPE html>
                <html>
                <head>
                    <meta charset="UTF-8">
                    <title>Misael Andrejezieski | Portfólio</title>
                    <link rel="stylesheet" href="/style.css">
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
                                    <span class="neon-badge">✦ Disponível para trabalho</span>
                                </div>
                                <h1 class="hero-title">
                                    <span class="neon-text-pink">Misael</span>
                                    <span class="neon-text-blue">Andrejezieski</span>
                                </h1>
                                <h2 class="hero-subtitle">
                                    <span class="neon-text-cyan">Analista</span>
                                    <span class="neon-text-purple">|</span>
                                    <span class="neon-text-green">Desenvolvedor</span>
                                    <span class="neon-text-purple">|</span>
                                    <span class="neon-text-yellow">Sistemas</span>
                                </h2>
                                <p class="hero-description">
                                    Formado em <strong>Análise e Desenvolvimento de Sistemas</strong> pela <strong>Unicesumar</strong>.
                                    Construindo soluções com código, criatividade e propósito.
                                </p>
                                <div class="hero-buttons">
                                    <a href="/projetos" class="btn neon-btn-primary">✦ Ver Projetos</a>
                                    <a href="/contato" class="btn neon-btn-secondary">✉ Contato</a>
                                </div>
                            </div>
                            <div class="hero-illustration">
                                <div class="neon-orb pink"></div>
                                <div class="neon-orb cyan"></div>
                                <div class="neon-orb purple"></div>
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
                                <a href="https://github.com" target="_blank" class="neon-link-footer">GitHub</a>
                                <a href="https://linkedin.com" target="_blank" class="neon-link-footer">LinkedIn</a>
                                <a href="/contato" class="neon-link-footer">Contato</a>
                            </div>
                        </footer>
                    </main>
                    <script src="/script.js"></script>
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
                <html>
                <head>
                    <meta charset="UTF-8">
                    <title>Projetos | Misael Andrejezieski</title>
                    <link rel="stylesheet" href="/style.css">
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
                            </ul>
                            <div class="nav-status">
                                <span class="status-dot online"></span>
                                <span class="neon-text">#Online</span>
                            </div>
                        </div>
                    </nav>
                    <main>
                        <section style="text-align: center; padding: 20px 0;">
                            <h1 class="neon-title" style="font-size: 2.8em; margin-bottom: 10px;">✦ Projetos</h1>
                            <p style="color: #c8bdd0; font-size: 1.1em;">Alguns dos projetos que construí</p>
                        </section>
                        <div class="projetos-grid">
                            <div class="projeto-card neon-card">
                                <div class="projeto-icon">🚀</div>
                                <h3>Servidor HTTP + WebSocket</h3>
                                <p>Servidor construído em Java puro, com suporte a arquivos estáticos, API REST, sessões com cookies e WebSockets para chat em tempo real.</p>
                                <div class="projeto-tags">
                                    <span>Java</span>
                                    <span>Sockets</span>
                                    <span>WebSocket</span>
                                </div>
                                <a href="#" class="projeto-link">Ver mais →</a>
                            </div>
                            <div class="projeto-card neon-card">
                                <div class="projeto-icon">🧠</div>
                                <h3>Interpretador Brainfuck</h3>
                                <p>Interpretador da linguagem esotérica Brainfuck, com suporte a loops, entrada/saída e memória de 30.000 bytes. Turing-completo!</p>
                                <div class="projeto-tags">
                                    <span>Java</span>
                                    <span>Linguagens</span>
                                    <span>Compiladores</span>
                                </div>
                                <a href="#" class="projeto-link">Ver mais →</a>
                            </div>
                            <div class="projeto-card neon-card">
                                <div class="projeto-icon">🌀</div>
                                <h3>Labirinto 3D com A*</h3>
                                <p>Gerador de labirintos procedurais com visualização 3D no terminal usando raycasting simples. Inclui resolução automática com A*.</p>
                                <div class="projeto-tags">
                                    <span>Java</span>
                                    <span>Algoritmos</span>
                                    <span>3D</span>
                                </div>
                                <a href="#" class="projeto-link">Ver mais →</a>
                            </div>
                            <div class="projeto-card neon-card">
                                <div class="projeto-icon">🌐</div>
                                <h3>Portfólio Neon</h3>
                                <p>Este site! Portfólio com tema neon pastel, servido pelo próprio servidor HTTP em Java. Inclui efeitos de partículas e glitch.</p>
                                <div class="projeto-tags">
                                    <span>HTML</span>
                                    <span>CSS</span>
                                    <span>JS</span>
                                </div>
                                <a href="#" class="projeto-link">Ver mais →</a>
                            </div>
                        </div>
                        <footer class="neon-footer">
                            <p>✦ Misael Andrejezieski ✦ 2026</p>
                            <div class="footer-links">
                                <a href="https://github.com" target="_blank" class="neon-link-footer">GitHub</a>
                                <a href="https://linkedin.com" target="_blank" class="neon-link-footer">LinkedIn</a>
                                <a href="/contato" class="neon-link-footer">Contato</a>
                            </div>
                        </footer>
                    </main>
                    <script src="/script.js"></script>
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
                <html>
                <head>
                    <meta charset="UTF-8">
                    <title>Contato | Misael Andrejezieski</title>
                    <link rel="stylesheet" href="/style.css">
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
                            <p style="color: #9a8aa2;">Ou me encontre em:</p>
                            <div style="display: flex; gap: 20px; justify-content: center; margin-top: 12px; flex-wrap: wrap;">
                                <a href="https://github.com" target="_blank" class="neon-link-footer" style="font-size: 1.1em;">🐙 GitHub</a>
                                <a href="https://linkedin.com" target="_blank" class="neon-link-footer" style="font-size: 1.1em;">🔗 LinkedIn</a>
                                <a href="mailto:misael@email.com" class="neon-link-footer" style="font-size: 1.1em;">📧 E-mail</a>
                            </div>
                        </div>
                        <footer class="neon-footer">
                            <p>✦ Misael Andrejezieski ✦ 2026</p>
                            <div class="footer-links">
                                <a href="https://github.com" target="_blank" class="neon-link-footer">GitHub</a>
                                <a href="https://linkedin.com" target="_blank" class="neon-link-footer">LinkedIn</a>
                                <a href="/contato" class="neon-link-footer">Contato</a>
                            </div>
                        </footer>
                    </main>
                    <script src="/script.js"></script>
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
                <html>
                <head>
                    <meta charset="UTF-8">
                    <title>Chat WebSocket</title>
                    <link rel="stylesheet" href="/style.css">
                </head>
                <body>
                    <canvas id="particles"></canvas>
                    <nav class="navbar">
                        <div class="nav-container">
                            <div class="nav-brand">
                                <span class="logo">🚀</span>
                                <span class="glitch-text" data-text="Chat">Chat Neon</span>
                            </div>
                            <ul class="nav-menu">
                                <li><a href="/">Home</a></li>
                                <li><a href="/projetos">Projetos</a></li>
                                <li><a href="/contato">Contato</a></li>
                            </ul>
                            <div class="nav-status">
                                <span id="chat-status-indicator" class="status-dot offline"></span>
                                <span id="chat-status-text" class="neon-text">Desconectado</span>
                            </div>
                        </div>
                    </nav>
                    <main>
                        <section style="text-align: center; padding: 20px 0;">
                            <h1 class="neon-title" style="font-size: 2.8em; margin-bottom: 10px;">💬 Chat Neon</h1>
                            <p style="color: #c8bdd0; font-size: 1.1em;">Conecte-se e converse em tempo real</p>
                        </section>
                        <div class="chat-container neon-card" style="max-width: 800px; margin: 0 auto;">
                            <div id="chat-messages" class="chat-messages" style="height: 400px;"></div>
                            <div class="chat-input-area">
                                <input type="text" id="chat-input" placeholder="⚡ Digite sua mensagem..." disabled>
                                <button id="chat-send" class="neon-btn" disabled>✨ Enviar</button>
                            </div>
                            <div class="chat-commands">
                                💡 Comandos: <code class="neon-code">/nick [nome]</code> | <code class="neon-code">/ping</code> | <code class="neon-code">/sair</code>
                            </div>
                        </div>
                        <div style="text-align: center; margin-top: 20px;">
                            <a href="/" class="neon-link-footer">← Voltar para Home</a>
                        </div>
                        <footer class="neon-footer">
                            <p>✦ Misael Andrejezieski ✦ 2026</p>
                        </footer>
                    </main>
                    <script>
                        // Script do chat via WebSocket
                        let ws = null;
                        const chat = document.getElementById('chat-messages');
                        const input = document.getElementById('chat-input');
                        const enviar = document.getElementById('chat-send');
                        const statusDot = document.getElementById('chat-status-indicator');
                        const statusText = document.getElementById('chat-status-text');

                        function adicionarMensagem(texto, classe = 'outro') {
                            const div = document.createElement('div');
                            div.className = 'msg ' + classe;
                            div.textContent = texto;
                            chat.appendChild(div);
                            chat.scrollTop = chat.scrollHeight;
                        }

                        function conectar() {
                            const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
                            ws = new WebSocket(protocol + '//' + window.location.host + '/ws');

                            ws.onopen = function() {
                                statusDot.className = 'status-dot online';
                                statusText.textContent = 'Conectado';
                                statusText.className = 'neon-text';
                                input.disabled = false;
                                enviar.disabled = false;
                                input.focus();
                                adicionarMensagem('🔗 Conectado ao servidor!', 'sistema');
                            };

                            ws.onmessage = function(evento) {
                                adicionarMensagem(evento.data, 'outro');
                            };

                            ws.onclose = function() {
                                statusDot.className = 'status-dot offline';
                                statusText.textContent = 'Desconectado';
                                input.disabled = true;
                                enviar.disabled = true;
                                adicionarMensagem('🔌 Desconectado. Tentando reconectar...', 'sistema');
                                setTimeout(conectar, 3000);
                            };

                            ws.onerror = function() {
                                adicionarMensagem('❌ Erro na conexão.', 'sistema');
                            };
                        }

                        function enviarMensagem() {
                            const texto = input.value.trim();
                            if (!texto || !ws || ws.readyState !== WebSocket.OPEN) return;
                            ws.send(texto);
                            adicionarMensagem('👤 ' + texto, 'usuario');
                            input.value = '';
                            input.focus();
                        }

                        input.addEventListener('keydown', function(e) {
                            if (e.key === 'Enter') enviarMensagem();
                        });
                        enviar.addEventListener('click', enviarMensagem);

                        conectar();
                    </script>
                </body>
                </html>
                """);
                System.out.println("📄 websocket.html criado em ./public/");
            } catch (IOException e) {
                System.err.println("⚠️ Erro ao criar websocket.html: " + e.getMessage());
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
                """);
            } catch (IOException e) {
                System.err.println("⚠️ Erro ao criar exemplo.txt: " + e.getMessage());
            }
        }
    }
}