# Friendai

[English](README.md) · [Русский](README.ru.md) · [Українська](README.uk.md) · [Deutsch](README.de.md) · **Français** · [Español](README.es.md) · [Português (Brasil)](README.pt-BR.md) · [简体中文](README.zh-CN.md)

![Minecraft 26.3](https://img.shields.io/badge/Minecraft-26.3-62B47A)
![Fabric](https://img.shields.io/badge/Fabric-Loader-DBB69C)
![Java 25](https://img.shields.io/badge/Java-25-ED8B00)
![MIT](https://img.shields.io/badge/License-MIT-blue)

<img src="src/main/resources/assets/friendai/icon.png" alt="Icône de Friendai" width="128" align="right">

Un mod Fabric open source qui ajoute un compagnon IA à Minecraft. Parlez-lui dans le chat ou à la voix : il vous suivra, ira chercher des objets, se rendra à des endroits et minera des blocs pour vous.

> « Friendai, mine 10 logs » — le compagnon répond dans le chat, se rend aux arbres, les abat, ramasse les objets tombés et vous fait son rapport.

## Sommaire

- [Ce qu'il peut faire](#ce-quil-peut-faire)
- [Prérequis](#prérequis)
- [Démarrage rapide](#démarrage-rapide)
- [Configurer un fournisseur](#configurer-un-fournisseur)
- [Voix](#voix)
- [Objectifs et planification](#objectifs-et-planification)
- [Commerce](#commerce)
- [Connaissance des ressources et recherche](#connaissance-des-ressources-et-recherche)
- [Langues](#langues)
- [Mises à niveau de la configuration](#mises-à-niveau-de-la-configuration)
- [Mode créatif](#mode-créatif)
- [Téléchargement et versions](#téléchargement-et-versions)
- [Compilation](#compilation)
- [Contribution](#contribution)

## Ce qu'il peut faire

| Domaine | Ce que fait le compagnon |
|---|---|
| Suivre et rester | Suit à distance de marche, reste là où on le lui dit, se rend à un point et revient. |
| Chercher et livrer | Ramasse les objets au sol, prend des objets dans les conteneurs qu'il a vérifiés et vous les remet. |
| Miner et construire | Mine un type de bloc en quantité donnée, abat un arbre debout, place des plans et construit des escaliers. |
| Explorer | Regarde autour de lui, cherche une ressource dans une zone délimitée et se déplace dans l'eau et les grottes. |
| Combattre | Vous protège, cible les créatures hostiles et bat en retraite ou mange lorsqu'il est blessé. |
| Continuer à travailler | Charge les chunks autour de lui, survit à un redémarrage et reprend sa dernière tâche. |
| Se souvenir | Conserve des souvenirs propres au propriétaire, des lieux, des signalements de ressources, des objectifs et des notes de commerce. |
| Parler | Répond dans le chat ou à la voix, et peut prononcer ses réponses avec une voix locale ou dans le cloud. |

## Prérequis

- Minecraft **26.3**
- [Fabric Loader](https://fabricmc.net/) et [Fabric API](https://modrinth.com/mod/fabric-api)
- Java **25**
- Facultatif : [Mod Menu](https://modrinth.com/mod/modmenu) et [YACL](https://modrinth.com/mod/yacl) pour l'écran de réglages en jeu. Sans eux, le mod fonctionne et les réglages s'éditent dans les fichiers de configuration.

Le mod doit être installé à la fois sur le client et sur le serveur (le serveur intégré en solo compte).

## Démarrage rapide

1. Installez le mod et configurez un fournisseur (voir ci-dessous).
2. Dans un monde, exécutez `/friendai summon`. Votre compagnon apparaît à côté de vous.
3. Parlez-lui :
   - dans le chat avec un préfixe : `@ come here`
   - dans le chat par son nom : `Friendai, mine 10 logs` ou `Friendai, chop the tree next to me`
   - à la voix : maintenez **V** (appui pour parler, réassignable dans les Commandes) et parlez.
4. `/friendai stop` annule ce qu'il fait, `/friendai dismiss` le renvoie, `/friendai status` affiche le fournisseur et la tâche en cours.

Les mêmes commandes se trouvent dans le **menu pause (Échap)** : l'icône du compagnon à côté de l'icône de signalement de bug ouvre un écran pour invoquer, renvoyer et arrêter votre compagnon, et pour voir sa tâche en cours et ses tâches récentes. Les opérateurs disposent en plus du journal du cerveau (messages, appels d'outils, erreurs) et d'outils de débogage : ce que voit le compagnon, son prompt actuel, un message manuel et une exécution manuelle d'outil.

Ce qu'il sait faire : vous suivre, rester sur place, se rendre à un point, ramasser des objets, vous donner des objets, regarder autour de lui, afficher son inventaire, s'arrêter et miner N blocs d'un type donné.

Le compagnon garde les chunks qui l'entourent chargés : il continue donc de travailler quand vous vous éloignez, et il reprend à sa dernière position après un redémarrage du serveur. `companion.chunkLoading.radius` définit combien de chunks autour de lui continuent de tourner (0–4) ; la valeur par défaut de 1 maintient une zone 3×3 active à l'intérieur d'une zone chargée de 5×5, et la dimension elle-même reste active. Désactivez cela avec `companion.chunkLoading.enabled` dans `config/friendai-server.json`.

## Mises à niveau de la configuration

Les anciens fichiers de configuration pris en charge sont mis à niveau automatiquement : schémas serveur 3–15 vers 16, et schémas client 1–3 vers 4. Les profils de fournisseur, clés d'API, modèles, appareils et réglages explicites sont conservés ; les nouveaux champs reçoivent des valeurs par défaut validées. Les réglages renommés sont convertis explicitement. Les champs d'extension inconnus survivent aux mises à niveau et aux sauvegardes suivantes, y compris lorsque les règles de routage sont réordonnées. Si plusieurs entrées de tableau modifiées portent des champs supplémentaires et que leur identité devient ambiguë, la sauvegarde est refusée plutôt que de deviner à quelle entrée appartiennent ces champs ; modifiez ce fichier directement, jeu ou serveur arrêté.

Avant remplacement, le mod valide le résultat et enregistre les octets originaux exacts à côté du fichier sous le nom `<filename>.v<old-version>.<unique-id>.bak`. Les sauvegardes ne sont jamais écrasées par une autre mise à niveau. Les écritures utilisent un remplacement atomique ; les fichiers de configuration sont limités à 8 MiB. Si une mise à niveau, une sauvegarde ou un remplacement échoue, l'original reste intact et en lecture seule, tandis que les valeurs par défaut sont utilisées en mémoire. Corrigez le problème, puis rouvrez l'écran de réglages du serveur ou redémarrez le jeu/serveur pour réessayer.

Les formats inconnus, non versionnés et futurs ne sont jamais devinés ni rétrogradés. Utilisez la version du mod correspondante, ou conservez une sauvegarde et remplacez délibérément le fichier. Pour restaurer une sauvegarde de mise à niveau, arrêtez d'abord le jeu/serveur et utilisez une version du mod qui comprend le schéma de cette sauvegarde. Les sauvegardes contiennent les mêmes identifiants que l'original ; protégez-les comme vous protégez la configuration. Les enregistrements de propriétaire et les sauvegardes de monde suivent leurs propres règles de persistance.

## Mode créatif

Ouvrez l'écran du compagnon depuis le menu pause et activez **Créatif** dans l'onglet Contrôle. Il est désactivé par défaut. Sur un serveur dédié, le propriétaire doit être opérateur pour l'activer ; en solo, le propriétaire du monde peut l'activer même lorsque les triches sont désactivées. Le propriétaire peut le désactiver à tout moment, et la préférence survit au renvoi, à une nouvelle invocation et aux rechargements du monde.

En mode créatif, le compagnon ignore les dégâts ordinaires et la faim, peut voler en contournant les collisions, casse instantanément les blocs autorisés sans butin normal et ne consomme pas de matériaux de construction. Les règles de protection s'appliquent toujours. Utilisez l'outil `create_item`, réservé au propriétaire, pour livrer des objets enregistrés avec leurs composants par défaut au compagnon ou à un propriétaire proche ; la livraison s'arrête à la capacité de l'inventaire de destination et signale tout reste. `fly_to` suit un itinéraire délimité à travers les chunks chargés.

L'outil `execute_command`, réservé au propriétaire, accepte une liste blanche de gameplay limitée : téléporter le compagnon, modifier ou consulter l'heure/la météo, donner des objets au propriétaire, appliquer ou retirer des effets au compagnon, et enchanter l'objet tenu par le propriétaire. Le propriétaire doit être en ligne et conserver la permission de commande normale au moment où une commande s'exécute. Une activation en solo avec les triches désactivées n'accorde pas la permission de commande. L'outil refuse `execute`, `function`, `schedule`, les autres racines de commande et les cibles de joueur autres que le propriétaire. Désactiver le mode créatif annule le travail créatif en file d'attente et rétablit les règles de déplacement, de faim et de dégâts de survie ; les objets déjà créés restent dans les inventaires.

## Configurer un fournisseur

Ouvrez **Mod Menu → Friendai → Fournisseur**, ou modifiez `config/friendai-server.json`. Sur un serveur dédié, modifiez le fichier et exécutez `/friendai reload`.

| Id de préréglage | Service | Remarques |
|---|---|---|
| `openai` | OpenAI | API Responses |
| `anthropic` | Anthropic | |
| `gemini` | Google Gemini | |
| `openrouter` | OpenRouter | une clé pour de nombreux modèles |
| `deepseek` | DeepSeek | |
| `groq` | Groq | modèles hébergés rapides |
| `mistral` | Mistral | |
| `xai` | xAI | |
| `together` | Together AI | |
| `fireworks` | Fireworks AI | |
| `cerebras` | Cerebras | |
| `github_models` | GitHub Models | jeton GitHub |
| `openai_compat` | Tout point de terminaison compatible OpenAI (LM Studio, vLLM, …) | `baseUrl` est requis |
| `ollama` | Ollama (API native) | aucune clé d'API |
| `opencode_zen` | OpenCode Zen | routage par nom de modèle |
| `command_code` | Command Code | routage par nom de modèle |

Gardez les clés d'API hors du fichier en référençant une variable d'environnement :

```json
"apiKey": "env:ANTHROPIC_API_KEY"
```

La clé n'est jamais écrite dans les journaux ni dans le chat. « Tester la connexion » dans l'écran de réglages vérifie que le fournisseur répond et que le modèle prend en charge l'appel d'outils.

## Voix

Maintenez **V** pour parler. Ouvrez **Réglages → Voix → Reconnaissance** et choisissez où la parole est convertie en texte :

- **Local (Whisper, hors ligne)** — [whisper.cpp](https://github.com/ggerganov/whisper.cpp) fait tourner l'un des modèles `tiny`, `base` ou `small` sur votre processeur, gratuitement. Appuyez sur **Télécharger le modèle** ; les modèles sont stockés dans `<game dir>/friendai/models/`.
- **OpenAI (payant)** — une API de transcription compatible OpenAI (`gpt-transcribe` par défaut). L'URL de base peut pointer vers n'importe quel serveur compatible OpenAI, y compris un serveur local, qui ne nécessite aucune clé.
- **Groq (payant)** — la même API via Groq (`whisper-large-v3` par défaut).
- **Google Cloud Speech-to-Text (payant)** — une clé et une langue ; l’enregistrement est envoyé en LINEAR16.
- **Deepgram (payant)** — une clé, un modèle et une langue facultative.
- **Yandex SpeechKit (payant)** — une clé d'API, l'identifiant du dossier cloud et une balise de langue précise.

La transcription est envoyée au serveur sous forme de message de chat. La reconnaissance locale garde l'audio sur votre machine ; un fournisseur cloud téléverse l'enregistrement, et uniquement tant que ce fournisseur est sélectionné.

Le chat écrit comme l'appui pour parler n'atteignent un compagnon que dans la même dimension et dans le rayon d'audition du serveur (32 blocs par défaut, `chat.hearingRadius`). Plus loin, le compagnon ne vous entend pas et ne répond pas ; il le dit au lieu de rester silencieux.

### Sortie vocale

Le compagnon peut aussi prononcer ses réponses. La synthèse est désactivée par défaut ; activez **Prononcer les réponses** dans **Réglages → Voix → Synthèse** et choisissez un fournisseur :

- **Local (gratuit, hors ligne)** — [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) fait tourner une voix Piper sur votre processeur. Cinq voix sont proposées : Irina et Dmitri (russe), Lessac et LibriTTS-R (anglais) et Alba (anglais britannique). Choisissez-en une et appuyez sur **Télécharger la voix** ; les voix sont stockées dans `<game dir>/friendai/models/tts/` (environ 67–82 Mo chacune). Les systèmes russes reçoivent Irina, tous les autres Lessac. Chaque voix porte sa propre licence, affichée sous la liste déroulante : les fichiers de modèle sont majoritairement sous MIT, la voix LibriTTS-R est sous CC BY 4.0, et les conditions des données d'entraînement varient.
- **OpenAI (payant)** — l'API vocale d'OpenAI avec une clé, une voix et une vitesse. L'URL de base peut pointer vers n'importe quel serveur compatible OpenAI, y compris un serveur local, qui ne nécessite aucune clé.
- **Google Cloud Text-to-Speech (payant)** — une clé, une voix, une langue et une vitesse ; l’audio arrive en LINEAR16.
- **ElevenLabs (payant)** — l'API de synthèse vocale d'ElevenLabs ; une clé, un identifiant de voix et une vitesse.
- **Yandex SpeechKit (payant)** — une clé d'API, l'identifiant du dossier cloud, une voix, une balise de langue, une vitesse et une sortie PCM 16 kHz.

Seul le texte de la réponse quitte votre machine, et uniquement pour la synthèse cloud ; les voix locales ne l'envoient jamais nulle part. Pour la reconnaissance cloud, seul l'enregistrement quitte votre machine. Les clés sont côté client dans les deux sens : elles résident dans la configuration du client, peuvent référencer une variable d'environnement (`env:OPENAI_API_KEY`), ne circulent que dans un en-tête HTTP et n'apparaissent jamais dans les journaux, le chat ou les messages d'erreur. Friendai ne réessaie jamais automatiquement une requête payante.

Le volume, le périphérique de sortie, la longueur maximale d'une phrase prononcée et la lecture des réponses adressées aux autres joueurs se trouvent sur le même écran. **La voix vient du compagnon** est activé par défaut : chaque réponse est diffusée depuis la position du compagnon, se déplace à gauche ou à droite quand vous tournez, et s'estompe avec la distance jusqu'au silence au rayon d'audition. Désactivez-le pour entendre les réponses centrées à plein volume. **Tester la voix** prononce une phrase d'exemple. Appuyer sur l'appui pour parler ou sur la touche **Arrêter la parole** (non assignée par défaut ; à définir dans les Commandes) coupe une réponse.

## Connaissance des ressources et recherche

Le serveur conserve un atlas de ressources distinct pour chaque propriétaire dans `<world>/friendai/knowledge/<owner-uuid>.json`. Il n'enregistre l'emplacement des blocs qu'après que le compagnon a eu une ligne de vue dégagée ou a confirmé sa propre casse de bloc réussie. Les comptes sont des observations historiques ; un site mémorisé peut être recouvert ou épuisé, donc `known_resources` indique l'ancienneté et l'incertitude et ne promet jamais que l'ancien compte est toujours disponible. Les requêtes s'appuient uniquement sur les preuves enregistrées et ne chargent pas de chunks.

Le contenu des conteneurs n'est mis en cache qu'après que le compagnon a ouvert un conteneur lors d'une vérification ou d'un transfert autorisé. `obtain_item` peut utiliser un instantané récent de la même dimension à moins de 16 blocs pour choisir un coffre source, puis il ouvre ce conteneur et vérifie le contenu réel avant de prendre des objets. Un conteneur fermé n'est jamais inspecté pendant la découverte.

`search_resource` permet à un joueur de confiance de demander une recherche délimitée. Le compagnon choisit des points de vue de surface déterministes et ne signale une ressource qu'après l'avoir vue directement. Il ne déduit pas un filon caché du terrain. `strategy=mine` peut utiliser l'itinéraire de minage protégé existant lorsqu'une pioche durable appropriée et 16 torches sont disponibles. `strategy=cave` exige un couloir de visée à ciel ouvert, récemment observé et dégagé, descendant par une entrée jusqu'à une paroi intérieure visible ; sans cette preuve, il refuse de partir. La preuve d'entrée est transitoire, limitée au propriétaire, expire au bout de cinq minutes et ne révèle pas de ressources à elle seule. Les demandes répétées pour le même propriétaire, la même dimension et le même objectif réutilisent le budget enregistré. Le travail de recherche actif est interrompu à la déconnexion du propriétaire ou au rechargement du monde plutôt que rejoué automatiquement.

La politique de recherche se trouve sous `tasks.resources` dans `config/friendai-server.json` : les recherches locales portent par défaut sur 64 blocs et 2 400 ticks. Les expéditions sont désactivées par défaut et exigent à la fois `allowExpeditions` et le chargement de chunks du compagnon. Elles ne détiennent au plus que le ticket de chunk du tronçon suivant ; si le chargement de chunks est désactivé, le compagnon s'arrête à sa frontière chargée actuelle. L'atlas, la couverture et les tracés exécutés sont limités au propriétaire et délimités.

## Objectifs et planification

Pour un objectif en plusieurs parties (« construire une maison et apporter du fer »), le compagnon enregistre un but avec les critères qui lui diront qu'il est atteint, et ce but survit aux plans, aux échecs, à la mort et aux redémarrages. Il continue d'y travailler via la file de tâches, lit les faits de chaque tâche terminée (objets gagnés et perdus, ce qui reste non satisfait) et, lorsqu'une étape échoue, reçoit un diagnostic et un remède qu'il n'a pas encore essayé. Après quelques réparations infructueuses, il s'arrête et vous demande ce qui le bloque.

Une demande complexe, ou un plan échoué auquel il reste des replanifications, est d'abord ébauché par un appel de planificateur séparé qui renvoie un plan validé ; le plan est mis en file avec votre niveau d'accès et ne peut jamais utiliser un outil que vous n'avez pas le droit d'utiliser. Les réglages se trouvent sous `agent` dans `config/friendai-server.json` :

| Réglage | Par défaut | Signification |
|---|---|---|
| `toolRouting` | `true` | N'envoie au modèle que les familles d'outils dont une demande a besoin ; il peut en déverrouiller d'autres avec `request_tools`. |
| `autonomy` | `assistive` | `passive` ne démarre jamais de lui-même ; `assistive` pousse doucement un compagnon inactif vers un objectif inachevé ; `autonomous` peut aussi demander quoi faire ensuite (il n'agit toujours jamais sans invitation). |
| `maxReplans` | `3` | Replanifications par objectif avant que le compagnon ne demande de l'aide (0–10). |
| `goalTickSec` | `30` | Secondes entre deux relances vers un objectif inachevé (10–600). |
| `promptBudget` | `24000` | Budget en caractères du prompt système ; `0` signifie illimité. |
| `planner` | aucun | Nom d'un profil à utiliser pour la planification ; vide utilise le profil actif. |
| `plannerTimeoutSec` | `60` | Durée maximale d'un appel de planificateur (10–300). |

## Commerce

Le propriétaire peut demander au compagnon de trouver des villageois chargés, des marchands ambulants et des piglins adultes, d'inspecter les offres vanilla exactes, de consulter les prix mémorisés et de prévisualiser des limites de dépense explicites. Les notes de marché sont enregistrées par propriétaire sous `<world>/friendai/trading/` ; les devis et les emplacements expirent indépendamment, et chaque achat revérifie l'offre du marchand en direct. Les commandes utilisent la réputation et l'inventaire du compagnon et ne rafraîchissent jamais le stock ni ne changent les métiers de force.

Les commandes de marchand exigent des plafonds et des réserves par objet. Les nouvelles tentatives de la file partagent la portée du tour de propriétaire d'origine et ne peuvent pas en augmenter les limites ; les achats en attente ne sont pas rejoués après un redémarrage. `obtain_item` ne prend un marchand en compte que si ce même tour de propriétaire a déjà fourni un budget de commerce explicite et une empreinte de paiement. Il ne planifie que des sorties neutres en composants ; les échanges de composants exacts utilisent `trade_item`.

L'outil `barter` utilise un vrai piglin adulte et l'interaction vanilla avec l'or. Ses résultats étant aléatoires, le compagnon ne signale que les objets attribués à cette tentative et s'arrête plutôt que de payer à nouveau si un lingot accepté n'a produit aucun résultat observé. Il se limite par défaut à huit lingots d'or par commande. Les contrôles de commerce sont privés au propriétaire dans l'onglet Marchands. Les valeurs par défaut du serveur se trouvent sous `tasks.trading` dans `config/friendai-server.json` ; `enabled` peut désactiver tous les outils de commerce et toutes les actions de l'interface.

## Langues

L'interface est disponible en anglais, russe, allemand (`de_de`), français (`fr_fr`), espagnol (`es_es`), portugais brésilien (`pt_br`), chinois simplifié (`zh_cn`) et ukrainien (`uk_ua`). Le compagnon répond dans la langue dans laquelle vous écrivez, ou dans une langue fixe si vous en définissez une. Les voix Piper locales n'existent qu'en russe et en anglais ; une interface traduite n'ajoute pas de voix locale, donc une synthèse cloud ou une voix anglaise/russe reste nécessaire pour les autres langues. Pour ajouter une langue, consultez [CONTRIBUTING.md](CONTRIBUTING.md).

| Langue | Code | Interface | Voix locale |
|---|---|---|---|
| Anglais | `en_us` | oui | oui |
| Russe | `ru_ru` | oui | oui |
| Allemand | `de_de` | oui | non |
| Français | `fr_fr` | oui | non |
| Espagnol | `es_es` | oui | non |
| Portugais brésilien | `pt_br` | oui | non |
| Chinois simplifié | `zh_cn` | oui | non |
| Ukrainien | `uk_ua` | oui | non |

## Téléchargement et versions

Les versions sont publiées sur la [page Releases](https://github.com/NemoKing1210/Friendai/releases). Chacune contient
exactement un jar du mod, `friendai-<version>.jar`, et `friendai-<version>.jar.sha256` ; téléchargez les deux, vérifiez la
somme de contrôle (`sha256sum -c friendai-<version>.jar.sha256`, ou `shasum -a 256 -c` sur macOS), puis placez le jar dans `mods/`
sur le client et sur le serveur. Les notes de version correspondent à la section de `CHANGELOG.md` pour cette version.

Seul le mainteneur crée un tag. Pousser `vX.Y.Z` lance le workflow de publication, qui refuse un tag ne correspondant pas à
`mod_version`, un commit qui n'est pas sur `main`, ou un commit dont l'exécution CI n'est pas verte ; il lance ensuite la compilation
complète et publie le jar vérifié. Voir `CONTRIBUTING.md` pour l'essai à blanc et les vérifications locales.

## Contribution

Lisez [CONTRIBUTING.md](CONTRIBUTING.md) pour les règles de branches, de commits, de tests et de traduction.

## Compilation

```bash
./gradlew build        # compile, unit tests and in-game tests
./gradlew test         # unit tests only
./gradlew runGameTest  # Fabric GameTests only
./gradlew runClient    # start a development client
```

## Licence

[MIT](LICENSE)
