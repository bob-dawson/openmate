package com.openmate.core.common.crash

import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File

class CrashLogManagerTest {

    private lateinit var crashDir: File
    private lateinit var prefs: FakePrefs
    private lateinit var manager: CrashLogManager

    @Before
    fun setup() {
        crashDir = File(System.getProperty("java.io.tmpdir"), "crash_test_${System.nanoTime()}").also { it.mkdirs() }
        prefs = FakePrefs()
        manager = CrashLogManager(crashDir, prefs)
    }

    @After
    fun teardown() {
        crashDir.deleteRecursively()
    }

    @Test
    fun `getReports returns empty when no crash files`() {
        assertThat(manager.getReports()).isEmpty()
    }

    @Test
    fun `getReports parses crash files sorted by timestamp descending`() {
        File(crashDir, "crash_1000.log").writeText("Time: t1\nThread: main\nDevice: d\n\nNullPointerException: msg\nstack")
        File(crashDir, "crash_2000.log").writeText("Time: t2\nThread: main\nDevice: d\n\nIllegalStateException: msg\nstack")

        val reports = manager.getReports()
        assertThat(reports).hasSize(2)
        assertThat(reports[0].timestamp).isEqualTo(2000L)
        assertThat(reports[1].timestamp).isEqualTo(1000L)
    }

    @Test
    fun `getReports ignores files without crash_ prefix`() {
        File(crashDir, "other.txt").writeText("stuff")
        assertThat(manager.getReports()).isEmpty()
    }

    @Test
    fun `deleteReport removes the file`() {
        val file = File(crashDir, "crash_1000.log")
        file.writeText("crash data")
        manager.deleteReport(1000L)
        assertThat(file.exists()).isFalse()
    }

    @Test
    fun `deleteAllReports removes all crash files`() {
        File(crashDir, "crash_1000.log").writeText("a")
        File(crashDir, "crash_2000.log").writeText("b")
        File(crashDir, "other.txt").writeText("c")
        manager.deleteAllReports()
        assertThat(crashDir.listFiles()?.filter { it.name.startsWith("crash_") }).isEmpty()
        assertThat(File(crashDir, "other.txt").exists()).isTrue()
    }

    @Test
    fun `unreadCount returns 0 when no crashes`() {
        assertThat(manager.getUnreadCount()).isEqualTo(0)
    }

    @Test
    fun `unreadCount counts crashes newer than lastReadTimestamp`() {
        File(crashDir, "crash_1000.log").writeText("Time: t1\nThread: main\nDevice: d\n\nNPE: m\ns")
        File(crashDir, "crash_2000.log").writeText("Time: t2\nThread: main\nDevice: d\n\nISE: m\ns")
        assertThat(manager.getUnreadCount()).isEqualTo(2)
    }

    @Test
    fun `markAllRead sets lastReadTimestamp to latest crash`() {
        File(crashDir, "crash_1000.log").writeText("Time: t1\nThread: main\nDevice: d\n\nNPE: m\ns")
        File(crashDir, "crash_2000.log").writeText("Time: t2\nThread: main\nDevice: d\n\nISE: m\ns")
        manager.markAllRead()
        assertThat(prefs.getLong("crash_last_read", 0L)).isEqualTo(2000L)
    }

    @Test
    fun `unreadCount returns 0 after markAllRead`() {
        File(crashDir, "crash_1000.log").writeText("Time: t1\nThread: main\nDevice: d\n\nNPE: m\ns")
        manager.markAllRead()
        assertThat(manager.getUnreadCount()).isEqualTo(0)
    }

    @Test
    fun `getReportContent returns raw text`() {
        val content = "Time: t1\nThread: main\nDevice: d\n\nNPE: m\nstack trace here"
        File(crashDir, "crash_1000.log").writeText(content)
        assertThat(manager.getReportContent(1000L)).isEqualTo(content)
    }

    class FakePrefs : SharedPreferences {
        private val map = mutableMapOf<String, Any>()

        override fun getLong(key: String, defValue: Long): Long = map[key] as? Long ?: defValue

        override fun edit(): SharedPreferences.Editor = FakeEditor(map)

        override fun getAll(): Map<String, *> = map
        override fun getString(key: String, defValue: String?): String? = map[key] as? String ?: defValue
        override fun getInt(key: String, defValue: Int): Int = map[key] as? Int ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = map[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
        override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? = (map[key] as? Set<*>)?.mapNotNull { it as? String }?.toSet() ?: defValues
        override fun contains(key: String): Boolean = map.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {}

        class FakeEditor(private val map: MutableMap<String, Any>) : SharedPreferences.Editor {
            override fun putLong(key: String, value: Long): SharedPreferences.Editor { map[key] = value; return this }
            override fun putString(key: String, value: String?): SharedPreferences.Editor { map[key] = value ?: ""; return this }
            override fun putInt(key: String, value: Int): SharedPreferences.Editor { map[key] = value; return this }
            override fun putFloat(key: String, value: Float): SharedPreferences.Editor { map[key] = value; return this }
            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor { map[key] = value; return this }
            override fun putStringSet(key: String, values: Set<String>?): SharedPreferences.Editor { map[key] = values ?: emptySet<String>(); return this }
            override fun remove(key: String): SharedPreferences.Editor { map.remove(key); return this }
            override fun clear(): SharedPreferences.Editor { map.clear(); return this }
            override fun commit(): Boolean = true
            override fun apply() {}
        }
    }
}
