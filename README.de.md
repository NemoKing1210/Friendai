# Friendai

[English](README.md) · [Русский](README.ru.md) · [Українська](README.uk.md) · **Deutsch** · [Français](README.fr.md) · [Español](README.es.md) · [Português (Brasil)](README.pt-BR.md) · [简体中文](README.zh-CN.md)

![Minecraft 26.3](https://img.shields.io/badge/Minecraft-26.3-62B47A)
![Fabric](https://img.shields.io/badge/Fabric-Loader-DBB69C)
![Java 25](https://img.shields.io/badge/Java-25-ED8B00)
![MIT](https://img.shields.io/badge/License-MIT-blue)

<img src="src/main/resources/assets/friendai/icon.png" alt="Friendai-Symbol" width="128" align="right">

Ein Open-Source-Fabric-Mod, der Minecraft einen KI-Gefährten hinzufügt. Sprich im Chat oder per Stimme mit ihm, und er folgt dir, holt Gegenstände, läuft zu Orten und baut für dich Blöcke ab.

> „Friendai, baue 10 Holz ab“ — der Gefährte antwortet im Chat, läuft zu den Bäumen, fällt sie, sammelt die Drops auf und meldet sich zurück.

## Inhalt

- [Was er kann](#was-er-kann)
- [Voraussetzungen](#voraussetzungen)
- [Schnellstart](#schnellstart)
- [Anbieter einrichten](#anbieter-einrichten)
- [Stimme](#stimme)
- [Ziele und Planung](#ziele-und-planung)
- [Handel](#handel)
- [Ressourcenwissen und Suche](#ressourcenwissen-und-suche)
- [Sprachen](#sprachen)
- [Konfigurations-Upgrades](#konfigurations-upgrades)
- [Kreativmodus](#kreativmodus)
- [Download und Releases](#download-und-releases)
- [Selbst kompilieren](#selbst-kompilieren)
- [Mitwirken](#mitwirken)

## Was er kann

| Bereich | Was der Gefährte tut |
|---|---|
| Folgen und bleiben | Folgt in Gehdistanz, bleibt, wo man es ihm sagt, läuft zu einem Punkt und kommt zurück. |
| Holen und liefern | Sammelt Drops auf, entnimmt Gegenstände aus Containern, die er geprüft hat, und übergibt dir Gegenstände. |
| Abbauen und bauen | Baut eine gezählte Anzahl einer Blockart ab, fällt einen stehenden Baum, setzt Baupläne und baut Treppen. |
| Erkunden | Schaut sich um, sucht eine Ressource in einem begrenzten Gebiet und findet Wege durch Wasser und Höhlen. |
| Kämpfen | Beschützt dich, greift feindliche Mobs an und zieht sich bei Verletzung zurück oder isst. |
| Weiterarbeiten | Lädt die Chunks um sich herum, übersteht einen Neustart und nimmt seine letzte Aufgabe wieder auf. |
| Erinnern | Speichert besitzerbezogene Erinnerungen, Orte, Ressourcensichtungen, Ziele und Handelsnotizen. |
| Sprechen | Antwortet im Chat oder per Stimme und kann Antworten mit lokaler oder Cloud-Stimme vorlesen. |

## Voraussetzungen

- Minecraft **26.3**
- [Fabric Loader](https://fabricmc.net/) und [Fabric API](https://modrinth.com/mod/fabric-api)
- Java **25**
- Optional: [Mod Menu](https://modrinth.com/mod/modmenu) und [YACL](https://modrinth.com/mod/yacl) für den Einstellungsbildschirm im Spiel. Ohne sie funktioniert der Mod, und die Einstellungen werden in den Konfigurationsdateien bearbeitet.

Der Mod muss sowohl auf dem Client als auch auf dem Server installiert sein (der integrierte Server im Einzelspieler zählt dazu).

## Schnellstart

1. Installiere den Mod und richte einen Anbieter ein (siehe unten).
2. Führe in einer Welt `/friendai summon` aus. Dein Gefährte erscheint neben dir.
3. Sprich mit ihm:
   - im Chat mit Präfix: `@ come here`
   - im Chat per Namen: `Friendai, mine 10 logs` oder `Friendai, chop the tree next to me`
   - per Stimme: halte **V** gedrückt (Push-to-Talk, in den Steuerungen änderbar) und sprich.
4. `/friendai stop` bricht ab, was er gerade tut, `/friendai dismiss` schickt ihn weg, `/friendai status` zeigt den Anbieter und die aktuelle Aufgabe.

Dieselben Steuerungsmöglichkeiten gibt es im **Pausenmenü (Esc)**: Das Symbol des Gefährten neben dem Symbol zum Melden von Fehlern öffnet einen Bildschirm, auf dem du deinen Gefährten beschwören, wegschicken und stoppen und seine aktuelle Aufgabe sowie die letzten Aufgaben sehen kannst. Operatoren erhalten zusätzlich das Gehirn-Log (Nachrichten, Tool-Aufrufe, Fehler) und Debug-Werkzeuge: was der Gefährte sieht, seinen aktuellen Prompt, eine manuelle Nachricht und einen manuellen Tool-Aufruf.

Was er kann: dir folgen, bleiben, zu einem Punkt gehen, Gegenstände aufheben, dir Gegenstände geben, sich umschauen, sein Inventar zeigen, stoppen und N Blöcke einer Art abbauen.

Der Gefährte hält die Chunks um sich herum geladen, arbeitet also weiter, wenn du weggehst, und nimmt nach einem Serverneustart seine Arbeit an der letzten Position wieder auf. `companion.chunkLoading.radius` legt fest, wie viele Chunks um ihn herum weiter ticken (0–4); der Standardwert 1 hält einen 3×3-Bereich innerhalb eines 5×5 geladenen Bereichs am Ticken, und die Dimension selbst bleibt aktiv. Schalte das mit `companion.chunkLoading.enabled` in `config/friendai-server.json` ab.

## Konfigurations-Upgrades

Unterstützte ältere Konfigurationsdateien werden automatisch aktualisiert: Server-Schemas von 3–15 auf 16 und Client-Schemas von 1–3 auf 4. Anbieterprofile, API-Schlüssel, Modelle, Geräte und ausdrücklich gesetzte Einstellungen bleiben erhalten; neue Felder bekommen geprüfte Standardwerte. Umbenannte Einstellungen werden ausdrücklich umgewandelt. Unbekannte Erweiterungsfelder überstehen Upgrades und spätere Speicherungen, auch bei neu geordneten Routing-Regeln. Tragen mehrere bearbeitete Array-Einträge zusätzliche Felder und wird dadurch ihre Identität mehrdeutig, wird das Speichern abgelehnt, statt zu raten, welchem Eintrag diese Felder gehören; bearbeite diese Datei direkt, während Spiel bzw. Server gestoppt sind.

Vor dem Ersetzen prüft der Mod das Ergebnis und speichert die exakten ursprünglichen Bytes neben der Datei als `<filename>.v<old-version>.<unique-id>.bak`. Backups werden nie von einem weiteren Upgrade überschrieben. Schreibvorgänge erfolgen durch atomares Ersetzen; Konfigurationsdateien sind auf 8 MiB begrenzt. Schlägt ein Upgrade, ein Backup oder ein Ersetzen fehl, bleibt das Original unversehrt und schreibgeschützt, während im Speicher Standardwerte verwendet werden. Behebe das Problem und öffne den Server-Einstellungsbildschirm erneut oder starte Spiel bzw. Server neu, um es noch einmal zu versuchen.

Unbekannte, versionslose und zukünftige Formate werden weder geraten noch herabgestuft. Verwende die passende Mod-Version oder behalte ein Backup und ersetze die Datei bewusst. Um ein Upgrade-Backup wiederherzustellen, stoppe zuerst Spiel bzw. Server und verwende eine Mod-Version, die das Schema dieses Backups versteht. Backups enthalten dieselben Zugangsdaten wie das Original; schütze sie so, wie du die Konfiguration schützt. Besitzerdatensätze und Weltstände folgen ihren eigenen Persistenzregeln.

## Kreativmodus

Öffne den Gefährten-Bildschirm aus dem Pausenmenü und aktiviere **Kreativmodus** im Tab Steuerung. Er ist standardmäßig aus. Auf einem dedizierten Server muss der Besitzer Operator sein, um ihn zu aktivieren; im Einzelspieler darf der Weltbesitzer ihn auch bei ausgeschalteten Cheats aktivieren. Der Besitzer kann ihn jederzeit ausschalten, und die Einstellung übersteht Wegschicken, erneutes Beschwören und das Neuladen der Welt.

Im Kreativmodus ignoriert der Gefährte gewöhnlichen Schaden und Hunger, kann Kollisionen umfliegen, zerstört erlaubte Blöcke sofort ohne normale Drops und verbraucht keine Baumaterialien. Schutzregeln gelten weiterhin. Mit dem nur dem Besitzer vorbehaltenen Werkzeug `create_item` lassen sich registrierte Gegenstände mit ihren Standardkomponenten an den Gefährten oder an einen Besitzer in der Nähe liefern; die Lieferung endet bei der Kapazität des Zielinventars und meldet jeden Rest. `fly_to` folgt einer begrenzten Route durch geladene Chunks.

Das nur dem Besitzer vorbehaltene Werkzeug `execute_command` akzeptiert eine begrenzte Gameplay-Allowlist: den Gefährten teleportieren, Zeit/Wetter ändern oder abfragen, dem Besitzer Gegenstände geben, Effekte auf den Gefährten anwenden oder entfernen und den gehaltenen Gegenstand des Besitzers verzaubern. Der Besitzer muss online sein und beim Ausführen eines Befehls die normalen Befehlsrechte besitzen. Eine Aktivierung im Einzelspieler bei ausgeschalteten Cheats verleiht keine Befehlsrechte. Das Werkzeug lehnt `execute`, `function`, `schedule`, andere Befehls-Wurzeln und andere Spielerziele als den Besitzer ab. Das Ausschalten des Kreativmodus bricht anstehende kreative Arbeiten ab und stellt Bewegungs-, Hunger- und Schadensregeln des Überlebensmodus wieder her; bereits erzeugte Gegenstände bleiben in den Inventaren.

## Anbieter einrichten

Öffne **Mod Menu → Friendai → Anbieter** oder bearbeite `config/friendai-server.json`. Auf einem dedizierten Server bearbeitest du die Datei und führst `/friendai reload` aus.

| Preset-ID | Dienst | Hinweise |
|---|---|---|
| `openai` | OpenAI | Responses API |
| `anthropic` | Anthropic | |
| `gemini` | Google Gemini | |
| `openrouter` | OpenRouter | ein Schlüssel für viele Modelle |
| `deepseek` | DeepSeek | |
| `groq` | Groq | schnelle gehostete Modelle |
| `mistral` | Mistral | |
| `xai` | xAI | |
| `together` | Together AI | |
| `fireworks` | Fireworks AI | |
| `cerebras` | Cerebras | |
| `github_models` | GitHub Models | GitHub-Token |
| `openai_compat` | Jeder OpenAI-kompatible Endpunkt (LM Studio, vLLM, …) | `baseUrl` ist erforderlich |
| `ollama` | Ollama (native API) | kein API-Schlüssel |
| `opencode_zen` | OpenCode Zen | routet nach Modellname |
| `command_code` | Command Code | routet nach Modellname |

Halte API-Schlüssel aus der Datei heraus, indem du auf eine Umgebungsvariable verweist:

```json
"apiKey": "env:ANTHROPIC_API_KEY"
```

Der Schlüssel wird nie in Logs oder im Chat geschrieben. „Verbindung testen“ im Einstellungsbildschirm prüft, ob der Anbieter antwortet und ob das Modell Tool-Aufrufe unterstützt.

## Stimme

Halte **V** gedrückt, um zu sprechen. Öffne **Einstellungen → Sprache → Erkennung** und wähle, wo Sprache in Text umgewandelt wird:

- **Lokal (Whisper, offline)** — [whisper.cpp](https://github.com/ggerganov/whisper.cpp) betreibt eines der Modelle `tiny`, `base` oder `small` kostenlos auf deiner CPU. Drücke **Modell herunterladen**; Modelle werden in `<game dir>/friendai/models/` gespeichert.
- **OpenAI (kostenpflichtig)** — eine OpenAI-kompatible Transkriptions-API (standardmäßig `gpt-transcribe`). Die Basis-URL kann auf jeden OpenAI-kompatiblen Server zeigen, auch auf einen lokalen, der keinen Schlüssel braucht.
- **Groq (kostenpflichtig)** — dieselbe API über Groq (standardmäßig `whisper-large-v3`).
- **Google Cloud Speech-to-Text (kostenpflichtig)** — ein Schlüssel und eine Sprache; die Aufnahme wird als LINEAR16 gesendet.
- **Yandex SpeechKit (kostenpflichtig)** — ein API-Schlüssel, die ID des Cloud-Ordners und ein konkretes Sprach-Tag.

Das Transkript wird als Chatnachricht an den Server gesendet. Lokale Erkennung lässt das Audio auf deinem Rechner; ein Cloud-Anbieter lädt die Aufnahme hoch, und zwar nur, solange dieser Anbieter ausgewählt ist.

Sowohl getippter Chat als auch Push-to-Talk erreichen einen Gefährten nur in derselben Dimension innerhalb des Hörradius des Servers (standardmäßig 32 Blöcke, `chat.hearingRadius`). Weiter entfernt hört der Gefährte dich weder noch antwortet er und sagt das, statt zu schweigen.

### Sprachausgabe

Der Gefährte kann seine Antworten auch vorlesen. Die Synthese ist standardmäßig aus; schalte **Antworten vorlesen** unter **Einstellungen → Sprache → Synthese** ein und wähle einen Anbieter:

- **Lokal (kostenlos, offline)** — [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) betreibt eine Piper-Stimme auf deiner CPU. Es stehen fünf Stimmen zur Wahl: Irina und Dmitri (Russisch), Lessac und LibriTTS-R (Englisch) und Alba (Britisches Englisch). Wähle eine und drücke **Stimme herunterladen**; Stimmen werden in `<game dir>/friendai/models/tts/` gespeichert (je etwa 67–82 MB). Russische Systeme bekommen Irina, alle anderen Lessac. Jede Stimme hat ihre eigene Lizenz, die unter dem Dropdown angezeigt wird: Die Modelldateien sind überwiegend MIT, die Stimme LibriTTS-R steht unter CC BY 4.0, und die Bedingungen der Trainingsdaten unterscheiden sich.
- **OpenAI (kostenpflichtig)** — die OpenAI-Sprach-API mit einem Schlüssel, einer Stimme und einer Geschwindigkeit. Die Basis-URL kann auf jeden OpenAI-kompatiblen Server zeigen, auch auf einen lokalen, der keinen Schlüssel braucht.
- **Google Cloud Text-to-Speech (kostenpflichtig)** — ein Schlüssel, eine Stimme, eine Sprache und eine Geschwindigkeit; Audio kommt als LINEAR16.
- **ElevenLabs (kostenpflichtig)** — die ElevenLabs-Text-to-Speech-API; ein Schlüssel, eine Stimm-ID und eine Geschwindigkeit.
- **Yandex SpeechKit (kostenpflichtig)** — ein API-Schlüssel, die Cloud-Ordner-ID, eine Stimme, ein Sprach-Tag, eine Geschwindigkeit und 16-kHz-PCM-Ausgabe.

Nur der Antworttext verlässt deinen Rechner, und nur bei Cloud-Synthese; lokale Stimmen senden ihn nirgendwohin. Bei Cloud-Erkennung verlässt nur die Aufnahme das Gerät. Schlüssel sind in beide Richtungen clientseitig: Sie liegen in der Client-Konfiguration, dürfen auf eine Umgebungsvariable verweisen (`env:OPENAI_API_KEY`), wandern nur in einem HTTP-Header und erscheinen nie in Logs, im Chat oder in Fehlermeldungen. Friendai wiederholt eine kostenpflichtige Anfrage nie automatisch.

Lautstärke, das Ausgabegerät, die maximale Länge einer gesprochenen Äußerung und das Vorlesen von Antworten an andere Spieler stehen auf demselben Bildschirm. **Stimme kommt vom Gefährten** ist standardmäßig an: Jede Antwort wird an der Position des Gefährten abgespielt, schwenkt beim Drehen nach links oder rechts und blendet mit der Entfernung ab, bis sie am Hörradius verstummt. Schalte es aus, um Antworten mittig und in voller Lautstärke zu hören. **Stimme testen** spricht einen Beispielsatz. Ein Druck auf Push-to-Talk oder die Taste **Sprechen beenden** (standardmäßig nicht belegt; in den Steuerungen festlegen) bricht eine Antwort ab.

## Ressourcenwissen und Suche

Der Server führt für jeden Besitzer einen eigenen Ressourcenatlas in `<world>/friendai/knowledge/<owner-uuid>.json`. Er verzeichnet Blockpositionen erst, wenn der Gefährte eine freie Sichtlinie hat oder seinen eigenen erfolgreichen Abbau bestätigt. Zählungen sind historische Beobachtungen; eine erinnerte Fundstelle kann überbaut oder erschöpft sein, deshalb nennt `known_resources` Alter und Unsicherheit und verspricht nie, dass die alte Anzahl noch vorhanden ist. Abfragen verwenden nur gespeicherte Belege und laden keine Chunks.

Containerinhalte werden erst zwischengespeichert, nachdem der Gefährte einen Container bei einer autorisierten Prüfung oder Übergabe geöffnet hat. `obtain_item` darf einen aktuellen Schnappschuss derselben Dimension innerhalb von 16 Blöcken nutzen, um eine Truhenquelle zu wählen, öffnet dann diesen Container und prüft den aktuellen Inhalt, bevor es Gegenstände entnimmt. Ein geschlossener Container wird bei der Erkundung nie eingesehen.

`search_resource` erlaubt einem vertrauenswürdigen Spieler eine begrenzte Suche. Der Gefährte wählt deterministische Aussichtspunkte an der Oberfläche und meldet eine Ressource erst nach direkter Sicht. Er schließt aus dem Gelände nicht auf eine versteckte Erzader. `strategy=mine` kann die vorhandene abgesicherte Abbauroute nutzen, wenn eine geeignete haltbare Spitzhacke und 16 Fackeln verfügbar sind. `strategy=cave` verlangt einen kürzlich beobachteten, unversperrten Sichtkorridor unter freiem Himmel, der durch einen Eingang zu einer sichtbaren Innenwand hinabführt; ohne diesen Beleg bricht er nicht auf. Der Eingangsbeleg ist flüchtig, besitzerbezogen, läuft nach fünf Minuten ab und verrät für sich keine Ressourcen. Wiederholte Anfragen desselben Besitzers, derselben Dimension und desselben Ziels verwenden das gespeicherte Budget wieder. Laufende Sucharbeit wird beim Ausloggen des Besitzers oder beim Neuladen der Welt abgebrochen statt automatisch wiederholt.

Die Suchrichtlinie steht unter `tasks.resources` in `config/friendai-server.json`: Lokale Suchen sind standardmäßig auf 64 Blöcke und 2.400 Ticks begrenzt. Expeditionen sind standardmäßig deaktiviert und verlangen sowohl `allowExpeditions` als auch das Chunk-Laden des Gefährten. Sie halten höchstens das Chunk-Ticket für den nächsten Abschnitt; bei deaktiviertem Chunk-Laden stoppt der Gefährte an seiner aktuellen geladenen Grenze. Atlas, Abdeckung und ausgeführte Pfade sind besitzerbezogen und begrenzt.

## Ziele und Planung

Für ein Ziel mit mehreren Teilen („baue ein Haus und bring Eisen“) legt der Gefährte ein Ziel samt der Bedingung an, an der er erkennt, dass es erreicht ist, und das Ziel übersteht Pläne, Fehlschläge, Tod und Neustarts. Er arbeitet über die Aufgabenwarteschlange daran weiter, liest die Fakten jeder abgeschlossenen Aufgabe (gewonnene und verlorene Gegenstände, was noch fehlt) und bekommt bei einem fehlgeschlagenen Schritt eine Diagnose und einen noch nicht versuchten Ausweg. Nach einigen erfolglosen Reparaturen hält er inne und fragt dich, was ihn blockiert.

Eine komplexe Anfrage oder ein fehlgeschlagener Plan mit verbleibenden Neuplanungen wird zuerst von einem eigenen Planer-Aufruf entworfen, der einen geprüften Plan zurückgibt; der Plan wird mit deiner Zugriffsebene eingereiht und kann nie ein Werkzeug verwenden, das du nicht verwenden darfst. Die Einstellungen stehen unter `agent` in `config/friendai-server.json`:

| Einstellung | Standard | Bedeutung |
|---|---|---|
| `toolRouting` | `true` | Sendet dem Modell nur die Werkzeugfamilien, die eine Anfrage braucht; es kann weitere mit `request_tools` freischalten. |
| `autonomy` | `assistive` | `passive` startet nie von selbst; `assistive` gibt einem untätigen Gefährten einen Anstoß Richtung unerledigtes Ziel; `autonomous` darf auch fragen, was als Nächstes zu tun ist (handelt aber weiterhin nie unaufgefordert). |
| `maxReplans` | `3` | Neuplanungen pro Ziel, bevor der Gefährte um Hilfe bittet (0–10). |
| `goalTickSec` | `30` | Sekunden zwischen Anstößen Richtung unerledigtes Ziel (10–600). |
| `promptBudget` | `24000` | Zeichenbudget des System-Prompts; `0` bedeutet unbegrenzt. |
| `planner` | keins | Name eines Profils für die Planung; leer verwendet das aktive Profil. |
| `plannerTimeoutSec` | `60` | Wie lange ein Planer-Aufruf dauern darf (10–300). |

## Handel

Der Besitzer kann den Gefährten bitten, geladene Dorfbewohner, wandernde Händler und erwachsene Piglins zu finden, die genauen Vanilla-Angebote zu prüfen, sich erinnerte Preise abzufragen und ausdrückliche Ausgabengrenzen anzuzeigen. Marktnotizen werden pro Besitzer unter `<world>/friendai/trading/` gespeichert; Angebote und Standorte laufen unabhängig voneinander ab, und jeder Kauf prüft das aktuelle Händlerangebot erneut. Bestellungen nutzen den eigenen Ruf und das eigene Inventar des Gefährten und füllen den Vorrat nie auf noch ändern sie Berufe mit Gewalt.

Händlerbestellungen verlangen Obergrenzen und Reserven pro Gegenstand. Wiederholungen aus der Warteschlange teilen den Geltungsbereich der ursprünglichen Besitzer-Runde und können deren Grenzen nicht erhöhen; ausstehende Käufe werden nach einem Neustart nicht wiederholt. `obtain_item` berücksichtigt einen Händler nur, wenn dieselbe Besitzer-Runde bereits ein ausdrückliches Handelsbudget und einen Zahlungs-Fingerabdruck geliefert hat. Es plant nur komponentenneutrale Ergebnisse; exakte Tauschvorgänge mit Komponenten verwenden `trade_item`.

Das Werkzeug `barter` nutzt einen echten erwachsenen Piglin und die Vanilla-Gold-Interaktion. Seine Ergebnisse sind zufällig, deshalb meldet der Gefährte nur Drops, die diesem Versuch zugeordnet sind, und hört auf, statt erneut zu zahlen, wenn ein angenommener Barren kein beobachtetes Ergebnis hat. Standardmäßig sind höchstens acht Goldbarren pro Bestellung erlaubt. Die Handelssteuerung ist besitzerprivat im Tab Händler. Server-Standardwerte stehen unter `tasks.trading` in `config/friendai-server.json`; `enabled` kann alle Handelswerkzeuge und UI-Aktionen abschalten.

## Sprachen

Die Oberfläche ist auf Englisch, Russisch, Deutsch (`de_de`), Französisch (`fr_fr`), Spanisch (`es_es`), brasilianischem Portugiesisch (`pt_br`), vereinfachtem Chinesisch (`zh_cn`) und Ukrainisch (`uk_ua`) verfügbar. Der Gefährte antwortet in der Sprache, in der du schreibst, oder in einer festen Sprache, wenn du eine setzt. Lokale Piper-Stimmen gibt es nur für Russisch und Englisch; eine übersetzte Oberfläche fügt keine lokale Stimme hinzu, deshalb sind für andere Sprachen weiterhin Cloud-Synthese oder englische bzw. russische Sprache nötig. Um eine Sprache hinzuzufügen, siehe [CONTRIBUTING.md](CONTRIBUTING.md).

| Sprache | Code | Oberfläche | Lokale Stimme |
|---|---|---|---|
| Englisch | `en_us` | ja | ja |
| Russisch | `ru_ru` | ja | ja |
| Deutsch | `de_de` | ja | nein |
| Französisch | `fr_fr` | ja | nein |
| Spanisch | `es_es` | ja | nein |
| Brasilianisches Portugiesisch | `pt_br` | ja | nein |
| Vereinfachtes Chinesisch | `zh_cn` | ja | nein |
| Ukrainisch | `uk_ua` | ja | nein |

## Download und Releases

Veröffentlichungen erscheinen auf der [Releases-Seite](https://github.com/NemoKing1210/Friendai/releases). Jede enthält
genau ein Mod-Jar, `friendai-<version>.jar`, und `friendai-<version>.jar.sha256`; lade beide herunter, prüfe die Prüfsumme
(`sha256sum -c friendai-<version>.jar.sha256` oder `shasum -a 256 -c` unter macOS) und lege das Jar in `mods/` auf dem Client
und auf dem Server ab. Die Versionshinweise sind der Abschnitt in `CHANGELOG.md` für diese Version.

Nur der Maintainer erstellt ein Tag. Das Pushen von `vX.Y.Z` startet den Release-Workflow; er lehnt ein Tag ab, das nicht zu
`mod_version` passt, einen Commit, der nicht auf `main` liegt, oder einen Commit, dessen CI-Lauf nicht grün ist, führt dann den
vollständigen Build aus und veröffentlicht das geprüfte Jar. Den Probelauf und die lokalen Prüfungen findest du in `CONTRIBUTING.md`.

## Mitwirken

Lies [CONTRIBUTING.md](CONTRIBUTING.md) für die Regeln zu Branches, Commits, Tests und Übersetzungen.

## Selbst kompilieren

```bash
./gradlew build        # compile, unit tests and in-game tests
./gradlew test         # unit tests only
./gradlew runGameTest  # Fabric GameTests only
./gradlew runClient    # start a development client
```

## Lizenz

[MIT](LICENSE)
