// ============ PROJETOS ============
const projetos = [
    {
        id: 1,
        icon: "🍸",
        titulo: "Cotton Candy Kabukicho",
        descricao: "Landing page para um clube privê temático de Tóquio. Design neon com drinks exclusivos e galeria.",
        tags: ["HTML/CSS", "Neon", "Landing Page"],
        link: "https://misaandrejezieski.github.io/Cotton-Candy-Kabukicho/",
        cor: "#ff8fab"
    },
    {
        id: 2,
        icon: "⬇️",
        titulo: "Site BaixarYou",
        descricao: "Plataforma para download de conteúdos. Interface limpa e funcional.",
        tags: ["HTML/CSS", "Download", "UI"],
        link: "https://misaandrejezieski.github.io/Site-BaixarYou/",
        cor: "#89b4fa"
    },
    {
        id: 3,
        icon: "🍜",
        titulo: "NekoLamen",
        descricao: "Site para delivery de yakisoba artesanal. Cardápio completo com preços e opções de retirada.",
        tags: ["HTML/CSS", "Delivery", "Cardápio"],
        link: "https://misaandrejezieski.github.io/NekoLamen/",
        cor: "#a6e3a1"
    },
    {
        id: 4,
        icon: "▶️",
        titulo: "NeonOn",
        descricao: "Player de vídeos e imagens com interface neon. Arraste e solte arquivos para reprodução.",
        tags: ["JS", "Player", "Neon"],
        link: "https://misaandrejezieski.github.io/NeonOn/",
        cor: "#cba6f7"
    },
    {
        id: 5,
        icon: "🔧",
        titulo: "Auto PES V2",
        descricao: "Projeto de automação de processos. (Atualmente em desenvolvimento)",
        tags: ["Vercel", "Em breve"],
        link: "https://auto-pes-v2-d3jlomtlc-misael-andrejezieskis-projects.vercel.app/",
        cor: "#f9e2af"
    },
    {
        id: 6,
        icon: "🎰",
        titulo: "Slot Madruga",
        descricao: "Slot machine com tema anime. Sistema de créditos e giros.",
        tags: ["JS", "Jogo", "Anime"],
        link: "https://misaandrejezieski.github.io/SlotMadruga/",
        cor: "#ff8fab"
    },
    {
        id: 7,
        icon: "✨",
        titulo: "Site Bonito",
        descricao: "Site com efeitos especiais e design clean. Exploração de estilos visuais.",
        tags: ["HTML/CSS", "Design", "Efeitos"],
        link: "https://misaandrejezieski.github.io/Site-Bonito/",
        cor: "#89b4fa"
    },
    {
        id: 8,
        icon: "🧘",
        titulo: "TRIBB US Carambei",
        descricao: "Comunidade focada em meditação e bem-estar. Respiração consciente e harmonia.",
        tags: ["HTML/CSS", "Meditação", "Bem-estar"],
        link: "https://misaandrejezieski.github.io/TRIBB-US-Carambei/",
        cor: "#a6e3a1"
    },
    {
        id: 9,
        icon: "⚽",
        titulo: "Copa 2026",
        descricao: "Álbum de figurinhas interativo para a Copa do Mundo. Colecione os times!",
        tags: ["HTML/CSS", "Album", "Futebol"],
        link: "https://misaandrejezieski.github.io/Copa-2026/",
        cor: "#f9e2af"
    },
    {
        id: 10,
        icon: "🍮",
        titulo: "PudimFlow",
        descricao: "Projeto em desenvolvimento na Vercel. (Em breve)",
        tags: ["Vercel", "Em breve"],
        link: "https://pudimflow-o5e2dnl0n-misael-andrejezieskis-projects.vercel.app/",
        cor: "#cba6f7"
    },
    {
        id: 11,
        icon: "🔥",
        titulo: "FileForge Converter",
        descricao: "Conversor de arquivos 100% no navegador. Arraste e solte arquivos para converter entre diferentes formatos.",
        tags: ["HTML/CSS", "JavaScript", "File API"],
        link: "https://misaandrejezieski.github.io/fileforge-converter/",
        cor: "#ff8fab"
    }
];

// ============ PROJETOS EM DESTAQUE (para a Home) ============
const projetosDestaque = [0, 2, 3, 8, 10];

// ============ FUNÇÕES PARA CARREGAR ============
function carregarProjetos() {
    const container = document.getElementById('projetos-container');
    if (!container) {
        console.log('⚠️ Container de projetos não encontrado');
        return;
    }

    console.log('✅ Carregando ' + projetos.length + ' projetos...');
    container.innerHTML = '';

    projetos.forEach(projeto => {
        const card = document.createElement('div');
        card.className = 'projeto-card neon-card';
        card.style.borderColor = projeto.cor;

        card.innerHTML = `
            <div class="projeto-icon">${projeto.icon}</div>
            <h3 style="color: ${projeto.cor};">${projeto.titulo}</h3>
            <p>${projeto.descricao}</p>
            <div class="projeto-tags">
                ${projeto.tags.map(tag => `<span>${tag}</span>`).join('')}
            </div>
            <a href="${projeto.link}" target="_blank" class="projeto-link">🌐 Ver projeto →</a>
        `;

        container.appendChild(card);
    });

    const total = document.getElementById('total-projetos');
    if (total) {
        total.textContent = projetos.length;
        console.log('✅ Total de projetos: ' + projetos.length);
    }
}

function carregarDestaques() {
    const container = document.getElementById('destaque-container');
    if (!container) {
        console.log('⚠️ Container de destaques não encontrado');
        return;
    }

    console.log('✅ Carregando destaques...');
    container.innerHTML = '';

    projetosDestaque.forEach(index => {
        const projeto = projetos[index];
        if (!projeto) return;

        const card = document.createElement('a');
        card.href = projeto.link;
        card.target = '_blank';
        card.className = 'destaque-card neon-card';

        card.innerHTML = `
            <span class="destaque-icon">${projeto.icon}</span>
            <h3>${projeto.titulo}</h3>
            <p>${projeto.descricao.split('.')[0]}</p>
        `;

        container.appendChild(card);
    });
    console.log('✅ Destaques carregados: ' + projetosDestaque.length);
}

// ============ INICIALIZAÇÃO AUTOMÁTICA ============
// Carrega assim que o DOM estiver pronto
if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', function() {
        console.log('🚀 DOM carregado, iniciando projetos...');
        carregarProjetos();
        carregarDestaques();
    });
} else {
    // Se o DOM já estiver carregado, executa imediatamente
    console.log('🚀 DOM já carregado, iniciando projetos...');
    carregarProjetos();
    carregarDestaques();
}

console.log('📦 projetos.js carregado com ' + projetos.length + ' projetos');