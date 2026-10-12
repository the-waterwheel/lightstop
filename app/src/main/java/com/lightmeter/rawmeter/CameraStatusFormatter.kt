package com.lightmeter.rawmeter

/** Pure bilingual status presentation; never changes session profiles or recovery decisions. */
internal class CameraStatusFormatter(private val localized: (String, String) -> String) {
    fun recovery(failure: CameraFailureKind): String = when (failure) {
        CameraFailureKind.IN_USE -> localized(
            "相机正被其他应用使用，正在重试",
            "Another app is using the camera. Retrying",
        )
        CameraFailureKind.RESOURCE_LIMIT -> localized(
            "相机暂时不可用，正在重试",
            "The camera is temporarily unavailable. Retrying",
        )
        CameraFailureKind.DISABLED -> localized(
            "相机已被系统停用",
            "The camera has been disabled by the system",
        )
        CameraFailureKind.DEVICE -> localized(
            "相机运行异常，正在尝试更稳定的方式",
            "The camera stopped unexpectedly. Trying a more stable method",
        )
        CameraFailureKind.SERVICE -> localized(
            "相机暂时无响应，正在重试",
            "The camera is not responding. Retrying",
        )
        CameraFailureKind.DISCONNECTED -> localized(
            "相机连接中断，正在重新连接",
            "The camera was disconnected. Reconnecting",
        )
        CameraFailureKind.UNKNOWN -> localized(
            "相机暂时无法使用，正在重试",
            "The camera is temporarily unavailable. Retrying",
        )
    }

    fun failure(failure: CameraFailureKind): String = when (failure) {
        CameraFailureKind.IN_USE -> localized(
            "相机正被其他应用使用，请关闭其他相机应用后重试",
            "Another app is using the camera. Close it and try again",
        )
        CameraFailureKind.RESOURCE_LIMIT -> localized(
            "相机资源不足，请关闭其他相机应用后重试",
            "Camera resources are busy. Close other camera apps and try again",
        )
        CameraFailureKind.DISABLED -> localized(
            "相机已被系统停用，请检查隐私或管理设置",
            "The camera is disabled. Check privacy or device management settings",
        )
        CameraFailureKind.DEVICE -> localized(
            "相机无法正常启动，请尝试稳定模式或兼容模式，或重启手机",
            "The camera could not start. Try Stable or Compatibility mode, or restart the phone",
        )
        CameraFailureKind.SERVICE -> localized(
            "相机服务无法恢复，请重启手机后重试",
            "The camera could not recover. Restart the phone and try again",
        )
        CameraFailureKind.DISCONNECTED -> localized(
            "相机连接已中断，请稍后重试",
            "The camera was disconnected. Try again later",
        )
        CameraFailureKind.UNKNOWN -> localized(
            "相机暂时无法使用，请稍后重试",
            "The camera is unavailable. Try again later",
        )
    }

    fun ready(
        cameraInfo: CameraUiInfo,
        pipelineMode: MeteringPipelineMode,
        lowLight: Boolean,
        reducedFrameRate: Boolean,
        safePreview: Boolean,
        transientRawAvailable: Boolean,
    ): String {
        val size = "${cameraInfo.previewSize?.width}×${cameraInfo.previewSize?.height}"
        if (lowLight) {
            return if (reducedFrameRate) {
                localized(
                    "$size · 环境光较暗，已自动降低取景帧率",
                    "$size · Low light; viewfinder frame rate reduced",
                )
            } else {
                localized(
                    "$size · 环境光过暗",
                    "$size · Ambient light is too low",
                )
            }
        }
        if (safePreview) {
            return localized("$size · 手动安全预览", "$size · Manual safe preview")
        }
        return when (pipelineMode) {
            MeteringPipelineMode.AUTO -> if (cameraInfo.rawAvailable || transientRawAvailable) {
                localized("$size · 高精度测光", "$size · High-accuracy metering")
            } else {
                localized("$size · 预览流测光", "$size · Preview-stream metering")
            }
            MeteringPipelineMode.ISOLATED ->
                localized("$size · 稳定模式", "$size · Stable mode")
            MeteringPipelineMode.FAST ->
                localized("$size · 兼容模式", "$size · Compatibility mode")
        }
    }

}
