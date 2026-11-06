"use strict";

/* Russian translation of the capability map. Keys are the exact English strings used in app.js and index.html;
   anything missing here is shown in English. Tool ids (snake_case) are never translated. Keep in sync with app.js. */
const RU_PLURALS = {
  tools: ["инструмент", "инструмента", "инструментов"],
  features: ["функция", "функции", "функций"]
};

const RU = {
  /* ---- page chrome ---- */
  "Overview": "Обзор",
  "Skills & Systems": "Навыки и системы",
  "One Turn": "Один ход",
  "Memory & Data": "Память и данные",
  "Architecture": "Архитектура",
  "What the companion is": "Что такое компаньон",
  "One companion per owner. It hears you, thinks with an LLM of your choice, and acts in the world through tools. Every block below is a group of abilities; the three branches split what it does, how it thinks and how it talks.":
    "Один компаньон на владельца. Он слышит вас, думает с помощью выбранной LLM и действует в мире через инструменты. Каждый блок ниже — группа умений; три ветки делят их на то, что он делает, как думает и как общается.",
  "Companion / branch": "Компаньон / ветка",
  "Category (click to open)": "Категория (клик — открыть)",
  "Tool the LLM can call": "Инструмент, который вызывает LLM",
  "Built-in behaviour / feature": "Встроенное поведение / функция",
  "From a sentence to a block placed": "От фразы до поставленного блока",
  "One chat or voice message becomes exactly one agent turn. Follow the arrows: dashed lines are feedback loops.":
    "Одно сообщение в чате или голосом становится ровно одним ходом агента. Идите по стрелкам: пунктир — обратная связь.",
  "Three layers, one mod": "Три слоя, один мод",
  "The server owns the companion; the client adds screens, voice and rendering; the pure-Java engine holds the logic and never imports Minecraft.":
    "Сервер владеет компаньоном; клиент добавляет экраны, голос и отрисовку; чисто-явовский движок хранит логику и никогда не импортирует Minecraft.",
  "BUILT FROM THE TOOL CATALOG AND ARCHITECTURE.MD · MIT LICENSED": "СОБРАНО ИЗ КАТАЛОГА ИНСТРУМЕНТОВ И ARCHITECTURE.MD · ЛИЦЕНЗИЯ MIT",
  "tools": "инструментов",
  "categories": "категорий",
  "LLM protocols": "протоколов LLM",
  "access levels": "уровня доступа",
  "Zoom in": "Приблизить",
  "Zoom out": "Отдалить",
  "Fit": "Вписать",
  "Fit the graph to the view": "Вписать граф в окно",
  "Reset": "Сброс",
  "Restore the original layout": "Вернуть исходную раскладку",
  "The graph library failed to load (check your connection).": "Не удалось загрузить библиотеку графов (проверьте подключение).",
  "Category": "Категория",
  "The companion": "Компаньон",
  "Branch": "Ветка",
  "Category - click to open": "Категория — клик, чтобы открыть",
  "Tool - the LLM can call it": "Инструмент — его вызывает LLM",
  "Built-in behaviour": "Встроенное поведение",
  "Source code": "Код",
  "Double-click to copy": "Двойной клик — скопировать",
  "Copied": "Скопировано",
  "Copy failed": "Не удалось скопировать",
  "Stage": "Этап",
  "Store": "Хранилище",
  "Companion": "Компаньон",
  "A Minecraft NPC bound to one owner. It lives on the server and is driven by an LLM.": "NPC в Minecraft, привязанный к одному владельцу. Живёт на сервере и управляется LLM.",

  /* ---- domains ---- */
  "Hands": "Руки",
  "World skills: everything the companion physically does.": "Навыки в мире: всё, что компаньон делает физически.",
  "Mind": "Разум",
  "Goals, plans, memory and places: how it decides and remembers.": "Цели, планы, память и места: как он решает и запоминает.",
  "Senses & Voice": "Чувства и голос",
  "How it perceives the world, hears you and talks back.": "Как он воспринимает мир, слышит вас и отвечает.",

  /* ---- Movement ---- */
  "Movement": "Передвижение",
  "Block-grid A* pathfinding that walks, falls, swims, climbs, digs, pillars and bridges within a per-tick time budget.": "Поиск пути A* по сетке блоков: ходит, падает, плавает, лазает, копает, строит столбы и мосты в рамках бюджета времени на тик.",
  "Follow the owner at a chosen distance.": "Следовать за владельцем на выбранной дистанции.",
  "Hold the current position.": "Стоять на месте.",
  "Pathfind to coordinates.": "Дойти до координат.",
  "Walk through a lit portal, or build and light one from carried obsidian and flint; it records a return anchor.": "Пройти через зажжённый портал или построить и зажечь его из обсидиана и кремня; он запоминает якорь возврата.",
  "Jump once.": "Прыгнуть один раз.",
  "Auto, walk or run pace.": "Темп: авто, шаг или бег.",
  "Choose whether it follows when idle.": "Выбрать, следовать ли за владельцем в простое.",
  "Move by a relative offset.": "Сместиться на относительное расстояние.",
  "Retreat from a target.": "Отступить от цели.",
  "Walk up to a player, mob or block.": "Подойти к игроку, мобу или блоку.",
  "Circle around a point.": "Кружить вокруг точки.",
  "How close it stays to the owner.": "Как близко держаться к владельцу.",
  "A* pathfinder": "Поиск пути A*",
  "Incremental, resumable search that spreads work over ticks.": "Пошаговый поиск с возобновлением, распределяет работу по тикам.",
  "Dig, pillar, bridge": "Копать, столбить, мостить",
  "Breaks and places blocks when the way is blocked.": "Ломает и ставит блоки, если путь перекрыт.",
  "Breaks and places blocks when the way is blocked, and walks only a complete stair or bridge: it never stops half way up.":
    "Ломает и ставит блоки, когда путь перекрыт, и проходит только целиком построенную лестницу или мост: на полпути оно не останавливается.",
  "Stuck recovery": "Выход из застревания",
  "Re-plans when the world disagrees with the path.": "Перепланирует, если мир не совпал с маршрутом.",

  /* ---- Mining ---- */
  "Mining & Gathering": "Добыча и сбор",
  "Collects raw materials: trees, ores, drops. Tool choice comes from item data components, never hard-coded tiers.": "Собирает сырьё: деревья, руды, выпавшие предметы. Инструмент выбирается по компонентам предмета, а не по зашитым уровням.",
  "Fell a whole tree from the base when one stance reaches it, or from the crown.": "Срубить дерево целиком снизу, если до него можно дотянуться с одной позиции, или сверху.",
  "Mine up to 64 blocks of a type in one call; wood comes from whole felled trees.":
    "Добыть до 64 блоков одного типа за один вызов; древесину получает из целых сваленных деревьев.",
  "Mine the block you point at, follow its vein, and report what was taken.":
    "Добыть указанный блок, пройти по его жиле и сообщить, что подобрано.",
  "Mine a box, tunnel, stair, shaft or strip mine in one call, with drops and safety.":
    "Прорыть коробку, тоннель, лестницу, шахту или штрек за один вызов, с дропом и безопасностью.",
  "Collect every drop in the radius in one call.": "Подобрать все выпавшие предметы в радиусе за один вызов.",
  "Scan for trees.": "Найти деревья.",
  "Scan for blocks and ore veins.": "Найти блоки и рудные жилы.",
  "List blocks the companion placed earlier.": "Список блоков, поставленных компаньоном раньше.",
  "Vein follower": "Следование за жилой",
  "Mines a whole vein, not one block.": "Добывает всю жилу, а не один блок.",
  "Tool selection": "Выбор инструмента",
  "Picks the best tool from the stack's tool component.": "Берёт лучший инструмент по компоненту предмета.",
  "Safe digging": "Безопасное копание",
  "Checks support and hazards before approaching.": "Проверяет опору и опасности перед подходом.",
  "Catch platform": "Ловящая площадка",
  "Builds a block under a valuable drop over lava, a hole or the void, mines, then takes it back.":
    "Ставит блок под ценной добычей над лавой, ямой или пустотой, добывает и затем убирает его.",

  /* ---- Building ---- */
  "Building": "Строительство",
  "Places blocks through a fake player so vanilla rules and protection apply. Blueprints up to 32x32x32.": "Ставит блоки через фейкового игрока, поэтому действуют ванильные правила и защита. Чертежи до 32x32x32.",
  "Place one block.": "Поставить один блок.",
  "Right-click a block like a player.": "Нажать ПКМ по блоку как игрок.",
  "Place torches to stop mob spawns.": "Расставить факелы, чтобы не спавнились мобы.",
  "Clear vegetation, snow and stray blocks, up to 256 cells per call.": "Убирает растительность, снег и лишние блоки — до 256 клеток за вызов.",
  "Show a ghost preview.": "Показать призрачный предпросмотр.",
  "Build from a generated or saved layout.": "Построить по сгенерированной или сохранённой схеме.",
  "Store a layout as JSON.": "Сохранить схему как JSON.",
  "List saved blueprints.": "Список сохранённых чертежей.",
  "Explain the blueprint format.": "Объяснить формат чертежа.",
  "Build an iron golem, snow golem or nether portal.": "Построить железного голема, снежного голема или портал в Незер.",
  "Dry-run a special structure.": "Пробный прогон особой постройки.",
  "Blueprint parser": "Разбор чертежей",
  "Palette, ops and ASCII layers; reports every error at once.": "Палитра, операции и ASCII-слои; сообщает обо всех ошибках сразу.",
  "Resumable builds": "Возобновляемые стройки",
  "Progress survives restarts.": "Прогресс переживает перезапуск.",
  "Fake-player hands": "Руки фейкового игрока",
  "Respects world protection.": "Уважает защиту мира.",

  /* ---- Crafting ---- */
  "Crafting & Cooking": "Крафт и готовка",
  "Recursive crafting plans (depth up to 5), furnace jobs and enchanting-station work through real menus.": "Рекурсивные планы крафта (глубина до 5), работа с печами и станциями зачарования через настоящие меню.",
  "Craft an item, placing a table if needed.": "Скрафтить предмет, при необходимости поставив верстак.",
  "Look up how something is made.": "Узнать рецепт предмета.",
  "Smelt up to 256 items in one call with fuel.": "Переплавить до 256 предметов за один вызов с топливом.",
  "Cook raw food.": "Приготовить сырую еду.",
  "Pick up finished furnace output.": "Забрать готовое из печи.",
  "Use an enchanting table.": "Использовать стол зачарования.",
  "Repair or combine at an anvil.": "Починить или объединить на наковальне.",
  "Strip enchantments at a grindstone.": "Снять зачарования на точиле.",
  "Brew a supported potion at a brewing stand.": "Сварить поддерживаемое зелье на варочной стойке.",
  "Show the brewing steps without doing them.": "Показать шаги варки, ничего не делая.",
  "Craft planner": "Планировщик крафта",
  "Turns a goal item into ordered steps.": "Превращает целевой предмет в упорядоченные шаги.",
  "Station leases": "Аренда станций",
  "Remembers a table it placed for 600 ticks.": "Помнит поставленный верстак 600 тиков.",

  /* ---- Combat ---- */
  "Combat": "Бой",
  "Reflex combat ticked before everything else, with a single arbiter for movement, shields, bow, potions and retreat.": "Рефлекторный бой выполняется раньше всего; единый арбитр решает за движение, щит, лук, зелья и отступление.",
  "Attack a chosen mob.": "Атаковать выбранного моба.",
  "Attack a player (owner approval).": "Атаковать игрока (с согласия владельца).",
  "Self only, bodyguard, guard or avoid combat.": "Только себя, телохранитель, страж или избегать боя.",
  "Read the current style.": "Прочитать текущий стиль.",
  "Fighting style and retreat preference.": "Стиль боя и предпочтение отступления.",
  "Shield timing": "Тайминг щита",
  "Raises on a draw or arrow, lowers to swing.": "Поднимает при натяжении лука или стреле, опускает для удара.",
  "Bow aim with lead": "Прицел с упреждением",
  "Bounded predictive aim.": "Ограниченное упреждающее прицеливание.",
  "Potion use": "Использование зелий",
  "Drinks the right potion at the right time.": "Пьёт нужное зелье в нужный момент.",
  "Weapon choice": "Выбор оружия",
  "Scores weapons by DPS and durability.": "Оценивает оружие по DPS и прочности.",
  "Group pressure": "Давление группы",
  "Sidesteps away from a second attacker.": "Уходит в сторону от второго нападающего.",
  "Hostiles target it": "Враждебные цели",
  "Mobs treat it like a player.": "Мобы воспринимают его как игрока.",

  /* ---- Survival ---- */
  "Survival": "Выживание",
  "A vanilla-like hunger model, food rules you control, sleep, and fire handling. It keeps what it carries when it dies.": "Ванильная модель голода, ваши правила питания, сон и работа с огнём. После смерти он сохраняет то, что нёс.",
  "Eat suitable food.": "Съесть подходящую еду.",
  "Gather food.": "Добыть еду.",
  "Allow an item as food.": "Разрешить предмет как еду.",
  "Never eat an item.": "Никогда не есть предмет.",
  "Forget a food rule.": "Забыть правило питания.",
  "Show food rules.": "Показать правила питания.",
  "Sleep in a bed at night.": "Спать в кровати ночью.",
  "Put fires out with a water bucket.": "Тушить огонь ведром воды.",
  "Hunger model": "Модель голода",
  "Food, saturation, exhaustion, regeneration, starvation.": "Еда, насыщение, истощение, регенерация, голодание.",
  "Fire reflex": "Рефлекс на огонь",
  "Reacts to nearby flames automatically.": "Автоматически реагирует на огонь рядом.",
  "Self-care reflex": "Рефлекс самопомощи",
  "Spends its own water bucket, milk, golden apples and potions to stay alive.": "Тратит своё ведро воды, молоко, золотые яблоки и зелья, чтобы выжить.",
  "Death and respawn": "Смерть и возрождение",
  "Inventory and queue return with the new body.": "Инвентарь и очередь возвращаются с новым телом.",

  /* ---- Farming ---- */
  "Farming & Animals": "Ферма и животные",
  "Plans a field, plants, tends and harvests; works with animals and fishing.": "Планирует поле, сажает, ухаживает и собирает урожай; работает с животными и рыбалкой.",
  "Plant and harvest a whole field in one pass.": "За один проход засеять и собрать целое поле.",
  "Preview what a farm would need.": "Показать, что понадобится ферме.",
  "Keep an existing farm going.": "Поддерживать существующую ферму.",
  "Speed up growth.": "Ускорить рост.",
  "Breed a species.": "Развести вид.",
  "Shear sheep.": "Остричь овец.",
  "Milk up to 16 cows in one call.": "Подоить до 16 коров за один вызов.",
  "Tame an animal.": "Приручить животное.",
  "Lead animals with a lead.": "Вести животных на поводке.",
  "Collect honey.": "Собрать мёд.",
  "Go fishing.": "Пойти на рыбалку.",
  "Farm planner": "Планировщик фермы",
  "Cells, soil, crops, food need.": "Клетки, почва, культуры, потребность в еде.",
  "Re-plans on mismatch": "Перепланирование при расхождении",
  "Adjusts when the world differs from the plan.": "Подстраивается, если мир отличается от плана.",

  /* ---- Inventory ---- */
  "Inventory & Storage": "Инвентарь и хранилища",
  "A 27-slot inventory plus six gear slots, shared container logic, and a bounded memory of containers the companion has opened.": "Инвентарь на 27 слотов, шесть слотов снаряжения, общая логика контейнеров и ограниченная память о контейнерах, которые компаньон открывал.",
  "List what it carries.": "Показать, что он несёт.",
  "Open the inventory view.": "Открыть окно инвентаря.",
  "Equip gear.": "Надеть снаряжение.",
  "Hand items to a player.": "Передать предметы игроку.",
  "Hand items to a player, and finish work the owner asked for without asking again.": "Передать предметы игроку и завершить работу, которую просил владелец, не переспрашивая.",
  "Drop items.": "Выбросить предметы.",
  "Throw an item.": "Бросить предмет.",
  "Store up to two stacks in one call.": "Положить до двух стаков за один вызов.",
  "Take items out of a container.": "Достать предметы из контейнера.",
  "Look inside a container.": "Заглянуть в контейнер.",
  "Deposit into the nearest storage.": "Сложить в ближайшее хранилище.",
  "Report where you last saw an item in a recorded container.": "Сообщить, где предмет последний раз видели в запомненном контейнере.",
  "Move a known quantity between two containers in one call.": "Перенести известное количество между двумя контейнерами за один вызов.",
  "Equipment picker": "Выбор снаряжения",
  "Armor and weapon loadout.": "Набор брони и оружия.",
  "Owner-only window": "Окно только для владельца",
  "Within 8 blocks, same dimension.": "В пределах 8 блоков, то же измерение.",

  /* ---- Acquisition ---- */
  "Resource Acquisition": "Добыча ресурсов",
  "Ask for an item and it works out how: inventory, chests, craft, smelt, mine, hunt, harvest, shear, milk or explore.": "Попросите предмет, и он сам решит как: инвентарь, сундуки, крафт, плавка, добыча, охота, урожай, стрижка, дойка или разведка.",
  "Get an item by any means; wood is felled as whole trees.":
    "Получить предмет любым способом; древесину добывает, свалив деревья целиком.",
  "Gather every material for a set of items once, then craft them all; wood is felled as whole trees.":
    "Собрать все материалы для набора предметов один раз, а потом создать их все; древесину добывает, свалив деревья целиком.",
  "Show the plan without doing it.": "Показать план, не выполняя его.",
  "Explore to find a resource.": "Отправиться на поиск ресурса.",
  "What it already knows about.": "Что ему уже известно.",
  "Give it a hint where to look.": "Подсказать, где искать.",
  "Resolve a search target.": "Определить цель поиска.",
  "Find a village, fortress or stronghold by markers.": "Найти деревню, крепость или твердыню по маркерам.",
  "Travel to a verified structure instance.": "Дойти до проверенного экземпляра структуры.",
  "Obtain planner": "Планировщик добычи",
  "Depth up to 8, nine step kinds.": "Глубина до 8, девять видов шагов.",
  "Frontier search": "Поиск по границе",
  "Biome edges, water, hazards and trails.": "Границы биомов, вода, опасности и следы.",
  "Search budget": "Бюджет поиска",
  "Gives up sensibly.": "Вовремя сдаётся.",

  /* ---- Trading ---- */
  "Trading": "Торговля",
  "Villager and piglin trading with a pure policy and an authority check on spending.": "Торговля с жителями и пиглинами: чистая политика и проверка полномочий на траты.",
  "Locate villagers.": "Найти жителей.",
  "Read a trader's offers.": "Прочитать предложения торговца.",
  "Re-check offers.": "Перепроверить предложения.",
  "Wait until stock returns.": "Ждать пополнения запасов.",
  "List remembered trades.": "Список запомненных сделок.",
  "Plan a trade.": "Спланировать сделку.",
  "Execute a trade.": "Совершить сделку.",
  "Level a villager up.": "Прокачать жителя.",
  "Barter with piglins.": "Обмен с пиглинами.",
  "Spend authority": "Право тратить",
  "Ephemeral spending rights are revalidated.": "Временные права на траты перепроверяются.",
  "Idle refresh": "Обновление в простое",
  "Server refreshes traders while idle.": "Сервер обновляет торговцев в простое.",

  /* ---- Body ---- */
  "Body & Interaction": "Тело и взаимодействие",
  "Expression and contact: looking, gesturing, sitting, riding and using entities.": "Выразительность и контакт: взгляд, жесты, сидение, верховая езда и использование сущностей.",
  "Look at a target.": "Посмотреть на цель.",
  "Point at something.": "Указать на что-либо.",
  "Turn the body.": "Повернуть тело.",
  "Sneak.": "Красться.",
  "Play an emote.": "Показать эмоцию.",
  "Sit down.": "Сесть.",
  "Stand up.": "Встать.",
  "Wait some ticks.": "Подождать несколько тиков.",
  "Swap main and off hand.": "Поменять руки.",
  "Use an entity.": "Взаимодействовать с сущностью.",
  "Ride a mount.": "Сесть верхом.",
  "Get off.": "Слезть.",
  "What to do when idle.": "Что делать в простое.",
  "Single facing policy": "Единая политика поворота",
  "One writer for body yaw, head and pitch.": "Один источник для поворота тела, головы и наклона.",

  /* ---- Creative ---- */
  "Creative Mode": "Творческий режим",
  "Gated abilities for creative worlds. The owner decides; the mode revision is checked on every action.": "Закрытые способности для творческих миров. Решает владелец; ревизия режима проверяется при каждом действии.",
  "Spawn items (creative).": "Создать предметы (творческий).",
  "Fly to a point (creative).": "Полететь в точку (творческий).",
  "Run a command from the allowlist.": "Выполнить команду из белого списка.",
  "Command allowlist": "Белый список команд",
  "Only vetted commands run.": "Выполняются только проверенные команды.",
  "Owner-gated": "Доступ владельца",
  "Initiator and mode revision are checked.": "Проверяются инициатор и ревизия режима.",
  "Bounded counts": "Ограниченные количества",
  "Safe arithmetic for item requests.": "Безопасная арифметика для запросов предметов.",

  /* ---- Planning ---- */
  "Goals & Planning": "Цели и планирование",
  "The cognitive layer above the task queue: goals, a conditional plan queue, and free time when nobody is giving orders.": "Когнитивный слой над очередью задач: цели, условная очередь планов и свободное время, когда никто не отдаёт приказов.",
  "Set the main goal (owner).": "Поставить главную цель (владелец).",
  "Update progress.": "Обновить прогресс.",
  "Close a goal.": "Закрыть цель.",
  "Add a step.": "Добавить шаг.",
  "Show the queue.": "Показать очередь.",
  "Edit queued steps.": "Изменить шаги в очереди.",
  "Explain plan syntax.": "Объяснить синтаксис плана.",
  "Dry-run a plan.": "Пробный прогон плана.",
  "Unlock a tool group for this turn.": "Открыть группу инструментов на этот ход.",
  "Stop everything.": "Остановить всё.",
  "Begin autonomous time.": "Начать автономное время.",
  "End it.": "Закончить его.",
  "Pick what to do in free time.": "Выбрать занятие на свободное время.",
  "LLM planner": "Планировщик LLM",
  "Preliminary planning for complex orders; recovery for troubled plans, unmet goals and free time.": "Предварительное планирование сложных приказов; восстановление сорванных планов, недостигнутых целей и свободного времени.",
  "Conditional plans": "Условные планы",
  "If, repeat and guards over world facts.": "Условия, повторы и охранные условия по фактам мира.",
  "Outcome checker": "Проверка результата",
  "Compares before and after snapshots.": "Сравнивает снимки до и после.",
  "Recovery advisor": "Советник по восстановлению",
  "Classifies a failure and suggests a new remedy.": "Классифицирует сбой и предлагает новое средство.",
  "Quiet outcomes": "Тихие итоги",
  "Learns without narrating repeated status.": "Обучается без повторных сообщений о ходе работы.",
  "Speech gate": "Речевой фильтр",
  "Irrelevant status stays silent; a new blocker gets one line; a player question is always answered.": "Неважный статус молчит; новый блокер получает одну строку; вопрос игрока всегда получает ответ.",

  /* ---- Memory ---- */
  "Memory & Trust": "Память и доверие",
  "Per-owner memory with strict visibility: guests only see facts about themselves, and trust changes need the owner's confirmation.": "Память по владельцам со строгой видимостью: гости видят только факты о себе, а смена доверия требует подтверждения владельца.",
  "Read your recent activity log (owner only).": "Прочитать свой недавний журнал действий (только владелец).",
  "Search the durable log files by level, category, event, turn, task, trace, execution or request (owner only).": "Искать в файлах логов по уровню, категории, событию, ходу, задаче, трассе, исполнению или запросу (только владелец).",
  "Read recent conversations with a player.": "Прочитать недавние разговоры с игроком.",
  "See facts with ids and how full each book is.": "Факты с id и заполненность каждой книги.",
  "Merge duplicate facts safely, dry run first (owner only).": "Безопасно слить дубликаты фактов, сначала пробный запуск (только владелец).",
  "Drop a manner the owner dislikes and never drift back into it (owner only).": "Убрать манеру, которая не нравится владельцу, и не возвращаться к ней (только владелец).",
  "Conversation transcript": "Запись разговоров",
  "Both sides of every chat, redacted, capped by count and age.": "Обе стороны каждого разговора, секреты скрыты, ограничено по числу и возрасту.",
  "Store a fact.": "Запомнить факт.",
  "List the owner's skills with ids, versions and on/off state.":
    "Показать навыки владельца: идентификаторы, версии и включённость.",
  "Read the skill-authoring checklist and its limits.":
    "Прочитать памятку по написанию навыков и её ограничения.",
  "Read facts back.": "Вспомнить факты.",
  "Delete a fact.": "Удалить факт.",
  "Raise a player to trusted (needs confirmation).": "Сделать игрока доверенным (нужно подтверждение).",
  "Ask another owner's companion for bounded help.": "Попросить компаньона другого владельца о помощи.",
  "Accept the named help only.": "Принять только названную помощь.",
  "Decline with no transfer.": "Отклонить без передачи.",
  "Offer an exact item quantity at a meeting point.": "Предложить точное количество предметов в точке встречи.",
  "Accept only the named transfer.": "Принять только названную передачу.",
  "Ask the planner to repair one stalled plan.": "Попросить планировщика починить один застрявший план.",
  "Lower trust.": "Понизить доверие.",
  "Rename the companion.": "Переименовать компаньона.",
  "Load an instruction skill once.": "Один раз загрузить инструктивный навык.",
  "Propose a skill after a finished goal.": "Предложить навык после завершённой цели.",
  "Access levels": "Уровни доступа",
  "GUEST < TRUSTED < OWNER on every tool.": "GUEST < TRUSTED < OWNER для каждого инструмента.",
  "Language learning": "Изучение языка",
  "Learns your language after a few samples.": "Определяет ваш язык после нескольких фраз.",
  "Skills advise only": "Навыки лишь советуют",
  "They never change permissions.": "Они никогда не меняют права.",
  "Opt-in retrieval": "Выборка по запросу",
  "Default off; caller-visible facts selected by a local word match within a bounded budget.":
    "По умолчанию выключено; видимые собеседнику факты отбираются локальным совпадением слов в пределах ограниченного бюджета.",

  /* ---- Places ---- */
  "Places": "Места",
  "A book of named places. Autonomous work never breaks inside your places and takes only from containers in one; dig sites it registered itself are work areas.": "Книга именованных мест. Автономная работа не ломает ничего внутри ваших мест и берёт только из контейнеров в них; выкопанные им самим места — рабочие.",
  "Save the current spot.": "Сохранить текущее место.",
  "Walk to a named place.": "Дойти до именованного места.",
  "Rename it.": "Переименовать его.",
  "Delete it.": "Удалить его.",
  "Look one up.": "Найти место.",
  "Auto registration": "Автозапись",
  "Notes beds, bases and farms.": "Запоминает кровати, базы и фермы.",
  "Place guard": "Защита мест",
  "Protects your places during free time; a farm it tends and its own dig sites are work areas.": "Бережёт ваши места в свободное время; ферма, за которой он ухаживает, и его собственные выкопанные места — рабочие.",
  "Mine box": "Границы шахты",
  "A remembered mine covers the blocks it dug, not the radius it searched.": "Запомненная шахта охватывает выкопанные блоки, а не радиус поиска.",
  "Shift reports": "Отчёты о сменах",
  "One line per blocker (missing support, an own place, a tree it cannot climb); a repeat is silent.": "Одна строка на помеху (не хватило опоры, своё место, дерево, на которое не забраться); повтор молчит.",
  "Same dimension": "То же измерение",
  "go_to_place never crosses dimensions.": "go_to_place никогда не пересекает измерения.",

  /* ---- Perception ---- */
  "Perception": "Восприятие",
  "Turns the world into prompt text: surroundings, threats, landmarks and what the owner is looking at.": "Превращает мир в текст промпта: окружение, угрозы, ориентиры и то, на что смотрит владелец.",
  "Describe the surroundings.": "Описать окружение.",
  "What the owner sees, holds and wears.": "Что владелец видит, держит и носит.",
  "Inspect one block.": "Осмотреть один блок.",
  "Survey a region.": "Обследовать область.",
  "Brief radius 8": "Краткий радиус 8",
  "Cheap default perception.": "Дешёвое восприятие по умолчанию.",
  "Detailed radius 16": "Подробный радиус 16",
  "When more detail is needed.": "Когда нужно больше деталей.",
  "Owner focus": "Фокус владельца",
  "Where the owner stands and looks, what both hands hold and which armor they wear.": "Где владелец стоит и куда смотрит, что держит в обеих руках и какую броню носит.",
  "Landmarks and area map": "Ориентиры и карта района",
  "Relative directions and a compact map.": "Относительные направления и компактная карта.",

  /* ---- Chat ---- */
  "Chat & Addressing": "Чат и обращение",
  "Decides who is being spoken to and which companion hears it.": "Определяет, к кому обращаются и какой компаньон это слышит.",
  "Address detection": "Распознавание обращения",
  "Name or direct address.": "Имя или прямое обращение.",
  "Companion picker": "Выбор компаньона",
  "Own within hearing range, else nearest other.": "Свой в зоне слышимости, иначе ближайший чужой.",
  "Reply splitter": "Разбиение ответа",
  "Splits long replies into lines.": "Делит длинные ответы на строки.",
  "Trust confirmations": "Подтверждения доверия",
  "Owner confirms yes or no in chat.": "Владелец отвечает да или нет в чате.",
  "/friendai commands": "Команды /friendai",
  "summon, dismiss, stop, trust, memory, skills, reload.": "summon, dismiss, stop, trust, memory, skills, reload.",
  "English and Russian": "Английский и русский",
  "Interface and intent vocabularies.": "Словари интерфейса и намерений.",

  /* ---- Voice in ---- */
  "Voice Input": "Голосовой ввод",
  "Push-to-talk speech recognition. Local audio never leaves the machine unless you pick a cloud provider.": "Распознавание речи по нажатию клавиши. Локальное аудио не покидает компьютер, если вы не выберете облачного провайдера.",
  "Push-to-talk": "Push-to-talk",
  "16 kHz, 16-bit, mono capture.": "Захват 16 кГц, 16 бит, моно.",
  "Local Whisper": "Локальный Whisper",
  "Wake word": "Слово-активатор",
  "Off by default; an explicitly armed local detector, with bounded pre-wake audio.": "Выключено по умолчанию; локальный детектор включается вручную, аудио до активации ограничено.",
  "tiny, base or small, size and SHA-256 checked.": "tiny, base или small, проверка размера и SHA-256.",
  "Parakeet V3": "Parakeet V3",
  "25 European languages, offline.": "25 европейских языков, офлайн.",
  "Nemotron 3.5": "Nemotron 3.5",
  "Streaming partials, 19 locales.": "Потоковые частичные расшифровки, 19 локалей.",
  "GigaAM v3": "GigaAM v3",
  "Russian with punctuation, offline.": "Русский с пунктуацией, офлайн.",
  "Verified bundles": "Проверенные наборы",
  "Pinned revision, size and SHA-256, atomic install.": "Закреплённая ревизия, размер и SHA-256, атомарная установка.",
  "OpenAI STT": "OpenAI STT",
  "Cloud recognition.": "Облачное распознавание.",
  "Groq STT": "Groq STT",
  "Yandex STT": "Yandex STT",
  "Key in headers": "Ключ в заголовках",
  "Keys never go in URLs or logs.": "Ключи не попадают в URL и логи.",

  /* ---- Voice out ---- */
  "Speech Output": "Озвучка ответов",
  "Replies can be spoken locally or through a cloud voice, panned and attenuated by where the companion is.": "Ответы озвучиваются локально или облачным голосом, с панорамой и затуханием по положению компаньона.",
  "Local Piper voices": "Локальные голоса Piper",
  "Five catalogued voices via sherpa-onnx.": "Пять голосов из каталога через sherpa-onnx.",
  "Streamed sentences": "Предложения потоком",
  "Ordered per stream and session, with one cut-off report.": "По порядку в потоке и сессии, с одним сообщением об обрыве.",
  "Early sentence": "Ранняя фраза",
  "The first safe sentence speaks before the provider finishes; the full reply stays authoritative.": "Первая безопасная фраза звучит до завершения ответа; полный ответ остаётся главным.",
  "OpenAI TTS": "OpenAI TTS",
  "Cloud speech.": "Облачная речь.",
  "ElevenLabs": "ElevenLabs",
  "Yandex SpeechKit": "Yandex SpeechKit",
  "Spatial audio": "Пространственный звук",
  "Equal-power pan and distance level.": "Панорама равной мощности и уровень по расстоянию.",
  "Stop speaking key": "Клавиша «замолчать»",
  "Cuts playback instantly.": "Мгновенно прерывает воспроизведение.",
  "Off by default": "Выключено по умолчанию",
  "Opt in from settings.": "Включается в настройках.",

  /* ---- Menu ---- */
  "Companion Menu": "Меню компаньона",
  "A tabbed control screen that pauses the companion while open. Operator-only tabs are filtered on the server.": "Экран управления с вкладками; пока он открыт, компаньон на паузе. Вкладки только для операторов фильтруются на сервере.",
  "Status, health, hunger, task.": "Статус, здоровье, голод, задача.",
  "Toggles": "Переключатели",
  "Per-companion switches.": "Переключатели для компаньона.",
  "Tasks": "Задачи",
  "Queue and history.": "Очередь и история.",
  "Memory": "Память",
  "Browse and forget.": "Просмотр и удаление.",
  "Skills": "Навыки",
  "Instruction skills.": "Инструктивные навыки.",
  "Rename or forget places.": "Переименовать или забыть места.",
  "Map": "Карта",
  "Explored cells, sites and roads, never the full grid.": "Исследованные клетки, места и дороги; не вся карта.",
  "Plan controls": "Управление планом",
  "Pause, resume or defer the first plan from the menu.": "Пауза, продолжение или отсрочка первого плана из меню.",
  "Appearance": "Внешний вид",
  "Name and custom skin.": "Имя и свой скин.",
  "Traders": "Торговцы",
  "Known trades.": "Известные сделки.",
  "Log and Debug": "Лог и отладка",
  "Operator-only brain log with trace and execution prefixes, plus the incident bundle.":
    "Лог мозга только для операторов, с префиксами trace и execution, плюс пакет диагностики инцидента.",
  "Settings screen": "Экран настроек",
  "YACL, optional.": "YACL, необязательно.",

  /* ---- One turn ---- */
  "Player": "Игрок",
  "Types in chat or holds push-to-talk.": "Пишет в чат или удерживает push-to-talk.",
  "Voice STT": "Голос → текст",
  "Local Whisper or a cloud recogniser; the transcript is sent to the server.": "Локальный Whisper или облачный распознаватель; транскрипт уходит на сервер.",
  "ChatRouter": "ChatRouter",
  "Detects whether the companion is addressed.": "Определяет, обращаются ли к компаньону.",
  "Own companion in hearing range, else the nearest other whose owner is online.": "Свой компаньон в зоне слышимости, иначе ближайший другой, чей владелец в сети.",
  "CompanionBrain": "CompanionBrain",
  "The per-owner agent loop. One principal per turn; late replies are dropped by a generation counter.": "Цикл агента для каждого владельца. Один принципал на ход; запоздалые ответы отбрасывает счётчик поколений.",
  "TurnRouter": "TurnRouter",
  "Picks chat, action, goal, recovery or event, and which tool groups are visible; PlanningPolicy then names what a plan-worthy turn is about.": "Выбирает режим: чат, действие, цель, восстановление или событие, и какие группы инструментов видны; затем PlanningPolicy решает, о чём стоит планировать.",
  "Planner": "Планировщик",
  "Ordinary orders with enough context can answer and queue in one request. Complex orders retain preliminary planning; troubled plans, unmet goals and free time also use the planner.": "Обычные приказы с достаточным контекстом можно выполнить ответом и постановкой плана в очередь за один запрос. Сложные приказы по-прежнему используют предварительное планирование; сорванные планы, недостигнутые цели и свободное время тоже обращаются к планировщику.",
  "PromptBuilder": "PromptBuilder",
  "Identity, memory, perception, skills and mission, cut to a character budget.": "Личность, память, восприятие, навыки и миссия, обрезанные по лимиту символов.",
  "LLM gateway": "LLM-шлюз",
  "OpenAI chat and responses, Anthropic, Gemini, Ollama. Retries only retryable errors; keys stay in headers.": "OpenAI chat и responses, Anthropic, Gemini, Ollama. Повторяет только повторяемые ошибки; ключи остаются в заголовках.",
  "ToolRegistry": "ToolRegistry",
  "Validates arguments and access. Never throws: bad input becomes an error result.": "Проверяет аргументы и доступ. Не бросает исключений: плохой ввод становится результатом-ошибкой.",
  "Speaker": "Озвучка",
  "Posts the chat line and sends the speech packet to the client for TTS.": "Отправляет строку в чат и пакет речи клиенту для TTS.",
  "Task queue": "Очередь задач",
  "TaskRunner ticks one task at a time; guards, retries and plans live here.": "TaskRunner тикает одну задачу за раз; здесь живут охранные условия, повторы и планы.",
  "Minecraft world": "Мир Minecraft",
  "Changes happen only on the server thread, through the fake player.": "Изменения происходят только в серверном потоке, через фейкового игрока.",
  "mic": "микрофон",
  "message": "сообщение",
  "text": "текст",
  "system + history": "система + история",
  "plan-worthy?": "нужен план?",
  "queue plan": "план в очередь",
  "tool calls": "вызовы инструментов",
  "reply text": "текст ответа",
  "start task": "запуск задачи",
  "act": "действие",
  "TaskFinished": "TaskFinished",
  "result facts": "факты результата",

  /* ---- Architecture ---- */
  "Client": "Клиент",
  "Renderer, screens, HUD, voice capture and speech output.": "Отрисовка, экраны, HUD, захват голоса и озвучка.",
  "Server mod": "Серверная часть",
  "Owns the entity, brain, tasks and memory. The integrated server counts.": "Владеет сущностью, мозгом, задачами и памятью. Встроенный сервер тоже считается.",
  "Engine": "Движок",
  "Pure Java: no Minecraft, no Fabric, no native audio.": "Чистая Java: без Minecraft, Fabric и нативного аудио.",
  "LLM / STT / TTS providers": "Провайдеры LLM / STT / TTS",
  "Optional cloud endpoints. Keys travel in headers only.": "Необязательные облачные эндпоинты. Ключи передаются только в заголовках.",
  "payloads": "пакеты",
  "embeds + ports": "встраивание + порты",
  "LLM HTTP": "LLM по HTTP",
  "STT / TTS": "STT / TTS",
  "Engine module": "Модуль движка",
  "Reusable pure Java library, embedded once in the mod jar.": "Переиспользуемая чисто-явовская библиотека, один раз встроенная в jar мода.",
  "foundation": "foundation",
  "Text, limits, diagnostics, persistence contracts.": "Текст, лимиты, диагностика, контракты хранения.",
  "execution": "execution",
  "Queue, tool and history contracts.": "Контракты очереди, инструментов и истории.",
  "agent": "agent",
  "Turn loop, planner, prompt, LLM protocols.": "Цикл хода, планировщик, промпт, протоколы LLM.",
  "features": "features",
  "Pure policies per skill.": "Чистые политики для каждого навыка.",
  "infrastructure": "infrastructure",
  "Config, speech HTTP, persistence.": "Конфиг, HTTP речи, хранение.",
  "Server side": "Серверная сторона",
  "Server": "Сервер",
  "The only place that touches the world, and only on the server thread.": "Единственное место, которое трогает мир, и только в серверном потоке.",
  "One lifecycle per server.": "Один жизненный цикл на сервер.",
  "Sessions, gateways, planner adapter.": "Сессии, шлюзы, адаптер планировщика.",
  "Active task, queue driver, persistence.": "Активная задача, драйвер очереди, сохранение.",
  "Minecraft kernel: movement, blocks, food, targeting, placement.": "Ядро Minecraft: движение, блоки, еда, наведение, размещение.",
  "Tasks and tools per skill.": "Задачи и инструменты каждого навыка.",
  "CompanionEntity and manager.": "CompanionEntity и менеджер.",
  "Protocol and commands.": "Протокол и команды.",
  "Two, so hostiles target it.": "Два — чтобы враждебные мобы целились в него.",
  "Client side": "Клиентская сторона",
  "Reads only data the server syncs.": "Читает только данные, синхронизированные сервером.",
  "Humanoid model, skin, armour.": "Гуманоидная модель, скин, броня.",
  "Control screen and tabs.": "Экран управления и вкладки.",
  "Mic, push-to-talk, STT runtime.": "Микрофон, push-to-talk, рантайм STT.",
  "Speech playback, spatial mix.": "Воспроизведение речи, пространственный микс.",
  "Local models and native binding.": "Локальные модели и нативная привязка.",
  "YACL screen, optional.": "Экран YACL, необязательно.",
  "Rules that keep it working": "Правила, на которых всё держится",
  "Checked by tests and reviews": "Проверяются тестами и ревью",
  "Rules": "Правила",
  "Violating any of these breaks the mod in subtle ways.": "Нарушение любого из них тонко ломает мод.",
  "Server thread only": "Только серверный поток",
  "World access never leaves it.": "Доступ к миру никогда не покидает его.",
  "Late replies dropped": "Запоздалые ответы отбрасываются",
  "Generation counter after stop or logout.": "Счётчик поколений после остановки или выхода.",
  "Bad tool args": "Плохие аргументы инструмента",
  "Become an error result, never an exception.": "Становятся результатом-ошибкой, а не исключением.",
  "Ordered tool calls": "Упорядоченные вызовы",
  "Anthropic and Gemini get one merged result message.": "Anthropic и Gemini получают одно объединённое сообщение с результатами.",
  "Keys never leak": "Ключи не утекают",
  "Not in logs, URLs, toasts or toString.": "Ни в логах, ни в URL, ни в уведомлениях, ни в toString.",
  "Layer boundary": "Граница слоёв",
  "Pure code never imports Minecraft.": "Чистый код никогда не импортирует Minecraft.",
  "Cheap per tick": "Дёшево на тик",
  "Bounded work, resumable state.": "Ограниченная работа, возобновляемое состояние.",

  /* ---- Memory & data ---- */
  "Per-owner files": "Файлы владельца",
  "<world>/friendai/ - one file per owner UUID, atomic writes, .bak recovery": "<world>/friendai/ — по файлу на UUID владельца, атомарная запись, восстановление из .bak",
  "Owner data": "Данные владельца",
  "Memory, profile, goals and free-time stores share one bounded runtime writer; it flushes latest-revision snapshots on a 5 s server-tick cadence. Unsupported versions are never overwritten.":
    "Память, профиль, цели и свободное время пишет один ограниченный по работе компонент: он сбрасывает снимки последней ревизии раз в 5 секунд по тикам сервера. Неподдерживаемые версии никогда не перезаписываются.",
  "Facts about players, trust, learned language, food rules.": "Факты об игроках, доверие, выученный язык, правила питания.",
  "Companion profile": "Профиль компаньона",
  "Name, description, language and address.": "Имя, описание, язык и обращение.",
  "Goals": "Цели",
  "One primary and three background goals, lessons, replan budget.": "Одна главная и три фоновые цели, уроки, бюджет перепланирования.",
  "Free time": "Свободное время",
  "Shifts, history, cooldowns, away summaries, and shift.start/end/report records in the durable log.": "Смены, история, перезарядки, сводки об отсутствии и записи shift.start/end/report в постоянном логе.",
  "Instruction skills": "Инструктивные навыки",
  "19 builtins, global and owner records; list_skills and skill_help read, manage_skill writes.":
    "19 встроенных, общие и владельческие записи; list_skills и skill_help читают, manage_skill записывает.",
  "Named places with aliases, dimension and visits.": "Именованные места с псевдонимами, измерением и посещениями.",
  "Placed blocks": "Поставленные блоки",
  "Ledger of blocks it placed (cap 512).": "Журнал поставленных им блоков (лимит 512).",
  "Knowledge": "Знания",
  "Observed resources.": "Замеченные ресурсы.",
  "Trades": "Сделки",
  "Known villager trades.": "Известные сделки жителей.",
  "Blueprints": "Чертежи",
  "Saved layouts as JSON.": "Сохранённые схемы в JSON.",
  "In the world save": "В сохранении мира",
  "Companion entity + SavedData": "Сущность компаньона + SavedData",
  "Companion entity": "Сущность компаньона",
  "Saved with the world; versioned by Mojang's entity data.": "Сохраняется вместе с миром; версионируется данными сущностей Mojang.",
  "Inventory 27 slots": "Инвентарь, 27 слотов",
  "Plus six gear slots.": "Плюс шесть слотов снаряжения.",
  "Hunger and XP": "Голод и опыт",
  "Food, saturation, exhaustion, level.": "Еда, насыщение, истощение, уровень.",
  "Combat and pace": "Бой и темп",
  "Mode, style, pace.": "Режим, стиль, темп.",
  "Saved task queue": "Сохранённая очередь задач",
  "Bounded checkpoints, restored by stable keys.": "Ограниченные чекпоинты, восстанавливаются по стабильным ключам.",
  "Carry data": "Данные переноски",
  "Body captured at death + last five deaths.": "Тело, снятое при смерти, и последние пять смертей.",
  "Anchors": "Якоря",
  "Owner to chunk, used to load on startup.": "Владелец → чанк, для загрузки при старте.",
  "Settings & caches": "Настройки и кэши",
  "config/ and the game directory": "config/ и каталог игры",
  "Settings": "Настройки",
  "API keys may be written as env:NAME so they never sit in a file.": "Ключи API можно писать как env:NAME, чтобы они не лежали в файле.",
  "Server config v22": "Серверный конфиг v22",
  "Automatic config upgrades": "Автоматическое обновление конфигурации",
  "Validated upgrades with unique backups; unknown and future formats stay read-only.": "Проверка перед обновлением и отдельные резервные копии; неизвестные и будущие форматы остаются только для чтения.",
  "Tasks, agent, places, autonomy, chunk loading, build pace.": "Задачи, агент, места, автономность, загрузка чанков, темп строительства.",
  "Client config v9": "Клиентский конфиг v9",
  "Voice recognition and synthesis, UI.": "Распознавание и синтез речи, интерфейс.",
  "Speech models": "Модели речи",
  "Whisper plus Parakeet, Nemotron and GigaAM; pinned hashes.": "Whisper, Parakeet, Nemotron и GigaAM; закреплённые хэши.",
  "Piper voices": "Голоса Piper",
  "Five catalogued voices.": "Пять голосов из каталога.",
  "Skins": "Скины",
  "Fetched and cached by hash.": "Скачиваются и кэшируются по хэшу.",
  "Atomic save": "Атомарное сохранение",
  "Temp file then move.": "Временный файл, затем перемещение.",
  "The memory book": "Книга памяти",
  "What it remembers and who may see it": "Что он помнит и кто это может видеть",
  "Rights are checked per subject on every read, write and forget.": "Права проверяются по субъекту при каждом чтении, записи и удалении.",
  "Facts": "Факты",
  "Per subject, five categories.": "По субъектам, пять категорий.",
  "Player records": "Записи об игроках",
  "Trust level per player.": "Уровень доверия для каждого игрока.",
  "Learned language": "Выученный язык",
  "First confident sample, switches after three.": "Первый уверенный образец; смена после трёх подряд.",
  "Food rules": "Правила питания",
  "Allow and forbid lists.": "Списки разрешённого и запрещённого.",
  "Guest privacy": "Приватность гостей",
  "A guest sees only facts about themself.": "Гость видит только факты о себе.",
  "Trust confirmation": "Подтверждение доверия",
  "Owner confirms changes.": "Владелец подтверждает изменения.",
  "Goals & free time": "Цели и свободное время",
  "What it does when nobody gives orders": "Что он делает, когда никто не даёт приказов",
  "Autonomy": "Автономность",
  "Owner work, an active owner goal and the quiet period after you speak always come first.": "Задачи владельца, его активная цель и тихий период после вашей реплики всегда в приоритете.",
  "Primary goal": "Главная цель",
  "With criteria and a replan budget.": "С критериями и бюджетом перепланирования.",
  "Background goals": "Фоновые цели",
  "Up to three more.": "Ещё до трёх.",
  "Lessons": "Уроки",
  "Up to five short notes.": "До пяти коротких заметок.",
  "Reflex shifts": "Рефлекторные смены",
  "Hunger, sleep, shelter, unload, farm.": "Голод, сон, укрытие, разгрузка, ферма.",
  "Director shifts": "Смены директора",
  "LLM chooses, budgeted per hour.": "Выбирает LLM, бюджет по часам.",
  "Fallback ladder": "Запасная лестница",
  "Wood, stone, iron, diamond.": "Дерево, камень, железо, алмаз.",
  "Idle watchdog": "Сторожевой таймер простоя",
  "Closes stuck shifts.": "Закрывает зависшие смены.",
  "Away summary": "Сводка об отсутствии",
  "Routines": "Расписания",
  "One owner-set timetable with a phase or interval, saved across restarts.": "Одно расписание владельца с фазой дня или интервалом, сохраняется между перезапусками.",
  "Tells you what happened.": "Рассказывает, что произошло."
};

/* ---- reference pages: testing, logging, debugging, protocol, persistence, CI ---- */
Object.assign(RU, {
  /* ---- chrome ---- */
  "The map": "Карта",
  "Internals": "Внутренности",
  "Filter sections": "Фильтр разделов",
  "Previous section": "Предыдущий раздел",
  "Next section": "Следующий раздел",
  "Sections": "Разделы",
  "GameTest classes": "классов GameTest",
  "CI shards": "шардов CI",
  "COMPANION MAP": "КАРТА КОМПАНЬОНА",
  "BUILT FROM THE TOOL CATALOG AND ARCHITECTURE.MD": "СОБРАНО ИЗ КАТАЛОГА ИНСТРУМЕНТОВ И ARCHITECTURE.MD",
  "Testing": "Тестирование",
  "Logging": "Логирование",
  "Debug & Diagnostics": "Отладка и диагностика",
  "Network Protocol": "Сетевой протокол",
  "Config & Persistence": "Настройки и сохранения",
  "Build, CI & Release": "Сборка, CI и релиз",

  /* ---- testing ---- */
  "Two suites, one gate": "Два набора тестов, один шлюз",
  "Pure logic is proven by unit tests, world behaviour by in-game GameTests, and both run in CI as one required check. While working, run only the group that covers your change; the full suite belongs to CI.":
    "Чистая логика доказывается юнит-тестами, поведение в мире — GameTest'ами, и оба набора входят в один обязательный шлюз CI. Во время работы запускайте только группу, покрывающую изменение; полный прогон — дело CI.",
  "Engine unit tests": "Юнит-тесты движка",
  "`engine/src/test`: queue, planner, policies, LLM protocols, config migration. No Minecraft, no Fabric.":
    "`engine/src/test`: очередь, планировщик, политики, протоколы LLM, миграции конфигурации. Без Minecraft и Fabric.",
  "Root unit tests": "Юнит-тесты мода",
  "`src/test`: server adapters, tools and features, run without booting a game.":
    "`src/test`: серверные адаптеры, инструменты и фичи, без запуска игры.",
  "111 classes in the separate `friendai-gametest` mod, in one shared world, six CI shards.":
    "111 классов в отдельном моде `friendai-gametest`, в общем мире, шесть шардов CI.",
  "Client UI test": "Тест клиентского интерфейса",
  "`runClientGameTest` drives the real YACL screen and checks both UI languages.":
    "`runClientGameTest` управляет настоящим экраном YACL и проверяет оба языка интерфейса.",
  "The required check: unit suites, GameTest shards, formatting, packaging, shard map and docs links.":
    "Обязательная проверка: юнит-наборы, шарды GameTest, форматирование, сборка, карта шардов и ссылки в документации.",
  "display": "экран",
  "required": "обязательно",
  "Unit suites": "Юнит-наборы",
  "JUnit 5, hand-written fakes, no mocking framework": "JUnit 5, рукописные фейки, без библиотек моков",
  "Fast tests that pin behaviour, boundaries, state transitions and failure paths.":
    "Быстрые тесты, закрепляющие поведение, границы, переходы состояний и пути отказа.",
  "Engine suite": "Набор движка",
  "`engine/src/test` proves pure logic: plan queue, pathfinding, combat policy, protocols, migrations.":
    "`engine/src/test` доказывает чистую логику: очередь планов, поиск пути, боевая политика, протоколы, миграции.",
  "Root suite": "Набор мода",
  "`src/test` proves Minecraft-side adapters and features without starting a game.":
    "`src/test` доказывает адаптеры и фичи на стороне Minecraft без запуска игры.",
  "Test fixtures": "Тестовые фикстуры",
  "`engine/src/testFixtures` holds reusable fakes; they never enter the shipped jar.":
    "`engine/src/testFixtures` хранит переиспользуемые фейки; они не попадают в собранный jar.",
  "Reusable fakes": "Переиспользуемые фейки",
  "Package rules": "Правила пакетов",
  "`PackageDependencyRules` fails the build when the engine imports Minecraft.":
    "`PackageDependencyRules` валит сборку, если движок импортирует Minecraft.",
  "Scenario baselines": "Сценарные эталоны",
  "`ScenarioBaselineTest` replays JSON scenarios through the real turn loop with fakes.":
    "`ScenarioBaselineTest` прогоняет JSON-сценарии через настоящий цикл хода с фейками.",
  "A separate mod, one shared world, six CI shards": "Отдельный мод, один общий мир, шесть шардов CI",
  "In-game tests that drive the real server thread, entity, tasks and world.":
    "Тесты в игре, которые работают с настоящим серверным потоком, сущностью, задачами и миром.",
  "Separate mod": "Отдельный мод",
  "`friendai-gametest` registers 111 classes in `src/gametest/resources/fabric.mod.json`.":
    "`friendai-gametest` регистрирует 111 классов в `src/gametest/resources/fabric.mod.json`.",
  "Shards": "Шарды",
  "`.github/gametest-shards.json` splits them into six CI shards, one class per server boot.":
    "`.github/gametest-shards.json` делит их на шесть шардов CI, по одному классу на запуск сервера.",
  "Shard check": "Проверка шардов",
  "`verifyGametestShards` fails a class that is missing, listed twice or unknown.":
    "`verifyGametestShards` валит класс, которого нет в карте, который указан дважды или неизвестен.",
  "Shared world": "Общий мир",
  "Each test owns its block type and arena; counts are filtered by type.":
    "Каждый тест берёт свой тип блока и свою арену; подсчёты фильтруются по типу.",
  "`platform`, `platformAt`, `enclose` and `mockPlayerAt` build safe arenas and players.":
    "`platform`, `platformAt`, `enclose` и `mockPlayerAt` строят безопасные арены и игроков.",
  "Records one line per tick; a hang prints its last ticks and CI uploads the log.":
    "Пишет строку на тик; зависание печатает последние тики, а CI загружает лог.",
  "Filtered runs": "Запуск с фильтром",
  "One glob: `-Dfabric-api.gametest.filter=friendai-gametest:<class>_tests_*`.":
    "Один глоб: `-Dfabric-api.gametest.filter=friendai-gametest:<class>_tests_*`.",
  "What each kind proves": "Что доказывает каждый вид",
  "Golden files, language files, the tool catalog": "Эталоны, языковые файлы, каталог инструментов",
  "Proof": "Доказательство",
  "Each check guards a different failure, and each one can fail.":
    "Каждая проверка сторожит свой отказ, и каждая умеет падать.",
  "Prompt goldens": "Эталоны промпта",
  "`PromptGoldenTest` pins the system prompt; `updatePromptGolden` rewrites it on purpose.":
    "`PromptGoldenTest` закрепляет системный промпт; `updatePromptGolden` переписывает его осознанно.",
  "Language files": "Языковые файлы",
  "`LangFilesTest` compares every locale, every key and every placeholder, and flags unknown keys.":
    "`LangFilesTest` сравнивает все локали, все ключи и все подстановки и ловит неизвестные ключи.",
  "Tool catalog": "Каталог инструментов",
  "A GameTest fails until every registered tool has a catalog entry.":
    "GameTest падает, пока у каждого зарегистрированного инструмента нет записи в каталоге.",
  "Client UI": "Клиентский интерфейс",
  "`runClientGameTest` checks fields, transitions, save/cancel/reset and all eight shipped languages.":
    "`runClientGameTest` проверяет поля, переходы, сохранение, отмену, сброс и все восемь поставляемых языков.",
  "Break it once": "Сломай один раз",
  "A test written beside its code is proven by breaking the guarded line and watching the red.":
    "Тест, написанный рядом с кодом, доказывается поломкой охраняемой строки и красным прогоном.",
  "A green first run": "Зелёный первый прогон",
  "Proves nothing on its own: that is why the break-it step is part of the batch.":
    "Сам по себе ничего не доказывает: поэтому шаг «сломай» входит в ту же пачку.",
  "The gates": "Шлюзы",
  "What CI requires before anything merges": "Что требует CI до слияния",
  "Gates": "Шлюзы",
  "Nothing merges on a red or pending gate.": "Ничего не сливается на красном или незавершённом шлюзе.",
  "Unit suites, all GameTest shards, `spotlessCheck`, packaging, the shard map and docs links.":
    "Юнит-наборы, все шарды GameTest, `spotlessCheck`, сборка, карта шардов и ссылки в документации.",
  "Commit lint gate": "Шлюз коммитов",
  "Conventional commits and a conventional PR title.": "Conventional commits и conventional заголовок PR.",
  "Docs links": "Проверка ссылок",
  "`scripts/check-docs.sh` checks relative links, anchors and table cell counts.":
    "`scripts/check-docs.sh` проверяет относительные ссылки, якоря и число ячеек в таблицах.",
  "Workflow lint": "Линт воркфлоу",
  "`actionlint` and `shellcheck` over the workflows and scripts.":
    "`actionlint` и `shellcheck` по воркфлоу и скриптам.",
  "`actionlint` and `shellcheck` over workflows and scripts.":
    "`actionlint` и `shellcheck` по воркфлоу и скриптам.",
  "Nightly": "Ночной прогон",
  "The whole suite in one server, the only cross-class run.":
    "Весь набор в одном сервере — единственный прогон, где классы встречаются.",
  "A tag must match `mod_version`, sit on `origin/main` and have a green CI run.":
    "Тег должен совпадать с `mod_version`, стоять на `origin/main` и иметь зелёный прогон CI."
});

/* ---- logging ---- */
Object.assign(RU, {
  "One record per thing that happened": "Одна запись на каждое событие",
  "Every brain turn, tool call, task and shift writes a durable JSONL record under `logs/friendai/`. The same record carries a `cause` link, so an error can be walked back to the turn that started it.":
    "Каждый ход, вызов инструмента, задача и смена пишут долговечную запись JSONL в `logs/friendai/`. У записи есть ссылка `cause`, поэтому ошибку можно проследить назад до хода, который её начал.",
  "Every brain turn, tool call, task and shift writes a durable JSONL record under the current world's `friendai/logs/`. The same record carries a `cause` link, so an error can be walked back to the turn that started it.":
    "Каждый ход, вызов инструмента, задача и смена пишут долговечную запись JSONL в `friendai/logs/` текущего мира. У записи есть ссылка `cause`, поэтому ошибку можно проследить назад до хода, который её начал.",
  "Brain, tool, task, shift": "Мозг, инструмент, задача, смена",
  "Callers emit typed records: identity keys, level, category, event, bounded data.":
    "Источники пишут типизированные записи: ключи идентичности, уровень, категорию, событие, ограниченные данные.",
  "Redacts secrets first, then truncates, then fans out to every sink.":
    "Сначала вычищает секреты, потом обрезает, потом раздаёт во все приёмники.",
  "DebugLog ring": "Кольцо DebugLog",
  "Bounded live buffer behind the Log tab and `read_log`.":
    "Ограниченный живой буфер для вкладки Log и `read_log`.",
  "JSONL files": "Файлы JSONL",
  "Server, per-owner and client trees with rotation, retention and a generated digest.":
    "Деревья сервера, владельцев и клиента с ротацией, хранением и сгенерированным дайджестом.",
  "Digest .md": "Дайджест .md",
  "A closed file's chain rendered as prose; safe to delete, regenerated on demand.":
    "Цепочка закрытого файла, изложенная прозой; можно удалять, создаётся заново.",
  "Search and diagnose": "Поиск и диагностика",
  "`logs_search`, `/friendai diagnose`, the Log tab and the digest read the same records.":
    "`logs_search`, `/friendai diagnose`, вкладка Log и дайджест читают одни и те же записи.",
  "live": "живой",
  "prose": "прозой",
  "`<world>/friendai/logs/` plus `<gameDir>/logs/friendai/`": "`<world>/friendai/logs/` и `<gameDir>/logs/friendai/`",
  "Each world owns its server tree and the shared root keeps the client records and the world index; `/friendai logs path` and the Open log folder button point at the current world.":
    "У каждого мира своё серверное дерево, а общий корень хранит клиентские записи и индекс миров; `/friendai logs path` и кнопка открытия папки указывают на текущий мир.",
  "Lifecycle records and every warn or error from `friendai*` loggers, inside the world.":
    "Записи жизненного цикла и все warn/error логгеров `friendai*`, внутри мира.",
  "One owner's brain, tool, task and queue records, inside the world.":
    "Записи мозга, инструментов, задач и очереди одного владельца, внутри мира.",
  "One line per world load and close: time, world, path, boot and versions.":
    "Одна строка на загрузку и закрытие мира: время, мир, путь, boot и версии.",
  "The `/friendai diagnose` bundle, inside the world.": "Пакет `/friendai diagnose`, внутри мира.",
  "One world, one tree": "Один мир — одно дерево",
  "A companion can only search the world it lives in; a busy world cannot prune another's history.":
    "Компаньон ищет только в мире, где живёт; активный мир не вытесняет историю другого.",
  "Before 0.203.0": "До 0.203.0",
  "`server/` and `owners/` left at the shared root stay untouched and unread.":
    "`server/` и `owners/` в общем корне остаются нетронутыми и не читаются.",
  "50 MiB per world, 7 days, 20 sessions per owner by default.":
    "50 МиБ на мир, 7 дней, 20 сессий на владельца по умолчанию.",
  "Where the files are": "Где лежат файлы",
  "Log tree": "Дерево логов",
  "`/friendai logs path` and the Open log folder button are authoritative; the folder can be anywhere.":
    "`/friendai logs path` и кнопка открытия папки — истина в последней инстанции; папка может быть где угодно.",
  "Lifecycle records and every warn or error from `friendai*` loggers.":
    "Записи жизненного цикла и все warn/error логгеров `friendai*`.",
  "One owner's brain, tool, task and queue records.":
    "Записи мозга, инструментов, задач и очереди одного владельца.",
  "Client and speech records, written on the client only.":
    "Клиентские записи и записи речи — только на клиенте.",
  "The `/friendai diagnose` bundle.": "Пакет `/friendai diagnose`.",
  "A generated one-page schema, event table and jq recipes.":
    "Сгенерированная одностраничная схема, таблица событий и рецепты jq.",
  "Singleplayer": "Одиночная игра",
  "The client folder equals the server folder only in singleplayer.":
    "Папка клиента совпадает с папкой сервера только в одиночной игре.",
  "What a record holds": "Что содержит запись",
  "Fields worth knowing when you are reading": "Поля, которые стоит знать при чтении",
  "Record": "Запись",
  "One JSON object per line, with a header on line one.":
    "Один JSON-объект на строку, заголовок в первой строке.",
  "seq and cause": "seq и cause",
  "`cause` is the seq of the record that caused this one, so a chain is walkable backwards.":
    "`cause` — это seq записи, вызвавшей эту, так что цепочку можно идти назад.",
  "Identity keys": "Ключи идентичности",
  "category and event": "категория и событие",
  "`brain`, `tool`, `task`, `queue`, `speech`, `log` and their event names.":
    "`brain`, `tool`, `task`, `queue`, `speech`, `log` и имена их событий.",
  "level": "уровень",
  "durationMs and data": "durationMs и данные",
  "Bounded values, redacted before they are truncated.":
    "Ограниченные значения, вычищенные до обрезки.",
  "Spans and timings": "Спаны и тайминги",
  "`TaskLogSpan`, `ExecutionTiming`, holds versus gaps, bounded stall snapshots.":
    "`TaskLogSpan`, `ExecutionTiming`, удержания против пауз, ограниченные снимки зависаний.",
  "Levels, retention and privacy": "Уровни, хранение и приватность",
  "What is written, how much, and what never is": "Что пишется, сколько и что — никогда",
  "Policy": "Политика",
  "Configured keys become `***` before any sink, on the pipeline and on the log4j bridge.":
    "Настроенные ключи становятся `***` до любого приёмника — и в конвейере, и в мосте log4j.",
  "`normal` records metadata only; `detailed` adds prompt and reply bodies after redaction.":
    "`normal` пишет только метаданные; `detailed` добавляет тексты промпта и ответа после вычистки.",
  "Rotation": "Ротация",
  "8 MiB per file, then `-2` and `-3` parts.": "8 МиБ на файл, затем части `-2` и `-3`.",
  "Retention": "Хранение",
  "50 MiB total, 7 days, 20 sessions per owner by default.":
    "50 МиБ всего, 7 дней, 20 сессий на владельца по умолчанию.",
  "Never block": "Никогда не блокирует",
  "The writer drops records instead of stalling the server and writes one `log.dropped` line.":
    "Писатель теряет записи вместо остановки сервера и пишет одну строку `log.dropped`.",
  "Torn last line": "Оборванная последняя строка",
  "A crash-truncated line is skipped by readers instead of breaking the file.":
    "Обрезанная падением строка пропускается читателями, а не ломает файл.",
  "Detailed is a decision": "Detailed — это решение",
  "Do not raise it on a server carrying other people's chat without their knowledge.":
    "Не поднимайте его на сервере с чужим чатом без ведома этих людей.",
  "Reading it back": "Как это читать",
  "Four ways in, one source of truth": "Четыре входа, один источник истины",
  "Readers": "Читатели",
  "The ring, the files and the bundle all project the same records.":
    "Кольцо, файлы и пакет показывают одни и те же записи.",
  "Log tab": "Вкладка Log",
  "Operator-only live tail with trace and execution prefixes; Pause freezes it.":
    "Живой хвост только для оператора, с префиксами trace и execution; Pause его замораживает.",
  "The companion reads its own recent ring, owner only.":
    "Компаньон читает своё недавнее кольцо, только владелец.",
  "Owner-only search by level, category, event, turn, task, trace, execution or request.":
    "Поиск только для владельца по уровню, категории, событию, ходу, задаче, trace, execution или запросу.",
  "Digest": "Дайджест",
  "A `.md` beside each closed file renders the same chain as prose.":
    "`.md` рядом с каждым закрытым файлом излагает ту же цепочку прозой.",
  "Commands": "Команды",
  "Never paste unread": "Не вставляйте непрочитанное",
  "A bundle can hold coordinates and, at `detailed`, prompt text.":
    "Пакет может содержать координаты и, при `detailed`, текст промпта."
});

/* ---- debugging and diagnostics ---- */
Object.assign(RU, {
  "Three answers to 'what just happened'": "Три ответа на вопрос «что только что было»",
  "The live ring answers now, the files answer after the fact, and a bundle answers a report. All three carry the same identity keys, so a menu row, a log line and an incident timeline line up.":
    "Живое кольцо отвечает сейчас, файлы — после факта, а пакет — на отчёт. У всех трёх одни и те же ключи идентичности, поэтому строка меню, строка лога и таймлайн инцидента совпадают.",
  "Something looks wrong": "Что-то не так",
  "The companion did not do what you asked, stopped, or repeated itself.":
    "Компаньон сделал не то, остановился или повторяется.",
  "Bounded live buffer, redacted then truncated; the Log tab tails it.":
    "Ограниченный живой буфер, вычищенный и обрезанный; вкладка Log следит за ним.",
  "The durable tree with `cause` links between records.":
    "Долговечное дерево со связями `cause` между записями.",
  "A tick-by-tick timeline written by a GameTest; a hang leaves it in the shard log.":
    "Таймлайн по тикам, который пишет GameTest; зависание оставляет его в логе шарда.",
  "Operator-only live view with pause.": "Живой вид только для оператора, с паузой.",
  "diagnose bundle": "Пакет diagnose",
  "Manifest, event excerpt and incident timeline, bounded and partial-aware.":
    "Манифест, выдержка событий и таймлайн инцидента — ограниченные и с пометкой о неполноте.",
  "CI artifact": "Артефакт CI",
  "A failing shard uploads `build/gametest-shards/<class>.log`.":
    "Упавший шард загружает `build/gametest-shards/<class>.log`.",
  "A report someone can act on": "Отчёт, по которому можно действовать",
  "Evidence first: the failing record, its chain, and what the task was doing.":
    "Сначала доказательства: упавшая запись, её цепочка и то, чем занималась задача.",
  "The live ring": "Живое кольцо",
  "What the operator sees while it happens": "Что видит оператор в момент события",
  "Live": "Живое",
  "A bounded buffer, not a file: it is fast, lossy and redacted.":
    "Ограниченный буфер, а не файл: быстрый, с потерями и вычищенный.",
  "Bounded ring, redacted then truncated, and it never reuses the file sequence.":
    "Ограниченное кольцо, вычищенное и обрезанное; последовательность файла не переиспользуется.",
  "Operator tail with trace and execution prefixes; Pause freezes it.":
    "Хвост для оператора с префиксами trace и execution; Pause его замораживает.",
  "Debug tab": "Вкладка Debug",
  "Incident bundle, session facts and the per-connection movement overlay switch.":
    "Пакет инцидента, факты сессии и переключатель оверлея движения для соединения.",
  "`onReply` reports the text a player actually heard, not the draft.":
    "`onReply` сообщает текст, который игрок действительно услышал, а не черновик.",
  "Writes every prompt line, tool call, result, provider error and token usage.":
    "Пишет каждую строку промпта, вызов инструмента, результат, ошибку провайдера и расход токенов.",
  "The incident bundle": "Пакет инцидента",
  "Bundle": "Пакет",
  "Written atomically into its own bounded directory with retention.":
    "Пишется атомарно в свой ограниченный каталог с хранением.",
  "Versions, masked config, session identity.": "Версии, маскированный конфиг, идентичность сессии.",
  "A record-boundary excerpt around the incident.": "Выдержка по границам записей вокруг инцидента.",
  "A bounded, partial-aware timeline of the same records.":
    "Ограниченный таймлайн тех же записей с пометкой о неполноте.",
  "The queue, task history and the first thing to read.":
    "Очередь, история задач и первое, что стоит прочитать.",
  "Two-second barrier": "Двухсекундный барьер",
  "Waits at most two seconds for the writer, then says what is missing instead of lying.":
    "Ждёт писатель не больше двух секунд, потом честно говорит, чего не хватает.",
  "Redacted": "Вычищено",
  "Config masks and credential-free URLs, on every file in the bundle.":
    "Маски конфига и URL без учётных данных — в каждом файле пакета.",
  "Finding the cause": "Найти причину",
  "From a symptom to the record that explains it": "От симптома к записи, которая его объясняет",
  "Method": "Метод",
  "Read the failing line, then walk `cause` backwards until it is zero.":
    "Прочитайте упавшую строку, затем идите по `cause` назад, пока не станет ноль.",
  "Find the error": "Найдите ошибку",
  "Follow the chain": "Идите по цепочке",
  "`jq -c 'select(.seq==N or .cause==N)'` until `cause` is 0.":
    "`jq -c 'select(.seq==N or .cause==N)'`, пока `cause` не станет 0.",
  "Correlate": "Сопоставьте",
  "`turn` groups one chat turn; `task`, `job` and `plan` group execution.":
    "`turn` группирует один ход; `task`, `job` и `plan` — исполнение.",
  "Widen only when needed": "Расширяйте только при необходимости",
  "Then `/friendai diagnose`, not before.": "Тогда `/friendai diagnose`, и не раньше.",
  "A hanging test": "Зависший тест",
  "`TaskTrace` prints the last twelve ticks every six hundred failed ticks, at most twice.":
    "`TaskTrace` печатает последние двенадцать тиков каждые шестьсот неудачных, максимум дважды.",
  "A stall": "Зависание",
  "`TaskDiagnosticEpisode` records what the task was doing when it stalled.":
    "`TaskDiagnosticEpisode` записывает, чем задача занималась в момент зависания.",
  "Privacy when sharing": "Приватность при передаче",
  "A bundle is evidence, not a transcript dump": "Пакет — это доказательство, а не дамп переписки",
  "Sharing": "Передача",
  "Operator-gated on the server; an owner sees only their own subtree.":
    "На сервере только для оператора; владелец видит лишь своё поддерево.",
  "Operator only": "Только оператор",
  "Brain log, prompt, perception text and debug tools are gated by `DebugAccess` on the server.":
    "Лог мозга, промпт, текст восприятия и отладочные инструменты закрыты `DebugAccess` на сервере.",
  "Owner scope": "Область владельца",
  "`/friendai logs path` prints an owner their own subtree, an operator the root.":
    "`/friendai logs path` показывает владельцу его поддерево, оператору — корень.",
  "Masks": "Маски",
  "Config values are masked; URLs carry no credentials.":
    "Значения конфига маскированы; в URL нет учётных данных.",
  "Not in logs, chat, exception messages or `toString()`.":
    "Ни в логах, ни в чате, ни в тексте исключений, ни в `toString()`.",
  "Read before pasting": "Прочитайте перед вставкой",
  "A `detailed` file can contain prompt and reply text.":
    "Файл `detailed` может содержать текст промпта и ответа."
});

/* ---- network protocol ---- */
Object.assign(RU, {
  "One versioned protocol": "Один версионированный протокол",
  "Client and server talk through one family and operation protocol over Fabric payloads. The codecs are pure, bounded and live in the engine; the Fabric wrappers only move bytes.":
    "Клиент и сервер говорят через один протокол семейств и операций поверх Fabric-пейлоадов. Кодеки чистые, ограниченные и живут в движке; обёртки Fabric только переносят байты.",
  "Client joins": "Клиент заходит",
  "The server creates a pending session for the player.":
    "Сервер создаёт ожидающую сессию для игрока.",
  "Bounded version and capability offer.": "Ограниченное предложение версии и возможностей.",
  "Requests stay closed until the server accepts it; a pending handshake expires after 100 ticks.":
    "Запросы закрыты, пока сервер не примет; ожидающее рукопожатие истекает через 100 тиков.",
  "Family and operation IDs, a monotonic request ID, a typed body, bounded.":
    "ID семейства и операции, монотонный ID запроса, типизированное тело, всё ограничено.",
  "Server dispatch": "Диспетчеризация на сервере",
  "Validates the connection, family, sequence and bounds, then runs on the server thread.":
    "Проверяет соединение, семейство, последовательность и границы, затем выполняет в серверном потоке.",
  "One typed view frame with request correlation.":
    "Один типизированный кадр вида с привязкой к запросу.",
  "Client applies": "Клиент применяет",
  "Hops to the client thread, verifies the captured session, then applies the matching view.":
    "Переходит в клиентский поток, проверяет сохранённую сессию и применяет подходящий вид.",
  "next request": "следующий запрос",
  "Handshake": "Рукопожатие",
  "Nothing is accepted before it completes": "До его завершения ничего не принимается",
  "A failed bootstrap disconnects the peer.": "Неудачный бутстрап отключает собеседника.",
  "Bounded version and capability offer after join.":
    "Ограниченное предложение версии и возможностей после входа.",
  "Accepts the negotiated version and capability set.":
    "Принимает согласованные версию и набор возможностей.",
  "A pending handshake expires and its work is rejected.":
    "Ожидающее рукопожатие истекает, и его работа отклоняется.",
  "Capabilities": "Возможности",
  "At most 32 unique entries, each name at most 64 bytes.":
    "Не более 32 уникальных записей, каждое имя не длиннее 64 байт.",
  "Fail closed": "Отказ по умолчанию",
  "Unknown versions, malformed frames, stale sessions and out-of-sequence requests are dropped.":
    "Неизвестные версии, битые кадры, устаревшие сессии и запросы не по порядку отбрасываются.",
  "Frames": "Кадры",
  "Explicit families and operations, every one bounded": "Явные семейства и операции, каждая ограничена",
  "Body formats and bounds live in the pure engine `protocol/` package.":
    "Форматы тел и границы живут в чистом пакете движка `protocol/`.",
  "Version, family, operation, monotonic request ID, typed body.":
    "Версия, семейство, операция, монотонный ID запроса, типизированное тело.",
  "One typed view with metadata and request correlation.":
    "Один типизированный вид с метаданными и привязкой к запросу.",
  "Families": "Семейства",
  "control, memory, places, instructions, trading, debug, speech.":
    "control, memory, places, instructions, trading, debug, speech.",
  "Threads": "Потоки",
  "Dispatch hops onto the server thread; receivers hop to the client thread.":
    "Диспетчеризация переходит в серверный поток; получатели — в клиентский.",
  "Stale views": "Устаревшие виды",
  "A frame from an old connection session is ignored, never applied.":
    "Кадр из старой сессии соединения игнорируется и никогда не применяется.",
  "What the client may see": "Что может видеть клиент",
  "Synced data only, filtered on the server": "Только синхронизированные данные, отфильтрованные на сервере",
  "Client data": "Клиентские данные",
  "A screen, HUD or renderer never reads server-only state.":
    "Экран, HUD или рендерер никогда не читают серверное состояние.",
  "Synced entity data": "Синхронизированные данные сущности",
  "Food, XP level and creative mode arrive as `EntityDataAccessor`s.":
    "Еда, уровень опыта и креатив приходят как `EntityDataAccessor`.",
  "Menu snapshot": "Снимок меню",
  "`SnapshotBuilder` builds the state for the requester's own companion.":
    "`SnapshotBuilder` собирает состояние для компаньона самого запрашивающего.",
  "Operator tabs": "Вкладки оператора",
  "The brain log, prompt and perception text are filtered on the server.":
    "Лог мозга, промпт и текст восприятия фильтруются на сервере.",
  "Map view": "Вид карты",
  "At most 128 cells, 32 sites and 32 roads, never the full grid.":
    "Не более 128 клеток, 32 мест и 32 дорог, никогда не вся сетка.",
  "Inventory window": "Окно инвентаря",
  "A vanilla container menu, not a custom payload.": "Ванильное меню контейнера, а не свой пейлоад.",
  "Debug stream": "Отладочный поток",
  "Live views for the menu and the movement overlay": "Живые виды для меню и оверлея движения",
  "Stream": "Поток",
  "A rejected frame closes that viewer's menu with a warning instead of escaping into the tick.":
    "Отклонённый кадр закрывает меню этого зрителя с предупреждением, а не утекает в тик.",
  "10-tick cadence": "Шаг в 10 тиков",
  "Framed control views while the menu is open.": "Кадры control-видов, пока меню открыто.",
  "Subscribe and close": "Подписка и закрытие",
  "One typed request, and the same path a disconnect and a ten-tick sweep take.":
    "Один типизированный запрос и тот же путь, что у отключения и десятитиковой проверки.",
  "Movement overlay": "Оверлей движения",
  "The active task's work snapshot plus independent route obstacle cells.":
    "Снимок работы активной задачи плюс независимые клетки препятствий маршрута.",
  "The overlay switch resets per connection and is never saved.":
    "Переключатель оверлея сбрасывается на соединение и никогда не сохраняется.",
  "Bounded": "Ограничено",
  "Cells are capped, and an optional codec section keeps true per-status counts.":
    "Клетки ограничены, а необязательная секция кодека сохраняет настоящие счётчики по статусам."
});

/* ---- configuration and persistence ---- */
Object.assign(RU, {
  "Settings and saves that survive": "Настройки и сохранения, которые выживают",
  "Configuration and owner records are versioned, validated and protected. A failed write never destroys the previous file, and a format nobody can read stays read-only instead of being guessed at.":
    "Конфигурация и записи владельца версионированы, проверены и защищены. Неудачная запись никогда не уничтожает предыдущий файл, а формат, который никто не умеет читать, остаётся только для чтения, вместо того чтобы угадываться.",
  "Configuration": "Конфигурация",
  "Config": "Конфиг",
  "A key may be written as `env:NAME` so it never sits in a file.":
    "Ключ можно записать как `env:NAME`, чтобы он не лежал в файле.",
  "Server schema 22": "Схема сервера 22",
  "Tasks, agent, places, autonomy, chunk loading, build pace, logging.":
    "Задачи, агент, места, автономность, загрузка чанков, темп стройки, логирование.",
  "Client schema 9": "Схема клиента 9",
  "Voice recognition and synthesis, provider maps, UI preferences.":
    "Распознавание и синтез речи, карты провайдеров, настройки интерфейса.",
  "Adjacent migrations": "Соседние миграции",
  "`ConfigMigration` steps from every supported version; its tests are the source of truth.":
    "`ConfigMigration` ведёт шаги от каждой поддерживаемой версии; его тесты — источник истины.",
  "Unknown and future": "Неизвестные и будущие",
  "Left untouched; server startup uses in-memory defaults.":
    "Остаются нетронутыми; сервер стартует со значениями по умолчанию в памяти.",
  "Unique backups": "Уникальные резервные копии",
  "A byte-exact `.vN.<id>.bak` before an atomic replacement.":
    "Байт-в-байт `.vN.<id>.bak` перед атомарной заменой.",
  "Extension fields": "Поля расширений",
  "`ConfigJson` preserves unknown fields through later saves.":
    "`ConfigJson` сохраняет неизвестные поля при последующих записях.",
  "Keys": "Ключи",
  "`env:NAME` is preferred; keys never appear in logs, URLs or toasts.":
    "`env:NAME` предпочтителен; ключи не попадают в логи, URL и тосты.",
  "Owner files": "Файлы владельца",
  "`<world>/friendai/` — one file per owner": "`<world>/friendai/` — один файл на владельца",
  "Memory, profile, goals, free time, routines, places, placements, knowledge, trading and skills.":
    "Память, профиль, цели, свободное время, рутины, места, постройки, знания, торговля и навыки.",
  "Lazy load, debounced flush, atomic replace, `.bak` on corruption.":
    "Ленивая загрузка, отложенная запись, атомарная замена, `.bak` при повреждении.",
  "One writer": "Один писатель",
  "A single bounded writer is borrowed by every store and closed once.":
    "Единственный ограниченный писатель берётся всеми хранилищами и закрывается один раз.",
  "Typed status": "Типизированный статус",
  "Failure and read-only reasons carry no paths, values or exception text.":
    "Причины отказа и «только чтение» не содержат путей, значений и текста исключений.",
  "Unsupported versions": "Неподдерживаемые версии",
  "Read-only and never overwritten, even when the backup succeeds.":
    "Только для чтения и никогда не перезаписываются, даже если копия удалась.",
  "Independent revisions": "Независимые ревизии",
  "Every record has its own format version and revision.":
    "У каждой записи своя версия формата и ревизия.",
  "Corrupt file": "Повреждённый файл",
  "Backed up, then the original stays read-only.": "Копируется, после чего оригинал остаётся только для чтения.",
  "What lives where": "Что где лежит",
  "World save, owner books, game directory": "Сохранение мира, книги владельца, каталог игры",
  "Locations": "Расположения",
  "Diagnostics are not a store: each world's `friendai/logs/` is written, rotated and pruned.":
    "Диагностика — не хранилище: `friendai/logs/` каждого мира пишется, ротируется и чистится.",
  "World save": "Сохранение мира",
  "The companion entity, its inventory and hunger, anchors and the carry record.":
    "Сущность компаньона, её инвентарь и голод, якоря и запись переноса.",
  "Owner books and the conversation transcript.": "Книги владельца и стенограмма разговоров.",
  "Server and client settings.": "Настройки сервера и клиента.",
  "Speech models and voices, pinned by revision, size and SHA-256.":
    "Модели и голоса речи, закреплённые ревизией, размером и SHA-256.",
  "The diagnostics tree, described on the Logging page.":
    "Дерево диагностики, описано на странице логирования.",
  "Rules for changing it": "Правила изменения",
  "A schema change is a product decision": "Смена схемы — продуктовое решение",
  "Existing saves must keep loading, or the maintainer decides otherwise.":
    "Существующие сохранения должны продолжать загружаться, или мейнтейнер решает иначе.",
  "Bump the format": "Поднимите формат",
  "And the mod version, in the same change.": "И версию мода, в том же изменении.",
  "Test the chain": "Проверьте цепочку",
  "Every supported older version, plus defaults for new fields.":
    "Каждая поддерживаемая старая версия плюс значения по умолчанию для новых полей.",
  "Never guess": "Не угадывайте",
  "Older and future record formats are unsupported and untouched.":
    "Старые и будущие форматы записей неподдерживаемы и не трогаются.",
  "Stay bounded": "Держите границы",
  "8 MiB read and write limits, capped collections, eviction.":
    "Лимиты чтения и записи 8 МиБ, ограниченные коллекции, вытеснение.",
  "Update the table": "Обновите таблицу",
  "The persistence table in `ARCHITECTURE.md` moves with the code.":
    "Таблица хранения в `ARCHITECTURE.md` движется вместе с кодом."
});

/* ---- build, CI and release ---- */
Object.assign(RU, {
  "Build, gates and release": "Сборка, шлюзы и релиз",
  "What runs on your machine, what runs on a pull request, and what a tag has to satisfy. Java 25 is required; the first build downloads Minecraft and takes minutes.":
    "Что запускается у вас, что на pull request и что должен выполнить тег. Нужна Java 25; первая сборка скачивает Minecraft и занимает минуты.",
  "Local commands": "Локальные команды",
  "The ones used while working": "Те, что нужны в работе",
  "Only one Gradle run per checkout at a time: builds share a lock and the `run/` directory.":
    "Только один запуск Gradle на checkout: сборки делят лок и каталог `run/`.",
  "Both unit suites; add `--tests '<pattern>'` for one group.":
    "Оба юнит-набора; добавьте `--tests '<pattern>'` для одной группы.",
  "Compile every source set, package and inspect the jar; refreshes `test-builds/`.":
    "Компилирует все сорсеты, пакует и проверяет jar; обновляет `test-builds/`.",
  "One class glob, one server boot, under a minute.":
    "Один глоб класса, один запуск сервера, меньше минуты.",
  "Formats only your files; the bare form is forbidden in a shared checkout.":
    "Форматирует только ваши файлы; голый вызов запрещён в общем checkout.",
  "./gradlew exportTestJar and deployTestJar": "./gradlew exportTestJar и deployTestJar",
  "Refresh the built jar, or copy it to `TEST_BUILD_PATH`.":
    "Обновляет собранный jar или копирует его в `TEST_BUILD_PATH`.",
  "The full gate. CI runs it; agents run the covering group instead.":
    "Полный шлюз. Его запускает CI; агенты запускают покрывающую группу.",
  "CI jobs": "Задачи CI",
  "Every pull request and every push to main": "Каждый pull request и каждый пуш в main",
  "The required check is `CI gate`, and it requires every job below.":
    "Обязательная проверка — `CI gate`, и она требует все задачи ниже.",
  "Links, anchors and table shapes in the guides.": "Ссылки, якоря и форма таблиц в руководствах.",
  "Formatting": "Форматирование",
  "`spotlessCheck` over every Java file.": "`spotlessCheck` по всем Java-файлам.",
  "Both suites, with a counted summary.": "Оба набора с подсчитанной сводкой.",
  "Game tests": "GameTest-тесты",
  "Six shards, one class per server, logs uploaded on failure.":
    "Шесть шардов, один класс на сервер, логи загружаются при падении.",
  "Build mod jar": "Сборка jar мода",
  "Assemble, verify and upload the artifact.": "Собрать, проверить и загрузить артефакт.",
  "A tag, a jar and a checksum": "Тег, jar и контрольная сумма",
  "Tagging, merging and publishing are the maintainer's decision, never an agent's.":
    "Теги, слияния и публикация — решение мейнтейнера, никогда не агента.",
  "The single source of truth in `gradle.properties`.":
    "Единственный источник истины в `gradle.properties`.",
  "Tag": "Тег",
  "`vX.Y.Z` must equal `mod_version`.": "`vX.Y.Z` должен совпадать с `mod_version`.",
  "Guard rails": "Ограждения",
  "The tag must sit on `origin/main` and point at a commit with a green CI run.":
    "Тег должен стоять на `origin/main` и указывать на коммит с зелёным прогоном CI.",
  "Artifact": "Артефакт",
  "One jar plus `friendai-X.Y.Z.jar.sha256`.": "Один jar плюс `friendai-X.Y.Z.jar.sha256`.",
  "Dry run": "Пробный прогон",
  "Dispatch the workflow with `dry_run: true` to build and verify without publishing.":
    "Запустите воркфлоу с `dry_run: true`, чтобы собрать и проверить без публикации.",
  "Check script": "Проверочный скрипт",
  "`scripts/check-release.sh vX.Y.Z` checks the version, tag, jar name and metadata.":
    "`scripts/check-release.sh vX.Y.Z` проверяет версию, тег, имя jar и метаданные.",
  "Versioning": "Версионирование",
  "One bump per task, sized by the largest change": "Один подъём версии на задачу, по самому крупному изменению",
  "Versions": "Версии",
  "While the major version is 0, a breaking change bumps MINOR instead of MAJOR.":
    "Пока мажорная версия 0, ломающее изменение поднимает MINOR, а не MAJOR.",
  "A fix, a tweak, a translation or a dependency patch.":
    "Исправление, мелочь, перевод или патч зависимости.",
  "A feature, a skill, a setting or a provider preset.":
    "Фича, навык, настройка или пресет провайдера.",
  "No bump": "Без подъёма",
  "docs, ci, test, style, chore and pure refactor changes.":
    "Изменения docs, ci, test, style, chore и чистый рефакторинг.",
  "One player-facing line per shipped change, in the same commit.":
    "Одна строка для игрока на каждое поставляемое изменение, в том же коммите.",
  "Pre-1.0": "До 1.0",
  "Moving to 1.0.0 is the maintainer's call.": "Переход на 1.0.0 — решение мейнтейнера."
});

/* ---- technical labels that still read as words ---- */
Object.assign(RU, {
  "Unit tests": "Юнит-тесты",
  "Release": "Релиз",
  "100 ticks": "100 тиков"
});
