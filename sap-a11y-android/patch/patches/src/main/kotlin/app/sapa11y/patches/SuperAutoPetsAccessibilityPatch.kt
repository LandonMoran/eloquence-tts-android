package app.sapa11y.patches

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.document
import app.morphe.patcher.patch.listApkEntries
import app.morphe.patcher.patch.patch
import app.morphe.patcher.patch.resourceFile
import org.w3c.dom.Element

private const val STARTUP_PROVIDER = "app.sapa11y.extension.SapStartupProvider"
private const val STARTUP_AUTHORITY = "app.sapa11y.startup"

private val SUPPORTED_ABIS = listOf("arm64-v8a", "armeabi-v7a")
private const val METADATA_DIR = "assets/bin/Data/Managed/Metadata/"

// Resolves resources from the .mpp this class was compiled into, not from the patcher.
private val BUNDLE_LOADER: ClassLoader = object {}.javaClass.classLoader

/**
 * Super Auto Pets ships on Android as an IL2CPP Unity build, so the Windows
 * mod's MelonLoader host does not exist. This patch adds an equivalent runtime:
 *
 *  - libsapa11y.so, which resolves the il2cpp reflection API out of the
 *    already-loaded libil2cpp.so and walks the game's live UGUI controls,
 *  - the extension (merged from sapa11y.mpe), which polls those snapshots,
 *    injects a virtual accessibility tree into the game's own window, speaks
 *    the controls, filters hardware key events and activates controls,
 *  - the keyword dictionary, and a startup provider that writes the report.
 *
 * Everything runs inside the game's process, so no root, no Xposed and no
 * separate app are involved. There is no accessibility service: the tree is
 * added to the game activity, so nothing has to be enabled in Android settings.
 */
val SUPER_AUTO_PETS = Compatibility(
    name = "Super Auto Pets",
    packageName = "com.teamwood.spacewood.unity",
    apkFileType = ApkFileType.APK,
    // The game icon's background.
    appIconColor = 0xFFB84D,
    targets = listOf(
        // No version is pinned yet: the ABI and the il2cpp metadata are what the
        // patch depends on, and both are stable across releases. Tighten this to
        // the exact versions tested once the port has been run on a device.
        AppTarget(version = null, isExperimental = true),
    ),
)

@Suppress("unused")
val superAutoPetsAccessibilityPatch = patch(
    name = "Super Auto Pets accessibility",
    description = "Adds screen reader support: the current menu item is read as you move, " +
        "keys drive the game, and the keyword dictionary works on the phone.",
    default = true,
) {
    compatibleWith(SUPER_AUTO_PETS)
    category("Super Auto Pets accessibility")

    // The service and its helpers, compiled to dex.
    extendWith("extensions/sapa11y.mpe")

    execute {
        // Morphe's entry listing only indexes directories, so IL2CPP and the shipped
        // architectures are detected from their directories rather than individual files.
        if (listApkEntries(METADATA_DIR).isEmpty()) {
            throw PatchException(
                "This is not an IL2CPP build of Super Auto Pets. The patch needs the IL2CPP APK " +
                    "(the one with libil2cpp.so and global-metadata.dat).",
            )
        }

        // Entries can be files on one APK and bare directories on another, so take the
        // first path segment after lib/ in either form.
        val abis = listApkEntries("lib/")
            .map { it.removePrefix("lib/").substringBefore('/') }
            .filter { it in SUPPORTED_ABIS }
            .distinct()
        if (abis.isEmpty()) {
            throw PatchException("No arm64-v8a or armeabi-v7a libraries in this APK.")
        }

        val bundle = BUNDLE_LOADER

        // Native bridge, one per architecture the game ships.
        for (abi in abis) {
            val stream = bundle.getResourceAsStream("native/$abi/libsapa11y.so")
                ?: throw PatchException("Patch bundle is missing libsapa11y.so for $abi")
            resourceFile("lib/$abi/libsapa11y.so", false).outputStream().use { out ->
                stream.use { it.copyTo(out) }
            }
        }

        // The keyword dictionary the Windows mod embeds in its DLL.
        val dictionary = bundle.getResourceAsStream("assets/sapa11y/WikiDictionary.json")
            ?: throw PatchException("Patch bundle is missing WikiDictionary.json")
        resourceFile("assets/sapa11y/WikiDictionary.json", false).outputStream().use { out ->
            dictionary.use { it.copyTo(out) }
        }

        // Manifest: only the startup reporter. Nothing is added to Android's
        // accessibility settings, and no service XML is shipped.
        document("AndroidManifest.xml").use { manifest ->
            val application = manifest.getElementsByTagName("application").item(0) as? Element
                ?: throw PatchException("Manifest has no <application>")

            val providers = manifest.getElementsByTagName("provider")
            val providerPresent = (0 until providers.length).any { index ->
                (providers.item(index) as? Element)?.getAttribute("android:name") == STARTUP_PROVIDER
            }
            if (!providerPresent) {
                application.appendChild(
                    manifest.createElement("provider").apply {
                        setAttribute("android:name", STARTUP_PROVIDER)
                        setAttribute("android:authorities", STARTUP_AUTHORITY)
                        setAttribute("android:exported", "false")
                    },
                )
            }

            // No accessibility service is declared: the tree is injected into the
            // game's own window, so nothing has to be enabled in Android settings.
        }
    }
}
