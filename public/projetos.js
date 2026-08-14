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
// Índices dos projetos que aparecerão na página inicial
const projetosDestaque = [0, 2, 3, 8, 10]; // Cotton, NekoLamen, NeonOn, Copa 2026, FileForge