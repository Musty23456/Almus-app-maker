package com.almus.appcreatorpro.transfer

import com.almus.appcreatorpro.data.storage.FileProjectStore
import com.almus.appcreatorpro.data.transfer.BackupStore
import com.almus.appcreatorpro.data.transfer.ProjectArchiver
import com.almus.appcreatorpro.data.transfer.ProjectImportService
import com.almus.appcreatorpro.domain.transfer.ImportLimits
import com.almus.appcreatorpro.domain.transfer.TreeSource
import kotlinx.coroutines.Dispatchers
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Everything a transfer test needs, wired like the real app but on a temp folder and without Android. */
class TransferEnv(tmp: TemporaryFolder, limits: ImportLimits = ImportLimits(), maxBackups: Int = 30) {
    var time = 1_000L
    private var projectCounter = 0
    private var stageCounter = 0
    private var backupCounter = 0

    val projectsDir = File(tmp.root, "projects")
    val stagingDir = File(tmp.root, "staging")
    val backupsDir = File(tmp.root, "backups")

    val store = FileProjectStore(
        rootDir = projectsDir,
        clock = { time++ },
        idGenerator = { "proj${projectCounter++}" },
        ioDispatcher = Dispatchers.Unconfined,
    )
    val importer = ProjectImportService(
        stagingRoot = stagingDir,
        projects = store,
        transfer = store,
        limits = limits,
        ioDispatcher = Dispatchers.Unconfined,
        idGenerator = { "s${stageCounter++}" },
    )
    val archiver = ProjectArchiver(store, Dispatchers.Unconfined)
    val backups = BackupStore(
        rootDir = backupsDir,
        projects = store,
        transfer = store,
        archiver = archiver,
        importer = importer,
        clock = { time++ },
        idGenerator = { "bk${backupCounter++}" },
        ioDispatcher = Dispatchers.Unconfined,
        maxBackupsPerProject = maxBackups,
    )

    /** Anything left in the staging area (an empty "downloads" folder is fine). */
    fun stagingLeftovers(): List<File> = stagingDir.listFiles().orEmpty()
        .filter { !(it.name == "downloads" && it.walkTopDown().none { f -> f.isFile }) }
}

fun zipBytes(vararg entries: Pair<String, ByteArray>): ByteArray {
    val bytes = ByteArrayOutputStream()
    ZipOutputStream(bytes).use { zip ->
        for ((name, data) in entries) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(data)
            zip.closeEntry()
        }
    }
    return bytes.toByteArray()
}

fun text(s: String): ByteArray = s.toByteArray(Charsets.UTF_8)

val PNG_BYTES = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D)

/** A small but realistic Android Kotlin project. */
fun androidKotlinFiles(wrapper: String = ""): Array<Pair<String, ByteArray>> {
    val p = if (wrapper.isEmpty()) "" else "$wrapper/"
    return arrayOf(
        "${p}settings.gradle.kts" to text("rootProject.name = \"Demo App\"\ninclude(\":app\")\n"),
        "${p}build.gradle.kts" to text("plugins { id(\"com.android.application\") version \"8.7.3\" apply false }\n"),
        "${p}app/build.gradle.kts" to text(
            "plugins {\n id(\"com.android.application\")\n id(\"org.jetbrains.kotlin.android\")\n}\n" +
                "android {\n namespace = \"com.demo.app\"\n defaultConfig { applicationId = \"com.demo.app\" }\n}\n",
        ),
        "${p}app/src/main/AndroidManifest.xml" to text("<manifest package=\"com.demo.app\"/>\n"),
        "${p}app/src/main/java/com/demo/app/MainActivity.kt" to text("package com.demo.app\nclass MainActivity\n"),
        "${p}app/src/main/res/values/strings.xml" to text("<resources/>\n"),
        "${p}app/src/main/res/mipmap-hdpi/ic_launcher.png" to PNG_BYTES,
        "${p}gradle/wrapper/gradle-wrapper.jar" to byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x00, 0x01),
        "${p}gradle/wrapper/gradle-wrapper.properties" to text("distributionUrl=https\\://services.gradle.org/distributions/gradle-8.9-bin.zip\n"),
    )
}

/** In-memory folder for testing the folder (Storage Access Framework) import path. */
class FakeTree(override val rootName: String, private val files: Map<String, ByteArray>) : TreeSource {
    override fun forEachFile(action: (path: String, size: Long?, open: () -> InputStream) -> Unit) {
        for ((path, data) in files) action(path, data.size.toLong()) { ByteArrayInputStream(data) }
    }
}
