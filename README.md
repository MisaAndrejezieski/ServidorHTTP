# 🚀 Portfólio Neon - Servidor HTTP em Java

[![Java](https://img.shields.io/badge/Java-17+-ED8B00?style=for-the-badge&logo=java&logoColor=white)](https://www.oracle.com/java/)
[![License](https://img.shields.io/badge/License-MIT-blue?style=for-the-badge)](LICENSE)
[![GitHub Pages](https://img.shields.io/badge/GitHub%20Pages-Deployed-success?style=for-the-badge&logo=github)](https://misaandrejezieski.github.io/)

> Um servidor HTTP construído em Java puro, com tema neon pastel e suporte a WebSocket para chat em tempo real.

---

## 📋 Sobre o Projeto

Este projeto é um **servidor HTTP e WebSocket** desenvolvido em Java, sem o uso de frameworks externos, servindo um portfólio pessoal com tema neon pastel. Ele foi criado com o objetivo de:

- **Aprender** os fundamentos do protocolo HTTP e WebSocket na prática
- **Demonstrar** habilidades em desenvolvimento backend com Java
- **Apresentar** projetos desenvolvidos em um portfólio visualmente atrativo
- **Explorar** conceitos como sessões, cookies, multi-threading e comunicação em tempo real

O projeto roda localmente na porta 8080 (HTTP) e 8081 (WebSocket), permitindo que o chat funcione perfeitamente em ambiente de desenvolvimento.

---

## ✨ Funcionalidades

### 🌐 Servidor HTTP
- **Servir arquivos estáticos**: HTML, CSS, JavaScript, imagens e outros
- **API REST**: Endpoints para dados em JSON
- **Sessões e Cookies**: Gerenciamento de estado do usuário
- **Multi-thread**: Atende múltiplas requisições simultaneamente
- **Rotas personalizadas**: `/` (home), `/projetos`, `/contato`, `/ws` (chat)

### 💬 WebSocket (Chat em tempo real)
- **Conexão persistente**: Comunicação bidirecional
- **Comandos especiais**:
  - `/nick [nome]` → Muda seu nome no chat
  - `/ping` → Testa a conexão
  - `/sair` → Sai do chat
- **Broadcast**: Mensagens enviadas para todos os conectados

### 🎨 Tema Neon Pastel
- Cores vibrantes: rosa, azul, ciano, verde, roxo e amarelo
- Efeitos de brilho e glitch
- Partículas animadas no fundo
- Glassmorphism e transições suaves

---

## 🛠️ Tecnologias Utilizadas

### Backend
| Tecnologia | Descrição |
|---|---|
| **Java 17+** | Linguagem principal |
| **Sockets** | Comunicação em rede |
| **NIO/Files** | Manipulação de arquivos |
| **ConcurrentHashMap** | Sincronização de sessões |
| **Base64/SHA-1** | Handshake WebSocket |

### Frontend
| Tecnologia | Descrição |
|---|---|
| **HTML5** | Estrutura das páginas |
| **CSS3** | Estilos neon pastel |
| **JavaScript** | Interatividade e WebSocket |
| **Canvas API** | Partículas de fundo |

---

## 📁 Estrutura do Projeto
ServidorHTTP/
├── ServidorHTTP.java # Servidor HTTP principal
├── ServidorWebSocket.java # Servidor WebSocket separado (porta 8081)
├── public/ # Arquivos estáticos
│ ├── index.html # Página inicial
│ ├── projetos.html # Lista de projetos
│ ├── contato.html # Página de contato
│ ├── websocket.html # Chat em tempo real
│ ├── style.css # Estilos neon pastel
│ └── script.js # Interatividade e partículas
├── README.md # Documentação
└── LICENSE # Licença MIT

text

---

## 🚀 Como Executar Localmente

### Pré-requisitos
- **Java JDK 17+** instalado
- **Git** (opcional, para clonar)

### Passo a Passo

#### 1. Clone o repositório
```bash
git clone https://github.com/MisaAndrejezieski/ServidorHTTP.git
cd ServidorHTTP
2. Compile o servidor HTTP
bash
javac ServidorHTTP.java
3. Compile o servidor WebSocket
bash
javac ServidorWebSocket.java
4. Execute os servidores
bash
# Terminal 1 - Servidor HTTP
java ServidorHTTP

# Terminal 2 - Servidor WebSocket
java ServidorWebSocket
5. Acesse no navegador
text
http://localhost:8080/
📡 Endpoints Disponíveis
Páginas Web
Rota	Descrição
/	Página inicial com apresentação
/projetos	Lista de todos os projetos
/contato	Formulário de contato
/ws	Chat WebSocket
API REST
Rota	Método	Descrição
/api/status	GET	Status do servidor
/api/hello?nome=X	GET	Saudação personalizada
/api/hora	GET	Hora atual em JSON
/api/data	GET	Data atual em JSON
/api/contador	GET	Incrementa contador na sessão
/api/sessao	GET	Dados da sessão atual
/api/websockets	GET	Conexões WebSocket ativas
/api/echo	POST	Ecoa o corpo da requisição
/api/contato	POST	Recebe dados de contato
WebSocket
Rota	Protocolo	Descrição
/ws	WebSocket	Chat em tempo real
📦 Publicação no GitHub Pages
O projeto está configurado para ser publicado no GitHub Pages como um site estático, sem a parte do servidor Java.

Por que o chat não funciona no GitHub Pages?
O GitHub Pages é um serviço de hospedagem de sites estáticos, servindo apenas arquivos HTML, CSS e JavaScript. Ele não consegue executar código Java ou manter conexões WebSocket.

Ambiente	URL	Chat	Servidor Java
Local	http://localhost:8080	✅ Funciona	✅ Rodando
GitHub Pages	https://misaandrejezieski.github.io/	❌ Não funciona	❌ Não disponível
Conclusão: O chat é uma funcionalidade para aprendizado e demonstração local, e o portfólio é a parte pública no GitHub Pages.

Como publicar no GitHub Pages
1. Crie um repositório no GitHub
text
misaandrejezieski.github.io
2. Adicione apenas os arquivos da pasta public
text
/
├── index.html
├── projetos.html
├── contato.html
├── websocket.html (opcional)
├── style.css
└── script.js
3. Faça o push
bash
git add .
git commit -m "Meu portfólio neon"
git push origin main
4. Ative o GitHub Pages
Vá em Settings → Pages

Em "Branch", selecione main e a pasta /root

Clique em Save

5. Site público
text
https://misaandrejezieski.github.io/
🧠 Conceitos Aprendidos
Este projeto foi desenvolvido para explorar e consolidar os seguintes conceitos:

HTTP
Requisições e respostas (GET, POST)

Cabeçalhos (Headers)

Códigos de status (200, 404, 500)

Cookies e sessões

WebSocket
Handshake e upgrade de protocolo

Frames e comunicação bidirecional

Comandos personalizados

Java
Sockets e ServerSocket

Multi-threading e concorrência

Manipulação de arquivos (NIO/Files)

Criptografia (SHA-1, Base64)

Mapas concorrentes (ConcurrentHashMap)

Frontend
Design com tema neon pastel

Animações com CSS e Canvas

Consumo de API REST

Conexão WebSocket via JavaScript

🎯 Próximos Passos
O projeto pode ser expandido com:

□ Banco de dados (SQLite) para persistência
□ Autenticação de usuários
□ Envio de arquivos (upload)
□ Logs de acesso em arquivo
□ Interface administrativa
□ Deploy em nuvem (Render, Railway)
🤝 Contribuição
Contribuições são bem-vindas! Sinta-se à vontade para abrir issues e pull requests.

Faça um fork do projeto

Crie uma branch para sua feature (git checkout -b feature/nova-feature)

Commit suas mudanças (git commit -m 'Adiciona nova feature')

Push para a branch (git push origin feature/nova-feature)

Abra um Pull Request

📝 Licença
Este projeto está sob a licença MIT. Veja o arquivo LICENSE para mais detalhes.

📧 Contato
Misael Andrejezieski

https://img.shields.io/badge/GitHub-MisaAndrejezieski-181717?style=flat-square&logo=github
https://img.shields.io/badge/LinkedIn-Misael%2520Andrejezieski-0A66C2?style=flat-square&logo=linkedin
https://img.shields.io/badge/Instagram-%2540misaelandrejezieski-E4405F?style=flat-square&logo=instagram
https://img.shields.io/badge/Facebook-Misa%2520Misa-1877F2?style=flat-square&logo=facebook
https://img.shields.io/badge/Site-Transformo%2520caf%C3%A9%2520em%2520c%C3%B3digo-000000?style=flat-square

⭐ Se este projeto foi útil para você, não esqueça de dar uma estrela no GitHub!

text

---

## Resumo

| Seção | O que explica |
|---|---|
| **Sobre o Projeto** | Visão geral do servidor e objetivos |
| **Funcionalidades** | HTTP, WebSocket, tema neon |
| **Tecnologias** | Backend e frontend utilizados |
| **Como Executar** | Passo a passo para rodar localmente |
| **Endpoints** | Rotas disponíveis no servidor |
| **GitHub Pages** | Explicação sobre hospedagem estática |
| **Conceitos Aprendidos** | O que você aprendeu com o projeto |
| **Próximos Passos** | Sugestões de evolução |

--- 🚀
