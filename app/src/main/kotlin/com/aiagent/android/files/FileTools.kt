package com.aiagent.android.files

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.documentfile.provider.DocumentFile
import com.aiagent.android.data.Settings
import java.io.File

/**
 * Filesystem operations exposed to the agent.
 *
 * The behaviour depends on [Settings.fileAccessMode]:
 *   - "saf"  (default): only paths inside one of [Settings.allowedFolders] (SAF tree URIs) are accessible.
 *   - "all"           : full read/write to external storage when MANAGE_EXTERNAL_STORAGE is granted.
 *   - "app"           : only the app's private storage (`/Android/data/com.aiagent.android/files`).
 *
 * Paths are passed as either:
 *   - a SAF URI (`content://...`) or `tree:<uri>:<sub/path>`
 *   - an absolute filesystem path (`/storage/emulated/0/...`)  — only allowed in `all` / `app` modes
 *   - a relative path under the app's private dir (`./logs/x.txt`)
 */
class FileTools(private val context: Context, private val settings: Settings) {

    fun listEntries(path: String): String {
        val mode = settings.fileAccessMode
        return when {
            path.startsWith("content://") -> listSaf(Uri.parse(path))
            mode == "saf" -> listInsideAllowedTree(path)
            else -> listFsPath(resolvePath(path, mode))
        }
    }

    fun readText(path: String, maxBytes: Int = 64 * 1024): String {
        val mode = settings.fileAccessMode
        val bytes = when {
            path.startsWith("content://") -> readSafBytes(Uri.parse(path), maxBytes)
            mode == "saf" -> readInsideAllowedTreeBytes(path, maxBytes)
            else -> readFsBytes(resolvePath(path, mode), maxBytes)
        }
        return bytes.toString(Charsets.UTF_8)
    }

    fun writeText(path: String, content: String, mimeType: String = "text/plain"): String {
        val mode = settings.fileAccessMode
        return when {
            path.startsWith("content://") ->
                writeSafBytes(Uri.parse(path), content.toByteArray(Charsets.UTF_8), mimeType)
            mode == "saf" -> writeInsideAllowedTree(path, content.toByteArray(Charsets.UTF_8), mimeType)
            else -> {
                val file = resolvePath(path, mode)
                file.parentFile?.mkdirs()
                file.writeText(content)
                "ок: ${file.absolutePath} (${content.length} симв.)"
            }
        }
    }

    fun makeDir(path: String): String {
        val mode = settings.fileAccessMode
        return when {
            path.startsWith("content://") -> {
                val tree = DocumentFile.fromTreeUri(context, Uri.parse(path))
                    ?: return "ошибка: не удаётся открыть дерево"
                if (tree.exists()) "ок: каталог уже есть" else "ошибка: создание корневого дерева не поддерживается"
            }
            mode == "saf" -> mkdirInsideAllowedTree(path)
            else -> {
                val dir = resolvePath(path, mode)
                if (dir.mkdirs() || dir.isDirectory) "ок: ${dir.absolutePath}"
                else "ошибка: не удалось создать ${dir.absolutePath}"
            }
        }
    }

    fun deletePath(path: String): String {
        val mode = settings.fileAccessMode
        return when {
            path.startsWith("content://") -> {
                val df = DocumentFile.fromSingleUri(context, Uri.parse(path))
                if (df?.delete() == true) "ок: удалено" else "ошибка: не удалось удалить"
            }
            mode == "saf" -> deleteInsideAllowedTree(path)
            else -> {
                val file = resolvePath(path, mode)
                if (file.deleteRecursively()) "ок: ${file.absolutePath}" else "ошибка: не удалить ${file.absolutePath}"
            }
        }
    }

    /** Returns absolute file targeted by [path] (relative paths anchor to app-private storage). */
    private fun resolvePath(path: String, mode: String): File {
        val privateRoot = context.getExternalFilesDir(null) ?: context.filesDir
        return when {
            path.startsWith("/") -> {
                if (mode != "all") {
                    // Only accept paths that already live under the app's private dir.
                    val abs = File(path).absoluteFile
                    if (!abs.absolutePath.startsWith(privateRoot.absolutePath)) {
                        throw SecurityException(
                            "Доступ запрещён: ${abs.absolutePath} вне разрешённой области (режим=$mode).",
                        )
                    }
                    abs
                } else {
                    File(path)
                }
            }
            else -> File(privateRoot, path)
        }
    }

    // -- SAF helpers --------------------------------------------------------------------------

    private fun listSaf(uri: Uri): String {
        val tree = DocumentFile.fromTreeUri(context, uri) ?: return "ошибка: не удаётся открыть дерево"
        if (!tree.isDirectory) return "ошибка: это не каталог"
        val sb = StringBuilder()
        sb.append(tree.uri).append('\n')
        for (child in tree.listFiles()) {
            sb.append(if (child.isDirectory) "[d] " else "[f] ")
            sb.append(child.name ?: "?")
            if (child.isFile) sb.append(" (").append(child.length()).append(" B)")
            sb.append('\n')
        }
        return sb.toString()
    }

    private fun listInsideAllowedTree(path: String): String {
        val (tree, sub) = resolveAllowed(path) ?: return "ошибка: путь вне разрешённых папок"
        val target = if (sub.isBlank()) tree else findDescendant(tree, sub)
        if (target == null || !target.isDirectory) return "ошибка: каталог не найден"
        val sb = StringBuilder()
        sb.append(target.uri).append('\n')
        for (child in target.listFiles()) {
            sb.append(if (child.isDirectory) "[d] " else "[f] ")
            sb.append(child.name ?: "?")
            if (child.isFile) sb.append(" (").append(child.length()).append(" B)")
            sb.append('\n')
        }
        return sb.toString()
    }

    private fun readSafBytes(uri: Uri, maxBytes: Int): ByteArray {
        val resolver = context.contentResolver
        return resolver.openInputStream(uri)?.use { it.readBytes().take(maxBytes).toByteArray() }
            ?: ByteArray(0)
    }

    private fun readInsideAllowedTreeBytes(path: String, maxBytes: Int): ByteArray {
        val (tree, sub) = resolveAllowed(path) ?: return "ошибка: путь вне разрешённых папок".toByteArray()
        val target = findDescendant(tree, sub) ?: return "ошибка: файл не найден".toByteArray()
        if (!target.isFile) return "ошибка: не файл".toByteArray()
        return readSafBytes(target.uri, maxBytes)
    }

    private fun writeSafBytes(uri: Uri, content: ByteArray, mimeType: String): String {
        val tree = DocumentFile.fromTreeUri(context, uri) ?: return "ошибка: не удаётся открыть дерево"
        // If the URI points at a file, overwrite it; otherwise we expect a tree:<uri>:<name>
        val target: DocumentFile? = if (tree.isFile) tree else null
        return if (target != null && target.canWrite()) {
            context.contentResolver.openOutputStream(target.uri, "wt")?.use { it.write(content) }
            "ок: ${target.uri} (${content.size} B)"
        } else {
            "ошибка: для записи передайте 'tree:<uri>:filename.ext'"
        }
    }

    private fun writeInsideAllowedTree(path: String, content: ByteArray, mimeType: String): String {
        val (tree, sub) = resolveAllowed(path) ?: return "ошибка: путь вне разрешённых папок"
        if (sub.isBlank()) return "ошибка: укажите имя файла"
        val parts = sub.split('/').filter { it.isNotBlank() }
        val fileName = parts.last()
        val parentDirs = parts.dropLast(1)
        var dir = tree
        for (segment in parentDirs) {
            dir = dir.findFile(segment) ?: dir.createDirectory(segment) ?: return "ошибка: mkdir $segment"
        }
        val existing = dir.findFile(fileName)
        existing?.delete()
        val newFile = dir.createFile(mimeType, fileName) ?: return "ошибка: createFile $fileName"
        context.contentResolver.openOutputStream(newFile.uri, "wt")?.use { it.write(content) }
        return "ок: ${newFile.uri} (${content.size} B)"
    }

    private fun mkdirInsideAllowedTree(path: String): String {
        val (tree, sub) = resolveAllowed(path) ?: return "ошибка: путь вне разрешённых папок"
        if (sub.isBlank()) return "ошибка: укажите имя каталога"
        var dir = tree
        for (segment in sub.split('/').filter { it.isNotBlank() }) {
            dir = dir.findFile(segment) ?: dir.createDirectory(segment) ?: return "ошибка: mkdir $segment"
        }
        return "ок: ${dir.uri}"
    }

    private fun deleteInsideAllowedTree(path: String): String {
        val (tree, sub) = resolveAllowed(path) ?: return "ошибка: путь вне разрешённых папок"
        if (sub.isBlank()) return "ошибка: укажите путь"
        val target = findDescendant(tree, sub) ?: return "ошибка: не найдено"
        return if (target.delete()) "ок: удалено" else "ошибка: не удалить"
    }

    /** Splits a `name:sub/path` string into the matching allowed root + remaining path. */
    private fun resolveAllowed(path: String): Pair<DocumentFile, String>? {
        val allowed = settings.allowedFolders
        if (allowed.isEmpty()) return null
        // Match the first allowed root that starts the input.
        for (root in allowed) {
            val tree = DocumentFile.fromTreeUri(context, Uri.parse(root)) ?: continue
            val rootName = tree.name ?: continue
            if (path == rootName) return tree to ""
            if (path.startsWith("$rootName/")) return tree to path.removePrefix("$rootName/")
        }
        // Fall back to the first allowed folder.
        val first = DocumentFile.fromTreeUri(context, Uri.parse(allowed.first())) ?: return null
        return first to path.trim('/')
    }

    private fun findDescendant(root: DocumentFile, sub: String): DocumentFile? {
        var cur: DocumentFile? = root
        for (segment in sub.split('/').filter { it.isNotBlank() }) {
            cur = cur?.findFile(segment) ?: return null
        }
        return cur
    }

    private fun listFsPath(file: File): String {
        if (!file.exists()) return "ошибка: не найдено: ${file.absolutePath}"
        if (!file.isDirectory) return "[f] ${file.name} (${file.length()} B)"
        val sb = StringBuilder()
        sb.append(file.absolutePath).append('\n')
        for (child in file.listFiles().orEmpty()) {
            sb.append(if (child.isDirectory) "[d] " else "[f] ")
            sb.append(child.name)
            if (child.isFile) sb.append(" (").append(child.length()).append(" B)")
            sb.append('\n')
        }
        return sb.toString()
    }

    private fun readFsBytes(file: File, maxBytes: Int): ByteArray {
        if (!file.exists() || !file.isFile) return "ошибка: не файл".toByteArray()
        return file.inputStream().use { it.readBytes().take(maxBytes).toByteArray() }
    }

    /** Whether MANAGE_EXTERNAL_STORAGE is currently granted (Android 11+ runtime). */
    fun hasAllFilesAccess(): Boolean = if (Build.VERSION.SDK_INT >= 30) {
        Environment.isExternalStorageManager()
    } else {
        true
    }
}
