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