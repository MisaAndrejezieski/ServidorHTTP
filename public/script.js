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

    // Conexões entre partículas próximas
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

// ============ CONTATO ============
function enviarContato(event) {
    event.preventDefault();
    
    const nome = document.getElementById('nome').value;
    const email = document.getElementById('email').value;
    const assunto = document.getElementById('assunto').value;
    const mensagem = document.getElementById('mensagem').value;

    // Simulação de envio
    const btn = event.target.querySelector('.btn');
    const textoOriginal = btn.textContent;
    btn.textContent = '⏳ Enviando...';
    btn.disabled = true;

    // Envia para o servidor (POST /api/contato)
    fetch('/api/contato', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ nome, email, assunto, mensagem })
    })
    .then(r => r.json())
    .then(data => {
        btn.textContent = '✅ Enviado!';
        btn.style.background = '#a6e3a1';
        btn.style.color = '#0f0a12';
        btn.style.boxShadow = '0 0 40px rgba(166, 227, 161, 0.5)';
        
        // Limpa o formulário
        document.querySelector('.contato-form').reset();
        
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