@file:Suppress("UNUSED_VARIABLE")

import org.telegram.telegrambots.bots.TelegramLongPollingBot
import org.telegram.telegrambots.meta.TelegramBotsApi
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.api.objects.Message
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto
import org.telegram.telegrambots.meta.api.methods.send.SendChatAction
import org.telegram.telegrambots.meta.api.methods.invoices.SendInvoice
import org.telegram.telegrambots.meta.api.objects.payments.LabeledPrice
import org.telegram.telegrambots.meta.api.objects.InputFile
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand
import org.telegram.telegrambots.meta.api.objects.commands.scope.BotCommandScopeDefault
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONArray
import org.json.JSONObject

import java.io.ByteArrayInputStream
import java.net.ServerSocket
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.time.LocalDate
import kotlin.math.ceil
import kotlin.math.max
import com.deepl.api.Translator

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.database.*
import org.telegram.telegrambots.meta.api.methods.ActionType
import org.telegram.telegrambots.meta.api.methods.AnswerPreCheckoutQuery
import java.io.FileInputStream
import java.util.concurrent.*
import kotlin.system.exitProcess
import java.util.Locale
import java.util.concurrent.ThreadLocalRandom
import org.telegram.telegrambots.meta.api.methods.updates.DeleteWebhook
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage

/* ───────────────── Single instance ───────────────── */

object BotRunGuard {
    private val registered = AtomicBoolean(false)
    fun tryLockOrExit() {
        if (!registered.compareAndSet(false, true)) exitProcess(1)
    }
}

fun main() {
    SingleInstance.acquire(44569)
    BotRunGuard.tryLockOrExit()
    initFirebase()

    val api = TelegramBotsApi(DefaultBotSession::class.java)
    val bot = EmilyVirtualGirlBot()

    try { bot.execute(DeleteWebhook()) } catch (_: Exception) {}

    api.registerBot(bot)
    bot.registerBotMenu()
}

object SingleInstance {
    private var lock: ServerSocket? = null
    fun acquire(port: Int) {
        try { lock = ServerSocket(port) } catch (_: Exception) { exitProcess(1) }
    }
}

/* ───────────────── Context trimming ───────────────── */

private const val MAX_CONTEXT_MESSAGES = 20
private fun trimmedHistory(history: List<Pair<String, String>>): List<Pair<String, String>> {
    val sys = history.firstOrNull { it.first == "system" }
    val rest = history.filter { it.first != "system" }
    val tail = if (rest.size > MAX_CONTEXT_MESSAGES) rest.takeLast(MAX_CONTEXT_MESSAGES) else rest
    return buildList { if (sys != null) add(sys); addAll(tail) }
}

/* ───────────────── Noise filter & autoclean ───────────────── */

private val NOISE_REGEX = Regex("""^([/#][\p{L}\p{N}_@-]+.*|\s*)$""", RegexOption.IGNORE_CASE)
private fun shouldSkipForContext(text: String): Boolean = NOISE_REGEX.matches(text.trim())

private fun defaultSystemPrompt(): String =
    "Ты — Эмили, виртуальная девушка: тёплая, игривая, поддерживающая и немного пошлая! " +
            "любишь немного флиртовать. Отвечай дружелюбно и запоминай контекст разговора."

/* ───────────────── Firebase ───────────────── */

fun initFirebase() {
    val credsPath = "emilyvirtualgirlbot-firebase-adminsdk-fbsvc-2b1c251dfd.json"
    val dbUrl = "https://emilyvirtualgirlbot-default-rtdb.firebaseio.com"
    FileInputStream(credsPath).use { serviceAccount ->
        val options = FirebaseOptions.builder()
            .setCredentials(GoogleCredentials.fromStream(serviceAccount))
            .setDatabaseUrl(dbUrl)
            .build()
        if (FirebaseApp.getApps().isEmpty()) FirebaseApp.initializeApp(options)
    }
}

/* ───────────────── Plans ───────────────── */

enum class Plan(
    val code: String,
    val title: String,
    val priceRub: Int,
    val monthlyTextTokens: Int,
    val monthlyImageCredits: Int,
    val photoUrl: String
) {
    BASIC(
        "basic",
        "Скромница",
        399,
        100_000,
        15,
        "https://drive.google.com/uc?export=download&id=1TCRXGBCDeju4zjER_lUvsn5yZPcv-V7s"
    ),
    PRO(
        "pro",
        "Шлюшка",
        650,
        300_000,
        50,
        "https://drive.google.com/uc?export=download&id=1a3kI5IXbX95QMSpRb72vj0RRIKaXs9T6"
    ),
    ULTRA(
        "ultra",
        "Грязная развратница",
        1800,
        800_000,
        150,
        "https://drive.google.com/uc?export=download&id=1IYIATc4zTZvKuXLfc5G08ALBZNG8fE32"
    );
}

enum class ImagePack(
    val code: String,
    val title: String,
    val priceRub: Int,
    val images: Int,
    val photoUrl: String
) {
    P10("pack10", "Фото для возбуждения", 99, 10,
        "https://drive.google.com/uc?export=download&id=1pojAKJs7hChiLZhF_27HEKCv6vktDfac"),
    P50("pack50", "Порочный альбом", 249, 50,
        "https://drive.google.com/uc?export=download&id=1f67uMVIMFWCe4DvQU4GlgnI5vx0cH6iC");
}

/* ───────────────── Free quota ───────────────── */

const val FREE_TEXT_TOKENS = 12_000
const val FREE_IMAGE_CREDITS = 1

const val DAILY_IMAGE_CAP_BASIC = 10
const val DAILY_IMAGE_CAP_PRO = 25
const val DAILY_IMAGE_CAP_ULTRA = 60

data class UserBalance(
    val userId: Long = 0L,
    var plan: String? = null,
    var planExpiresAt: Long? = null,
    var textTokensLeft: Int = FREE_TEXT_TOKENS,
    var imageCreditsLeft: Int = FREE_IMAGE_CREDITS,
    var dayImageUsed: Int = 0,
    var dayStamp: String = LocalDate.now().toString(),
    var createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis()
)

private fun blockingGet(ref: DatabaseReference, timeoutMs: Long = 10_000): DataSnapshot {
    val latch = CountDownLatch(1)
    var result: DataSnapshot? = null
    var error: Exception? = null
    ref.addListenerForSingleValueEvent(object : ValueEventListener {
        override fun onDataChange(snapshot: DataSnapshot) { result = snapshot; latch.countDown() }
        override fun onCancelled(dbError: DatabaseError) { error = RuntimeException(dbError.toException()); latch.countDown() }
    })
    if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS))
        throw TimeoutException("Firebase get() timeout after ${timeoutMs}ms for path: ${ref.path}")
    error?.let { throw it }
    return result ?: throw IllegalStateException("Snapshot is null for path: ${ref.path}")
}

class BalanceRepo {
    private val db by lazy { FirebaseDatabase.getInstance() }
    private val balancesRef by lazy { db.getReference("balances") }
    private val paymentsRef by lazy { db.getReference("payments") }

    fun get(userId: Long): UserBalance {
        val ref: DatabaseReference = balancesRef.child(userId.toString())
        val snap: DataSnapshot = blockingGet(ref)
        return if (snap.exists()) {
            UserBalance(
                userId = userId,
                plan = snap.child("plan").getValue(String::class.java),
                planExpiresAt = snap.child("planExpiresAt").getValue(Long::class.java),
                textTokensLeft = snap.child("textTokensLeft").getValue(Long::class.java)?.toInt() ?: FREE_TEXT_TOKENS,
                imageCreditsLeft = snap.child("imageCreditsLeft").getValue(Long::class.java)?.toInt() ?: FREE_IMAGE_CREDITS,
                dayImageUsed = snap.child("dayImageUsed").getValue(Long::class.java)?.toInt() ?: 0,
                dayStamp = snap.child("dayStamp").getValue(String::class.java) ?: LocalDate.now().toString(),
                createdAt = snap.child("createdAt").getValue(Long::class.java) ?: System.currentTimeMillis(),
                updatedAt = snap.child("updatedAt").getValue(Long::class.java) ?: System.currentTimeMillis()
            )
        } else {
            val def = UserBalance(userId = userId) // тут выдаются бесплатные лимиты
            put(def)
            def
        }
    }

    fun put(b: UserBalance) {
        b.updatedAt = System.currentTimeMillis()
        val m = mapOf(
            "userId" to b.userId,
            "plan" to b.plan,
            "planExpiresAt" to b.planExpiresAt,
            "textTokensLeft" to b.textTokensLeft,
            "imageCreditsLeft" to b.imageCreditsLeft,
            "dayImageUsed" to b.dayImageUsed,
            "dayStamp" to b.dayStamp,
            "createdAt" to b.createdAt,
            "updatedAt" to b.updatedAt
        )
        balancesRef.child(b.userId.toString()).setValueAsync(m)
    }

    fun addPayment(userId: Long, payload: String, amountRub: Int) {
        val id = UUID.randomUUID().toString()
        val m = mapOf("payload" to payload, "amountRub" to amountRub, "ts" to System.currentTimeMillis())
        paymentsRef.child(userId.toString()).child(id).setValueAsync(m)
    }

    fun logUsage(userId: Long, tokens: Int, meta: Map<String, Any?> = emptyMap()) {
        val id = UUID.randomUUID().toString()
        val m = mutableMapOf<String, Any?>("tokens" to tokens, "ts" to System.currentTimeMillis())
        m.putAll(meta)
        paymentsRef.child(userId.toString()).child("usage").child(id).setValueAsync(m)
    }
}

/* ───────────────── Provider data ───────────────── */

private fun rubToStrRub(rub: Int) = String.format(Locale.US, "%.2f", rub.toDouble())
private fun makeProviderData(desc: String, rub: Int, includeVat: Boolean = true): String {
    val item = JSONObject()
        .put("description", desc.take(128))
        .put("quantity", "1")
        .put("amount", JSONObject().put("value", rubToStrRub(rub)).put("currency", "RUB"))
        .apply { if (includeVat) put("vat_code", 1) }
    val receipt = JSONObject().put("items", JSONArray().put(item))
    return JSONObject().put("receipt", receipt).toString()
}

/* ───────────────── System message tracker ───────────────── */

private val systemMsgIds = ConcurrentHashMap<Long, MutableList<Int>>()        // чат → список id системных постов
private val protectedMsgIds = ConcurrentHashMap<Long, MutableSet<Int>>()      // чат → id защищённых (инвойсы)

private fun rememberSystemMsg(chatId: Long, messageId: Int) {
    val list = systemMsgIds.computeIfAbsent(chatId) { Collections.synchronizedList(mutableListOf()) }
    list += messageId
}

private fun markProtected(chatId: Long, messageId: Int) {
    val set = protectedMsgIds.computeIfAbsent(chatId) { Collections.synchronizedSet(mutableSetOf()) }
    set += messageId
}

private fun isProtected(chatId: Long, messageId: Int): Boolean =
    protectedMsgIds[chatId]?.contains(messageId) == true

private fun EmilyVirtualGirlBot.deleteOldSystemMessages(chatId: Long) {
    val list = systemMsgIds[chatId] ?: return
    val it = list.iterator()
    while (it.hasNext()) {
        val mid = it.next()
        if (isProtected(chatId, mid)) continue
        runCatching { execute(DeleteMessage(chatId.toString(), mid)) }
        it.remove()
    }
}

/* ───────────────── Ephemeral (TTL) ───────────────── */

private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
    Thread(r, "ephemeral-cleaner").apply { isDaemon = true }
}

private fun EmilyVirtualGirlBot.sendEphemeral(chatId: Long, text: String, ttlSeconds: Long = 12, html: Boolean = false) {
    val m = SendMessage(chatId.toString(), text).apply { if (html) parseMode = "HTML" }
    val sent = execute(m)
    scheduler.schedule({
        runCatching { execute(DeleteMessage(chatId.toString(), sent.messageId)) }
    }, ttlSeconds, TimeUnit.SECONDS)
}

/* ───────────────── Helper: delete user command message ───────────────── */

private fun isDeletableCommand(text: String): Boolean {
    val t = text.trim().lowercase()
    return t == "/start" || t == "/buy" || t == "/balance" || t == "/reset" || t == "/pic"
}

private fun EmilyVirtualGirlBot.deleteUserCommandMessage(chatId: Long, messageId: Int, text: String) {
    if (isDeletableCommand(text)) {
        runCatching { execute(DeleteMessage(chatId.toString(), messageId)) }
    }
}

/* ───────────────── Bot ───────────────── */

private val userContext = ConcurrentHashMap<Long, MutableList<Pair<String, String>>>()

class EmilyVirtualGirlBot : TelegramLongPollingBot() {

    private val telegramToken: String = "8341155085:AAGl_Ba7IGAjC1OIEPfJIW5Mo_cOayofySU"
    private val providerToken1: String = "390540012:LIVE:78849"
    private val veniceToken: String = "8NgXj7n0BrXVvm8dyIgCFmAxAioOhpLIGNKI3KKzAJ"
    private val deeplKey: String = "2a72f4e3-6b4d-4d44-9dab-1f337803eb34:fx"

    override fun getBotUsername(): String = "EmilyVirtualGirlBot"
    override fun getBotToken(): String = telegramToken

    private val chatModel = "venice-uncensored"
    private val imageModel = "wai-Illustrious"
    private val IMAGE_TAG = "#pic"

    private val JSON = "application/json".toMediaType()
    private val client = OkHttpClient.Builder()
        .addInterceptor(HttpLoggingInterceptor { }.apply { level = HttpLoggingInterceptor.Level.BODY })
        .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val deepl: Translator? = try { if (deeplKey.isNotBlank()) Translator(deeplKey) else null } catch (_: Exception) { null }
    private fun translateRuToEn(text: String): String? = try { deepl?.translateText(text, "ru", "en-US")?.text } catch (_: Exception) { null }

    private val repo = BalanceRepo()

    override fun onUpdateReceived(update: Update) {
        try {
            if (update.hasPreCheckoutQuery()) {
                val ans = AnswerPreCheckoutQuery().apply {
                    preCheckoutQueryId = update.preCheckoutQuery.id
                    ok = true
                }
                execute(ans)
                return
            }

            if (update.hasMessage() && update.message.successfulPayment != null) {
                onSuccessfulPayment(update.message)
                return
            }

            if (update.hasMessage() && update.message.hasText()) {
                val chatId = update.message.chatId
                val textRaw = update.message.text.trim()
                val userMsgId = update.message.messageId

                when {
                    textRaw.equals("/start", true) -> {
                        initContextIfNeeded(chatId)
                        ensureUserBalance(chatId)
                        autoCleanContext(chatId)
                        deleteOldSystemMessages(chatId)
                        sendWelcomeSystem(chatId)
                        deleteUserCommandMessage(chatId, userMsgId, textRaw)
                    }
                    textRaw.equals("/buy", true) -> {
                        ensureUserBalance(chatId)
                        autoCleanContext(chatId)
                        deleteOldSystemMessages(chatId)
                        sendBuyMenuSystem(chatId)
                        deleteUserCommandMessage(chatId, userMsgId, textRaw)
                    }
                    textRaw.equals("/balance", true) -> {
                        val b = ensureUserBalance(chatId)
                        autoCleanContext(chatId)
                        deleteOldSystemMessages(chatId)
                        sendBalanceSystem(chatId, b)
                        deleteUserCommandMessage(chatId, userMsgId, textRaw)
                    }
                    textRaw.equals("/reset", true) -> {
                        userContext.remove(chatId)
                        deleteOldSystemMessages(chatId)
                        sendEphemeral(chatId, "Память диалога очищена 🙈", 10)
                        deleteUserCommandMessage(chatId, userMsgId, textRaw)
                    }
                    // одиночное /pic — это тоже команда → удаляем; /pic с описанием не трогаем
                    textRaw.equals("/pic", true) -> {
                        sendEphemeral(chatId, "Формат: отправь сообщение вида:\n#pic описание сцены", 20)
                        deleteUserCommandMessage(chatId, userMsgId, textRaw)
                    }
                    textRaw.startsWith(IMAGE_TAG, true) || textRaw.startsWith("/pic ", true) -> {
                        ensureUserBalance(chatId); autoCleanContext(chatId)
                        handleImage(chatId, textRaw)
                    }
                    else -> {
                        ensureUserBalance(chatId); autoCleanContext(chatId)
                        handleChat(chatId, textRaw)
                    }
                }
            } else if (update.hasCallbackQuery()) {
                val chatId = update.callbackQuery.message.chatId
                val cb = update.callbackQuery.data
                autoCleanContext(chatId)

                // удаляем прошлые системные посты (кроме инвойсов)
                deleteOldSystemMessages(chatId)

                when {
                    cb.startsWith("buy:plan:") -> createPlanInvoice(chatId, cb.removePrefix("buy:plan:"))
                    cb.startsWith("buy:pack:") -> createPackInvoice(chatId, cb.removePrefix("buy:pack:"))
                }
            }
        } catch (_: Exception) { }
    }

    /* ─────────── System sends ─────────── */

    private fun sendWelcomeSystem(chatId: Long) {
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
        val m = SendMessage(chatId.toString(), text)
        val sent = execute(m)
        rememberSystemMsg(chatId, sent.messageId)
    }

    private fun sendBalanceSystem(chatId: Long, b: UserBalance) {
        val planTitle = when (b.plan) {
            Plan.BASIC.code -> Plan.BASIC.title
            Plan.PRO.code -> Plan.PRO.title
            Plan.ULTRA.code -> Plan.ULTRA.title
            else -> "нет (Free)"
        }
        val until = b.planExpiresAt?.let { java.time.Instant.ofEpochMilli(it).toString() } ?: "—"
        val text = """
<b>План:</b> $planTitle
<b>Действует до:</b> $until
<b>Текущие текстовые токены:</b> ${b.textTokensLeft}
<b>Кредиты на изображения:</b> ${b.imageCreditsLeft}
<b>Сегодня использовано изображений:</b> ${b.dayImageUsed}
""".trimIndent()
        val m = SendMessage(chatId.toString(), text).apply { parseMode = "HTML" }
        val sent = execute(m)
        rememberSystemMsg(chatId, sent.messageId)
    }

    private fun sendBuyMenuSystem(chatId: Long) {
        val rows = mutableListOf<List<InlineKeyboardButton>>()
        Plan.values().forEach { p ->
            rows += listOf(
                InlineKeyboardButton().apply {
                    text = "${p.title} (${p.priceRub}₽/мес)"
                    callbackData = "buy:plan:${p.code}"
                }
            )
        }
        rows += listOf(InlineKeyboardButton().apply {
            text = "Фото для возбуждения (10)"
            callbackData = "buy:pack:${ImagePack.P10.code}"
        })
        rows += listOf(InlineKeyboardButton().apply {
            text = "Порочный альбом (50)"
            callbackData = "buy:pack:${ImagePack.P50.code}"
        })
        val kb = InlineKeyboardMarkup().apply { keyboard = rows }
        val m = SendMessage(chatId.toString(), "Выбери пакет. После оплаты баланс пополнится автоматически.\n\nПодписка идёт без автопродления")
        m.replyMarkup = kb
        val sent = execute(m)
        rememberSystemMsg(chatId, sent.messageId)
    }

    /* ─────────── Invoices (protected) ─────────── */

    private fun safeExecuteInvoice(chatId: Long, inv: SendInvoice) {
        try {
            val sent: Message = execute(inv) // сообщение-инвойс
            markProtected(chatId, sent.messageId) // не удаляем чистилкой
        } catch (e: TelegramApiRequestException) {
            val msg = buildString {
                appendLine("Invoice error:")
                appendLine("message=${e.message}")
                appendLine("apiResponse=${e.apiResponse}")
                appendLine("parameters=${e.parameters}")
            }
            sendEphemeral(chatId, "❌ $msg", 20)
        } catch (e: Exception) {
            sendEphemeral(chatId, "❌ Unexpected invoice error: ${e.message ?: e.toString()}", 20)
        }
    }

    private fun createPlanInvoice(chatId: Long, planCode: String) {
        val plan = Plan.values().find { it.code == planCode } ?: return
        val payloadStr = "plan:${plan.code}:${UUID.randomUUID()}"
        val providerDataStr = makeProviderData(
            desc = "Пакет ${plan.title} — 30 дней. Текстовые токены + кредиты изображений.",
            rub  = plan.priceRub,
            includeVat = true
        )

        val inv = SendInvoice().apply {
            this.chatId = chatId.toString()
            title = "Пакет: ${plan.title}"
            description = "30 дней: ${plan.monthlyTextTokens} токенов и ${plan.monthlyImageCredits} изображений."
            payload = payloadStr
            providerToken = providerToken1
            currency = "RUB"
            startParameter = "plan-${plan.code}"
            prices = listOf(LabeledPrice("${plan.title} 30 дней", plan.priceRub * 100))
            needEmail = true
            sendEmailToProvider = true
            isFlexible = false
            providerData = providerDataStr
            photoUrl = plan.photoUrl
            photoWidth = 960
            photoHeight = 1280
        }
        safeExecuteInvoice(chatId, inv)
    }

    private fun createPackInvoice(chatId: Long, packCode: String) {
        val pack = ImagePack.values().find { it.code == packCode } ?: return
        val payloadStr = "pack:${pack.code}:${UUID.randomUUID()}"
        val providerDataStr = makeProviderData(
            desc = "${pack.title}. Дополнительные единицы генерации изображений.",
            rub  = pack.priceRub,
            includeVat = true
        )

        val inv = SendInvoice().apply {
            this.chatId = chatId.toString()
            title = pack.title
            description = "Разовый пакет: ${pack.title}"
            payload = payloadStr
            providerToken = providerToken1
            currency = "RUB"
            startParameter = "pack-${pack.code}"
            prices = listOf(LabeledPrice(pack.title, pack.priceRub * 100))
            needEmail = true
            sendEmailToProvider = true
            isFlexible = false
            providerData = providerDataStr
            photoUrl = pack.photoUrl
            photoWidth = 960
            photoHeight = 1280
        }
        safeExecuteInvoice(chatId, inv)
    }

    /* ─────────── Payments → crediting ─────────── */

    private fun onSuccessfulPayment(msg: Message) {
        val chatId = msg.chatId
        val sp = msg.successfulPayment
        val payload = sp.invoicePayload ?: return
        val totalRub = (sp.totalAmount / 100.0).toInt()
        val b = ensureUserBalance(chatId)
        when {
            payload.startsWith("plan:") -> {
                val code = payload.split(":").getOrNull(1)
                val plan = Plan.values().find { it.code == code } ?: return
                val monthMs = 30L * 24 * 60 * 60 * 1000
                val now = System.currentTimeMillis()
                val base = max(b.planExpiresAt ?: 0L, now)

                b.plan = plan.code
                b.planExpiresAt = base + monthMs
                b.textTokensLeft += plan.monthlyTextTokens
                b.imageCreditsLeft += plan.monthlyImageCredits
                repo.put(b)
                repo.addPayment(chatId, payload, totalRub)
                sendEphemeral(chatId, "✅ Подписка «${plan.title}» активирована до ${java.time.Instant.ofEpochMilli(b.planExpiresAt!!)}.\n" +
                        "Начислено: ${plan.monthlyTextTokens} токенов и ${plan.monthlyImageCredits} изображений.", 20)
            }
            payload.startsWith("pack:") -> {
                val code = payload.split(":").getOrNull(1)
                val pack = ImagePack.values().find { it.code == code } ?: return
                b.imageCreditsLeft += pack.images
                repo.put(b)
                repo.addPayment(chatId, payload, totalRub)
                sendEphemeral(chatId, "✅ Начислено: ${pack.images} изображений по пакету «${pack.title}».", 15)
            }
        }
    }

    /* ─────────── Balance/limits & context ─────────── */

    private fun ensureUserBalance(userId: Long): UserBalance {
        val b = repo.get(userId)
        val now = System.currentTimeMillis()
        if (b.planExpiresAt != null && now > b.planExpiresAt!!) { b.plan = null; b.planExpiresAt = null }
        val today = LocalDate.now().toString()
        if (b.dayStamp != today) { b.dayStamp = today; b.dayImageUsed = 0 }
        repo.put(b)
        return b
    }

    data class ChatResult(val text: String, val tokensUsed: Int, val rawUsage: JSONObject? = null)

    private fun callVeniceChatWithUsage(history: List<Pair<String, String>>): ChatResult {
        val h = trimmedHistory(history)
        val messages = JSONArray().apply { h.forEach { (role, content) -> put(JSONObject().put("role", role).put("content", content)) } }
        val bodyStr = JSONObject().put("model", chatModel).put("messages", messages).toString()
        val req = Request.Builder()
            .url("https://api.venice.ai/api/v1/chat/completions")
            .header("Authorization", "Bearer $veniceToken")
            .header("Accept", "application/json")
            .post(bodyStr.toByteArray(Charsets.UTF_8).toRequestBody(JSON))
            .build()

        client.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) return ChatResult("Проблемы со связью 😢 Попробуем ещё раз?", 0, null)
            val json = JSONObject(raw)
            val content = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")?.ifBlank { "..." } ?: "..."
            var totalTokens = 0
            var usageJson: JSONObject? = null
            json.optJSONObject("usage")?.let { usage ->
                usageJson = usage
                totalTokens = usage.optInt("total_tokens", -1)
                if (totalTokens < 0) totalTokens = usage.optInt("prompt_tokens", 0) + usage.optInt("completion_tokens", 0)
            }
            if (totalTokens <= 0) {
                val lastUserMsg = history.lastOrNull { it.first == "user" }?.second ?: ""
                totalTokens = max(1, ceil(lastUserMsg.length / 4.0).toInt())
            }
            return ChatResult(content, totalTokens, usageJson)
        }
    }

    private fun handleChat(chatId: Long, userText: String) {
        val b = ensureUserBalance(chatId)
        if (b.textTokensLeft <= 0) { sendEphemeral(chatId, "⚠️ У тебя закончились текстовые токены.\nКупи подписку в /buy", 15); return }

        initContextIfNeeded(chatId)

        if (!shouldSkipForContext(userText)) {
            userContext[chatId]?.add("user" to userText)
            userContext[chatId] = trimmedHistory(userContext[chatId]!!).toMutableList()
        }

        val replyRes = withTyping(chatId) { callVeniceChatWithUsage(userContext[chatId]!!) }
        val reply = replyRes.text
        val tokensUsed = replyRes.tokensUsed

        userContext[chatId]?.add("assistant" to reply)
        userContext[chatId] = trimmedHistory(userContext[chatId]!!).toMutableList()

        send(chatId, reply)

        if (tokensUsed > 0) {
            b.textTokensLeft -= tokensUsed
            if (b.textTokensLeft < 0) b.textTokensLeft = 0
            repo.put(b)
            repo.logUsage(chatId, tokensUsed, meta = mapOf("type" to "chat", "model" to chatModel))
        }

        if (b.plan == null && (b.textTokensLeft <= 0))
            sendEphemeral(chatId, "Бесплатный лимит исчерпан. Оформи подписку: /buy", 15)
    }

    private fun dailyCap(plan: String?): Int = when (plan) {
        Plan.BASIC.code -> DAILY_IMAGE_CAP_BASIC
        Plan.PRO.code -> DAILY_IMAGE_CAP_PRO
        Plan.ULTRA.code -> DAILY_IMAGE_CAP_ULTRA
        else -> 1
    }

    private fun callVeniceImageAsPng(prompt: String): ByteArray? {
        val persona = """
Emily — petite yet curvy, soft skin, short straight silver hair, green eyes; large natural breasts; 20+; semi-realistic anime proportions.
IMPORTANT: follow pose/hand/gaze/composition exactly as asked.
""".trimIndent()

        val body = JSONObject()
            .put("model", imageModel)
            .put("prompt", "$persona, $prompt")
            .put("seed", ThreadLocalRandom.current().nextInt(0, 1_000_000_000))
            .put("width", 960)
            .put("height", 1280)
            .put("steps", 30)
            .put("format", "png")
            .put("safe_mode", false)
            .toString()

        val req = Request.Builder()
            .url("https://api.venice.ai/api/v1/image/generate")
            .header("Authorization", "Bearer $veniceToken")
            .header("Accept", "application/json")
            .post(body.toByteArray(Charsets.UTF_8).toRequestBody(JSON))
            .build()

        client.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) return null
            val json = JSONObject(raw)
            json.optJSONArray("images")?.let { arr ->
                decodeB64(arr.optString(0))?.let { return it }
            }
            json.optJSONArray("data")?.let { arr ->
                decodeB64(arr.optJSONObject(0)?.optString("b64_json"))?.let { return it }
            }
            decodeB64(json.optString("image"))?.let { return it }
            return null
        }
    }
    private fun handleImage(chatId: Long, textRaw: String) {
        val b = ensureUserBalance(chatId)
        val cap = dailyCap(b.plan)
        if (b.plan == null && b.imageCreditsLeft <= 1) {
            sendEphemeral(chatId, "Дневной лимит изображений исчерпан ($cap). Попробуй завтра или купи пакет /buy.", 20); return
        }
        if (b.imageCreditsLeft <= 0) {
            sendEphemeral(chatId, "У тебя нет кредитов на изображения. Купи подписку или пакет: /buy", 20); return
        }

        val originalPrompt = textRaw.removePrefix(IMAGE_TAG).removePrefix("/pic").trim()
        if (originalPrompt.isBlank()) { sendEphemeral(chatId, "После #pic укажи описание 🙂", 10); return }
        if (!isPromptAllowed(originalPrompt)) { sendEphemeral(chatId, "❌ Нельзя темы про несовершеннолетних/насилие/принуждение.", 15); return }

        val containsCyrillic = originalPrompt.any { it.code in 0x0400..0x04FF }
        val finalPrompt = if (containsCyrillic) withUploadPhoto(chatId) { (translateRuToEn(originalPrompt) ?: originalPrompt) } else originalPrompt

        val bytes = withUploadPhoto(chatId) { callVeniceImageAsPng(finalPrompt) }
        if (bytes == null) { sendEphemeral(chatId, "Не удалось сгенерировать изображение. Попробуй ещё раз.", 12); return }

        sendPhotoBytes(chatId, bytes, null)

        b.imageCreditsLeft -= 1
        b.dayImageUsed += 1
        repo.put(b)
        repo.logUsage(chatId, 0, meta = mapOf("type" to "image", "model" to imageModel, "credits_used" to 1))

        if (b.plan == null && (b.textTokensLeft <= 0 || b.imageCreditsLeft <= 0))
            sendEphemeral(chatId, "Бесплатный лимит исчерпан. Оформи подписку: /buy", 15)
    }

    /* ─────────── Utils ─────────── */

    private fun initContextIfNeeded(chatId: Long) {
        if (!userContext.containsKey(chatId)) {
            userContext[chatId] = mutableListOf("system" to defaultSystemPrompt())
        }
    }

    private fun autoCleanContext(chatId: Long) {
        val cur = userContext[chatId] ?: return
        var seenSystem = false
        val cleaned = mutableListOf<Pair<String, String>>()
        for ((role, content) in cur) {
            if (role == "system") {
                if (!seenSystem) { cleaned += "system" to content; seenSystem = true }
                continue
            }
            if (shouldSkipForContext(content)) continue
            cleaned += role to content
        }
        if (!seenSystem) cleaned.add(0, "system" to defaultSystemPrompt())
        userContext[chatId] = trimmedHistory(cleaned).toMutableList()
    }

    private fun isPromptAllowed(text: String): Boolean {
        val t = text.lowercase()
        val bad = listOf(
            "несовершеннолет", "школьник", "школьница", "подрост", "minor", "teen", "loli", "shota",
            "изнасил", "насилие", "принужд", "без согласи", "rape", "forced"
        )
        return bad.none { t.contains(it) }
    }

    private fun decodeB64(b64: String?): ByteArray? {
        if (b64.isNullOrBlank()) return null
        val clean = b64.replace("\\s".toRegex(), "")
        return runCatching { Base64.getDecoder().decode(clean) }.getOrNull()
    }

    private fun <T> withChatAction(chatId: Long, action: String, work: () -> T): T {
        val running = AtomicBoolean(true)
        val th = Thread {
            try {
                while (running.get()) {
                    execute(
                        SendChatAction.builder()
                            .chatId(chatId.toString())
                            .action(action)
                            .build()
                    )
                    Thread.sleep(1000)
                }
            } catch (_: Exception) { }
        }
        th.isDaemon = true
        th.start()
        return try { work() } finally { running.set(false); th.interrupt() }
    }

    private fun <T> withTyping(chatId: Long, work: () -> T) =
        withChatAction(chatId, ActionType.TYPING.toString(), work)

    private fun <T> withUploadPhoto(chatId: Long, work: () -> T) =
        withChatAction(chatId, ActionType.UPLOADPHOTO.toString(), work)

    private fun send(chatId: Long, text: String, html: Boolean = false) {
        val m = SendMessage(chatId.toString(), text)
        if (html) m.parseMode = "HTML"
        execute(m)
    }

    private fun send(chatId: Long, text: String, markup: InlineKeyboardMarkup?) {
        val m = SendMessage(chatId.toString(), text)
        m.replyMarkup = markup
        execute(m)
    }

    private fun sendPhotoBytes(chatId: Long, bytes: ByteArray, caption: String?) {
        val photo = SendPhoto()
        photo.chatId = chatId.toString()
        photo.photo = InputFile(ByteArrayInputStream(bytes), "image.png")
        photo.caption = caption ?: "Готово 💕"
        execute(photo)
    }

    fun registerBotMenu() {
        val commands = listOf(
            BotCommand("/start", "Начать общение с Эмили"),
            BotCommand("/buy", "Купить подписку или пакет"),
            BotCommand("/balance", "Посмотреть баланс"),
            BotCommand("/reset", "Очистить память диалога"),
            BotCommand("/pic", "Сгенерировать изображение")
        )
        val setMyCommands = SetMyCommands(commands, BotCommandScopeDefault(), null)
        execute(setMyCommands)
    }
}
