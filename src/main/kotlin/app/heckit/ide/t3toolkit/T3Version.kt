package app.heckit.ide.t3toolkit

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.jetbrains.php.PhpIndex

/**
 * Welche PHP-Attribute die TYPO3-Version des Projekts zum Registrieren von Services kennt.
 * Quelle: TYPO3-Changelog (#101567 AsCommand ab 12.4.8, #101544 AsEventListener, #101700 AsMessageHandler und
 * #102631 AsController ab 13.0; davor ab 12.1 `#[Controller]`).
 */
class T3Version private constructor(val version: List<Int>?, private val classes: Set<String>) {

    fun atLeast(vararg v: Int): Boolean = version?.let { compare(it, v.toList()) >= 0 } ?: false

    fun has(fqn: String) = fqn in classes

    val eventListener get() = has(AS_EVENT_LISTENER)
    val messageHandler get() = has(AS_MESSAGE_HANDLER) && (atLeast(13) || version == null && eventListener)
    val command get() = has(AS_COMMAND) && (atLeast(12, 4, 8) || version == null && eventListener)
    val controller: String? get() = when {
        has(AS_CONTROLLER) -> AS_CONTROLLER
        has(CONTROLLER_V12) -> CONTROLLER_V12
        else -> null
    }
    val autoconfigure get() = has(AUTOCONFIGURE)

    val label get() = version?.joinToString(".") ?: "unknown"

    companion object {
        const val AS_EVENT_LISTENER = "TYPO3\\CMS\\Core\\Attribute\\AsEventListener"
        const val AS_MESSAGE_HANDLER = "Symfony\\Component\\Messenger\\Attribute\\AsMessageHandler"
        const val AS_COMMAND = "Symfony\\Component\\Console\\Attribute\\AsCommand"
        const val AS_CONTROLLER = "TYPO3\\CMS\\Backend\\Attribute\\AsController"
        const val CONTROLLER_V12 = "TYPO3\\CMS\\Backend\\Attribute\\Controller"
        const val AUTOCONFIGURE = "Symfony\\Component\\DependencyInjection\\Attribute\\Autoconfigure"
        private val ALL = listOf(AS_EVENT_LISTENER, AS_MESSAGE_HANDLER, AS_COMMAND, AS_CONTROLLER, CONTROLLER_V12, AUTOCONFIGURE)
        private val CORE_IN_LOCK = Regex(""""name"\s*:\s*"typo3/cms-core"\s*,\s*"version"\s*:\s*"v?(\d+(?:\.\d+)*)"""")

        fun get(project: Project): T3Version = CachedValuesManager.getManager(project).getCachedValue(project) {
            CachedValueProvider.Result.create(build(project), PsiModificationTracker.MODIFICATION_COUNT)
        }

        private fun build(project: Project): T3Version {
            val version = FilenameIndex.getVirtualFilesByName("composer.lock", GlobalSearchScope.allScope(project))
                .firstNotNullOfOrNull { f ->
                    runCatching { VfsUtilCore.loadText(f) }.getOrNull()?.let { CORE_IN_LOCK.find(it)?.groupValues?.get(1) }
                }?.split('.')?.mapNotNull { it.toIntOrNull() }
            val index = PhpIndex.getInstance(project)
            val classes = ALL.filter { index.getAnyByFQN("\\" + it).isNotEmpty() }.toSet()
            return T3Version(version, classes)
        }

        private fun compare(a: List<Int>, b: List<Int>): Int {
            for (i in 0 until maxOf(a.size, b.size)) {
                val d = (a.getOrNull(i) ?: 0) - (b.getOrNull(i) ?: 0)
                if (d != 0) return d
            }
            return 0
        }
    }
}
