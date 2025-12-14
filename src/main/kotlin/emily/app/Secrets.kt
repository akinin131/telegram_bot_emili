package emily.app

import java.io.File
import java.io.FileInputStream
import java.util.*

object Secrets {

    private val props: Properties by lazy {
        val file = File("secrets.properties")
        if (!file.exists()) {
            error("secrets.properties not found in project root")
        }

        Properties().apply {
            FileInputStream(file).use { load(it) }
        }
    }

    fun get(key: String): String =
        props.getProperty(key)
            ?: error("Secret '$key' not found in secrets.properties")
}
