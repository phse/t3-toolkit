package app.heckit.ide.t3toolkit.services

import app.heckit.ide.t3toolkit.T3Version
import app.heckit.ide.t3toolkit.php.PhpAttribute
import app.heckit.ide.t3toolkit.php.PhpAttributes
import com.intellij.psi.PsiElement
import com.jetbrains.php.PhpIndex
import com.jetbrains.php.lang.psi.elements.PhpClass
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLSequence
import org.jetbrains.yaml.psi.YAMLSequenceItem

/** Was beim Umstellen eines Services.yaml-Eintrags auf PHP-Attribute passiert. */
class AttributeConversion(
    val service: YAMLKeyValue,
    val phpClass: PhpClass,
    val attributes: List<PhpAttribute>,
    /** YAML-Elemente, die wegfallen (Tag-Einträge, `tags`, `public`). */
    val remove: List<PsiElement>,
    /** Der ganze Eintrag fällt weg (Klasse wird per resource gefunden). */
    val removeService: Boolean,
) {
    val title: String get() = "Move to " + attributes.map { "#[" + it.fqn.substringAfterLast('\\') + "]" }.distinct().joinToString(", ")

    companion object {
        /** Prüft einen Eintrag unter `services:`; null, wenn sich nichts umstellen lässt. */
        fun analyze(service: YAMLKeyValue): AttributeConversion? {
            val mapping = service.value as? YAMLMapping ?: return null
            val servicesMapping = service.parent as? YAMLMapping ?: return null
            if ((servicesMapping.parent as? YAMLKeyValue)?.keyText != "services") return null
            val id = ServicesIndex.normalize(service.keyText)
            if (id.startsWith("_") || id.endsWith("\\")) return null
            val fqn = mapping.getKeyValueByKey("class")?.valueText?.let(ServicesIndex::normalize) ?: id
            // Nur wenn die Service-ID die Klasse ist - sonst ginge die ID verloren
            if (fqn != id || !ServicesIndex.CLASS_NAME.matches(fqn)) return null
            val project = service.project
            val phpClass = PhpIndex.getInstance(project).getClassesByFQN("\\" + fqn).firstOrNull() ?: return null
            if (phpClass.containingFile?.virtualFile?.isWritable != true) return null

            // Attribute wirken nur mit autoconfigure
            val defaults = servicesMapping.getKeyValueByKey("_defaults")?.value as? YAMLMapping
            val autoconfigure = mapping.getKeyValueByKey("autoconfigure")?.valueText
                ?: defaults?.getKeyValueByKey("autoconfigure")?.valueText
            if (autoconfigure != "true") return null

            val v = T3Version.get(project)
            val attributes = ArrayList<PhpAttribute>()
            val remove = ArrayList<PsiElement>()
            val tagsKv = mapping.getKeyValueByKey("tags")
            val items = (tagsKv?.value as? YAMLSequence)?.items.orEmpty()
            val commandTags = ArrayList<Pair<YAMLSequenceItem, YAMLMapping?>>()
            for (item in items) {
                val tag = item.value as? YAMLMapping
                val name = tag?.getKeyValueByKey("name")?.valueText ?: (item.value as? YAMLScalar)?.textValue ?: continue
                val keys = tag?.keyValues?.associate { it.keyText to it.valueText }.orEmpty() - "name"
                when (name) {
                    "event.listener" -> if (v.eventListener && keys.keys.all { it in setOf("identifier", "event", "method", "before", "after") }) {
                        attributes += PhpAttribute(T3Version.AS_EVENT_LISTENER, keys.map { (k, value) ->
                            k to if (k == "event") PhpAttributes.cls(ServicesIndex.normalize(value)) else PhpAttributes.str(value)
                        }.sortedBy { listOf("identifier", "event", "method", "before", "after").indexOf(it.first) })
                        remove += item
                    }
                    "messenger.message_handler" -> if (v.messageHandler) {
                        val map = mapOf("handles" to "handles", "method" to "method", "priority" to "priority", "from_transport" to "fromTransport", "bus" to "bus")
                        if (keys.keys.all { it in map }) {
                            attributes += PhpAttribute(T3Version.AS_MESSAGE_HANDLER, keys.map { (k, value) ->
                                map.getValue(k) to when (k) {
                                    "handles" -> PhpAttributes.cls(ServicesIndex.normalize(value))
                                    "priority" -> value.toIntOrNull()?.toString() ?: PhpAttributes.str(value)
                                    else -> PhpAttributes.str(value)
                                }
                            })
                            remove += item
                        }
                    }
                    "console.command" -> if (v.command && keys.keys.all { it in setOf("command", "description", "hidden", "schedulable", "alias") } &&
                        keys["schedulable"] != "false") commandTags += item to tag
                    "backend.controller" -> v.controller?.let { if (keys.isEmpty()) { attributes += PhpAttribute(it); remove += item } }
                }
            }
            if (commandTags.isNotEmpty()) {
                val main = commandTags.filter { it.second?.getKeyValueByKey("alias")?.valueText != "true" }
                if (main.size == 1) {
                    val tag = main.single().second
                    val name = tag?.getKeyValueByKey("command")?.valueText
                    if (name != null) {
                        val args = ArrayList<Pair<String, String>>()
                        args += "name" to PhpAttributes.str(name)
                        tag.getKeyValueByKey("description")?.valueText?.let { args += "description" to PhpAttributes.str(it) }
                        val aliases = commandTags.filter { it !in main }.mapNotNull { it.second?.getKeyValueByKey("command")?.valueText }
                        if (aliases.isNotEmpty()) args += "aliases" to PhpAttributes.list(aliases)
                        if (tag.getKeyValueByKey("hidden")?.valueText == "true") args += "hidden" to "true"
                        attributes += PhpAttribute(T3Version.AS_COMMAND, args)
                        remove += commandTags.map { it.first }
                    }
                }
            }
            if (attributes.isEmpty()) return null

            val allTagsGone = items.all { it in remove }
            if (allTagsGone && tagsKv != null) { remove.removeAll(items.toSet()); remove += tagsKv }
            val publicKv = mapping.getKeyValueByKey("public")
            if (allTagsGone && publicKv?.valueText == "true" && v.autoconfigure) {
                attributes += PhpAttribute(T3Version.AUTOCONFIGURE, listOf("public" to "true"))
                remove += publicKv
            }
            val autodiscovered = ServicesIndex.get(project).usagesOf(fqn, phpClass.containingFile.virtualFile)
                .any { it.label.startsWith("autodiscovered") }
            val removeService = autodiscovered && defaults?.getKeyValueByKey("autoconfigure")?.valueText == "true" && mapping.keyValues.all { kv -> kv in remove || kv.keyText == "autoconfigure" }
            return AttributeConversion(service, phpClass, attributes, remove, removeService)
        }
    }
}
