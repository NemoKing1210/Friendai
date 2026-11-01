# Friendai

[English](README.md) · [Русский](README.ru.md) · [Українська](README.uk.md) · [Deutsch](README.de.md) · [Français](README.fr.md) · [Español](README.es.md) · **Português (Brasil)** · [简体中文](README.zh-CN.md)

![Minecraft 26.3](https://img.shields.io/badge/Minecraft-26.3-62B47A)
![Fabric](https://img.shields.io/badge/Fabric-Loader-DBB69C)
![Java 25](https://img.shields.io/badge/Java-25-ED8B00)
![MIT](https://img.shields.io/badge/License-MIT-blue)

<img src="src/main/resources/assets/friendai/icon.png" alt="Ícone do Friendai" width="128" align="right">

Um mod Fabric de código aberto que adiciona um companheiro de IA ao Minecraft. Converse com ele no chat ou por voz, e ele vai te seguir, buscar itens, caminhar até lugares e minerar blocos para você.

> "Friendai, mine 10 logs" — o companheiro responde no chat, caminha até as árvores, corta-as, recolhe os itens deixados e dá o relatório.

## Conteúdo

- [O que ele pode fazer](#o-que-ele-pode-fazer)
- [Requisitos](#requisitos)
- [Início rápido](#início-rápido)
- [Configurando um provedor](#configurando-um-provedor)
- [Voz](#voz)
- [Objetivos e planejamento](#objetivos-e-planejamento)
- [Negociação](#negociação)
- [Conhecimento de recursos e busca](#conhecimento-de-recursos-e-busca)
- [Idiomas](#idiomas)
- [Atualizações de configuração](#atualizações-de-configuração)
- [Modo criativo](#modo-criativo)
- [Download e versões](#download-e-versões)
- [Compilação](#compilação)
- [Contribuindo](#contribuindo)

## O que ele pode fazer

| Área | O que o companheiro faz |
|---|---|
| Seguir e ficar | Segue a uma distância de caminhada, fica onde for mandado, caminha até um ponto e volta. |
| Buscar e entregar | Recolhe itens deixados no chão, pega itens de contêineres que já verificou e entrega itens para você. |
| Minerar e construir | Mina uma quantidade contada de um tipo de bloco, derruba uma árvore em pé, posiciona plantas de construção e constrói escadas. |
| Explorar | Olha ao redor, procura um recurso em uma área delimitada e se desloca por água e cavernas. |
| Lutar | Protege você, ataca mobs hostis e recua ou come quando está ferido. |
| Continuar trabalhando | Mantém os chunks ao seu redor carregados, sobrevive a uma reinicialização e retoma sua última tarefa. |
| Lembrar | Guarda memórias restritas ao proprietário: lugares, avistamentos de recursos, objetivos e anotações de negociação. |
| Falar | Responde no chat ou por voz, e pode falar as respostas com uma voz local ou na nuvem. |

## Requisitos

- Minecraft **26.3**
- [Fabric Loader](https://fabricmc.net/) e [Fabric API](https://modrinth.com/mod/fabric-api)
- Java **25**
- Opcional: [Mod Menu](https://modrinth.com/mod/modmenu) e [YACL](https://modrinth.com/mod/yacl) para a tela de configurações dentro do jogo. Sem eles o mod funciona e as configurações são editadas nos arquivos de configuração.

O mod precisa estar instalado tanto no cliente quanto no servidor (o servidor integrado no modo singleplayer conta).

## Início rápido

1. Instale o mod e configure um provedor (veja abaixo).
2. Em um mundo, execute `/friendai summon`. Seu companheiro aparece ao seu lado.
3. Fale com ele:
   - no chat com um prefixo: `@ come here`
   - no chat pelo nome: `Friendai, mine 10 logs` ou `Friendai, chop the tree next to me`
   - por voz: segure **V** (push-to-talk, remapeável em Controles) e fale.
4. `/friendai stop` cancela o que ele está fazendo, `/friendai dismiss` o dispensa, `/friendai status` mostra o provedor e a tarefa atual.

Os mesmos controles estão no **menu de pausa (Esc)**: o ícone do companheiro ao lado do ícone de reportar bug abre uma tela para convocar, dispensar e parar seu companheiro e para ver a tarefa atual e as tarefas recentes. Operadores também recebem o registro do cérebro (mensagens, chamadas de ferramentas, erros) e ferramentas de depuração: o que o companheiro vê, seu prompt atual, uma mensagem manual e uma execução manual de ferramenta.

O que ele pode fazer: seguir você, ficar, ir até um ponto, recolher itens, dar itens para você, olhar ao redor, mostrar o inventário, parar e minerar N blocos de um tipo.

O companheiro mantém os chunks ao seu redor carregados, então continua trabalhando quando você se afasta, e retoma na última posição após uma reinicialização do servidor. `companion.chunkLoading.radius` define quantos chunks ao redor continuam sendo processados (0–4); o padrão 1 mantém uma área 3×3 ativa dentro de uma área carregada de 5×5, e a dimensão em si permanece ativa. Desative isso com `companion.chunkLoading.enabled` em `config/friendai-server.json`.

## Atualizações de configuração

Arquivos de configuração antigos compatíveis são atualizados automaticamente: esquemas de servidor 3–15 para 16, e esquemas de cliente 1–3 para 4. Perfis de provedor, chaves de API, modelos, dispositivos e configurações explícitas são mantidos; novos campos recebem padrões validados. Configurações renomeadas são convertidas explicitamente. Campos de extensão desconhecidos sobrevivem às atualizações e às gravações seguintes, inclusive com regras de roteamento reordenadas. Se várias entradas de array editadas carregarem campos extras e sua identidade se tornar ambígua, a gravação é recusada em vez de adivinhar qual entrada é dona desses campos; edite esse arquivo diretamente com o jogo/servidor parado.

Antes da substituição, o mod valida o resultado e salva os bytes originais exatos ao lado do arquivo como `<filename>.v<old-version>.<unique-id>.bak`. Backups nunca são sobrescritos por outra atualização. As gravações usam substituição atômica; arquivos de configuração são limitados a 8 MiB. Se uma atualização, um backup ou uma substituição falhar, o original permanece intacto e somente leitura, enquanto os padrões são usados na memória. Corrija o problema e reabra a tela de configurações do servidor ou reinicie o jogo/servidor para tentar de novo.

Formatos desconhecidos, sem versão e futuros nunca são adivinhados nem rebaixados. Use a versão correspondente do mod, ou mantenha um backup e substitua o arquivo deliberadamente. Para restaurar um backup de atualização, pare o jogo/servidor primeiro e use uma versão do mod que entenda o esquema desse backup. Os backups contêm as mesmas credenciais do original; proteja-os como você protege a configuração. Registros de proprietário e salvamentos de mundo seguem suas próprias regras de persistência.

## Modo criativo

Abra a tela do companheiro pelo menu de pausa e ative **Criativo** na aba Controle. Ele vem desativado por padrão. O proprietário de um servidor dedicado precisa ser operador para ativá-lo; no singleplayer, o dono do mundo pode ativá-lo mesmo com cheats desativados. O proprietário pode desativá-lo a qualquer momento, e a preferência sobrevive à dispensa, à reconvocação e ao recarregamento do mundo.

No modo criativo o companheiro ignora dano e fome comuns, pode voar atravessando colisões, quebra instantaneamente blocos permitidos sem os drops normais e não consome materiais de construção. As regras de proteção continuam valendo. Use a ferramenta exclusiva do proprietário `create_item` para entregar itens registrados com seus componentes padrão ao companheiro ou a um proprietário próximo; a entrega para na capacidade do inventário de destino e informa qualquer excedente. `fly_to` segue uma rota delimitada por chunks carregados.

A ferramenta exclusiva do proprietário `execute_command` aceita uma lista limitada de comandos de jogo permitidos: teleportar o companheiro, alterar ou consultar hora/clima, dar itens ao proprietário, aplicar ou remover efeitos no companheiro e encantar o item na mão do proprietário. O proprietário precisa estar online e manter a permissão normal de comando quando um comando é executado. A ativação no singleplayer com cheats desativados não concede permissão de comando. A ferramenta rejeita `execute`, `function`, `schedule`, outras raízes de comando e alvos que não sejam o proprietário. Desativar o modo criativo cancela o trabalho criativo na fila e restaura as regras de movimento, fome e dano do modo sobrevivência; itens já criados permanecem nos inventários.

## Configurando um provedor

Abra **Mod Menu → Friendai → Provedor**, ou edite `config/friendai-server.json`. Em um servidor dedicado, edite o arquivo e execute `/friendai reload`.

| ID do preset | Serviço | Observações |
|---|---|---|
| `openai` | OpenAI | API Responses |
| `anthropic` | Anthropic | |
| `gemini` | Google Gemini | |
| `openrouter` | OpenRouter | uma chave para muitos modelos |
| `deepseek` | DeepSeek | |
| `groq` | Groq | modelos hospedados rápidos |
| `mistral` | Mistral | |
| `xai` | xAI | |
| `together` | Together AI | |
| `fireworks` | Fireworks AI | |
| `cerebras` | Cerebras | |
| `github_models` | GitHub Models | token do GitHub |
| `openai_compat` | Qualquer endpoint compatível com OpenAI (LM Studio, vLLM, …) | `baseUrl` é obrigatório |
| `ollama` | Ollama (API nativa) | sem chave de API |
| `opencode_zen` | OpenCode Zen | roteia pelo nome do modelo |
| `command_code` | Command Code | roteia pelo nome do modelo |

Mantenha as chaves de API fora do arquivo referenciando uma variável de ambiente:

```json
"apiKey": "env:ANTHROPIC_API_KEY"
```

A chave nunca é gravada em logs ou no chat. "Testar conexão" na tela de configurações verifica se o provedor responde e se o modelo suporta chamada de ferramentas.

## Voz

Segure **V** para falar. Abra **Configurações → Voz → Reconhecimento** e escolha onde a fala é convertida em texto:

- **Local (Whisper, offline)** — o [whisper.cpp](https://github.com/ggerganov/whisper.cpp) executa um dos modelos `tiny`, `base` ou `small` na sua CPU, de graça. Pressione **Baixar modelo**; os modelos ficam em `<game dir>/friendai/models/`.
- **OpenAI (pago)** — uma API de transcrição compatível com OpenAI (`gpt-transcribe` por padrão). A URL base pode apontar para qualquer servidor compatível com OpenAI, inclusive um local, que não precisa de chave.
- **Groq (pago)** — a mesma API através da Groq (`whisper-large-v3` por padrão).
- **Yandex SpeechKit (pago)** — uma chave de API, o id da pasta na nuvem e uma tag de idioma concreta.

A transcrição é enviada ao servidor como mensagem de chat. O reconhecimento local mantém o áudio na sua máquina; um provedor na nuvem envia a gravação, e somente enquanto esse provedor estiver selecionado.

Tanto o chat digitado quanto o push-to-talk só alcançam um companheiro na mesma dimensão dentro do raio de audição do servidor (32 blocos por padrão, `chat.hearingRadius`). Mais longe, o companheiro não te ouve nem responde, e diz isso em vez de ficar em silêncio.

### Saída de voz

O companheiro também pode falar suas respostas. A síntese vem desativada por padrão; ative **Falar respostas** em **Configurações → Voz → Síntese** e escolha um provedor:

- **Local (grátis, offline)** — o [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) executa uma voz Piper na sua CPU. Cinco vozes são oferecidas: Irina e Dmitri (russo), Lessac e LibriTTS-R (inglês) e Alba (inglês britânico). Escolha uma e pressione **Baixar voz**; as vozes ficam em `<game dir>/friendai/models/tts/` (cerca de 67–82 MB cada). Sistemas em russo recebem Irina, todos os outros recebem Lessac. Cada voz traz sua própria licença, exibida abaixo da lista: os arquivos dos modelos são em grande parte MIT, a voz LibriTTS-R é CC BY 4.0, e os termos dos dados de treinamento variam.
- **OpenAI (pago)** — a API de fala da OpenAI com uma chave, uma voz e uma velocidade. A URL base pode apontar para qualquer servidor compatível com OpenAI, inclusive um local, que não precisa de chave.
- **Google Cloud Text-to-Speech (pago)** — uma chave, uma voz, um idioma e uma velocidade; o áudio chega como LINEAR16.
- **ElevenLabs (pago)** — a API de text-to-speech da ElevenLabs; uma chave, um id de voz e uma velocidade.
- **Yandex SpeechKit (pago)** — uma chave de API, o id da pasta na nuvem, uma voz, uma tag de idioma, uma velocidade e saída PCM de 16 kHz.

Somente o texto da resposta sai da sua máquina, e apenas para síntese na nuvem; vozes locais nunca o enviam a lugar nenhum. Para reconhecimento na nuvem, somente a gravação sai. As chaves ficam no lado do cliente nas duas direções: vivem na configuração do cliente, podem referenciar uma variável de ambiente (`env:OPENAI_API_KEY`), viajam apenas em um cabeçalho HTTP e nunca aparecem em logs, no chat ou em mensagens de erro. O Friendai nunca repete automaticamente uma solicitação paga.

O volume, o dispositivo de saída, o comprimento máximo de uma frase falada e falar respostas dirigidas a outros jogadores ficam na mesma tela. **A voz vem do companheiro** vem ativado por padrão: cada resposta é reproduzida a partir da posição do companheiro, desloca-se para a esquerda ou para a direita conforme você gira e desaparece com a distância até ficar silenciosa no raio de audição. Desative para ouvir as respostas centralizadas e com volume total. **Testar voz** fala uma frase de exemplo. Pressionar push-to-talk ou a tecla **Parar de falar** (sem bind por padrão; defina em Controles) interrompe uma resposta.

## Conhecimento de recursos e busca

O servidor mantém um atlas de recursos separado para cada proprietário em `<world>/friendai/knowledge/<owner-uuid>.json`. Ele registra locais de blocos somente depois que o companheiro tem uma linha de visão limpa ou confirma sua própria quebra de mineração bem-sucedida. As contagens são observações históricas; um local lembrado pode estar coberto ou esgotado, então `known_resources` informa idade e incerteza e nunca promete que a contagem antiga ainda está disponível. As consultas usam apenas evidências salvas e não carregam chunks.

O conteúdo de contêineres é guardado em cache somente depois que o companheiro abre um contêiner durante uma verificação ou transferência autorizada. `obtain_item` pode usar um snapshot recente na mesma dimensão a até 16 blocos para escolher um baú como fonte, e então abre esse contêiner e verifica o conteúdo ao vivo antes de pegar itens. Um contêiner fechado nunca é inspecionado durante a descoberta.

`search_resource` permite que um jogador confiável solicite uma busca delimitada. O companheiro escolhe pontos de vista determinísticos na superfície e relata um recurso apenas após visão direta. Ele não infere uma veia de minério oculta a partir do terreno. `strategy=mine` pode usar a rota de mineração protegida existente quando uma picareta durável adequada e 16 tochas estiverem disponíveis. `strategy=cave` exige um corredor de visão a céu aberto, desobstruído e observado recentemente, descendo por uma entrada até uma parede interna visível; sem essa evidência ele se recusa a partir. A evidência da entrada é transitória, restrita ao proprietário, expira após cinco minutos e não revela recursos por si só. Solicitações repetidas para o mesmo proprietário, dimensão e objetivo reutilizam o orçamento salvo. O trabalho de busca ativo é interrompido no logout do proprietário ou no recarregamento do mundo em vez de ser repetido automaticamente.

A política de busca fica em `tasks.resources` em `config/friendai-server.json`: buscas locais têm padrão de 64 blocos e 2.400 ticks. Expedições vêm desativadas por padrão e exigem tanto `allowExpeditions` quanto o carregamento de chunks do companheiro. Elas mantêm no máximo o ticket de chunk do próximo trecho; com o carregamento de chunks desativado, o companheiro para na fronteira carregada atual. O atlas, a cobertura e as trilhas executadas são restritos ao proprietário e delimitados.

## Objetivos e planejamento

Para um objetivo com várias partes ("construir uma casa e trazer ferro") o companheiro registra um objetivo com o critério de quando saberá que terminou, e o objetivo sobrevive a planos, falhas, morte e reinicializações. Ele continua trabalhando nele pela fila de tarefas, lê os fatos de cada tarefa concluída (itens ganhos e perdidos, o que ainda não foi atendido) e, quando uma etapa falha, recebe um diagnóstico e um remédio que ainda não tentou. Depois de algumas reparações malsucedidas, ele para e pergunta a você o que está bloqueando.

Uma solicitação complexa, ou um plano que falhou com replanejamentos restantes, é primeiro elaborada por uma chamada de planejador separada que devolve um plano validado; o plano é enfileirado com seu nível de acesso e nunca pode usar uma ferramenta que você não pode usar. As configurações ficam em `agent` em `config/friendai-server.json`:

| Configuração | Padrão | Significado |
|---|---|---|
| `toolRouting` | `true` | Envia ao modelo apenas as famílias de ferramentas de que uma solicitação precisa; ele pode desbloquear mais com `request_tools`. |
| `autonomy` | `assistive` | `passive` nunca começa por conta própria; `assistive` incentiva um companheiro ocioso em direção a um objetivo inacabado; `autonomous` também pode perguntar o que fazer em seguida (ainda assim ele nunca age sem ser solicitado). |
| `maxReplans` | `3` | Replanejamentos por objetivo antes de o companheiro pedir ajuda (0–10). |
| `goalTickSec` | `30` | Segundos entre incentivos em direção a um objetivo inacabado (10–600). |
| `promptBudget` | `24000` | Orçamento de caracteres do prompt de sistema; `0` significa ilimitado. |
| `planner` | nenhum | Nome de um perfil a usar para planejamento; vazio usa o perfil ativo. |
| `plannerTimeoutSec` | `60` | Quanto tempo uma chamada de planejador pode levar (10–300). |

## Negociação

O proprietário pode pedir ao companheiro que encontre aldeões carregados, comerciantes ambulantes e piglins adultos, inspecione ofertas vanilla exatas, consulte preços memorizados e visualize limites de gasto explícitos. As notas de mercado são salvas por proprietário em `<world>/friendai/trading/`; cotações e locais expiram de forma independente, e cada compra reverifica a oferta ao vivo do comerciante. Os pedidos usam a reputação e o inventário do próprio companheiro e nunca reabastecem o estoque nem mudam profissões à força.

Pedidos de comerciante exigem limites e reservas por item. Novas tentativas na fila compartilham o escopo do turno original do proprietário e não podem aumentar seus limites; compras pendentes não são repetidas após uma reinicialização. `obtain_item` considera um comerciante apenas quando esse mesmo turno do proprietário já forneceu um orçamento de troca explícito e uma impressão digital de pagamento. Ele só planeja saídas neutras em componentes; trocas exatas por componente usam `trade_item`.

A ferramenta `barter` usa um piglin adulto real e a interação vanilla com ouro. Suas saídas são aleatórias, então o companheiro relata apenas os drops atribuídos a essa tentativa e para em vez de pagar de novo se um lingote aceito não tiver resultado observado. Ele usa por padrão no máximo oito lingotes de ouro por pedido. Os controles de negociação são privados do proprietário na aba Comerciantes. Os padrões do servidor ficam em `tasks.trading` em `config/friendai-server.json`; `enabled` pode desativar todas as ferramentas e ações de interface de negociação.

## Idiomas

A interface está disponível em inglês, russo, alemão (`de_de`), francês (`fr_fr`), espanhol (`es_es`), português do Brasil (`pt_br`), chinês simplificado (`zh_cn`) e ucraniano (`uk_ua`). O companheiro responde no idioma em que você escreve, ou em um idioma fixo se você definir um. Vozes Piper locais existem apenas para russo e inglês; uma interface traduzida não adiciona uma voz local, então síntese na nuvem ou fala em inglês/russo ainda é necessária para outros idiomas. Para adicionar um idioma, veja [CONTRIBUTING.md](CONTRIBUTING.md).

| Idioma | Código | Interface | Voz local |
|---|---|---|---|
| Inglês | `en_us` | sim | sim |
| Russo | `ru_ru` | sim | sim |
| Alemão | `de_de` | sim | não |
| Francês | `fr_fr` | sim | não |
| Espanhol | `es_es` | sim | não |
| Português brasileiro | `pt_br` | sim | não |
| Chinês simplificado | `zh_cn` | sim | não |
| Ucraniano | `uk_ua` | sim | não |

## Download e versões

As versões são publicadas na [página de Releases](https://github.com/NemoKing1210/Friendai/releases). Cada uma traz
exatamente um jar do mod, `friendai-<version>.jar`, e `friendai-<version>.jar.sha256`; baixe os dois, verifique o checksum
(`sha256sum -c friendai-<version>.jar.sha256`, ou `shasum -a 256 -c` no macOS), e coloque o jar em `mods/` no cliente
e no servidor. As notas da versão são a seção do `CHANGELOG.md` referente a essa versão.

Apenas o mantenedor cria uma tag. Enviar `vX.Y.Z` inicia o fluxo de trabalho de release, que recusa uma tag que não corresponde a
`mod_version`, um commit que não está em `main`, ou um commit cuja execução de CI não está verde; ele então executa o build completo e
publica o jar verificado. Veja `CONTRIBUTING.md` para o ensaio e as verificações locais.

## Contribuindo

Leia [CONTRIBUTING.md](CONTRIBUTING.md) para as regras de branch, commit, teste e tradução.

## Compilação

```bash
./gradlew build        # compile, unit tests and in-game tests
./gradlew test         # unit tests only
./gradlew runGameTest  # Fabric GameTests only
./gradlew runClient    # start a development client
```

## Licença

[MIT](LICENSE)
