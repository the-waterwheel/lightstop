package com.lightmeter.rawmeter

enum class SettingsSectionKey {
    METERING,
    GENERAL,
    CALIBRATION,
}

enum class SettingKey {
    APERTURE_STEP,
    SHUTTER_STEP,
    METERING_MODE,
    LANGUAGE,
    THEME,
    HANDEDNESS,
}

data class LocalizedLabel(
    val chinese: String,
    val english: String,
) {
    fun resolve(language: MenuLanguage): String =
        if (language == MenuLanguage.ENGLISH) english else chinese
}

data class SettingOptionSpec(
    val value: String,
    val label: LocalizedLabel,
)

data class SettingItemSpec(
    val key: SettingKey,
    val label: LocalizedLabel,
    val options: List<SettingOptionSpec>,
)

data class SettingsSectionSpec(
    val key: SettingsSectionKey,
    val label: LocalizedLabel,
    val items: List<SettingItemSpec>,
    val enabled: Boolean = true,
)

object SettingsCatalog {
    val sections: List<SettingsSectionSpec> = listOf(
        SettingsSectionSpec(
            key = SettingsSectionKey.METERING,
            label = LocalizedLabel("测光设置", "Metering"),
            items = listOf(
                SettingItemSpec(
                    key = SettingKey.APERTURE_STEP,
                    label = LocalizedLabel("光圈档位", "Aperture step"),
                    options = exposureStepOptions(),
                ),
                SettingItemSpec(
                    key = SettingKey.SHUTTER_STEP,
                    label = LocalizedLabel("快门档位", "Shutter step"),
                    options = exposureStepOptions(),
                ),
                SettingItemSpec(
                    key = SettingKey.METERING_MODE,
                    label = LocalizedLabel("测光方式", "Metering mode"),
                    options = listOf(
                        option(
                            MeteringMode.CENTER_WEIGHTED,
                            "中央重点",
                            "Center-weighted",
                        ),
                        option(MeteringMode.SPOT, "点测光", "Spot"),
                    ),
                ),
            ),
        ),
        SettingsSectionSpec(
            key = SettingsSectionKey.GENERAL,
            label = LocalizedLabel("通用设置", "General"),
            items = listOf(
                SettingItemSpec(
                    key = SettingKey.LANGUAGE,
                    label = LocalizedLabel("语言 / Language", "Language / 语言"),
                    options = listOf(
                        option(MenuLanguage.CHINESE, "中文", "Chinese"),
                        option(MenuLanguage.ENGLISH, "English", "English"),
                    ),
                ),
                SettingItemSpec(
                    key = SettingKey.THEME,
                    label = LocalizedLabel("显示模式", "Appearance"),
                    options = listOf(
                        option(AppTheme.LIGHT, "浅色", "Light"),
                        option(AppTheme.DARK, "深色", "Dark"),
                    ),
                ),
                SettingItemSpec(
                    key = SettingKey.HANDEDNESS,
                    label = LocalizedLabel("惯用手", "Handedness"),
                    options = listOf(
                        option(Handedness.RIGHT, "右手", "Right"),
                        option(Handedness.LEFT, "左手", "Left"),
                    ),
                ),
            ),
        ),
        SettingsSectionSpec(
            key = SettingsSectionKey.CALIBRATION,
            label = LocalizedLabel("测光校准", "Calibration"),
            items = emptyList(),
            enabled = false,
        ),
    )

    private fun exposureStepOptions(): List<SettingOptionSpec> = listOf(
        option(ExposureStep.FULL, "一档", "1 stop"),
        option(ExposureStep.HALF, "二分之一档", "1/2 stop"),
        option(ExposureStep.THIRD, "三分之一档", "1/3 stop"),
    )

    private fun option(value: Enum<*>, chinese: String, english: String) =
        SettingOptionSpec(value.name, LocalizedLabel(chinese, english))
}
