package io.github.jman9700.quartermaster.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class AppDirsTest {
    @Test
    fun windowsUsesAppData() {
        val dir = AppDirs.resolve("Windows 11", mapOf("APPDATA" to "C:\\Users\\me\\AppData\\Roaming")::get, "C:\\Users\\me")
        assertEquals(File("C:\\Users\\me\\AppData\\Roaming", "QuarterMaster"), dir)
    }

    @Test
    fun linuxUsesXdgDataHomeOrLocalShare() {
        assertEquals(File("/data", "quartermaster"), AppDirs.resolve("Linux", mapOf("XDG_DATA_HOME" to "/data")::get, "/home/me"))
        assertEquals(File("/home/me/.local/share", "quartermaster"), AppDirs.resolve("Linux", { null }, "/home/me"))
    }
}
