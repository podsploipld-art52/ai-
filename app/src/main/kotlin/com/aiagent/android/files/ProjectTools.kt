package com.aiagent.android.files

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * Tools that target the agent's own "code projects" folder, regardless of the user's
 * [com.aiagent.android.data.Settings.fileAccessMode]. The point is to let the agent build
 * multi-file projects (e.g. "напиши клон Майнкрафта") without the user first having to
 * grant SAF / MANAGE_EXTERNAL_STORAGE access to anything.
 *
 * Layout: `<root>/projects/<project-name>/<file-path>`
 *
 * Where `<root>` is the first writable directory among:
 *  1. `Documents/AI-Agent/` — public, openable via any file manager. Best UX.
 *  2. `<external app dir>/` — readable from `/Android/data/<pkg>/files/` (Android <11).
 *  3. `<filesDir>` — always writable, fully private.
 *
 * Project / file names are sanitised: only `[A-Za-z0-9._-]` and `/` (for sub-dirs) are
 * accepted. No `..`, no leading `/`. Parent directories are created on write.
 */
class ProjectTools(private val context: Context) {

    private val safeNameRegex = Regex("^[A-Za-z0-9_.\\-]+$")
    private val safeRelPathRegex = Regex("^[A-Za-z0-9_./\\-]+$")

    fun root(): File {
        val publicDocs = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val candidates = listOfNotNull(
            File(publicDocs, "AI-Agent"),
            context.getExternalFilesDir(null),
            context.filesDir,
        )
        for (dir in candidates) {
            val ok = runCatching { dir.mkdirs() }.getOrDefault(false) || dir.exists()
            if (ok && dir.canWrite()) return dir
        }
        // Last-resort: filesDir is guaranteed to exist and be writable.
        return context.filesDir
    }

    fun projectsDir(): File = File(root(), "projects").apply { mkdirs() }

    fun projectDir(project: String): File {
        require(project.matches(safeNameRegex)) {
            "Имя проекта может содержать только буквы/цифры/.-_, получено: $project"
        }
        return File(projectsDir(), project).apply { mkdirs() }
    }

    fun resolveFile(project: String, relative: String): File {
        require(relative.matches(safeRelPathRegex) && !relative.startsWith("/") && !relative.contains("..")) {
            "Имя файла должно быть относительным, без `..` и спец-символов, получено: $relative"
        }
        return File(projectDir(project), relative)
    }

    fun write(project: String, relative: String, content: String): String {
        val target = resolveFile(project, relative)
        target.parentFile?.mkdirs()
        target.writeText(content)
        return "ок: ${target.absolutePath} (${content.length} симв.)"
    }

    fun read(project: String, relative: String, maxBytes: Int = 64 * 1024): String {
        val target = resolveFile(project, relative)
        if (!target.exists() || !target.isFile) return "ошибка: файл не найден: ${target.absolutePath}"
        // readBytes() loads everything; we then truncate. Files that aren't multi-megabyte
        // text fit comfortably even on low-RAM devices.
        val all = target.readBytes()
        val cut = if (all.size > maxBytes) all.copyOfRange(0, maxBytes) else all
        val raw = cut.toString(Charsets.UTF_8)
        return if (all.size > maxBytes) "$raw\n…[truncated, full size=${all.size}b]" else raw
    }

    fun list(project: String?): String {
        val dir = if (project.isNullOrBlank()) projectsDir() else projectDir(project)
        if (!dir.exists()) return "(пусто): ${dir.absolutePath}"
        val sb = StringBuilder()
        sb.append("корень: ${dir.absolutePath}\n")
        val tree = dir.walkTopDown().maxDepth(6).toList().drop(1) // drop the root itself
        if (tree.isEmpty()) {
            sb.append("(пусто)")
            return sb.toString()
        }
        for (f in tree.sortedBy { it.absolutePath }) {
            val rel = f.relativeTo(dir).path
            val type = if (f.isDirectory) "d" else "f"
            val size = if (f.isFile) f.length() else 0
            sb.append("$type  $rel  ${size}b\n")
        }
        return sb.toString()
    }

    fun delete(project: String, relative: String?): String {
        val target = if (relative.isNullOrBlank()) projectDir(project) else resolveFile(project, relative)
        return if (target.deleteRecursively()) "ок: ${target.absolutePath}"
        else "ошибка: не удалить ${target.absolutePath}"
    }
}
