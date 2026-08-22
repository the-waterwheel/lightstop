package com.lightmeter.rawmeter

internal data class ColorTemperatureReferenceEntry(
    val chinese: String,
    val english: String,
    val kelvin: String,
)

internal object ColorTemperatureReference {
    val entries = listOf(
        ColorTemperatureReferenceEntry("烛光", "Candlelight", "1,800–2,000 K"),
        ColorTemperatureReferenceEntry("家用钨丝灯", "Household tungsten", "2,700–3,200 K"),
        ColorTemperatureReferenceEntry("日出或日落", "Sunrise or sunset", "3,000–4,000 K"),
        ColorTemperatureReferenceEntry("暖白荧光灯", "Warm fluorescent", "3,200–3,700 K"),
        ColorTemperatureReferenceEntry("中性白色 LED", "Neutral white LED", "4,000–5,000 K"),
        ColorTemperatureReferenceEntry("正午日光", "Noon daylight", "5,200–5,600 K"),
        ColorTemperatureReferenceEntry("电子闪光灯", "Electronic flash", "5,500–6,000 K"),
        ColorTemperatureReferenceEntry("阴天", "Overcast", "6,000–7,000 K"),
        ColorTemperatureReferenceEntry("晴天阴影", "Open shade", "7,000–9,000 K"),
        ColorTemperatureReferenceEntry("蓝天", "Blue sky", "10,000 K 以上"),
    )
}
