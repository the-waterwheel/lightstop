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
    ZONE_MARKING_METHOD,
    LANGUAGE,
    THEME,
    HANDEDNESS,
}

enum class SettingActionKey {
    MANAGE_CAMERAS,
    START_METERING_CALIBRATION,
    START_VIGNETTING_CALIBRATION,
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
    val actions: List<SettingActionSpec> = emptyList(),
    val enabled: Boolean = true,
)

data class SettingActionSpec(
    val key: SettingActionKey,
    val label: LocalizedLabel,
    val description: LocalizedLabel? = null,
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
                SettingItemSpec(
                    key = SettingKey.ZONE_MARKING_METHOD,
                    label = LocalizedLabel("标点方式", "Marking method"),
                    options = listOf(
                        option(ZoneMarkingMethod.BUTTON, "按键标点", "Button marking"),
                        option(ZoneMarkingMethod.TOUCH, "触屏标点", "Touch marking"),
                    ),
                ),
            ),
            actions = listOf(
                SettingActionSpec(
                    key = SettingActionKey.MANAGE_CAMERAS,
                    label = LocalizedLabel("选择与管理摄像头", "Select and manage cameras"),
                    description = LocalizedLabel(
                        "选择测光摄像头、添加备注或隐藏不用的摄像头",
                        "Select a metering camera, add notes, or hide unused cameras",
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
            label = LocalizedLabel("校准", "Calibration"),
            items = emptyList(),
            actions = listOf(
                SettingActionSpec(
                    key = SettingActionKey.START_METERING_CALIBRATION,
                    label = LocalizedLabel("测光校准", "Metering calibration"),
                    description = LocalizedLabel(
                        "使用参考 EV、相机曝光值或 18% 灰卡 Lux 校准",
                        "Calibrate with EV, camera exposure, or lux on an 18% gray card",
                    ),
                ),
                SettingActionSpec(
                    key = SettingActionKey.START_VIGNETTING_CALIBRATION,
                    label = LocalizedLabel("暗角矫正", "Vignetting correction"),
                    description = LocalizedLabel(
                        "拍摄亮度均匀画面，为当前镜头生成 RAW 二维矫正图",
                        "Capture a uniform scene and build a 2D RAW map for this lens",
                    ),
                ),
            ),
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
