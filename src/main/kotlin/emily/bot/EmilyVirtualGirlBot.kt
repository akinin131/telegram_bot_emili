package emily.bot

import emily.app.BotConfig
import emily.app.WebAppStory
import emily.data.*
import emily.service.ChatService
import emily.service.ConversationMemory
import emily.service.ImageService
import emily.service.MyMemoryTranslator
import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.Base64
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.slf4j.LoggerFactory
import org.telegram.telegrambots.bots.TelegramLongPollingBot
import org.telegram.telegrambots.meta.api.methods.ActionType
import org.telegram.telegrambots.meta.api.methods.AnswerPreCheckoutQuery
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands
import org.telegram.telegrambots.meta.api.methods.invoices.SendInvoice
import org.telegram.telegrambots.meta.api.methods.send.*
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage
import org.telegram.telegrambots.meta.api.objects.InputFile
import org.telegram.telegrambots.meta.api.objects.Message
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand
import org.telegram.telegrambots.meta.api.objects.commands.scope.BotCommandScopeDefault
import org.telegram.telegrambots.meta.api.objects.payments.LabeledPrice
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException
import kotlin.text.buildString
import kotlin.text.orEmpty

class EmilyVirtualGirlBot(
    private val config: BotConfig,
    private val repository: BalanceRepository,
    private val selectionRepository: StorySelectionRepository,
    private val chatService: ChatService,
    private val imageService: ImageService,
    private val memory: ConversationMemory,
    private val translator: MyMemoryTranslator?
) : TelegramLongPollingBot() {

    private val log = LoggerFactory.getLogger(EmilyVirtualGirlBot::class.java)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val systemMessages = ConcurrentHashMap<Long, MutableList<Int>>()
    private val protectedMessages = ConcurrentHashMap<Long, MutableSet<Int>>()
    private val imageTag = "#pic"
    private val chatModel = "venice-uncensored"
    private val imageModel = "wai-Illustrious"

    // БАЗОВАЯ ПЕРСОНА ПО УМОЛЧАНИЮ (если что-то пошло не так)
    private val defaultPersona = """
        Emily — petite yet curvy, with soft skin; short, straight silver hair; green eyes;
        large, full, natural breasts (large, prominent, realistic, proportional);
        enjoys being nude; age 20+; semi-realistic anime style with natural body proportions.
        IMPORTANT: Carefully follow the user's instructions regarding poses and the situation —
        make sure the pose, hand placement, gaze direction, and overall composition strictly
        match the given description.
    """.trimIndent()

    // Текущие персона для каждого пользователя
    private val userPersonas = ConcurrentHashMap<Long, String>()

    // Невидимые символы (совпадают с Python)
    private val Z0: Char = '\u200B'   // 0: zero width space
    private val Z1: Char = '\u200C'   // 1: zero width non-joiner
    private val START_MARK: String = "\u2063\u200D" // маркер начала
    private val END_MARK: String = "\u200D\u2063"   // маркер конца

    data class HiddenWebAppData(
        val characterId: Int,
        val storyId: Int,
        val styleCode: Int
    )

    override fun getBotUsername(): String = "EmilyVirtualGirlBot"
    override fun getBotToken(): String = config.telegramToken

    private fun getPersona(chatId: Long): String {
        return userPersonas[chatId] ?: defaultPersona
    }
    private fun setPersona(chatId: Long, persona: String) {
        userPersonas[chatId] = persona
    }

    fun registerBotMenu() = runBlocking {
        println("🚀 registerBotMenu() - Регистрация команд бота")
        log.info("registerBotMenu()")
        val commands = listOf(
            BotCommand("/start", "Начать общение с Эмили"),
            BotCommand("/buy", "Купить подписку или пакет"),
            BotCommand("/balance", "Посмотреть баланс"),
            BotCommand("/reset", "Очистить память диалога"),
            BotCommand("/pic", "Сгенерировать изображение")
        )
        executeSafe(SetMyCommands(commands, BotCommandScopeDefault(), null))
    }

    override fun onUpdateReceived(update: Update) {
        println("📥 onUpdateReceived - Новое обновление получено")
        scope.launch {
            try {
                handleUpdate(update)
            } catch (e: Exception) {
                println("❌ Ошибка в handleUpdate: ${e.message}")
                log.error("Exception in handleUpdate", e)
            }
        }
    }

    override fun onClosing() {
        println("🔴 onClosing - Бот завершает работу")
        super.onClosing()
        scope.cancel()
    }

    private suspend fun handleUpdate(update: Update) {
        println("🔄 handleUpdate - Обработка обновления")
        when {
            update.hasPreCheckoutQuery() -> {
                println("💰 handleUpdate: preCheckout id=${update.preCheckoutQuery.id}")
                log.info("handleUpdate: preCheckout id={}", update.preCheckoutQuery.id)
                val answer = AnswerPreCheckoutQuery().apply {
                    preCheckoutQueryId = update.preCheckoutQuery.id
                    ok = true
                }
                executeSafe(answer)
            }

            update.hasMessage() && update.message.webAppData != null -> {
                val dataJson = update.message.webAppData.data
                println("🌐 WebAppData: $dataJson")
            }

            update.hasMessage() && update.message.successfulPayment != null -> {
                println("✅ handleUpdate: successfulPayment")
                log.info("handleUpdate: successfulPayment")
                onSuccessfulPayment(update.message)
            }

            update.hasMessage() && update.message.hasText() -> {
                val t = update.message.text
                println("📝 handleUpdate: textMessage chatId=${update.message.chatId}, text.len=${t?.length ?: -1}")
                log.info(
                    "handleUpdate: textMessage chatId={}, text.len={}",
                    update.message.chatId,
                    t?.length ?: -1
                )
                handleTextMessage(update)
            }

            update.hasCallbackQuery() -> {
                println("🔘 handleUpdate: callback ${update.callbackQuery.data}")
                log.info("handleUpdate: callback {}", update.callbackQuery.data)
                handleCallback(update)
            }

            else -> {
                println("❓ handleUpdate: unhandled update")
                log.warn("handleUpdate: unhandled update")
            }
        }
    }

    private suspend fun handleTextMessage(update: Update) {
        val chatId = update.message.chatId
        val textRaw = update.message.text.trim()
        val messageId = update.message.messageId

        println("📨 handleTextMessage START: chatId=$chatId, msgId=$messageId, text='${textRaw.replace('\n', ' ')}'")
        log.info(
            "handleTextMessage: chatId={}, msgId={}, text='{}'",
            chatId,
            messageId,
            textRaw.replace('\n', ' ')
        )

        // 1️⃣ Пытаемся вытащить невидимые данные (charId|storyId|styleCode)
        val hidden = decodeHiddenData(textRaw)
        if (hidden != null) {
            println(
                "🎯 Hidden WebApp data detected: charId=${hidden.characterId}, " +
                        "storyId=${hidden.storyId}, style=${hidden.styleCode}"
            )
            log.info(
                "Hidden WebApp data: charId={}, storyId={}, style={}",
                hidden.characterId,
                hidden.storyId,
                hidden.styleCode
            )

            val parsed = parseWebAppMessage(textRaw)
            if (parsed == null) {
                println("❌ Не удалось распарсить текст истории из сообщения")
                sendText(chatId, "Не удалось обработать выбор истории 😔")
                return
            }

            // 🔥 Восстанавливаем внешний вид по characterId + styleCode
            val personaForSelection = resolvePersona(
                characterId = hidden.characterId,
                styleCode = hidden.styleCode
            )

            // 🔥 Скрытое описание истории по characterId + storyId (РУССКИЙ текст с реальным именем)
            val hiddenStoryPrompt = resolveStoryPrompt(
                characterId = hidden.characterId,
                storyId = hidden.storyId
            )

            // Обновляем persona для конкретного пользователя
            setPersona(chatId, personaForSelection)
            println("🎨 persona resolved for charId=${hidden.characterId}, style=${hidden.styleCode}, chatId=$chatId")

            val selection = StorySelection(
                userId = chatId,
                characterName = parsed.characterName,
                // внешний вид персонажа (полный промт)
                characterAppearance = personaForSelection,
                // характер: либо короткий текст из WebApp, либо используем тот же промт внешности
                characterPersonality = parsed.characterPersonality ?: personaForSelection,
                storyTitle = parsed.storyTitle,
                // сюда кладём скрытое русское описание истории + инструкцию, fallback — то что пришло из WebApp
                storyDescription = hiddenStoryPrompt.ifBlank { parsed.storyDescription ?: parsed.storyTitle },
                full_story_text = parsed.fullStoryText,
                style = hidden.styleCode.toString()
            )

            applySelection(
                chatId = chatId,
                selection = selection,
                source = "webapp_hidden",
                sendConfirmation = false
            )

            println("✅ WebApp hidden selection applied successfully for chatId=$chatId")
            return
        }

        // 2️⃣ Остальные команды / сообщения
        when {
            textRaw.equals("/start", true) -> {
                println("🔹 Обработка команды /start для chatId=$chatId")
                memory.initIfNeeded(chatId)
                ensureUserBalance(chatId)
                memory.autoClean(chatId)
                deleteOldSystemMessages(chatId)
                sendWelcome(chatId)
                deleteUserCommand(chatId, messageId, textRaw)
            }

            textRaw.equals("/buy", true) -> {
                println("🔹 Обработка команды /buy для chatId=$chatId")
                ensureUserBalance(chatId)
                memory.autoClean(chatId)
                deleteOldSystemMessages(chatId)
                sendBuyMenu(chatId)
                deleteUserCommand(chatId, messageId, textRaw)
            }

            textRaw.equals("/balance", true) -> {
                println("🔹 Обработка команды /balance для chatId=$chatId")
                val balance = ensureUserBalance(chatId)
                memory.autoClean(chatId)
                deleteOldSystemMessages(chatId)
                sendBalance(chatId, balance)
                deleteUserCommand(chatId, messageId, textRaw)
            }

            textRaw.equals("/reset", true) -> {
                println("🔹 Обработка команды /reset для chatId=$chatId")
                memory.reset(chatId)
                deleteOldSystemMessages(chatId)
                sendEphemeral(chatId, "Память диалога очищена 🙈", ttlSeconds = 10)
                deleteUserCommand(chatId, messageId, textRaw)
            }

            textRaw.equals("/pic", true) -> {
                println("🔹 Обработка команды /pic")
                sendEphemeral(
                    chatId,
                    "Формат: отправь сообщение вида:\n#pic описание сцены",
                    ttlSeconds = 20
                )
                deleteUserCommand(chatId, messageId, textRaw)
            }

            textRaw.startsWith(imageTag, true) || textRaw.startsWith("/pic ", true) -> {
                println("🖼️ Обработка запроса изображения для chatId=$chatId")
                ensureUserBalance(chatId)
                memory.autoClean(chatId)
                handleImage(chatId, textRaw)
            }

            else -> {
                println("💬 Обработка обычного сообщения чата для chatId=$chatId")
                ensureUserBalance(chatId)
                memory.autoClean(chatId)
                handleChat(chatId, textRaw)
            }
        }
    }

    private fun preview(s: String?, max: Int = 220): String {
        if (s.isNullOrBlank()) return "∅"
        val clean = s.replace("\n", "\\n").replace("\r", "\\r")
        return if (clean.length <= max) clean else clean.take(max) + "… (len=" + clean.length + ")"
    }

    // =================================================================
    //   ДЕКОД-ЛОГИКА ДЛЯ НЕВИДИМЫХ ДАННЫХ (charId|storyId|styleCode)
    // =================================================================
    private fun decodeHiddenData(text: String): HiddenWebAppData? {
        val startIdx = text.indexOf(START_MARK)
        if (startIdx == -1) return null
        val endIdx = text.indexOf(END_MARK, startIdx + START_MARK.length)
        if (endIdx == -1) return null

        val encoded = text.substring(startIdx + START_MARK.length, endIdx)
        if (encoded.isEmpty()) return null

        val bits = StringBuilder(encoded.length)
        for (ch in encoded) {
            when (ch) {
                Z0 -> bits.append('0')
                Z1 -> bits.append('1')
                else -> return null
            }
        }

        if (bits.length % 8 != 0) return null
        val byteCount = bits.length / 8
        val bytes = ByteArray(byteCount)
        for (i in 0 until byteCount) {
            val byteStr = bits.substring(i * 8, i * 8 + 8)
            bytes[i] = byteStr.toInt(2).toByte()
        }

        val outerB64 = bytes.toString(Charsets.UTF_8)
        val payloadBytes = runCatching { Base64.getDecoder().decode(outerB64) }.getOrElse { return null }
        val payload = String(payloadBytes, Charsets.UTF_8)

        val parts = payload.split("|")
        if (parts.size < 3) return null

        val charId = parts[0].toIntOrNull() ?: return null
        val storyId = parts[1].toIntOrNull() ?: return null
        val styleCode = parts[2].toIntOrNull() ?: return null

        return HiddenWebAppData(
            characterId = charId,
            storyId = storyId,
            styleCode = styleCode
        )
    }

    // ==============================================================
    //  ВСЕ ВАРИАНТЫ ВНЕШНОСТИ (3 персонажа × 2 стиля)
    //  styleCode: 1 = anime, 2 = realistic
    // ==============================================================
    private fun resolvePersona(
        characterId: Int,
        styleCode: Int
    ): String {
        val isAnime = (styleCode == 1)
        return when (characterId) {
            // 1 — Шарлотта
            1 -> {
                if (isAnime) {
                    """
petite girl , fair skin;
shoulder-length wavy brown hair, large brown eyes behind thin,
elegant glasses; natural light makeup; . She has large breasts, proportional to her petite figure, and a slim waist. Semi-realistic anime style with natural
body proportions and soft shading.  Office background with monitors and evening lighting. important: Carefully follow the user's instructions
regarding poses and situations — make sure that the pose, hand position, facial expression, gaze direction, and overall
composition strictly match this description..
                    """.trimIndent()
                } else {
                    """petite girl , fair skin;
shoulder-length wavy brown hair, large brown eyes behind thin,
elegant glasses; natural light makeup; . She has large breasts, proportional to her petite figure, and a slim waist. realistic style with natural
body proportions and soft shading.  Office background with monitors and evening lighting. important: Carefully follow the user's instructions
regarding poses and situations — make sure that the pose, hand position, facial expression, gaze direction, and overall
composition strictly match this description..
                    """.trimIndent()
                }
            }

            // 2 — Анжела
            2 -> {
                if (isAnime) {
                    """
Emily — tall, confident business woman with an elegant, mature aura; height above average, long legs, toned figure with clearly defined waist and hips; light olive skin tone; very long straight black hair that falls down her back or over one shoulder; sharp almond-shaped dark green eyes with defined lashes; well-groomed eyebrows; full lips with a calm, knowing smile. She has a full, firm bust, proportional to her tall frame. Semi-realistic anime style with clean lines and realistic anatomy with slight stylization. She wears a tailored dark suit jacket, a fitted pencil skirt, a silky blouse with the top button casually undone, and high heels. Office or hotel interior, evening warm lighting. IMPORTANT: Carefully follow the user's instructions regarding poses and the situation — strictly match pose, posture, hand position, gaze direction and overall composition.
                    """.trimIndent()
                } else {
                    """
Emily — successful business executive woman in her early to mid 30s, tall and athletic yet feminine; smooth light olive skin; straight jet-black hair, perfectly styled, either loose or tucked behind one ear; piercing green eyes with a confident, focused gaze; elegant, minimal makeup with emphasis on eyes and lips. Realistic, athletic body with natural curves, proportional bust and hips, graceful posture that shows authority. She wears a perfectly fitted dark-blue or black pantsuit or skirt suit, a light silk blouse, subtle jewelry (watch, thin bracelet, small earrings). Realistic photographic style, hotel lobby or conference room background, warm evening light, professional atmosphere. IMPORTANT: Carefully follow the user's instructions regarding poses and the situation — pose, body language, hands, gaze and framing must exactly follow the description.
                    """.trimIndent()
                }
            }

            // 3 — Вика
            3 -> {
                if (isAnime) {
                    """
Emily — creative, slightly bohemian artist with a playful, relaxed vibe; medium height, slim but softly curvy body; light warm skin tone with faint paint smudges on fingers or forearms; shoulder-length wavy pastel-pink hair with a few messy strands falling into her face; big turquoise eyes, expressive and curious; a small beauty mark under one eye; casual natural makeup or almost no makeup. She has a modest to medium bust, proportional to her slim frame, and graceful hands used to holding brushes. Semi-realistic anime style with smooth shading and natural proportions. She wears a loose off-shoulder t-shirt or tank top with traces of paint, comfortable shorts or loose pants, sometimes an unbuttoned shirt as a layer. Studio background: canvases, easel, paints, warm or evening light. IMPORTANT: Carefully follow the user's instructions regarding poses and the situation — pose, gesture, gaze direction, props and composition must strictly follow the description.
                    """.trimIndent()
                } else {
                    """
Emily — young woman in her mid 20s, artistic and free-spirited; average height, slim, flexible body; warm skin tone with a few freckles; naturally wavy dark-blond or dyed pastel-pink hair pulled into a loose bun or falling freely; light blue or grey-blue eyes with a dreamy gaze; almost no makeup, just a hint of mascara. Realistic, natural body with soft curves, medium bust, graceful hands of someone who paints a lot. She wears loose, comfortable clothes with visible paint stains: oversized t-shirt or linen shirt, soft joggers or jeans, sometimes barefoot in the studio. Realistic photographic style, cozy artist studio background with canvases, sketches, scattered brushes, warm ambient light or late-night lamp glow. IMPORTANT: Carefully follow the user's instructions regarding poses and the situation — pose, hand placement, head tilt, gaze and camera angle must strictly match the description.
                    """.trimIndent()
                }
            }

            else -> defaultPersona
        }
    }

    // ==============================================================
    //  СКРЫТОЕ ОПИСАНИЕ ИСТОРИЙ (story prompts) НА РУССКОМ
    //  подбирается по characterId + storyId, юзер это НЕ видит
    // ==============================================================
    private fun resolveStoryPrompt(
        characterId: Int,
        storyId: Int
    ): String {
        return when (characterId) {
            // 1 — Шарлотта (истории 1 и 2)
            1 -> when (storyId) {

                // История 1: Ошибка с фото в офисе
                1 -> """
Скрытое описание истории:
Поздний вечер в почти пустом офисе. Шарлотта — застенчивая, усердная сотрудница, уставшая после долгого дня. По ошибке она отправляет начальнику не отчёт, а личное интимное фото из закрытой папки. Начальник почти сразу пишет и просит её зайти к нему в кабинет. Там полумрак, жалюзи опущены, двери закрыты. Вместо ожидаемого скандала он говорит с ней мягко, уточняет, всё ли с ней в порядке, и внимательно наблюдает за её реакциями. Напряжение стыда и страха постепенно превращается в тёплое, осторожное эротическое напряжение и ощущение, что её видят и желают.

Инструкция для игры сцены:
- Отвечай от первого лица от имени Шарлотты: она стеснительная, смущается, краснеет, но внутри у неё просыпается любопытство и возбуждение.
- Делай акцент на эмоциях, взглядах, паузах, дыхании, неловких жестах, а не на грубом описании тела.
- Используй детали офисной обстановки: полутёмный кабинет, свет монитора, шёпот голосов через закрытую дверь, стул, стол, расстояние между ними.
- Постепенно усиливай эротическое напряжение через диалог, игру взглядов и осторожные, согласованные сближения, ъ.
- Всегда подчёркивай обоюдное согласие: если ситуация заходит дальше, это происходит только по желанию Шарлотты, а начальник внимательно реагирует на её слова и сигналы.
                """.trimIndent()

                // История 2: Ночное дежурство
                2 -> """
Скрытое описание истории:
Поздняя ночь в офисе. На этаже почти никого нет, только охранник и шум кондиционера. Шарлотта задержалась, чтобы доделать накопившиеся задачи, и неожиданно остаётся не одна — коллега тоже задержался из-за завала в почте. Они садятся рядом, разбирают письма, пьют кофе у окна. Смех, шутки и рабочие комментарии постепенно переходят в более личный разговор. Случайные прикосновения, сидение плечом к плечу, наклон над одной клавиатурой создают тёплую, интимную атмосферу и взаимное притяжение.

Инструкция для игры сцены:
- Отвечай от лица Шарлотты, которая сначала просто «по-деловому помогает», но мало-по-малу расслабляется и всё больше флиртует.
- Делай акцент на мелочах: как их плечи соприкасаются, как они переглядываются, как меняется тон голоса, когда разговор становится более личным.
- Коллега добрый, внимательный и уважительный, ни в чём не давит, реагирует на настроение и инициативу Шарлотты.
- Используй атмосферу позднего офиса: темноту за окнами, отражения в стекле, мягкий свет настольной лампы, лёгкий запах кофе и бумаги.
- Развивай мягкое, взаимное эротическое напряжение через слова, смех и лёгкие прикосновения, но не переходи к грубому, натуралистичному описанию секса.
- Всегда соблюдай рамки согласия и безопасности: любые более интимные действия происходят только по обоюдному желанию, без принуждения.
                """.trimIndent()

                else -> ""
            }

            // 2 — Анжела (истории 3 и 4)
            2 -> when (storyId) {

                // История 3: Корпоративный выезд
                3 -> """
Скрытое описание истории:
Загородный отель у озера. Днём Анжела — уверенная, сильная руководительница, жёстко и чётко управляющая рабочими процессами. Вечером после насыщенного дня она позволяет себе немного расслабиться: снимает каблуки, выходит на террасу с видом на воду и зовёт героя/героиню продолжить разговор. Всё начинается с обсуждения планов на завтра, но плавно переходит в разговор о личных целях, желаниях и границах. Между ними возникает мягкая игра власти и притяжения: Анжела старше/опытнее, она ведёт диалог, задаёт тон, но не ломает волю собеседника.

Инструкция для игры сцены:
- Отвечай от лица Анжелы: она говорит уверенно, спокойно, немного снисходительно и очень сексуально в своей сдержанности.
- Совмещай деловой тон и интимный подтекст: вопросы о карьере, амбициях и желаниях легко переходят в тонкий флирт.
- Подчёркивай «мягкое доминирование»: Анжела задаёт темп общения, инициирует близость, но всегда остаётся внимательной к реакции партнёра.
- Описывай атмосферу: ночной воздух, тихое озеро, огни отеля, её обнажённые ступни после каблуков, расслабленная поза после тяжёлого дня.
- Эротика строится через силу характера, взгляды, невербальные жесты, задержки в речи и неожиданно личные вопросы, а не через «грубую анатомию».
- Всегда оставляй пространство для явного согласия: Анжела не давит, а приглашает. Если партнёр сомневается — она проговаривает границы и поддерживает чувство безопасности.
                """.trimIndent()

                // История 4: Вечер переговоров
                4 -> """
Скрытое описание истории:
После тяжёлых переговоров в номере отеля или переговорной комнате договор наконец подписан. Напряжение рабочего дня спадает, и Анжела предлагает «остаться на пять минут, обсудить детали». В комнате идеальный порядок, на столе чай или вино. Она снимает часть делового образа (например, расстёгивает пиджак или снимает туфли), но сохраняет авторитет и контроль над ситуацией. Разговор незаметно поворачивает с деловых вопросов к тому, что важно герою/героине вне работы — к желаниям, удовольствиям, личным границам. Власть Анжелы остаётся, но в более интимной, взрослой игре.

Инструкция для игры сцены:
- Отвечай от лица Анжелы — как уравновешенная, умная, соблазнительная руководительница, которая привычна к переговорам и власти.
- Построй общение как «продолжение переговоров», только теперь тема — желания, комфорт и сексуальное притяжение, а не контракт.
- Используй обстановку номера: документы на столе, аккуратно сложенные вещи, мягкий тёплый свет, чай/вино, закрытая дверь.
- Делай акцент на словах, интонациях и близости: Анжела смотрит прямо, иногда прикасается рукой к плечу/кисти, задаёт откровенные вопросы, но всегда даёт возможность не отвечать.
- Эротика должна исходить из чувства равенства взрослых людей и осознанного согласия, а не из давления или зависимости.
- Не переходи в грубое порнографическое описание; удерживай тон в области зрелого, психологического эротизма: ожидание, напряжение, игра, шаг вперёд — только когда обе стороны явно этого хотят.
                """.trimIndent()

                else -> ""
            }

            // 3 — Вика (истории 5 и 6)
            3 -> when (storyId) {

                // История 5: Творческий вечер
                5 -> """
Скрытое описание истории:
Уютная художественная студия. Вика — творческая художница с лёгким хаосом вокруг: холсты, эскизы, глина, краски. Она показывает герою/героине работы, в которых угадываются их черты, просит помочь с композицией, повернуть лампу, подержать ткань, посидеть в определённой позе. Каждое действие понемногу сближает их: Вика подходит ближе, поправляет руку, касается плеча, шепчет указания почти на ухо. Ночь длинная, никто не торопится, атмосфера мягкая, интимная и очень телесная через искусство.

Инструкция для игры сцены:
- Отвечай от лица Вики: она лёгкая, живая, немного рассеянная, но очень чувственная и внимательная к телу другого человека.
- Используй художественный контекст как повод для близости: поправить позу, слегка коснуться шеи, сдуть прядь волос, подправить ткань на плече.
- Описывай детали студии: запах красок и глины, мягкий тёплый свет, музыку, пятна краски на её руках и одежде.
- Эротическое напряжение должно рождаться из процесса творчества и того, как Вика смотрит на героя/героиню как на «музу», а не из грубого описания секса.
- Подчёркивай мягкость и добровольность происходящего: Вика никогда не давит, всегда чувствует реакцию партнёра и легко замедляется или отступает.
- Не используй натуралистичные, порнографические детали; концентрируйся на ощущениях, прикосновениях, взглядах и чувстве «мы вдвоём в отдельном мире».
                """.trimIndent()

                // История 6: Ночь вдохновения
                6 -> """
Скрытое описание истории:
Поздняя ночь, студия погружена в творческий хаос. Вика и герой/героиня вместе работают над смелой картиной. Вокруг — музыка, кружки с водой для кистей, брызги краски на полу и одежде. Ошибки и кляксы превращаются в игру: краска попадает на запястье, шею или щёку, Вика смеётся и вытирает её рукой или тряпочкой, невольно задерживаясь с прикосновением. Она наблюдает за тем, как партнёр держит кисть, как двигается, как реагирует на близость, и использует это, чтобы ещё сильнее подогреть атмосферу.

Инструкция для игры сцены:
- Отвечай от лица Вики: она в состоянии вдохновения, смеётся, шутит, легко переходит от серьёзности к флирту.
- Делай акцент на «случайных» поводаx для прикосновений: краска на коже, неловкое движение, когда они сталкиваются плечами или бёдрами.
- Используй физическое взаимодействие как естественную часть процесса: Вика становится за спиной, берёт руку партнёра, чтобы вести кисть, наклоняется близко, говорит почти в ухо.
- Атмосфера должна быть живой, тёплой и немного безумной творческой ночью, где искусство и эротика мягко переплетаются.
- Не скатывайся в грубые, подробные сцены секса; держи фокус на эмоциональной и телесной близости, а не на техническом описании действий.
- Всегда сохраняй чувство обоюдного согласия и безопасности: Вика внимательна к реакции, и если партнёру нужно замедлиться — она подстраивается и бережно поддерживает.
                """.trimIndent()

                else -> ""
            }

            else -> ""
        }
    }

    // ================== ПАРСИНГ WEBAPP-ТЕКСТА ==================
    fun parseWebAppMessage(text: String): WebAppStory? {
        val clean = text.trim()

        val characterName = Regex("""Персонаж:\s*(.+)""")
            .find(clean)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()

        val storyTitle = Regex("""История:\s*(.+)""")
            .find(clean)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()

        if (characterName.isNullOrBlank() || storyTitle.isNullOrBlank()) {
            println("❌ parseWebAppMessage: не нашли персонажа или историю")
            return null
        }

        val fullStoryText = Regex("""full_story_text:\s*(.+)""", RegexOption.DOT_MATCHES_ALL)
            .find(clean)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?: run {
                clean.substringAfter("История:", "")
                    .substringAfter(storyTitle, "")
                    .substringBefore("⏰")
                    .substringBefore("📊")
                    .trim()
            }

        val styleStr = Regex("""style:\s*([^\n\r]+)""")
            .find(clean)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()

        val style = styleStr?.toIntOrNull()

        val characterPersonality = Regex("""characterPersonality:\s*([^\n\r]+)""")
            .find(clean)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()

        val storyDescription = Regex("""storyDescription:\s*([^\n\r]+)""")
            .find(clean)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()

        return WebAppStory(
            characterName = characterName,
            storyTitle = storyTitle,
            style = style,
            characterPersonality = characterPersonality,
            storyDescription = storyDescription,
            fullStoryText = fullStoryText
        )
    }

    // ================== ПРИМЕНЕНИЕ ВЫБОРА ИСТОРИИ ==================
    suspend fun applySelection(
        chatId: Long,
        selection: StorySelection,
        source: String,
        sendConfirmation: Boolean = true
    ) {
        println(
            "🎭 applySelection: chatId=$chatId, source=$source, character='${selection.characterName}', " +
                    "story.len=${selection.full_story_text?.length ?: 0}"
        )
        selectionRepository.save(selection)

        val scenario = buildString {
            append("Ты играешь роль персонажа по имени ${selection.characterName}. ")

            selection.characterPersonality?.let {
                append("Характер и внешность персонажа (для внутреннего понимания роли): $it. ")
            }

            selection.style?.let {
                val styleText = when (it) {
                    "1" -> "аниме (semi-realistic anime), с естественной анатомией и живыми эмоциями."
                    "2" -> "реалистичный (realistic), с фотореалистичным ощущением сцены и естественными пропорциями тела."
                    else -> it
                }
                append("Основной стилевой контекст: $styleText ")
            }

            selection.storyDescription?.let {
                append("Скрытое описание истории и инструкции по роли: $it ")
            }

            append("Начальная сцена (оригинальный текст истории на русском): ${selection.full_story_text}. ")
            append("Отвечай на том же языке, на котором пишет пользователь (если он пишет по-русски — отвечай по-русски). ")
            append("Отвечай от лица персонажа, развивай эротическую сцену, но избегай тем несовершеннолетних, насилия и принуждения. ")
            append("Всегда подчёркивай обоюдное согласие и эмоциональную безопасность, делай упор на чувства, атмосферу и взаимодействие, а не на грубое натуралистичное описание секса.")
        }

        memory.reset(chatId)
        memory.setSystem(chatId, scenario)

        if (sendConfirmation) {
            sendStorySelectionConfirmation(chatId, selection)
        }
    }

    private suspend fun sendStorySelectionConfirmation(chatId: Long, selection: StorySelection) {
        println("📤 sendStorySelectionConfirmation: chatId=$chatId")
        val message = """
            🎭 <b>Сцена выбрана!</b>
            
            Персонаж: <b>${escapeHtml(selection.characterName)}</b>
            
            Теперь напиши первое сообщение — и мы начнём нашу историю! 💕
        """.trimIndent()

        executeSafe(SendMessage(chatId.toString(), message).apply { parseMode = "HTML" })
        println("✅ Confirmation message sent for chatId=$chatId")
    }

    // ================== ДАЛЬШЕ — ВСЁ КАК У ТЕБЯ БЫЛО (платежи, баланс, чат, картинки) ==================

    private suspend fun handleCallback(update: Update) {
        val chatId = update.callbackQuery.message.chatId
        val data = update.callbackQuery.data
        println("🔘 handleCallback chatId=$chatId, data=$data")
        log.info("handleCallback chatId={}, data={}", chatId, data)
        memory.autoClean(chatId)
        deleteOldSystemMessages(chatId)
        when {
            data.startsWith("buy:plan:") -> {
                println("💰 Создание инвойса для плана: ${data.removePrefix("buy:plan:")} для chatId=$chatId")
                createPlanInvoice(chatId, data.removePrefix("buy:plan:"))
            }

            data.startsWith("buy:pack:") -> {
                println("💰 Создание инвойса для пакета: ${data.removePrefix("buy:pack:")} для chatId=$chatId")
                createPackInvoice(chatId, data.removePrefix("buy:pack:"))
            }
        }
    }

    private suspend fun sendWelcome(chatId: Long) {
        println("👋 sendWelcome: chatId=$chatId")
        val text = """
        Привет! Я Эмили 💕
        Я умею разговаривать и создавать изображения.
        Команды:
          /buy — оплатить подписку/пакет (с фото и чеком)
          /balance — показать текущий баланс
          /reset — очистить память диалога
          /pic — как генерировать картинку
        Бесплатно: ~30 коротких сообщений и 1 изображение.
        """.trimIndent()
        val message = executeSafe(SendMessage(chatId.toString(), text))
        rememberSystemMessage(chatId, message.messageId)
    }

    private suspend fun sendBalance(chatId: Long, balance: UserBalance) {
        println("💰 sendBalance: chatId=$chatId")
        val planTitle = when (balance.plan) {
            Plan.BASIC.code -> Plan.BASIC.title
            Plan.PRO.code -> Plan.PRO.title
            Plan.ULTRA.code -> Plan.ULTRA.title
            else -> "нет (Free)"
        }
        val until = balance.planExpiresAt?.let { Instant.ofEpochMilli(it).toString() } ?: "—"
        val text = """
        <b>План:</b> $planTitle
        <b>Действует до:</b> $until
        <b>Текущие текстовые токены:</b> ${balance.textTokensLeft}
        <b>Кредиты на изображения:</b> ${balance.imageCreditsLeft}
        <b>Сегодня использовано изображений:</b> ${balance.dayImageUsed}
        """.trimIndent()
        val message = SendMessage(chatId.toString(), text).apply { parseMode = "HTML" }
        rememberSystemMessage(chatId, executeSafe(message).messageId)
    }

    private suspend fun sendBuyMenu(chatId: Long) {
        println("🛍️ sendBuyMenu: chatId=$chatId")
        val rows = mutableListOf<List<InlineKeyboardButton>>()
        Plan.values().forEach { plan ->
            rows += listOf(
                InlineKeyboardButton().apply {
                    text = "${plan.title} (${plan.priceRub}₽/мес)"
                    callbackData = "buy:plan:${plan.code}"
                }
            )
        }
        rows += listOf(
            InlineKeyboardButton().apply {
                text = "Фото для возбуждения (10)"
                callbackData = "buy:pack:${ImagePack.P10.code}"
            }
        )
        rows += listOf(
            InlineKeyboardButton().apply {
                text = "Порочный альбом (50)"
                callbackData = "buy:pack:${ImagePack.P50.code}"
            }
        )
        val markup = InlineKeyboardMarkup().apply { keyboard = rows }
        val msg = SendMessage(
            chatId.toString(),
            "Выбери пакет. После оплаты баланс пополнится автоматически.\n\nПодписка идёт без автопродления"
        ).apply {
            replyMarkup = markup
        }
        rememberSystemMessage(chatId, executeSafe(msg).messageId)
    }

    private suspend fun createPlanInvoice(chatId: Long, planCode: String) {
        println("🧾 createPlanInvoice: chatId=$chatId, planCode=$planCode")
        val plan = Plan.byCode(planCode) ?: return
        val invoicePayload = "plan:${plan.code}:${UUID.randomUUID()}"
        val providerDataJson = makeProviderData(
            desc = "Пакет ${plan.title} — 30 дней. Текстовые токены + кредиты изображений.",
            rub = plan.priceRub,
            includeVat = true
        )
        val invoice = SendInvoice().apply {
            this.chatId = chatId.toString()
            title = "Пакет: ${plan.title}"
            description =
                "30 дней: ${plan.monthlyTextTokens} токенов и ${plan.monthlyImageCredits} изображений."
            payload = invoicePayload
            providerToken = config.providerToken
            currency = "RUB"
            startParameter = "plan-${plan.code}"
            prices = listOf(LabeledPrice("${plan.title} 30 дней", plan.priceRub * 100))
            needEmail = true
            sendEmailToProvider = true
            isFlexible = false
            providerData = providerDataJson
            photoUrl = plan.photoUrl
            photoWidth = 960
            photoHeight = 1280
        }
        safeExecuteInvoice(chatId, invoice)
    }

    private suspend fun createPackInvoice(chatId: Long, packCode: String) {
        println("🧾 createPackInvoice: chatId=$chatId, packCode=$packCode")
        val pack = ImagePack.byCode(packCode) ?: return
        val invoicePayload = "pack:${pack.code}:${UUID.randomUUID()}"
        val providerDataJson = makeProviderData(
            desc = "${pack.title}. Дополнительные единицы генерации изображений.",
            rub = pack.priceRub,
            includeVat = true
        )
        val invoice = SendInvoice().apply {
            this.chatId = chatId.toString()
            title = pack.title
            description = "Разовый пакет: ${pack.title}"
            payload = invoicePayload
            providerToken = config.providerToken
            currency = "RUB"
            startParameter = "pack-${pack.code}"
            prices = listOf(LabeledPrice(pack.title, pack.priceRub * 100))
            needEmail = true
            sendEmailToProvider = true
            isFlexible = false
            providerData = providerDataJson
            photoUrl = pack.photoUrl
            photoWidth = 960
            photoHeight = 1280
        }
        safeExecuteInvoice(chatId, invoice)
    }

    private suspend fun onSuccessfulPayment(message: Message) {
        println("✅ onSuccessfulPayment: chatId=${message.chatId}")
        val chatId = message.chatId
        val payment = message.successfulPayment ?: return
        val payload = payment.invoicePayload ?: return
        val totalRub = (payment.totalAmount / 100.0).toInt()
        val balance = ensureUserBalance(chatId)
        when {
            payload.startsWith("plan:") -> {
                val code = payload.split(":").getOrNull(1)
                val plan = Plan.byCode(code) ?: return
                val monthMs = 30L * 24 * 60 * 60 * 1000
                val now = System.currentTimeMillis()
                val base = maxOf(balance.planExpiresAt ?: 0L, now)
                balance.plan = plan.code
                balance.planExpiresAt = base + monthMs
                balance.textTokensLeft += plan.monthlyTextTokens
                balance.imageCreditsLeft += plan.monthlyImageCredits
                repository.put(balance)
                repository.addPayment(chatId, payload, totalRub)
                sendEphemeral(
                    chatId,
                    "✅ Подписка «${plan.title}» активирована до ${
                        Instant.ofEpochMilli(
                            balance.planExpiresAt!!
                        )
                    }.\n" +
                            "Начислено: ${plan.monthlyTextTokens} токенов и ${plan.monthlyImageCredits} изображений.",
                    ttlSeconds = 20
                )
                println("🎉 План активирован: ${plan.title} для chatId=$chatId")
            }

            payload.startsWith("pack:") -> {
                val code = payload.split(":").getOrNull(1)
                val pack = ImagePack.byCode(code) ?: return
                balance.imageCreditsLeft += pack.images
                repository.put(balance)
                repository.addPayment(chatId, payload, totalRub)
                sendEphemeral(
                    chatId,
                    "✅ Начислено: ${pack.images} изображений по пакету «${pack.title}».",
                    ttlSeconds = 15
                )
                println("🎉 Пакет активирован: ${pack.title} для chatId=$chatId")
            }
        }
    }

    private suspend fun handleChat(chatId: Long, text: String) {
        println("💬 handleChat: chatId=$chatId, text='${preview(text, 50)}'")
        val balance = ensureUserBalance(chatId)
        if (balance.textTokensLeft <= 0) {
            println("⚠️ Недостаточно токенов: chatId=$chatId")
            sendEphemeral(
                chatId,
                "⚠️ У тебя закончились текстовые токены.\nКупи подписку в /buy",
                ttlSeconds = 15
            )
            return
        }
        memory.initIfNeeded(chatId)

        memory.append(chatId, "user", text)
        val history = memory.history(chatId)

        val result = withTyping(chatId) { chatService.generateReply(history) }
        println("🤖 ChatService result: text.len=${result.text.length}, tokensUsed=${result.tokensUsed} для chatId=$chatId")
        log.info("ChatService result: text.len={}, tokensUsed={}", result.text.length, result.tokensUsed)

        memory.append(chatId, "assistant", result.text)
        sendText(chatId, result.text)

        if (result.tokensUsed > 0) {
            balance.textTokensLeft -= result.tokensUsed
            if (balance.textTokensLeft < 0) balance.textTokensLeft = 0
            repository.put(balance)
            repository.logUsage(chatId, result.tokensUsed, mapOf("type" to "chat", "model" to chatModel))
            println("📊 Токены обновлены: chatId=$chatId, tokensLeft=${balance.textTokensLeft}")
            log.info("tokens updated chatId={}, tokensLeft={}", chatId, balance.textTokensLeft)
        }
        if (balance.plan == null && balance.textTokensLeft <= 0) {
            println("⚠️ Бесплатный лимит исчерпан: chatId=$chatId")
            sendEphemeral(chatId, "Бесплатный лимит исчерпан. Оформи подписку: /buy", ttlSeconds = 15)
        }
    }

    private suspend fun handleImage(chatId: Long, textRaw: String) {
        println("🖼️ handleImage: chatId=$chatId, text='${preview(textRaw, 50)}'")
        val balance = ensureUserBalance(chatId)
        val cap = dailyCap(balance.plan)
        if (balance.plan == null && balance.imageCreditsLeft < 1) {
            println("⚠️ Дневной лимит изображений исчерпан: chatId=$chatId")
            sendEphemeral(
                chatId,
                "Дневной лимит изображений исчерпан ($cap). Попробуй завтра или купи пакет /buy.",
                ttlSeconds = 20
            )
            return
        }
        if (balance.imageCreditsLeft <= 0) {
            println("⚠️ Нет кредитов на изображения: chatId=$chatId")
            sendEphemeral(
                chatId,
                "У тебя нет кредитов на изображения. Купи подписку или пакет: /buy",
                ttlSeconds = 20
            )
            return
        }
        val originalPrompt = textRaw.removePrefix(imageTag).removePrefix("/pic").trim()
        if (originalPrompt.isBlank()) {
            println("⚠️ Пустой промпт для изображения: chatId=$chatId")
            sendEphemeral(chatId, "После #pic укажи описание 🙂", ttlSeconds = 10)
            return
        }
        if (!isPromptAllowed(originalPrompt)) {
            println("🚫 Запрещенный промпт: chatId=$chatId")
            sendEphemeral(
                chatId,
                "❌ Нельзя темы про несовершеннолетних/насилие/принуждение.",
                ttlSeconds = 15
            )
            return
        }

        // УЛУЧШЕННАЯ ПРОВЕРКА РУССКОГО ТЕКСТА
        val containsCyrillic = hasCyrillic(originalPrompt)
        println("🔤 Проверка языка: containsCyrillic=$containsCyrillic, prompt='${preview(originalPrompt, 30)}'")

        val finalPrompt = if (containsCyrillic) {
            println("🔤 Перевод промпта с русского: chatId=$chatId")
            val translated = withUploadPhoto(chatId) { translateRuToEn(originalPrompt) }
            if (translated != null) {
                println("✅ Переведено: '$translated'")
                translated
            } else {
                println("❌ Перевод не удался, использую оригинал")
                originalPrompt
            }
        } else {
            println("🔤 Английский промпт, перевод не требуется")
            originalPrompt
        }

        println("🎨 Генерация изображения: chatId=$chatId, finalPrompt='${preview(finalPrompt, 50)}'")
        val bytes = withUploadPhoto(chatId) {
            imageService.generateImage(finalPrompt, getPersona(chatId))
        }
        if (bytes == null) {
            println("❌ Ошибка генерации изображения: chatId=$chatId")
            sendEphemeral(chatId, "Не удалось сгенерировать изображение. Попробуй ещё раз.", ttlSeconds = 12)
            return
        }
        sendPhoto(chatId, bytes, caption = null)
        balance.imageCreditsLeft -= 1
        balance.dayImageUsed += 1
        repository.put(balance)
        repository.logUsage(
            chatId,
            0,
            mapOf("type" to "image", "model" to imageModel, "credits_used" to 1)
        )
        println("✅ Изображение сгенерировано: chatId=$chatId, creditsLeft=${balance.imageCreditsLeft}")
        if (balance.plan == null && (balance.textTokensLeft <= 0 || balance.imageCreditsLeft <= 0)) {
            println("⚠️ Бесплатный лимит исчерпан после генерации: chatId=$chatId")
            sendEphemeral(chatId, "Бесплатный лимит исчерпан. Оформи подписку: /buy", ttlSeconds = 15)
        }
    }

    // УЛУЧШЕННАЯ ФУНКЦИЯ ПРОВЕРКИ КИРИЛЛИЦЫ
    private fun hasCyrillic(text: String): Boolean {
        val cyrillicPattern = Regex("[а-яА-ЯёЁ]")
        val hasCyrillic = cyrillicPattern.containsMatchIn(text)
        println("🔍 Проверка кириллицы: text='${preview(text, 20)}', hasCyrillic=$hasCyrillic")
        return hasCyrillic
    }

    // УЛУЧШЕННАЯ ФУНКЦИЯ ПЕРЕВОДА


    private suspend fun deleteOldSystemMessages(chatId: Long) {
        val ids = systemMessages[chatId] ?: return
        println("🗑️ deleteOldSystemMessages: chatId=$chatId, count=${ids.size}")
        val iterator = ids.iterator()
        while (iterator.hasNext()) {
            val id = iterator.next()
            if (protectedMessages[chatId]?.contains(id) == true) continue
            try {
                executeSafe(DeleteMessage(chatId.toString(), id))
            } catch (_: Exception) {
            }
            iterator.remove()
        }
    }

    private suspend fun sendText(chatId: Long, text: String, html: Boolean = false) {
        println("📤 sendText: chatId=$chatId, text='${preview(text, 50)}'")
        val message = SendMessage(chatId.toString(), text).apply { if (html) parseMode = "HTML" }
        executeSafe(message)
    }

    private suspend fun sendPhoto(chatId: Long, bytes: ByteArray, caption: String?) {
        println("📸 sendPhoto: chatId=$chatId, bytes=${bytes.size}, caption=$caption")
        val photo = SendPhoto().apply {
            this.chatId = chatId.toString()
            this.photo = InputFile(ByteArrayInputStream(bytes), "image.png")
            this.caption = caption ?: "Готово 💕"
        }
        executeSafe(photo)
    }

    private fun rememberSystemMessage(chatId: Long, messageId: Int) {
        val list = systemMessages.computeIfAbsent(chatId) { mutableListOf() }
        list += messageId
        println("💾 rememberSystemMessage: chatId=$chatId, messageId=$messageId")
    }

    private fun markProtected(chatId: Long, messageId: Int) {
        val set = protectedMessages.computeIfAbsent(chatId) { mutableSetOf() }
        set += messageId
        println("🛡️ markProtected: chatId=$chatId, messageId=$messageId")
    }

    private suspend fun safeExecuteInvoice(chatId: Long, invoice: SendInvoice) {
        println("🧾 safeExecuteInvoice: chatId=$chatId")
        try {
            val message = executeSafe(invoice)
            markProtected(chatId, message.messageId)
            println("✅ Invoice sent successfully: chatId=$chatId")
        } catch (ex: TelegramApiRequestException) {
            println("❌ Invoice error: ${ex.message}")
            val details = buildString {
                appendLine("Invoice error:")
                appendLine("message=${ex.message}")
                appendLine("apiResponse=${ex.apiResponse}")
                appendLine("parameters=${ex.parameters}")
            }
            sendEphemeral(chatId, "❌ $details", ttlSeconds = 20)
        } catch (ex: Exception) {
            println("❌ Unexpected invoice error: ${ex.message}")
            sendEphemeral(
                chatId,
                "❌ Unexpected invoice error: ${ex.message ?: ex.toString()}",
                ttlSeconds = 20
            )
        }
    }

    private fun makeProviderData(desc: String, rub: Int, includeVat: Boolean = true): String {
        val item = JSONObject()
            .put("description", desc.take(128))
            .put("quantity", "1")
            .put("amount", JSONObject().put("value", rubToStr(rub)).put("currency", "RUB"))
            .apply { if (includeVat) put("vat_code", 1) }
        val receipt = JSONObject().put("items", JSONArray().put(item))
        return JSONObject().put("receipt", receipt).toString()
    }

    private fun rubToStr(rub: Int) = String.format(Locale.US, "%.2f", rub.toDouble())

    private suspend fun ensureUserBalance(userId: Long): UserBalance {
        val balance = repository.get(userId)
        val now = System.currentTimeMillis()
        if (balance.planExpiresAt?.let { now > it } == true) {
            balance.plan = null
            balance.planExpiresAt = null
            println("🔄 План истек: userId=$userId")
        }
        val today = LocalDate.now().toString()
        if (balance.dayStamp != today) {
            balance.dayStamp = today
            balance.dayImageUsed = 0
            println("🔄 Сброс дневного лимита: userId=$userId")
        }
        repository.put(balance)
        return balance
    }

    private fun dailyCap(plan: String?): Int = when (plan) {
        Plan.BASIC.code -> DAILY_IMAGE_CAP_BASIC
        Plan.PRO.code -> DAILY_IMAGE_CAP_PRO
        Plan.ULTRA.code -> DAILY_IMAGE_CAP_ULTRA
        else -> 1
    }

    private fun isPromptAllowed(text: String): Boolean {
        val lower = text.lowercase()
        val bad = listOf(
            "несовершеннолет", "школьник", "школьница", "подрост", "minor", "teen", "loli", "shota",
            "изнасил", "насилие", "принужд", "без согласи", "rape", "forced"
        )
        return bad.none { lower.contains(it) }
    }

    private suspend fun translateRuToEn(text: String): String? = withContext(Dispatchers.IO) {
        return@withContext try {
            println("🌐 Перевод текста: '${preview(text, 30)}'")
            val result = translator?.translate(text, "ru", "en")
            println("🌐 Результат перевода: '${preview(result, 30)}'")
            result
        } catch (e: Exception) {
            println("❌ Ошибка перевода: ${e.message}")
            null
        }
    }

    private fun isDeletableCommand(text: String): Boolean {
        val t = text.trim().lowercase()
        return t == "/start" || t == "/buy" || t == "/balance" || t == "/reset" || t == "/pic"
    }

    private suspend fun deleteUserCommand(chatId: Long, messageId: Int, text: String) {
        if (isDeletableCommand(text)) {
            println("🗑️ deleteUserCommand: chatId=$chatId, messageId=$messageId")
            try {
                executeSafe(DeleteMessage(chatId.toString(), messageId))
            } catch (_: Exception) {
            }
        }
    }

    private fun escapeHtml(text: String): String = buildString {
        for (ch in text) {
            when (ch) {
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '&' -> append("&amp;")
                else -> append(ch)
            }
        }
    }

    private suspend fun sendEphemeral(
        chatId: Long,
        text: String,
        ttlSeconds: Long,
        html: Boolean = false
    ) {
        println("⏳ sendEphemeral: chatId=$chatId, text='${preview(text, 50)}', ttl=$ttlSeconds")
        val message = SendMessage(chatId.toString(), text).apply { if (html) parseMode = "HTML" }
        val sent = executeSafe(message)
        scope.launch {
            delay(ttlSeconds * 1000)
            try {
                executeSafe(DeleteMessage(chatId.toString(), sent.messageId))
                println("🗑️ Ephemeral message deleted: chatId=$chatId")
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun <T> withChatAction(
        chatId: Long,
        action: ActionType,
        block: suspend () -> T
    ): T {
        val job = scope.launch {
            while (isActive) {
                try {
                    val chatAction = SendChatAction.builder()
                        .chatId(chatId.toString())
                        .action(action.toString())
                        .build()
                    executeSafe(chatAction)
                } catch (_: Exception) {
                }
                delay(1000)
            }
        }
        return try {
            block()
        } finally {
            job.cancelAndJoin()
        }
    }

    private suspend fun <T> withTyping(chatId: Long, block: suspend () -> T): T =
        withChatAction(chatId, ActionType.TYPING, block)

    private suspend fun <T> withUploadPhoto(chatId: Long, block: suspend () -> T): T =
        withChatAction(chatId, ActionType.UPLOADPHOTO, block)

    // --- Telegram execute wrappers ---
    private suspend fun executeSafe(method: SendMessage): Message =
        withContext(Dispatchers.IO) { execute(method) }

    private suspend fun executeSafe(method: SendPhoto): Message =
        withContext(Dispatchers.IO) { execute(method) }

    private suspend fun executeSafe(method: DeleteMessage): Boolean =
        withContext(Dispatchers.IO) { execute(method) }

    private suspend fun executeSafe(method: SendInvoice): Message =
        withContext(Dispatchers.IO) { execute(method) }

    private suspend fun executeSafe(method: AnswerPreCheckoutQuery): Boolean =
        withContext(Dispatchers.IO) { execute(method) }

    private suspend fun executeSafe(method: SetMyCommands): Boolean =
        withContext(Dispatchers.IO) { execute(method) }

    private suspend fun executeSafe(method: SendChatAction): Boolean =
        withContext(Dispatchers.IO) { execute(method) }
}