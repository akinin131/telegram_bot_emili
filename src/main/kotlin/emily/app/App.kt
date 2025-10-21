package emily.app

import com.deepl.api.Translator
import emily.bot.EmilyVirtualGirlBot
import emily.data.BalanceRepository
import emily.data.StorySelectionRepository
import emily.service.ChatService
import emily.service.ConversationMemory
import emily.service.ImageService
import emily.service.defaultSystemPrompt
import emily.firebase.FirebaseInitializer
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.telegram.telegrambots.meta.TelegramBotsApi
import org.telegram.telegrambots.meta.api.methods.updates.DeleteWebhook
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession

private const val CHAT_MODEL = "venice-uncensored"
private const val IMAGE_MODEL = "wai-Illustrious"

fun main() {
    SingleInstance.acquire(44569)
    BotRunGuard.tryLockOrExit()

    FirebaseInitializer(
        credentialsPath = "emilyvirtualgirlbot-firebase-adminsdk-fbsvc-2b1c251dfd.json",
        databaseUrl = "https://emilyvirtualgirlbot-default-rtdb.firebaseio.com"
    ).init()

    val config = BotConfig(
        telegramToken = "8341155085:AAGl_Ba7IGAjC1OIEPfJIW5Mo_cOayofySU",
        providerToken = "390540012:LIVE:78849",
        veniceToken = "kK5vqPy3fU32foa_h06s04rkb6ELejHeCMr0S1_8Sq",
        deeplToken = "2a72f4e3-6b4d-4d44-9dab-1f337803eb34:fx"
    )

    val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BODY })
        .connectTimeout(java.time.Duration.ofSeconds(20))
        .readTimeout(java.time.Duration.ofSeconds(120))
        .writeTimeout(java.time.Duration.ofSeconds(30))
        .build()

    val translator = runCatching {
        if (config.deeplToken.isNotBlank()) Translator(config.deeplToken) else null
    }.getOrNull()

    val repo = BalanceRepository()
    val selectionRepository = StorySelectionRepository()
    val chatService = ChatService(okHttpClient, config.veniceToken, CHAT_MODEL)
    val imageService = ImageService(okHttpClient, config.veniceToken, IMAGE_MODEL)
    val memory = ConversationMemory(::defaultSystemPrompt)

    val bot = EmilyVirtualGirlBot(
        config = config,
        repository = repo,
        selectionRepository = selectionRepository,
        chatService = chatService,
        imageService = imageService,
        memory = memory,
        translator = translator
    )

    val botsApi = TelegramBotsApi(DefaultBotSession::class.java)
    runCatching { bot.execute(DeleteWebhook()) }
    botsApi.registerBot(bot)
    bot.registerBotMenu()
}
