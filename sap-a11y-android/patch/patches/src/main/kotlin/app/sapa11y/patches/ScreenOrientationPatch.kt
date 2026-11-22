package app.sapa11y.patches

import app.morphe.patcher.patch.patch
import app.morphe.patcher.patch.resourceFile
import app.morphe.patcher.patch.stringOption

/**
 * Screen orientation as its own patch: a dropdown in the patcher, baked into
 * the APK as a settings file the game's startup provider reads on launch.
 * The game locks itself to landscape; this overrides that lock, but an
 * Android rotation lock always wins over every setting here.
 */
@Suppress("unused")
val screenOrientationPatch = patch(
    name = "Screen orientation",
    description = "Controls how the game screen turns.",
    default = true,
) {
    compatibleWith(SUPER_AUTO_PETS)
    category("Super Auto Pets accessibility")

    val orientation by stringOption(
        "orientation",
        "user",
        mapOf(
            "Follow Android" to "user",
            "Landscape" to "landscape",
            "Portrait" to "portrait",
        ),
        "Screen orientation",
        "How the game screen turns. Follow Android rotates with the phone " +
            "when auto-rotate is on and stays put when the rotation is locked. " +
            "Landscape keeps the original game behavior. Portrait locks " +
            "portrait unless Android's rotation lock says otherwise.",
    )

    execute {
        resourceFile("assets/sapa11y/orientation.txt", false)
            .apply { parentFile?.mkdirs() }
            .writeText(orientation ?: "user")
    }
}
