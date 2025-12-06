package emily.app

import emily.bot.EmilyVirtualGirlBot
import emily.data.BalanceRepository
import emily.data.ChatHistoryRepository
import emily.data.StorySelectionRepository
import emily.service.ChatService
import emily.service.ConversationMemory
import emily.service.ImageService
import emily.service.MyMemoryTranslator
import emily.service.defaultSystemPrompt
import emily.firebase.FirebaseInitializer
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.telegram.telegrambots.meta.TelegramBotsApi
import org.telegram.telegrambots.meta.api.methods.updates.DeleteWebhook
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession

private const val CHAT_MODEL = "venice-uncensored"

// две разные модели под разные стили
private const val IMAGE_MODEL_ANIME = "wai-Illustrious"
private const val IMAGE_MODEL_REALISTIC = "lustify-v7"

fun main() {
    SingleInstance.acquire(44601)
    BotRunGuard.tryLockOrExit()

    FirebaseInitializer(
        credentialsPath = "emilyvirtualgirlbot-firebase-adminsdk-fbsvc-2b1c251dfd.json",
        databaseUrl = "https://emilyvirtualgirlbot-default-rtdb.firebaseio.com"
    ).init()

    val config = BotConfig(
        telegramToken = "8341155085:AAGl_Ba7IGAjC1OIEPfJIW5Mo_cOayofySU",
        providerToken = "390540012:LIVE:78849",
        veniceToken = "kK5vqPy3fU32foa_h06s04rkb6ELejHeCMr0S1_8Sq"
    )

    val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BODY })
        .connectTimeout(java.time.Duration.ofSeconds(20))
        .readTimeout(java.time.Duration.ofSeconds(120))
        .writeTimeout(java.time.Duration.ofSeconds(30))
        .build()

    // сейчас использует MyMemory, позже можно будет легко заменить на Яндекс
    val translator = MyMemoryTranslator(okHttpClient)

    val repo = BalanceRepository()
    val selectionRepository = StorySelectionRepository()
    val chatHistoryRepository = ChatHistoryRepository()
    val chatService = ChatService(okHttpClient, config.veniceToken, CHAT_MODEL)

    // отдельные ImageService под аниме и реализм
    val animeImageService = ImageService(okHttpClient, config.veniceToken, IMAGE_MODEL_ANIME)
    val realisticImageService = ImageService(okHttpClient, config.veniceToken, IMAGE_MODEL_REALISTIC)

    val memory = ConversationMemory { "" }

    val bot = EmilyVirtualGirlBot(
        config = config,
        repository = repo,
        selectionRepository = selectionRepository,
        chatHistoryRepository= chatHistoryRepository,
        chatService = chatService,
        animeImageService = animeImageService,
        realisticImageService = realisticImageService,
        memory = memory,
        translator = translator
    )

    val botsApi = TelegramBotsApi(DefaultBotSession::class.java)
    runCatching { bot.execute(DeleteWebhook()) }
    botsApi.registerBot(bot)
    bot.registerBotMenu()
}
