package com.opencode.mobile.vm

import android.content.ContextWrapper
import android.content.SharedPreferences
import com.opencode.mobile.data.ConversationStore
import com.opencode.mobile.data.SettingsStore
import com.opencode.mobile.data.ThinkingEffort
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ChatListSearchTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private class InMemoryPreferences : SharedPreferences {
        private val map = mutableMapOf<String, Any?>()
        override fun getAll(): MutableMap<String, *> = map
        override fun getString(key: String?, defValue: String?): String? = (map[key] as? String) ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String?, defValue: Int): Int = (map[key] as? Int) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (map[key] as? Long) ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (map[key] as? Float) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = (map[key] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            override fun putString(key: String?, value: String?): SharedPreferences.Editor { map[key.orEmpty()] = value; return this }
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = this
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor { map[key.orEmpty()] = value; return this }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor { map[key.orEmpty()] = value; return this }
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor { map[key.orEmpty()] = value; return this }
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor { map[key.orEmpty()] = value; return this }
            override fun remove(key: String?): SharedPreferences.Editor { map.remove(key); return this }
            override fun clear(): SharedPreferences.Editor { map.clear(); return this }
            override fun commit(): Boolean = true
            override fun apply() {}
        }
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }

    @Test
    fun testSearchFilteringByTitleAndModel() = runTest {
        val root = tempFolder.newFolder("files")
        val inMemPrefs = InMemoryPreferences()
        val context = object : ContextWrapper(null) {
            override fun getFilesDir(): File = root
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = inMemPrefs
        }

        val convStore = ConversationStore(context)
        val settingsStore = SettingsStore(context)

        val conv1 = convStore.newConversation("glm-5.3", ThinkingEffort.AUTO).copy(title = "Kotlin Coroutines Guide")
        val conv2 = convStore.newConversation("deepseek-v4-pro", ThinkingEffort.AUTO).copy(title = "Python Data Analysis")
        val conv3 = convStore.newConversation("qwen3.8-max", ThinkingEffort.AUTO).copy(title = "Rust Memory Safety")

        convStore.save(conv1)
        convStore.save(conv2)
        convStore.save(conv3)

        val vm = ChatListViewModel(convStore, settingsStore)

        // Initial state has all 3 conversations
        val stateAll = vm.state.first { it.conversations.size == 3 }
        assertEquals(3, stateAll.totalCount)
        assertEquals(3, stateAll.conversations.size)

        // Filter by title query "Kotlin"
        vm.setSearchQuery("Kotlin")
        val stateKotlin = vm.state.first { it.searchQuery == "Kotlin" }
        assertEquals(1, stateKotlin.conversations.size)
        assertEquals("Kotlin Coroutines Guide", stateKotlin.conversations[0].title)

        // Filter by model query "DeepSeek"
        vm.setSearchQuery("DeepSeek")
        val stateDeepSeek = vm.state.first { it.searchQuery == "DeepSeek" }
        assertEquals(1, stateDeepSeek.conversations.size)
        assertEquals("Python Data Analysis", stateDeepSeek.conversations[0].title)

        // Filter with non-matching query
        vm.setSearchQuery("NonExistentXYZ")
        val stateEmpty = vm.state.first { it.searchQuery == "NonExistentXYZ" }
        assertEquals(0, stateEmpty.conversations.size)
        assertEquals(3, stateEmpty.totalCount)

        // Clear query
        vm.setSearchQuery("")
        val stateReset = vm.state.first { it.searchQuery == "" }
        assertEquals(3, stateReset.conversations.size)
    }
}
