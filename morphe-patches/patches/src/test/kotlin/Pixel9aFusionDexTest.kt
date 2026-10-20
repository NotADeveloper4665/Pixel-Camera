package app.morphe.patches.pixelcamera

import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import org.junit.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class Pixel9aFusionDexTest {
    @Test
    fun generatedExtensionHasActivityAndCapturePipeline() {
        val resource = javaClass.classLoader.getResourceAsStream("Pixel9aFusion.dex")
        assertNotNull(resource, "Build pixel9a-fusion and generate Pixel9aFusion.dex before patch packaging")
        val dex = resource.buffered().use { DexBackedDexFile.fromInputStream(Opcodes.forApi(35), it) }
        val names = dex.classes.map { it.type }.toSet()
        for (type in listOf("FusionActivity", "CameraController", "CameraProfile", "FusionProcessor", "Fusion", "ZoomPlan")) {
            assertTrue("Lorg/eclipse/pixelcamera/fusion/$type;" in names, "Missing $type")
        }
        assertTrue(names.all { it.startsWith("Lorg/eclipse/pixelcamera/fusion/") },
            "The injected extension must stay within its own namespace")
        val activity = dex.classes.single { it.type == "Lorg/eclipse/pixelcamera/fusion/FusionActivity;" }
        assertTrue(activity.superclass == "Landroid/app/Activity;")
        assertTrue(activity.methods.any { it.name == "onCreate" })
    }
}
