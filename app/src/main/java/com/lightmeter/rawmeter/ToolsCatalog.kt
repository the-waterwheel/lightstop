package com.lightmeter.rawmeter

/**
 * Identifies the small utility tools hosted by the Tools panel. New tools are added here and in
 * [ToolsCatalog]; the panel renders and dispatches them automatically.
 */
enum class ToolId {
    DEPTH_OF_FIELD,
    LATITUDE,
    PARAMETER_LOG,
    RECIPROCITY,
    FLASH_INDEX,
    COLOR_TEMPERATURE,
    EXPOSURE_CORRECTION,
}

data class ToolSpec(
    val id: ToolId,
    val label: LocalizedLabel,
)

object ToolsCatalog {
    val tools: List<ToolSpec> = listOf(
        ToolSpec(ToolId.DEPTH_OF_FIELD, LocalizedLabel("景深计算", "Depth of field")),
        ToolSpec(ToolId.LATITUDE, LocalizedLabel("宽容度", "Latitude")),
        ToolSpec(ToolId.PARAMETER_LOG, LocalizedLabel("参数记录", "Parameter log")),
        ToolSpec(ToolId.RECIPROCITY, LocalizedLabel("倒易率计算", "Reciprocity")),
        ToolSpec(ToolId.FLASH_INDEX, LocalizedLabel("闪光曝光", "Flash exposure")),
        ToolSpec(ToolId.COLOR_TEMPERATURE, LocalizedLabel("色温估算", "Color temperature")),
    )
}
