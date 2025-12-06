package emily.resources

import java.text.MessageFormat
import java.util.ResourceBundle

object Strings {
    private val bundle: ResourceBundle = ResourceBundle.getBundle("strings")

    fun get(key: String, vararg args: Any?): String {
        val pattern = bundle.getString(key)
        return if (args.isNotEmpty()) MessageFormat.format(pattern, *args) else pattern
    }
}
