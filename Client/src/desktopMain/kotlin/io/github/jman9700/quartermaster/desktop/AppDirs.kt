package io.github.jman9700.quartermaster.desktop

import java.io.File

/** Where the desktop app keeps its database and photos, following each OS's convention. */
object AppDirs {
    /** `-Dquartermaster.dataDir=...` overrides it, e.g. to try things without touching real data. */
    val data: File by lazy {
        System.getProperty("quartermaster.dataDir")?.let(::File)
            ?: resolve(System.getProperty("os.name").orEmpty(), System::getenv, System.getProperty("user.home"))
    }

    internal fun resolve(osName: String, env: (String) -> String?, home: String): File {
        val os = osName.lowercase()
        return when {
            os.startsWith("windows") ->
                File(env("APPDATA")?.takeIf { it.isNotBlank() } ?: "$home\\AppData\\Roaming", "QuarterMaster")
            os.startsWith("mac") -> File(home, "Library/Application Support/QuarterMaster")
            else -> File(env("XDG_DATA_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.local/share", "quartermaster")
        }
    }
}
