package app.heckit.ide.t3toolkit

import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.psi.PsiElement

/** Darstellung einer Fundstelle im Gutter-Popup: Zeilentext, darunter Datei und Zeile. */
object UsageRenderer {
    fun presentation(element: PsiElement): TargetPresentation {
        val file = element.containingFile
        val text = file.viewProvider.contents
        val offset = element.textRange.startOffset
        val start = text.lastIndexOf('\n', (offset - 1).coerceAtLeast(0)) + 1
        val end = text.indexOf('\n', offset).let { if (it < 0) text.length else it }
        val line = text.subSequence(start, end).trim().toString().take(120)
        val lineNo = text.subSequence(0, offset).count { it == '\n' } + 1
        val vf = file.virtualFile
        val location = vf?.let { T3Project.get(element.project).extPathOf(it) }
            ?: vf?.let { f -> element.project.basePath?.let { VfsUtilCore.getRelativePath(f, f.fileSystem.findFileByPath(it) ?: return@let null) } }
            ?: file.name
        return TargetPresentation.builder(line.ifEmpty { file.name })
            .icon(file.getIcon(0))
            .locationText("$location:$lineNo")
            .presentation()
    }
}
