package app.setlog.capture.model

enum class PhysicalVolumeKey {
    UP,
    DOWN;

    fun opposite(): PhysicalVolumeKey = if (this == UP) DOWN else UP
}

enum class ShortcutAction {
    FINISH,
    OPEN_GALLERY,
    NONE,
}

data class InputSettings(
    val recordKey: PhysicalVolumeKey = PhysicalVolumeKey.UP,
    val doublePressAction: ShortcutAction = ShortcutAction.FINISH,
    val triplePressAction: ShortcutAction = ShortcutAction.OPEN_GALLERY,
)

enum class TimestampStyle {
    CLEAN,
    BOXED,
    MONOSPACED,
}

data class TimestampOverlaySettings(
    val enabled: Boolean = true,
    val x: Float = 0.50f,
    val y: Float = 0.14f,
    val scale: Float = 1.0f,
    val style: TimestampStyle = TimestampStyle.BOXED,
) {
    fun sanitized(): TimestampOverlaySettings = copy(
        x = x.coerceIn(0.08f, 0.92f),
        y = y.coerceIn(0.08f, 0.88f),
        scale = scale.coerceIn(0.60f, 1.80f),
    )
}
