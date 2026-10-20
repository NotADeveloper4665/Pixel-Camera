package app.morphe.patches.pixelcamera.fusion

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import org.w3c.dom.Element

private const val ANDROID = "http://schemas.android.com/apk/res/android"
private const val FUSION_ACTIVITY = "org.eclipse.pixelcamera.fusion.FusionActivity"

internal val pixel9aFusionResourcesPatch = resourcePatch {
    execute {
        document("AndroidManifest.xml").use { document ->
            val root = document.documentElement
            val application = root.getElementsByTagName("application").item(0) as? Element
                ?: error("Application element missing")
            val existing = document.getElementsByTagName("activity")
            check((0 until existing.length).none {
                (existing.item(it) as? Element)?.getAttributeNS(ANDROID, "name") == FUSION_ACTIVITY
            }) { "Fusion activity already exists" }
            val activity = document.createElement("activity")
            fun attribute(name: String, value: String) = activity.setAttributeNS(ANDROID, "android:$name", value)
            attribute("name", FUSION_ACTIVITY)
            attribute("label", "Pixel 9a Fusion")
            attribute("exported", "true")
            attribute("screenOrientation", "portrait")
            attribute("resizeableActivity", "false")
            attribute("theme", "@android:style/Theme.Material.NoActionBar")
            val filter = document.createElement("intent-filter")
            val action = document.createElement("action")
            action.setAttributeNS(ANDROID, "android:name", "android.intent.action.MAIN")
            val category = document.createElement("category")
            category.setAttributeNS(ANDROID, "android:name", "android.intent.category.LAUNCHER")
            filter.appendChild(action)
            filter.appendChild(category)
            activity.appendChild(filter)
            application.appendChild(activity)
            val permissions = document.getElementsByTagName("uses-permission")
            if ((0 until permissions.length).none {
                (permissions.item(it) as? Element)?.getAttributeNS(ANDROID, "name") == "android.permission.CAMERA"
            }) {
                val permission = document.createElement("uses-permission")
                permission.setAttributeNS(ANDROID, "android:name", "android.permission.CAMERA")
                root.insertBefore(permission, application)
            }
        }
    }
}

val pixel9aFusionPatch = bytecodePatch(
    name = "Pixel 9a Multi-Frame Fusion",
    description = "Adds a Pixel 9a Camera2 capture activity with timestamp-matched bursts, motion rejection, linear RGB fusion, and experimental 10x framing.",
    default = true
) {
    compatibleWith("com.google.android.GoogleCamera" to setOf("11.1.040.982810059.19"))
    dependsOn(pixel9aFusionResourcesPatch)
    // Generated from the SDK-only app by tools/build_pixel9a.sh or CI.
    // Public patcher extension API merges new classes without replacing native zoom controllers.
    extendWith("Pixel9aFusion.dex")
}
