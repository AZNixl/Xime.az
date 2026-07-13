package com.kingzcheung.xime.clipboard

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ClipboardManagerTest {
    
    private lateinit var context: Context
    private lateinit var clipboardManager: ClipboardManager
    
    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        clearClipboardPrefs()
        clipboardManager = ClipboardManager.getInstance(context)
    }
    
    @After
    fun tearDown() {
        clearClipboardPrefs()
    }
    
    private fun clearClipboardPrefs() {
        context.getSharedPreferences("clipboard_prefs", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }
    
    @Test
    fun getInstanceReturnsSingleton() {
        val instance1 = ClipboardManager.getInstance(context)
        val instance2 = ClipboardManager.getInstance(context)
        
        assertEquals(instance1, instance2)
    }
    
    @Test
    fun clipboardItemsEmptyInitially() {
        val items = clipboardManager.clipboardItems.value
        assertTrue("Initial clipboard should be empty", items.isEmpty())
    }
    
    @Test
    fun quickSendItemsEmptyInitially() {
        val items = clipboardManager.quickSendItems.value
        assertTrue("Initial quick send should be empty", items.isEmpty())
    }
    
    @Test
    fun addItemAddsItemToList() {
        clipboardManager.addItem("Test text")
        
        val items = clipboardManager.clipboardItems.value
        assertEquals(1, items.size)
        assertEquals("Test text", items[0].text)
        assertFalse("New item should not be pinned", items[0].isPinned)
    }
    
    @Test
    fun addItemNotAddBlankText() {
        clipboardManager.addItem("")
        clipboardManager.addItem("   ")
        
        val items = clipboardManager.clipboardItems.value
        assertTrue("Blank text should not be added", items.isEmpty())
    }
    
    @Test
    fun addItemUpdatesTimestampForExistingText() {
        clipboardManager.addItem("Test text")
        val firstTimestamp = clipboardManager.clipboardItems.value[0].timestamp
        
        Thread.sleep(10)
        clipboardManager.addItem("Test text")
        
        val items = clipboardManager.clipboardItems.value
        assertEquals("Should still have one item", 1, items.size)
        assertTrue("Timestamp should be updated", items[0].timestamp > firstTimestamp)
        assertEquals("Item should be at top", 0, items.indexOfFirst { it.text == "Test text" })
    }
    
    @Test
    fun addItemMovesExistingItemToTop() {
        clipboardManager.addItem("First")
        clipboardManager.addItem("Second")
        
        val itemsBefore = clipboardManager.clipboardItems.value
        assertEquals(listOf("Second", "First"), itemsBefore.map { it.text })
        
        clipboardManager.addItem("First")
        
        val itemsAfter = clipboardManager.clipboardItems.value
        assertEquals("First should now be at top", listOf("First", "Second"), itemsAfter.map { it.text })
    }
    
    @Test
    fun removeItemRemovesItemById() {
        clipboardManager.addItem("Test text")
        val itemId = clipboardManager.clipboardItems.value[0].id
        
        clipboardManager.removeItem(itemId)
        
        val items = clipboardManager.clipboardItems.value
        assertTrue("Item should be removed", items.isEmpty())
    }
    
    @Test
    fun removeItemNotAffectOtherItems() {
        clipboardManager.addItem("First")
        clipboardManager.addItem("Second")
        val firstId = clipboardManager.clipboardItems.value.find { it.text == "First" }!!.id
        
        clipboardManager.removeItem(firstId)
        
        val items = clipboardManager.clipboardItems.value
        assertEquals(1, items.size)
        assertEquals("Second", items[0].text)
    }
    
    @Test
    fun splitItemSplitsTextIntoCharacters() {
        clipboardManager.addItem("你好")
        val itemId = clipboardManager.clipboardItems.value[0].id
        
        clipboardManager.splitItem(itemId)
        
        val items = clipboardManager.clipboardItems.value
        assertEquals(2, items.size)
        assertEquals("你", items[0].text)
        assertEquals("好", items[1].text)
    }

    @Test
    fun splitItemHandlesSingleCharacter() {
        clipboardManager.addItem("A")
        val itemId = clipboardManager.clipboardItems.value[0].id
        
        clipboardManager.splitItem(itemId)
        
        val items = clipboardManager.clipboardItems.value
        assertEquals(1, items.size)
        assertEquals("A", items[0].text)
    }
    
    @Test
    fun clearAllClearsAllItems() {
        clipboardManager.addItem("Item1")
        clipboardManager.addItem("Item2")
        
        clipboardManager.clearAll()
        
        val items = clipboardManager.clipboardItems.value
        assertEquals(0, items.size)
    }
    
    @Test
    fun clearAllRemovesUnpinnedItems() {
        clipboardManager.addItem("First")
        clipboardManager.addItem("Second")
        clipboardManager.addItem("Third")
        
        clipboardManager.clearAll()
        
        assertTrue("All items should be removed", clipboardManager.clipboardItems.value.isEmpty())
    }
    
    @Test
    fun addToQuickSendAddsItemToQuickSendList() {
        clipboardManager.addItem("Quick text")
        val itemId = clipboardManager.clipboardItems.value[0].id
        
        clipboardManager.addToQuickSend(itemId)
        
        val quickSendItems = clipboardManager.quickSendItems.value
        assertEquals(1, quickSendItems.size)
        assertEquals("Quick text", quickSendItems[0].text)
        assertTrue("Quick send item should be marked", quickSendItems[0].isQuickSend)
        assertTrue("Quick send item should be pinned", quickSendItems[0].isPinned)
    }
    
    @Test
    fun removeFromQuickSendRemovesItem() {
        clipboardManager.addItem("Quick text")
        val itemId = clipboardManager.clipboardItems.value[0].id
        clipboardManager.addToQuickSend(itemId)
        
        clipboardManager.removeFromQuickSend(itemId)
        
        assertTrue("Quick send should be empty", clipboardManager.quickSendItems.value.isEmpty())
    }
    
    @Test
    fun addQuickSendItemAddsTextDirectly() {
        clipboardManager.addQuickSendItem("Direct quick")
        
        val quickSendItems = clipboardManager.quickSendItems.value
        assertEquals(1, quickSendItems.size)
        assertEquals("Direct quick", quickSendItems[0].text)
        assertTrue("Item should be marked as quick send", quickSendItems[0].isQuickSend)
    }
    
    @Test
    fun addQuickSendItemNotAddBlankText() {
        clipboardManager.addQuickSendItem("")
        clipboardManager.addQuickSendItem("   ")
        
        assertTrue("Blank text should not be added to quick send", clipboardManager.quickSendItems.value.isEmpty())
    }
    
    @Test
    fun getCurrentClipboardTextReturnsNullWhenEmpty() {
        val text = clipboardManager.getCurrentClipboardText()
        assertNull("Should return null when clipboard is empty", text)
    }
    
    @Test
    fun copyToSystemClipboardSetsClipboardText() {
        clipboardManager.copyToSystemClipboard("Copied text")
        
        val text = clipboardManager.getCurrentClipboardText()
        assertEquals("Copied text", text)
    }
    
    @Test
    fun clipboardItemHasCorrectDefaultValues() {
        val item = ClipboardItem(text = "Test")
        
        assertNotNull(item.id)
        assertEquals("Test", item.text)
        assertFalse(item.isPinned)
        assertFalse(item.isQuickSend)
        assertTrue(item.timestamp > 0)
    }
    
    @Test
    fun clipboardItemCopyPreservesValues() {
        val original = ClipboardItem(
            id = 123L,
            text = "Original",
            timestamp = 1000L,
            isPinned = true,
            isQuickSend = true
        )
        
        val copied = original.copy(isPinned = false)
        
        assertEquals(123L, copied.id)
        assertEquals("Original", copied.text)
        assertEquals(1000L, copied.timestamp)
        assertFalse(copied.isPinned)
        assertTrue(copied.isQuickSend)
    }
    
    @Test
    fun serializeDeserializePreservesData() {
        val items = listOf(
            ClipboardItem(1L, "Test:::with|||special", 1000L, true, false),
            ClipboardItem(2L, "Normal text", 2000L, false, true)
        )
        
        val prefs = context.getSharedPreferences("clipboard_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("clipboard_items", 
            items.joinToString("|||") { item ->
                "${item.id}:::${item.text.replace("|||", "〈PIPE〉").replace(":::", "〈COLON〉")}:::${item.timestamp}:::${item.isPinned}:::${item.isQuickSend}"
            }
        ).commit()
        
        ClipboardManager.getInstance(context)
        val loaded = ClipboardManager.getInstance(context).clipboardItems.value
        
        assertEquals(2, loaded.size)
        assertEquals("Test:::with|||special", loaded[0].text)
        assertEquals("Normal text", loaded[1].text)
    }
    
    @Test
    fun maxItemsLimitEnforced() {
        for (i in 1..55) {
            clipboardManager.addItem("Item $i")
        }
        
        val items = clipboardManager.clipboardItems.value
        assertTrue("Should have at most 50 items", items.size <= 50)
    }
}