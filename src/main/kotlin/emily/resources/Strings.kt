package emily.resources

import java.text.MessageFormat
import java.util.Locale
import java.util.ResourceBundle
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.asContextElement

object Strings {
    private val localeHolder = ThreadLocal.withInitial { Locale("ru") }
    private val fallbackBundle: ResourceBundle = ResourceBundle.getBundle("strings", Locale.ENGLISH)

    fun localeContext(language: String): ThreadContextElement<Locale> {
        val locale = if (language.lowercase(Locale.ROOT) == "ru") Locale("ru") else Locale.ENGLISH
        return localeHolder.asContextElement(locale)
    }

    fun get(key: String, vararg args: Any?): String {
        val locale = localeHolder.get()
        val bundle = runCatching { ResourceBundle.getBundle("strings", locale) }.getOrElse { fallbackBundle }
        val pattern = runCatching { bundle.getString(key) }.getOrElse { fallbackBundle.getString(key) }
        return if (args.isNotEmpty()) MessageFormat.format(pattern, *args) else pattern
    }
}
