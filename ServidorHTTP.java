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
    
    // Gerenciamento de sessões
    private static final Map<String, Map<String, Object>> SESSOES = new ConcurrentHashMap<>();
    private static final Map<String, Long> SESSOES_EXPIRACAO = new ConcurrentHashMap<>();
    private static final long TEMPO_EXPIRACAO_SESSAO = 30 * 60 * 1000; // 30 minutos
    
    // WebSockets
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
            System.out.println("⏹️  Pressione CTRL+C para parar\n");

            // Thread para limpar sessões expiradas
            Thread cleaner = new Thread(() -> {
                while (true) {
                    try {
                        Thread.sleep(60000); // A cada minuto
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
                        } catch (IOException e) {
                            // Ignora
                        }
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

        // Lê cabeçalhos
        Map<String, String> cabecalhos = new HashMap<>();
        while ((linha = in.readLine()) != null && !linha.isEmpty()) {
            String[] chaveValor = linha.split(": ", 2);
            if (chaveValor.length == 2) {
                cabecalhos.put(chaveValor[0], chaveValor[1]);
            }
        }

        // Lê corpo
        StringBuilder corpo = new StringBuilder();
        if (cabecalhos.containsKey("Content-Length")) {
            int contentLength = Integer.parseInt(cabecalhos.get("Content-Length"));
            for (int i = 0; i < contentLength; i++) {
                corpo.append((char) in.read());
            }
        }

        // Verifica WebSocket
        if (cabecalhos.containsKey("Upgrade") && cabecalhos.get("Upgrade").equalsIgnoreCase("websocket")) {
            handleWebSocket(cliente, cabecalhos);
            return;
        }

        // Processa cookies
        Map<String, String> cookies = parseCookies(cabecalhos.getOrDefault("Cookie", ""));
        
        // Gerencia sessão
        String sessionId = cookies.get("SESSION_ID");
        Map<String, Object> sessao = getSession(sessionId);
        if (sessao == null) {
            sessionId = gerarSessionId();
            sessao = new ConcurrentHashMap<>();
            SESSOES.put(sessionId, sessao);
            SESSOES_EXPIRACAO.put(sessionId, System.currentTimeMillis() + TEMPO_EXPIRACAO_SESSAO);
        }
        
        // Atualiza expiração
        SESSOES_EXPIRACAO.put(sessionId, System.currentTimeMillis() + TEMPO_EXPIRACAO_SESSAO);

        // Roteamento
        String response;
        if (caminhoBase.equals("/") || caminhoBase.equals("/index.html")) {
            response = servirArquivo("/index.html", out);
        } else if (caminhoBase.startsWith("/api/")) {
            response = processarAPI(metodo, caminho, corpo.toString(), sessao, out);
        } else if (caminhoBase.equals("/status")) {
            response = enviarStatus(out);
        } else if (caminhoBase.equals("/sessao")) {
            response = mostrarSessao(sessao, out);
        } else if (caminhoBase.equals("/ws")) {
            // Se não for upgrade, serve página de exemplo
            response = servirArquivo("/websocket.html", out);
        } else {
            response = servirArquivo(caminhoBase, out);
        }

        // Adiciona cookie de sessão se for nova
        if (response != null && !response.isEmpty() && !response.contains("Set-Cookie")) {
            String cookieHeader = "Set-Cookie: SESSION_ID=" + sessionId + "; Path=/; HttpOnly\r\n";
            // Insere o cookie antes do \r\n\r\n
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
        if (key == null) {
            return;
        }

        // Resposta de handshake WebSocket
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
        
        // Envia mensagem de boas-vindas
        ws.sendMessage("Bem-vindo ao WebSocket! ID: " + wsId);
        broadcast("🔵 Usuário " + wsId + " entrou no chat!");

        // Loop de leitura
        try {
            while (true) {
                String mensagem = ws.readMessage();
                if (mensagem == null) break;
                
                System.out.println("📨 WebSocket " + wsId + ": " + mensagem);
                
                // Comandos especiais
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
                            broadcast("🔴 Usuário " + wsId + " saiu do chat.");
                            ws.close();
                            return;
                        default:
                            ws.sendMessage("❌ Comando desconhecido. Comandos: /nick, /ping, /sair");
                    }
                } else {
                    // Broadcast para todos
                    broadcast("💬 " + ws.getNome() + ": " + mensagem);
                }
            }
        } catch (IOException e) {
            System.err.println("⚠️ WebSocket " + wsId + " desconectado: " + e.getMessage());
        } finally {
            WEBSOCKETS.remove(wsId);
            broadcast("🔴 Usuário " + wsId + " saiu do chat.");
            ws.close();
            System.out.println("🔌 WebSocket desconectado: " + wsId + " (Total: " + WEBSOCKETS.size() + ")");
        }
    }

    private static void broadcast(String mensagem) {
        for (WebSocketConnection ws : WEBSOCKETS.values()) {
            try {
                ws.sendMessage(mensagem);
            } catch (IOException e) {
                // Ignora
            }
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
        private String id;
        private String nome;

        public WebSocketConnection(Socket socket, String id) throws IOException {
            this.socket = socket;
            this.in = socket.getInputStream();
            this.out = socket.getOutputStream();
            this.id = id;
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
            
            // Frame WebSocket (texto)
            byte[] frame = new byte[2 + tamanho];
            frame[0] = (byte) 0x81; // FIN + opcode texto
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
            // Lê o primeiro byte (FIN + opcode)
            int b1 = in.read();
            if (b1 == -1) return null;
            
            // Verifica se é frame de fechamento
            if ((b1 & 0x0F) == 0x08) {
                return null;
            }
            
            // Lê o segundo byte (máscara + tamanho)
            int b2 = in.read();
            if (b2 == -1) return null;
            
            boolean mascara = (b2 & 0x80) != 0;
            int tamanho = b2 & 0x7F;
            
            // Lê tamanho extendido se necessário
            if (tamanho == 126) {
                tamanho = (in.read() << 8) | in.read();
            } else if (tamanho == 127) {
                tamanho = 0;
                for (int i = 0; i < 8; i++) {
                    tamanho = (tamanho << 8) | in.read();
                }
            }
            
            // Lê máscara (4 bytes)
            byte[] mascaraBytes = null;
            if (mascara) {
                mascaraBytes = new byte[4];
                in.read(mascaraBytes);
            }
            
            // Lê dados
            byte[] dados = new byte[tamanho];
            in.read(dados);
            
            // Desmascara se necessário
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
            } catch (IOException e) {
                // Ignora
            }
        }
    }

    // ==================== SERVE ARQUIVOS ====================
    
    private static String servirArquivo(String caminho, OutputStream out) throws IOException {
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
                                        Map<String, Object> sessao, OutputStream out) throws IOException {
        String resposta = "";
        int status = 200;

        // GET /api/hello?nome=Joao
        if (caminho.startsWith("/api/hello")) {
            String nome = extrairParametro(caminho, "nome");
            if (nome == null) nome = "Mundo";
            // Guarda na sessão
            sessao.put("ultimoNome", nome);
            sessao.put("ultimaVisita", new Date().toString());
            resposta = "{\"mensagem\": \"Olá, " + nome + "!\", \"timestamp\": \"" + new Date() + "\", \"session\": \"" + sessao + "\"}";
        } 
        // POST /api/echo
        else if (metodo.equals("POST") && caminho.equals("/api/echo")) {
            resposta = "{\"recebido\": " + corpo + "}";
        }
        // GET /api/status
        else if (caminho.equals("/api/status")) {
            resposta = "{\"status\": \"online\", \"versao\": \"3.0\", \"websockets\": " + WEBSOCKETS.size() + "}";
        }
        // GET /api/hora
        else if (caminho.equals("/api/hora")) {
            resposta = "{\"hora\": \"" + new SimpleDateFormat("HH:mm:ss").format(new Date()) + "\"}";
        }
        // GET /api/data
        else if (caminho.equals("/api/data")) {
            resposta = "{\"data\": \"" + new SimpleDateFormat("dd/MM/yyyy").format(new Date()) + "\"}";
        }
        // POST /api/contato
        else if (metodo.equals("POST") && caminho.equals("/api/contato")) {
            sessao.put("contato", corpo);
            resposta = "{\"mensagem\": \"Contato recebido com sucesso!\", \"dados\": " + corpo + "}";
        }
        // GET /api/sessao
        else if (caminho.equals("/api/sessao")) {
            resposta = "{\"sessao\": " + new com.google.gson.Gson().toJson(sessao) + "}";
        }
        // GET /api/contador
        else if (caminho.equals("/api/contador")) {
            Integer contador = (Integer) sessao.getOrDefault("contador", 0);
            contador++;
            sessao.put("contador", contador);
            resposta = "{\"contador\": " + contador + "}";
        }
        // GET /api/websockets
        else if (caminho.equals("/api/websockets")) {
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

    // ==================== STATUS ====================
    
    private static String enviarStatus(OutputStream out) {
        return "HTTP/1.1 200 OK\r\n" +
               "Content-Type: text/plain\r\n" +
               "Content-Length: 14\r\n" +
               "Server: JavaHTTPServer/3.0\r\n" +
               "Connection: close\r\n" +
               "\r\n" +
               "Servidor OK ✓";
    }

    // ==================== MOSTRAR SESSÃO ====================
    
    private static String mostrarSessao(Map<String, Object> sessao, OutputStream out) {
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

        criarIndexHtml();
        criarWebSocketHtml();
        criarExemploTxt();
    }

    private static void criarIndexHtml() {
        File index = new File(DIRETORIO_PUBLICO + "/index.html");
        if (!index.exists()) {
            try (FileWriter fw = new FileWriter(index)) {
                fw.write("""
                <!DOCTYPE html>
                <html>
                <head>
                    <meta charset="UTF-8">
                    <title>Servidor Java</title>
                    <style>
                        * { margin: 0; padding: 0; box-sizing: border-box; }
                        body {
                            font-family: 'Segoe UI', Arial, sans-serif;
                            max-width: 1000px;
                            margin: 50px auto;
                            text-align: center;
                            background: #f0f2f5;
                            padding: 20px;
                        }
                        h1 { color: #2c3e50; font-size: 2.8em; margin-bottom: 10px; }
                        .subtitle { color: #7f8c8d; font-size: 1.2em; margin-bottom: 30px; }
                        .info {
                            background: white;
                            padding: 30px;
                            border-radius: 15px;
                            box-shadow: 0 4px 20px rgba(0,0,0,0.08);
                            margin-bottom: 20px;
                        }
                        .badge {
                            display: inline-block;
                            background: #27ae60;
                            color: white;
                            padding: 8px 25px;
                            border-radius: 20px;
                            font-weight: bold;
                            margin-bottom: 20px;
                        }
                        .grid {
                            display: grid;
                            grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
                            gap: 15px;
                            margin: 20px 0;
                        }
                        .item {
                            background: #f8f9fa;
                            padding: 20px;
                            border-radius: 10px;
                            border: 1px solid #e9ecef;
                        }
                        .item strong { display: block; font-size: 1.3em; margin-bottom: 5px; color: #2c3e50; }
                        .item a { color: #3498db; text-decoration: none; }
                        .item a:hover { text-decoration: underline; }
                        .footer { margin-top: 30px; color: #95a5a6; font-size: 0.9em; }
                        .api-list { text-align: left; display: inline-block; margin: 15px auto; }
                        .api-list li { margin: 8px 0; padding: 8px; background: #f8f9fa; border-radius: 5px; }
                        .api-list code { background: #2c3e50; color: white; padding: 2px 10px; border-radius: 4px; font-size: 0.9em; }
                        .websocket-link {
                            display: inline-block;
                            background: #8e44ad;
                            color: white;
                            padding: 10px 30px;
                            border-radius: 25px;
                            text-decoration: none;
                            font-weight: bold;
                            margin: 10px 0;
                        }
                        .websocket-link:hover { background: #6c3483; }
                    </style>
                </head>
                <body>
                    <h1>🚀 Servidor Java</h1>
                    <p class="subtitle">HTTP + WebSockets + Sessões + Cookies</p>
                    
                    <div class="info">
                        <p><span class="badge">🟢 Online v3.0</span></p>
                        <p>📁 Arquivos: <code>./public/</code></p>
                        <p>🧵 Threads: pool dinâmico</p>
                        <p>🍪 Cookies e Sessões ativos</p>
                        <p>🔌 WebSockets: <strong id="ws-count">0</strong> conexões ativas</p>
                        
                        <div class="grid">
                            <div class="item">
                                <strong>📄 Arquivos</strong>
                                HTML, CSS, JS, imagens
                            </div>
                            <div class="item">
                                <strong>⚡ API REST</strong>
                                Endpoints JSON
                            </div>
                            <div class="item">
                                <strong>🔌 WebSocket</strong>
                                Chat em tempo real
                            </div>
                            <div class="item">
                                <strong>🍪 Sessão</strong>
                                Dados persistentes
                            </div>
                        </div>
                    </div>

                    <div class="info">
                        <h3>🔗 Endpoints</h3>
                        <ul class="api-list">
                            <li><a href="/">/</a> - Página inicial</li>
                            <li><a href="/status">/status</a> - Status</li>
                            <li><a href="/sessao">/sessao</a> - Ver sessão</li>
                            <li><a href="/api/hello?nome=Java">/api/hello?nome=Java</a> - Saudação</li>
                            <li><a href="/api/hora">/api/hora</a> - Hora</li>
                            <li><a href="/api/data">/api/data</a> - Data</li>
                            <li><a href="/api/contador">/api/contador</a> - Contador (sessão)</li>
                            <li><a href="/api/status">/api/status</a> - Status JSON</li>
                        </ul>
                    </div>

                    <div class="info">
                        <h3>🔌 WebSocket Chat</h3>
                        <p>Clique no botão abaixo para abrir o chat em tempo real:</p>
                        <a href="/ws" class="websocket-link">💬 Abrir Chat</a>
                        <p style="margin-top: 10px; font-size: 0.9em; color: #7f8c8d;">
                            Comandos: <code>/nick [nome]</code> | <code>/ping</code> | <code>/sair</code>
                        </p>
                    </div>

                    <div class="footer">
                        <p>🔧 Servidor Java puro - sem frameworks</p>
                        <p>Versão 3.0 com WebSockets, Cookies e Sessões</p>
                    </div>

                    <script>
                        // Atualiza contador de WebSockets
                        setInterval(() => {
                            fetch('/api/websockets')
                                .then(r => r.json())
                                .then(data => {
                                    document.getElementById('ws-count').textContent = data.total;
                                })
                                .catch(() => {});
                        }, 2000);
                    </script>
                </body>
                </html>
                """);
                System.out.println("📄 index.html criado em ./public/");
            } catch (IOException e) {
                System.err.println("⚠️ Erro ao criar index.html: " + e.getMessage());
            }
        }
    }

    private static void criarWebSocketHtml() {
        File wsHtml = new File(DIRETORIO_PUBLICO + "/websocket.html");
        if (!wsHtml.exists()) {
            try (FileWriter fw = new FileWriter(wsHtml)) {
                fw.write("""
                <!DOCTYPE html>
                <html>
                <head>
                    <meta charset="UTF-8">
                    <title>Chat WebSocket</title>
                    <style>
                        * { margin: 0; padding: 0; box-sizing: border-box; }
                        body {
                            font-family: 'Segoe UI', Arial, sans-serif;
                            max-width: 800px;
                            margin: 30px auto;
                            padding: 20px;
                            background: #f0f2f5;
                        }
                        h1 { color: #2c3e50; margin-bottom: 10px; }
                        .status {
                            padding: 10px 20px;
                            border-radius: 10px;
                            margin-bottom: 20px;
                            font-weight: bold;
                        }
                        .status.conectado { background: #d5f5e3; color: #1a7a42; }
                        .status.desconectado { background: #fadbd8; color: #922b21; }
                        #chat {
                            background: white;
                            border-radius: 15px;
                            padding: 20px;
                            height: 400px;
                            overflow-y: auto;
                            margin-bottom: 20px;
                            border: 1px solid #ddd;
                        }
                        #chat .msg {
                            padding: 8px 12px;
                            margin: 5px 0;
                            border-radius: 10px;
                            animation: fadeIn 0.3s;
                        }
                        #chat .msg.sistema { background: #e8daef; color: #6c3483; }
                        #chat .msg.usuario { background: #d6eaf8; color: #1a5276; }
                        #chat .msg.outro { background: #f0f0f0; color: #2c3e50; }
                        @keyframes fadeIn { from { opacity: 0; transform: translateY(-10px); } to { opacity: 1; transform: translateY(0); } }
                        .input-area {
                            display: flex;
                            gap: 10px;
                        }
                        #input {
                            flex: 1;
                            padding: 12px 20px;
                            border: 2px solid #ddd;
                            border-radius: 25px;
                            font-size: 16px;
                            outline: none;
                            transition: border-color 0.3s;
                        }
                        #input:focus { border-color: #3498db; }
                        #enviar {
                            padding: 12px 30px;
                            background: #3498db;
                            color: white;
                            border: none;
                            border-radius: 25px;
                            font-size: 16px;
                            cursor: pointer;
                            transition: background 0.3s;
                        }
                        #enviar:hover { background: #2471a3; }
                        #enviar:disabled { opacity: 0.5; cursor: not-allowed; }
                        .comandos {
                            margin-top: 10px;
                            color: #7f8c8d;
                            font-size: 0.9em;
                        }
                        .comandos code { background: #ecf0f1; padding: 2px 8px; border-radius: 4px; }
                        .voltar {
                            display: inline-block;
                            margin-top: 15px;
                            color: #3498db;
                            text-decoration: none;
                        }
                        .voltar:hover { text-decoration: underline; }
                    </style>
                </head>
                <body>
                    <h1>💬 Chat WebSocket</h1>
                    <div id="status" class="status desconectado">🔴 Desconectado</div>
                    <div id="chat"></div>
                    <div class="input-area">
                        <input type="text" id="input" placeholder="Digite sua mensagem..." disabled>
                        <button id="enviar" disabled>Enviar</button>
                    </div>
                    <div class="comandos">
                        Comandos: <code>/nick [nome]</code> - mudar nome | <code>/ping</code> - testar | <code>/sair</code> - sair
                    </div>
                    <a href="/" class="voltar">← Voltar para o início</a>

                    <script>
                        let ws = null;
                        const chat = document.getElementById('chat');
                        const input = document.getElementById('input');
                        const enviar = document.getElementById('enviar');
                        const statusDiv = document.getElementById('status');

                        function conectar() {
                            const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
                            ws = new WebSocket(protocol + '//' + window.location.host + '/ws');

                            ws.onopen = function() {
                                statusDiv.className = 'status conectado';
                                statusDiv.textContent = '🟢 Conectado';
                                input.disabled = false;
                                enviar.disabled = false;
                                input.focus();
                                adicionarMensagem('🔗 Conectado ao servidor!', 'sistema');
                            };

                            ws.onmessage = function(evento) {
                                adicionarMensagem(evento.data, 'outro');
                            };

                            ws.onclose = function() {
                                statusDiv.className = 'status desconectado';
                                statusDiv.textContent = '🔴 Desconectado';
                                input.disabled = true;
                                enviar.disabled = true;
                                adicionarMensagem('🔌 Desconectado do servidor.', 'sistema');
                                setTimeout(conectar, 3000);
                            };

                            ws.onerror = function() {
                                adicionarMensagem('❌ Erro na conexão.', 'sistema');
                            };
                        }

                        function adicionarMensagem(texto, classe = 'usuario') {
                            const div = document.createElement('div');
                            div.className = 'msg ' + classe;
                            div.textContent = texto;
                            chat.appendChild(div);
                            chat.scrollTop = chat.scrollHeight;
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

                        // Conecta automaticamente
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

    private static void criarExemploTxt() {
        File exemplo = new File(DIRETORIO_PUBLICO + "/exemplo.txt");
        if (!exemplo.exists()) {
            try (FileWriter fw = new FileWriter(exemplo)) {
                fw.write("""
                Servidor HTTP/WebSocket em Java
                ================================
                
                Versão: 3.0
                Data: """ + new Date() + """
                
                Funcionalidades:
                ✅ Servir arquivos estáticos
                ✅ API REST com JSON
                ✅ WebSockets (chat em tempo real)
                ✅ Cookies de sessão
                ✅ Sessões persistentes
                ✅ Multi-thread
                
                Endpoints:
                - GET  /api/hello?nome=...  -> Saudação
                - GET  /api/hora            -> Hora atual
                - GET  /api/data            -> Data atual
                - GET  /api/contador        -> Contador (sessão)
                - GET  /api/status          -> Status do servidor
                - GET  /api/websockets      -> Conexões WebSocket
                - POST /api/echo            -> Ecoa o corpo
                - POST /api/contato         -> Recebe contato
                
                WebSocket:
                - ws://localhost:8080/ws
                - Comandos: /nick, /ping, /sair
                """);
            } catch (IOException e) {
                System.err.println("⚠️ Erro ao criar exemplo.txt: " + e.getMessage());
            }
        }
    }
}