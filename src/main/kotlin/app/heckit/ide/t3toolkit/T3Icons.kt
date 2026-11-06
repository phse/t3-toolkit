package app.heckit.ide.t3toolkit

import com.intellij.openapi.util.IconLoader

object T3Icons {
    /** Gutter-Marker (12 px): Schild im heck\it-Verlauf */
    @JvmField
    val GUTTER = IconLoader.getIcon("/icons/t3Gutter.svg", T3Icons::class.java)

    /** Wie deaktiviert (Darstellung der IDE): Datei bzw. Label wird nirgends verwendet */
    @JvmField
    val GUTTER_UNUSED: javax.swing.Icon = IconLoader.getDisabledIcon(GUTTER)
}
