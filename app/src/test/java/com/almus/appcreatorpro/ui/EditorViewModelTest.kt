package com.almus.appcreatorpro.ui

import com.almus.appcreatorpro.data.storage.FileProjectStore
import com.almus.appcreatorpro.domain.model.ProjectType
import com.almus.appcreatorpro.domain.model.SourceFileEntry
import com.almus.appcreatorpro.domain.repository.SourceFileRepository
import com.almus.appcreatorpro.editor.EditorValue
import com.almus.appcreatorpro.testutil.MainDispatcherRule
import com.almus.appcreatorpro.ui.editor.EditorViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EditorViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var store: FileProjectStore
    private lateinit var projectId: String

    @Before
    fun setUp() = runTest {
        store = newStore()
        projectId = store.createProject("Web Site", "", "", ProjectType.WEB).id
    }

    private fun newStore(root: File = File(tmp.root, "projects")) =
        FileProjectStore(root, ioDispatcher = Dispatchers.Unconfined)

    private fun vm(s: FileProjectStore = store, id: String = projectId) = EditorViewModel(id, s, s)

    private fun EditorViewModel.type(text: String) {
        val open = state.value.activeFile!!
        onUserEdit(EditorValue(open.value.text + text, open.value.text.length + text.length))
    }

    @Test
    fun loads_project_and_opens_the_default_file() {
        val vm = vm()
        val s = vm.state.value
        assertFalse(s.isLoading)
        assertNull(s.loadError)
        assertEquals("Web Site", s.project?.name)
        assertEquals("index.html", s.activePath)
        assertEquals(1, s.openFiles.size)
        assertTrue(s.openFiles[0].value.text.contains("<!DOCTYPE html>"))
        assertFalse(s.openFiles[0].isDirty)
        assertEquals(4, s.files.size)
    }

    @Test
    fun missing_project_shows_an_error_state() {
        val vm = vm(id = "does-not-exist")
        assertEquals("This project no longer exists.", vm.state.value.loadError)
        assertFalse(vm.state.value.isLoading)
    }

    @Test
    fun typing_marks_dirty_and_saving_persists_to_disk() = runTest {
        val vm = vm()
        vm.type("<!-- hi -->")
        assertTrue(vm.state.value.activeFile!!.isDirty)
        assertTrue(vm.state.value.hasUnsavedChanges)

        vm.saveActive()
        assertFalse(vm.state.value.hasUnsavedChanges)
        assertEquals("Saved index.html", vm.message.value?.text)
        assertTrue(store.readFile(projectId, "index.html").endsWith("<!-- hi -->"))
    }

    @Test
    fun edits_saved_by_one_session_are_visible_to_a_new_session() = runTest {
        val first = vm()
        first.type("// persisted")
        first.saveAll { }

        val restarted = vm(newStore()) // new store + new ViewModel = app restart
        assertTrue(restarted.state.value.activeFile!!.value.text.endsWith("// persisted"))
    }

    @Test
    fun typing_back_to_the_saved_text_is_not_dirty() {
        val vm = vm()
        val original = vm.state.value.activeFile!!.value.text
        vm.type("x")
        assertTrue(vm.state.value.activeFile!!.isDirty)
        vm.onUserEdit(EditorValue(original, original.length))
        assertFalse(vm.state.value.activeFile!!.isDirty)
    }

    @Test
    fun undo_and_redo_work_through_the_viewmodel() {
        val vm = vm()
        val original = vm.state.value.activeFile!!.value.text
        vm.type("abc")
        assertTrue(vm.state.value.activeFile!!.canUndo)

        vm.undo()
        assertEquals(original, vm.state.value.activeFile!!.value.text)
        assertTrue(vm.state.value.activeFile!!.canRedo)

        vm.redo()
        assertEquals(original + "abc", vm.state.value.activeFile!!.value.text)
    }

    @Test
    fun auto_indent_adjusts_the_value_and_bumps_the_revision() {
        val vm = vm()
        vm.onUserEdit(EditorValue("", 0))
        vm.onUserEdit(EditorValue("fun a() {", 9))
        val before = vm.state.value.activeFile!!.revision
        val shown = vm.onUserEdit(EditorValue("fun a() {\n", 10))

        assertEquals("fun a() {\n    ", shown.text)
        assertEquals(before + 1, vm.state.value.activeFile!!.revision)
    }

    @Test
    fun plain_typing_does_not_bump_the_revision() {
        val vm = vm()
        val before = vm.state.value.activeFile!!.revision
        vm.type("a")
        assertEquals(before, vm.state.value.activeFile!!.revision)
    }

    @Test
    fun indent_outdent_and_insert_text() {
        val vm = vm()
        vm.onUserEdit(EditorValue("x", 1))
        vm.insertText("{}")
        assertEquals("x{}", vm.state.value.activeFile!!.value.text)
        vm.onUserEdit(EditorValue("line", 0))
        vm.indent()
        assertEquals("    line", vm.state.value.activeFile!!.value.text)
        vm.outdent()
        assertEquals("line", vm.state.value.activeFile!!.value.text)
    }

    @Test
    fun select_all_selects_everything() {
        val vm = vm()
        vm.selectAll()
        val v = vm.state.value.activeFile!!.value
        assertEquals(0, v.selMin)
        assertEquals(v.text.length, v.selMax)
    }

    // ---------------------------------------------------------------- multiple files

    @Test
    fun multiple_files_can_be_open_and_keep_their_own_text() {
        val vm = vm()
        vm.type("// one")
        vm.openFile("style.css")
        assertEquals("style.css", vm.state.value.activePath)
        vm.type("/* two */")

        vm.selectTab("index.html")
        assertTrue(vm.state.value.activeFile!!.value.text.endsWith("// one"))
        vm.selectTab("style.css")
        assertTrue(vm.state.value.activeFile!!.value.text.endsWith("/* two */"))
        assertEquals(2, vm.state.value.openFiles.count { it.isDirty })
    }

    @Test
    fun save_all_saves_every_modified_file() = runTest {
        val vm = vm()
        vm.type("// a")
        vm.openFile("script.js")
        vm.type("// b")
        var result: Boolean? = null
        vm.saveAll(silent = false) { result = it }

        assertEquals(true, result)
        assertFalse(vm.state.value.hasUnsavedChanges)
        assertTrue(store.readFile(projectId, "index.html").endsWith("// a"))
        assertTrue(store.readFile(projectId, "script.js").endsWith("// b"))
    }

    @Test
    fun closing_a_tab_selects_a_neighbour_and_drops_unsaved_text() {
        val vm = vm()
        vm.openFile("style.css")
        vm.type("unsaved")
        vm.closeFile("style.css")

        assertEquals("index.html", vm.state.value.activePath)
        assertEquals(listOf("index.html"), vm.state.value.openFiles.map { it.path })
        vm.openFile("style.css")
        assertFalse(vm.state.value.activeFile!!.value.text.contains("unsaved"))
    }

    @Test
    fun create_file_opens_it_and_lists_it() {
        val vm = vm()
        vm.createFile(" js/app.js ")
        assertEquals("js/app.js", vm.state.value.activePath)
        assertTrue(vm.state.value.files.any { it.path == "js/app.js" })
        assertEquals("Created js/app.js", vm.message.value?.text)
    }

    @Test
    fun create_file_errors_are_reported_without_crashing() {
        val vm = vm()
        vm.createFile("index.html")
        assertTrue(vm.message.value!!.isError)
        assertTrue(vm.message.value!!.text.contains("already exists"))
        vm.createFile("../escape.txt")
        assertTrue(vm.message.value!!.isError)
        assertEquals(1, vm.state.value.openFiles.size)
    }

    @Test
    fun renaming_an_open_modified_file_keeps_its_buffer() = runTest {
        val vm = vm()
        vm.openFile("script.js")
        vm.type("// edited")
        vm.renameFile("script.js", "main.js")

        val open = vm.state.value.activeFile!!
        assertEquals("main.js", open.path)
        assertTrue(open.isDirty)
        assertTrue(open.value.text.endsWith("// edited"))
        assertTrue(vm.state.value.files.any { it.path == "main.js" })
        assertFalse(vm.state.value.files.any { it.path == "script.js" })

        vm.saveActive()
        assertTrue(store.readFile(projectId, "main.js").endsWith("// edited"))
    }

    @Test
    fun rename_to_an_existing_name_fails_and_changes_nothing() {
        val vm = vm()
        vm.renameFile("script.js", "style.css")
        assertTrue(vm.message.value!!.isError)
        assertTrue(vm.state.value.files.any { it.path == "script.js" })
    }

    @Test
    fun deleting_a_file_closes_its_tab_and_removes_it_from_disk() = runTest {
        val vm = vm()
        vm.openFile("style.css")
        vm.deleteFile("style.css")

        assertFalse(vm.state.value.files.any { it.path == "style.css" })
        assertFalse(vm.state.value.openFiles.any { it.path == "style.css" })
        assertEquals("index.html", vm.state.value.activePath)
        assertFalse(File(tmp.root, "projects/$projectId/files/style.css").exists())
    }

    @Test
    fun deleting_the_last_open_file_leaves_an_empty_editor() {
        val vm = vm()
        vm.deleteFile("index.html")
        assertNull(vm.state.value.activePath)
        assertTrue(vm.state.value.openFiles.isEmpty())
    }

    @Test
    fun opening_a_file_that_vanished_reports_an_error() {
        val vm = vm()
        File(tmp.root, "projects/$projectId/files/script.js").delete()
        vm.openFile("script.js")
        assertTrue(vm.message.value!!.isError)
        assertEquals("index.html", vm.state.value.activePath)
    }

    @Test
    fun opening_a_binary_file_reports_an_error() {
        File(tmp.root, "projects/$projectId/files/pic.bin").writeBytes(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00))
        val vm = vm()
        vm.openFile("pic.bin")
        assertTrue(vm.message.value!!.text.contains("not a UTF-8 text file"))
    }

    @Test
    fun save_failure_keeps_the_file_dirty_and_reports_it() = runTest {
        val vm = vm()
        vm.type("// precious")
        File(tmp.root, "projects/$projectId/files/index.html").delete()
        File(tmp.root, "projects/$projectId/files/index.html").mkdirs() // a folder now blocks the file path

        vm.saveActive()
        assertTrue(vm.state.value.activeFile!!.isDirty)
        assertTrue(vm.message.value!!.isError)
        assertTrue(vm.state.value.activeFile!!.value.text.endsWith("// precious"))
    }

    @Test
    fun project_can_be_renamed_from_the_editor() = runTest {
        val vm = vm()
        vm.renameProject("Renamed Site")
        assertEquals("Renamed Site", vm.state.value.project?.name)
        assertEquals("Renamed Site", store.getProject(projectId)?.name)
        vm.renameProject("x")
        assertTrue(vm.message.value!!.isError)
        assertEquals("Renamed Site", vm.state.value.project?.name)
    }

    @Test
    fun folders_can_be_collapsed_and_expanded() {
        val vm = vm()
        vm.toggleFolder("src")
        assertEquals(setOf("src"), vm.state.value.collapsedFolders)
        vm.toggleFolder("src")
        assertTrue(vm.state.value.collapsedFolders.isEmpty())
    }

    // ---------------------------------------------------------------- find / replace

    private fun vmWithText(text: String): EditorViewModel {
        val vm = vm()
        vm.onUserEdit(EditorValue(text, 0))
        return vm
    }

    @Test
    fun find_counts_matches_and_navigates_with_wraparound() {
        val vm = vmWithText("cat dog cat bird cat")
        vm.toggleFind()
        vm.setFindQuery("cat")
        assertEquals(3, vm.state.value.find.matchCount)

        vm.findNext()
        assertEquals(0, vm.state.value.find.currentIndex)
        assertEquals("cat", vm.state.value.activeFile!!.value.selectedText)
        vm.findNext()
        assertEquals(8, vm.state.value.activeFile!!.value.selMin)
        vm.findNext()
        vm.findNext() // wraps
        assertEquals(0, vm.state.value.find.currentIndex)
        vm.findPrevious() // wraps backwards
        assertEquals(2, vm.state.value.find.currentIndex)
    }

    @Test
    fun find_respects_case_sensitivity_and_reports_no_matches() {
        val vm = vmWithText("Cat cat CAT")
        vm.toggleFind()
        vm.setFindQuery("cat")
        assertEquals(3, vm.state.value.find.matchCount)
        vm.setCaseSensitive(true)
        assertEquals(1, vm.state.value.find.matchCount)

        vm.setFindQuery("zebra")
        vm.findNext()
        assertEquals(0, vm.state.value.find.matchCount)
        assertEquals("No matches for \"zebra\".", vm.message.value?.text)
    }

    @Test
    fun replace_current_replaces_the_selected_match_and_moves_on() {
        val vm = vmWithText("a-a-a")
        vm.toggleFind()
        vm.setFindQuery("a")
        vm.setReplacement("bb")
        vm.findNext()          // selects first a
        vm.replaceCurrent()    // replaces it, selects next
        assertEquals("bb-a-a", vm.state.value.activeFile!!.value.text)
        assertEquals("a", vm.state.value.activeFile!!.value.selectedText)
        vm.undo()
        assertEquals("a-a-a", vm.state.value.activeFile!!.value.text)
    }

    @Test
    fun replace_all_is_one_undo_step_and_reports_the_count() {
        val vm = vmWithText("x y x y x")
        vm.toggleFind()
        vm.setFindQuery("x")
        vm.setReplacement("zz")
        vm.replaceAll()

        assertEquals("zz y zz y zz", vm.state.value.activeFile!!.value.text)
        assertEquals("Replaced 3 occurrence(s).", vm.message.value?.text)
        assertEquals(0, vm.state.value.find.matchCount)
        vm.undo()
        assertEquals("x y x y x", vm.state.value.activeFile!!.value.text)
    }

    @Test
    fun message_ids_are_unique_so_repeated_messages_are_shown_again() {
        val vm = vm()
        vm.createFile("index.html")
        val first = vm.message.value!!
        vm.consumeMessage()
        assertNull(vm.message.value)
        vm.createFile("index.html")
        assertNotNull(vm.message.value)
        assertTrue(vm.message.value!!.id > first.id)
    }

    @Test
    fun file_list_entries_are_sorted_paths_with_sizes() {
        val list: List<SourceFileEntry> = vm().state.value.files
        assertEquals(list.map { it.path.lowercase() }.sorted(), list.map { it.path.lowercase() })
        assertTrue(list.all { it.sizeBytes > 0 })
        assertEquals(512 * 1024, SourceFileRepository.MAX_FILE_BYTES)
    }
}
