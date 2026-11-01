# Friendai

[English](README.md) · [Русский](README.ru.md) · [Українська](README.uk.md) · [Deutsch](README.de.md) · [Français](README.fr.md) · **Español** · [Português (Brasil)](README.pt-BR.md) · [简体中文](README.zh-CN.md)

![Minecraft 26.3](https://img.shields.io/badge/Minecraft-26.3-62B47A)
![Fabric](https://img.shields.io/badge/Fabric-Loader-DBB69C)
![Java 25](https://img.shields.io/badge/Java-25-ED8B00)
![MIT](https://img.shields.io/badge/License-MIT-blue)

<img src="src/main/resources/assets/friendai/icon.png" alt="Icono de Friendai" width="128" align="right">

Un mod de Fabric de código abierto que añade un compañero con IA a Minecraft. Háblale por el chat o con la voz y te seguirá, recogerá objetos, irá a lugares y picará bloques por ti.

> «Friendai, mine 10 logs» — el compañero responde en el chat, va hasta los árboles, los tala, recoge lo que cae y te informa.

## Contenido

- [Qué puede hacer](#qué-puede-hacer)
- [Requisitos](#requisitos)
- [Inicio rápido](#inicio-rápido)
- [Configurar un proveedor](#configurar-un-proveedor)
- [Voz](#voz)
- [Objetivos y planificación](#objetivos-y-planificación)
- [Comercio](#comercio)
- [Conocimiento de recursos y búsqueda](#conocimiento-de-recursos-y-búsqueda)
- [Idiomas](#idiomas)
- [Actualizaciones de la configuración](#actualizaciones-de-la-configuración)
- [Modo creativo](#modo-creativo)
- [Descarga y versiones](#descarga-y-versiones)
- [Compilación](#compilación)
- [Contribuir](#contribuir)

## Qué puede hacer

| Área | Qué hace el compañero |
|---|---|
| Seguir y quedarse | Sigue a una distancia de caminata, se queda donde se le indica, camina hasta un punto y vuelve. |
| Recoger y entregar | Recoge lo que cae, toma objetos de contenedores que ha revisado y te entrega objetos. |
| Picar y construir | Pica una cantidad contada de un tipo de bloque, tala un árbol en pie, coloca planos y construye escaleras. |
| Explorar | Mira a su alrededor, busca un recurso en un área delimitada y se desplaza por agua y cuevas. |
| Luchar | Te protege, ataca a mobs hostiles y se retira o come cuando está herido. |
| Seguir trabajando | Carga los chunks a su alrededor, sobrevive a un reinicio y retoma su última tarea. |
| Recordar | Guarda recuerdos, lugares, avistamientos de recursos, objetivos y notas de comercio por propietario. |
| Hablar | Responde en el chat o con la voz, y puede decir sus respuestas con una voz local o en la nube. |

## Requisitos

- Minecraft **26.3**
- [Fabric Loader](https://fabricmc.net/) y [Fabric API](https://modrinth.com/mod/fabric-api)
- Java **25**
- Opcional: [Mod Menu](https://modrinth.com/mod/modmenu) y [YACL](https://modrinth.com/mod/yacl) para la pantalla de ajustes dentro del juego. Sin ellos el mod funciona y los ajustes se editan en los archivos de configuración.

El mod debe estar instalado tanto en el cliente como en el servidor (el servidor integrado del modo un jugador cuenta).

## Inicio rápido

1. Instala el mod y configura un proveedor (ver más abajo).
2. En un mundo, ejecuta `/friendai summon`. Tu compañero aparece a tu lado.
3. Háblale:
   - en el chat con un prefijo: `@ come here`
   - en el chat por su nombre: `Friendai, mine 10 logs` o `Friendai, chop the tree next to me`
   - con la voz: mantén **V** (pulsar para hablar, se puede reasignar en Controles) y habla.
4. `/friendai stop` cancela lo que está haciendo, `/friendai dismiss` lo despide, `/friendai status` muestra el proveedor y la tarea actual.

Los mismos controles están en el **menú de pausa (Esc)**: el icono del compañero junto al icono de informar de un fallo abre una pantalla para invocar, despedir y detener a tu compañero y ver su tarea actual y las tareas recientes. Los operadores también tienen el registro del cerebro (mensajes, llamadas a herramientas, errores) y herramientas de depuración: lo que ve el compañero, su prompt actual, un mensaje manual y una ejecución manual de herramientas.

Qué puede hacer: seguirte, quedarse, ir a un punto, recoger objetos, darte objetos, mirar a su alrededor, mostrar su inventario, detenerse y picar N bloques de un tipo.

El compañero mantiene cargados los chunks a su alrededor, así que sigue trabajando cuando te alejas, y retoma su última posición tras reiniciar el servidor. `companion.chunkLoading.radius` define cuántos chunks a su alrededor siguen activos (0–4); el valor predeterminado de 1 mantiene activa un área de 3×3 dentro de un área cargada de 5×5, y la dimensión en sí sigue activa. Desactívalo con `companion.chunkLoading.enabled` en `config/friendai-server.json`.

## Actualizaciones de la configuración

Los archivos de configuración antiguos compatibles se actualizan automáticamente: los esquemas del servidor de 3–15 a 16 y los esquemas del cliente de 1–3 a 4. Se conservan los perfiles de proveedor, las claves de API, los modelos, los dispositivos y los ajustes explícitos; los campos nuevos reciben valores predeterminados validados. Los ajustes renombrados se convierten de forma explícita. Los campos de extensión desconocidos sobreviven a las actualizaciones y a los guardados posteriores, incluidas las reglas de enrutamiento reordenadas. Si varias entradas de array editadas llevan campos adicionales y su identidad se vuelve ambigua, se rechaza el guardado en lugar de adivinar a qué entrada pertenecen esos campos; edita ese archivo directamente con el juego o el servidor detenido.

Antes de reemplazar, el mod valida el resultado y guarda los bytes originales exactos junto al archivo como `<filename>.v<old-version>.<unique-id>.bak`. Ninguna actualización sobrescribe las copias de seguridad. Las escrituras usan reemplazo atómico; los archivos de configuración están limitados a 8 MiB. Si falla una actualización, una copia de seguridad o un reemplazo, el original queda intacto y de solo lectura mientras se usan los valores predeterminados en memoria. Corrige el problema y vuelve a abrir la pantalla de ajustes del servidor o reinicia el juego o el servidor para reintentar.

Los formatos desconocidos, sin versión y futuros nunca se adivinan ni se degradan. Usa la versión del mod correspondiente, o guarda una copia de seguridad y reemplaza el archivo deliberadamente. Para restaurar una copia de una actualización, detén antes el juego o el servidor y usa una versión del mod que entienda el esquema de esa copia. Las copias contienen las mismas credenciales que el original; protégelas como proteges la configuración. Los registros de propietario y las partidas guardadas usan sus propias reglas de persistencia.

## Modo creativo

Abre la pantalla del compañero desde el menú de pausa y activa **Creative** en la pestaña Control. Está desactivado de forma predeterminada. El propietario de un servidor dedicado debe ser operador para activarlo; en el modo un jugador, el dueño del mundo puede activarlo incluso con los trucos desactivados. El propietario puede desactivarlo en cualquier momento, y la preferencia sobrevive a despedir al compañero, volver a invocarlo y recargar el mundo.

En el modo creativo el compañero ignora el daño y el hambre normales, puede volar a través de las colisiones, rompe al instante los bloques permitidos sin generar los drops normales y no consume materiales de construcción. Las reglas de protección siguen aplicándose. Usa la herramienta `create_item`, exclusiva del propietario, para entregar objetos registrados con sus componentes predeterminados al compañero o a un propietario cercano; la entrega se detiene al alcanzar la capacidad del inventario de destino e informa de lo que quede. `fly_to` sigue una ruta delimitada a través de chunks cargados.

La herramienta `execute_command`, exclusiva del propietario, acepta una lista blanca limitada de acciones de juego: teletransportar al compañero, cambiar o consultar la hora y el clima, dar objetos al propietario, aplicar o quitar efectos al compañero y encantar el objeto que el propietario tiene en la mano. El propietario debe estar conectado y mantener el permiso normal de comandos cuando se ejecuta un comando. La activación en modo un jugador con los trucos desactivados no concede permiso de comandos. La herramienta rechaza `execute`, `function`, `schedule`, otras raíces de comandos y objetivos de jugador distintos del propietario. Desactivar el modo creativo cancela el trabajo creativo en cola y restaura las reglas de movimiento, hambre y daño de supervivencia; los objetos ya creados permanecen en los inventarios.

## Configurar un proveedor

Abre **Mod Menu → Friendai → Provider**, o edita `config/friendai-server.json`. En un servidor dedicado edita el archivo y ejecuta `/friendai reload`.

| Id. de preset | Servicio | Notas |
|---|---|---|
| `openai` | OpenAI | API Responses |
| `anthropic` | Anthropic | |
| `gemini` | Google Gemini | |
| `openrouter` | OpenRouter | una clave para muchos modelos |
| `deepseek` | DeepSeek | |
| `groq` | Groq | modelos alojados rápidos |
| `mistral` | Mistral | |
| `xai` | xAI | |
| `together` | Together AI | |
| `fireworks` | Fireworks AI | |
| `cerebras` | Cerebras | |
| `github_models` | GitHub Models | token de GitHub |
| `openai_compat` | Cualquier endpoint compatible con OpenAI (LM Studio, vLLM, …) | `baseUrl` es obligatorio |
| `ollama` | Ollama (API nativa) | sin clave de API |
| `opencode_zen` | OpenCode Zen | enruta por nombre de modelo |
| `command_code` | Command Code | enruta por nombre de modelo |

Mantén las claves de API fuera del archivo haciendo referencia a una variable de entorno:

```json
"apiKey": "env:ANTHROPIC_API_KEY"
```

La clave nunca se escribe en los registros ni en el chat. «Probar conexión» en la pantalla de ajustes comprueba que el proveedor responde y que el modelo admite llamadas a herramientas.

## Voz

Mantén **V** para hablar. Abre **Ajustes → Voz → Reconocimiento** y elige dónde se convierte el habla en texto:

- **Local (Whisper, sin conexión)** — [whisper.cpp](https://github.com/ggerganov/whisper.cpp) ejecuta uno de los modelos `tiny`, `base` o `small` en tu CPU, sin coste. Pulsa **Descargar modelo**; los modelos se guardan en `<game dir>/friendai/models/`.
- **OpenAI (de pago)** — una API de transcripción compatible con OpenAI (`gpt-transcribe` de forma predeterminada). La URL base puede apuntar a cualquier servidor compatible con OpenAI, incluido uno local, que no necesita clave.
- **Groq (de pago)** — la misma API a través de Groq (`whisper-large-v3` de forma predeterminada).
- **Google Cloud Speech-to-Text (de pago)** — una clave y un idioma; la grabación se envía como LINEAR16.
- **Yandex SpeechKit (de pago)** — una clave de API, el id de la carpeta en la nube y una etiqueta de idioma concreta.

La transcripción se envía al servidor como un mensaje de chat. El reconocimiento local mantiene el audio en tu máquina; un proveedor en la nube sube la grabación, y solo mientras ese proveedor está seleccionado.

Tanto el chat escrito como el pulsar para hablar solo llegan a un compañero en la misma dimensión dentro del radio de audición del servidor (32 bloques de forma predeterminada, `chat.hearingRadius`). A mayor distancia el compañero no te oye ni responde, y lo dice en lugar de quedarse en silencio.

### Salida de voz

El compañero también puede decir sus respuestas en voz alta. La síntesis está desactivada de forma predeterminada; activa **Decir respuestas en voz alta** en **Ajustes → Voz → Síntesis** y elige un proveedor:

- **Local (gratis, sin conexión)** — [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) ejecuta una voz de Piper en tu CPU. Se ofrecen cinco voces: Irina y Dmitri (ruso), Lessac y LibriTTS-R (inglés) y Alba (inglés británico). Elige una y pulsa **Descargar voz**; las voces se guardan en `<game dir>/friendai/models/tts/` (unos 67–82 MB cada una). Los sistemas en ruso reciben Irina, todos los demás Lessac. Cada voz tiene su propia licencia, mostrada bajo el desplegable: los archivos de modelo son en su mayoría MIT, la voz LibriTTS-R es CC BY 4.0 y los términos de los datos de entrenamiento varían.
- **OpenAI (de pago)** — la API de voz de OpenAI con una clave, una voz y una velocidad. La URL base puede apuntar a cualquier servidor compatible con OpenAI, incluido uno local, que no necesita clave.
- **Google Cloud Text-to-Speech (de pago)** — una clave, una voz, un idioma y una velocidad; el audio llega como LINEAR16.
- **ElevenLabs (de pago)** — la API de texto a voz de ElevenLabs; una clave, un id de voz y una velocidad.
- **Yandex SpeechKit (de pago)** — una clave de API, el id de la carpeta en la nube, una voz, una etiqueta de idioma, una velocidad y salida PCM a 16 kHz.

Solo el texto de la respuesta sale de tu máquina, y solo con la síntesis en la nube; las voces locales nunca lo envían a ningún sitio. Con el reconocimiento en la nube solo sale la grabación. Las claves están del lado del cliente en ambas direcciones: viven en la configuración del cliente, pueden referenciar una variable de entorno (`env:OPENAI_API_KEY`), viajan solo en una cabecera HTTP y nunca aparecen en los registros, el chat ni los mensajes de error. Friendai nunca reintenta automáticamente una solicitud de pago.

El volumen, el dispositivo de salida, la duración máxima de una frase hablada y decir en voz alta las respuestas dirigidas a otros jugadores están en la misma pantalla. **La voz viene del compañero** está activado de forma predeterminada: cada respuesta suena desde la posición del compañero, se desplaza a la izquierda o a la derecha según giras y se atenúa con la distancia hasta quedar en silencio en el radio de audición. Desactívalo para oír las respuestas centradas y a volumen completo. **Probar voz** dice una frase de ejemplo. Pulsar para hablar o la tecla **Dejar de hablar** (sin asignar de forma predeterminada; configúrala en Controles) corta una respuesta.

## Conocimiento de recursos y búsqueda

El servidor mantiene un atlas de recursos separado para cada propietario en `<world>/friendai/knowledge/<owner-uuid>.json`. Solo registra ubicaciones de bloques después de que el compañero tenga una línea de visión despejada o confirme su propia rotura de minería exitosa. Los recuentos son observaciones históricas; un sitio recordado puede estar cubierto o agotado, así que `known_resources` informa de la antigüedad y la incertidumbre y nunca promete que el recuento antiguo siga disponible. Las consultas usan solo pruebas guardadas y no cargan chunks.

El contenido de los contenedores se almacena en caché solo después de que el compañero abra un contenedor durante una comprobación o transferencia autorizada. `obtain_item` puede usar una instantánea reciente de la misma dimensión a 16 bloques o menos para elegir un cofre como origen, y luego abre ese contenedor y verifica el contenido real antes de tomar objetos. Un contenedor cerrado nunca se inspecciona durante el descubrimiento.

`search_resource` permite a un jugador de confianza solicitar una búsqueda delimitada. El compañero elige puntos de vista deterministas en la superficie e informa de un recurso solo tras verlo directamente. No deduce una veta de mineral oculta a partir del terreno. `strategy=mine` puede usar la ruta de minería protegida existente cuando hay un pico duradero adecuado y 16 antorchas disponibles. `strategy=cave` exige un corredor de visión al aire libre, recientemente observado y sin obstáculos, que descienda por una entrada hasta una pared interior visible; sin esa prueba se niega a partir. La prueba de la entrada es transitoria, está limitada al propietario, caduca a los cinco minutos y no revela recursos por sí misma. Las solicitudes repetidas para el mismo propietario, dimensión y objetivo reutilizan el presupuesto guardado. El trabajo de búsqueda activo se interrumpe cuando el propietario se desconecta o el mundo se recarga, en lugar de repetirse automáticamente.

La política de búsqueda está en `tasks.resources` de `config/friendai-server.json`: las búsquedas locales usan 64 bloques y 2.400 ticks de forma predeterminada. Las expediciones están desactivadas de forma predeterminada y requieren tanto `allowExpeditions` como la carga de chunks del compañero. Retienen como máximo el ticket de chunk del siguiente tramo; con la carga de chunks desactivada, el compañero se detiene en su frontera cargada actual. El atlas, la cobertura y los rastros ejecutados están limitados al propietario y acotados.

## Objetivos y planificación

Para un objetivo con varias partes («construye una casa y trae hierro») el compañero registra un objetivo con la forma en que sabrá que está terminado, y el objetivo sobrevive a planes, fallos, muerte y reinicios. Sigue trabajando en él a través de la cola de tareas, lee los hechos de cada tarea terminada (objetos ganados y perdidos, lo que sigue sin cumplirse) y, cuando falla un paso, obtiene un diagnóstico y un remedio que aún no ha probado. Tras unos cuantos intentos de arreglo fallidos se detiene y te pregunta qué se lo impide.

Una solicitud compleja, o un plan fallido con replanificaciones restantes, se redacta primero mediante una llamada aparte al planificador que devuelve un plan validado; el plan se pone en cola con tu nivel de acceso y nunca puede usar una herramienta que tú no puedas usar. Los ajustes están en `agent` de `config/friendai-server.json`:

| Ajuste | Valor predeterminado | Significado |
|---|---|---|
| `toolRouting` | `true` | Enviar al modelo solo las familias de herramientas que necesita una solicitud; puede desbloquear más con `request_tools`. |
| `autonomy` | `assistive` | `passive` nunca empieza por su cuenta; `assistive` empuja a un compañero inactivo hacia un objetivo sin terminar; `autonomous` también puede preguntar qué hacer a continuación (sigue sin actuar nunca sin que se le pida). |
| `maxReplans` | `3` | Replanificaciones por objetivo antes de que el compañero pida ayuda (0–10). |
| `goalTickSec` | `30` | Segundos entre empujones hacia un objetivo sin terminar (10–600). |
| `promptBudget` | `24000` | Presupuesto de caracteres del prompt del sistema; `0` significa ilimitado. |
| `planner` | ninguno | Nombre de un perfil para la planificación; vacío usa el perfil activo. |
| `plannerTimeoutSec` | `60` | Cuánto puede tardar una llamada al planificador (10–300). |

## Comercio

El propietario puede pedir al compañero que encuentre aldeanos cargados, comerciantes errantes y piglins adultos, que inspeccione las ofertas exactas del juego base, consulte precios recordados y previsualice límites de gasto explícitos. Las notas de mercado se guardan por propietario en `<world>/friendai/trading/`; las cotizaciones y las ubicaciones caducan de forma independiente, y cada compra vuelve a comprobar la oferta real del comerciante. Los pedidos usan la reputación y el inventario propios del compañero y nunca reabastecen el stock ni cambian profesiones por la fuerza.

Los pedidos a comerciantes requieren límites y reservas por objeto. Los reintentos en cola comparten el alcance del turno original del propietario y no pueden aumentar sus límites; las compras pendientes no se repiten tras un reinicio. `obtain_item` solo considera a un comerciante cuando ese mismo turno del propietario ya indicó un presupuesto comercial explícito y una huella de pago. Solo planifica resultados neutros en componentes; los intercambios con componentes exactos usan `trade_item`.

La herramienta `barter` usa un piglin adulto real y la interacción con oro del juego base. Sus resultados son aleatorios, así que el compañero solo informa de los drops atribuidos a ese intento y se detiene en lugar de pagar otra vez si un lingote aceptado no tiene resultado observado. De forma predeterminada usa como máximo ocho lingotes de oro por pedido. Los controles de comercio son privados del propietario en la pestaña Comerciantes. Los valores predeterminados del servidor están en `tasks.trading` de `config/friendai-server.json`; `enabled` puede desactivar todas las herramientas de comercio y las acciones de la interfaz.

## Idiomas

La interfaz está disponible en inglés, ruso, alemán (`de_de`), francés (`fr_fr`), español (`es_es`), portugués de Brasil (`pt_br`), chino simplificado (`zh_cn`) y ucraniano (`uk_ua`). El compañero responde en el idioma en el que escribes, o en un idioma fijo si lo estableces. Las voces locales de Piper solo existen para ruso e inglés; una interfaz traducida no añade una voz local, así que para otros idiomas sigue siendo necesaria la síntesis en la nube o el habla en inglés o ruso. Para añadir un idioma, consulta [CONTRIBUTING.md](CONTRIBUTING.md).

| Idioma | Código | Interfaz | Voz local |
|---|---|---|---|
| Inglés | `en_us` | sí | sí |
| Ruso | `ru_ru` | sí | sí |
| Alemán | `de_de` | sí | no |
| Francés | `fr_fr` | sí | no |
| Español | `es_es` | sí | no |
| Portugués brasileño | `pt_br` | sí | no |
| Chino simplificado | `zh_cn` | sí | no |
| Ucraniano | `uk_ua` | sí | no |

## Descarga y versiones

Las versiones se publican en la [página de Releases](https://github.com/NemoKing1210/Friendai/releases). Cada una incluye
exactamente un jar del mod, `friendai-<version>.jar`, y `friendai-<version>.jar.sha256`; descarga ambos, comprueba la suma
de verificación (`sha256sum -c friendai-<version>.jar.sha256`, o `shasum -a 256 -c` en macOS) y coloca el jar en `mods/`
tanto en el cliente como en el servidor. Las notas de la versión son la sección correspondiente de `CHANGELOG.md`.

Solo el mantenedor crea una etiqueta. Subir `vX.Y.Z` inicia el flujo de publicación, que rechaza una etiqueta que no coincida
con `mod_version`, un commit que no esté en `main` o un commit cuya ejecución de CI no esté en verde; después ejecuta la
compilación completa y publica el jar verificado. Consulta `CONTRIBUTING.md` para el ensayo y las comprobaciones locales.

## Contribuir

Lee [CONTRIBUTING.md](CONTRIBUTING.md) para las reglas de ramas, commits, pruebas y traducción.

## Compilación

```bash
./gradlew build        # compile, unit tests and in-game tests
./gradlew test         # unit tests only
./gradlew runGameTest  # Fabric GameTests only
./gradlew runClient    # start a development client
```

## Licencia

[MIT](LICENSE)
