[CmdletBinding()]
param(
    [string]$OpenCvRoot = "",
    [string]$AndroidSdk = "",
    [string]$NdkVersion = "27.0.12077973",
    [string]$CmakeVersion = "3.22.1",
    [string]$PythonExecutable = "python",
    [switch]$SkipSdkBuild,
    [switch]$SkipAarBuild,
    [switch]$InstallIntoProject
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Get-LightstopFileHash {
    param(
        [Parameter(Mandatory = $true)][string]$Algorithm,
        [Parameter(Mandatory = $true)][string]$LiteralPath
    )
    $hasher = switch ($Algorithm.ToUpperInvariant()) {
        "MD5" { [Security.Cryptography.MD5]::Create() }
        "SHA256" { [Security.Cryptography.SHA256]::Create() }
        default { throw "Unsupported hash algorithm: $Algorithm" }
    }
    $stream = [IO.File]::OpenRead($LiteralPath)
    try {
        return [BitConverter]::ToString($hasher.ComputeHash($stream)).Replace("-", "")
    }
    finally {
        $stream.Dispose()
        $hasher.Dispose()
    }
}

$openCvVersion = "4.12.0"
$aarRevision = "r2"
$moduleList = "core,imgproc,imgcodecs,video,videoio,features2d,calib3d,java"
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path

if ([string]::IsNullOrWhiteSpace($OpenCvRoot)) {
    $OpenCvRoot = Join-Path (Split-Path -Parent $projectRoot) "opencv-lightstop-slim"
}
if ([string]::IsNullOrWhiteSpace($AndroidSdk)) {
    $sdkCandidates = @(
        $env:ANDROID_SDK_ROOT,
        $env:ANDROID_HOME,
        $(if ($env:LOCALAPPDATA) { Join-Path $env:LOCALAPPDATA "Android\Sdk" })
    ) | Where-Object { $_ -and (Test-Path -LiteralPath $_) }
    $AndroidSdk = $sdkCandidates | Select-Object -First 1
}
if ([string]::IsNullOrWhiteSpace($AndroidSdk)) {
    throw "Android SDK not found. Set ANDROID_SDK_ROOT or pass -AndroidSdk."
}
if (-not (Test-Path -LiteralPath $PythonExecutable)) {
    $pythonCommand = Get-Command -Name $PythonExecutable -ErrorAction SilentlyContinue
    if (-not $pythonCommand) {
        throw "Python not found. Put Python on PATH or pass -PythonExecutable."
    }
    $PythonExecutable = $pythonCommand.Source
}

$sourceDir = Join-Path $OpenCvRoot "src\opencv-$openCvVersion"
$sdkBuildDir = Join-Path $OpenCvRoot "build\android-sdk-$openCvVersion"
$aarBuildDir = Join-Path $OpenCvRoot "build\aar-$openCvVersion"
$outputDir = Join-Path $OpenCvRoot "outputs"
$configPath = Join-Path $PSScriptRoot "lightmeter-android.config.py"
$aarWrapperPath = Join-Path $PSScriptRoot "build_java_shared_aar_windows.py"
$gradleWrapperProperties = Join-Path $PSScriptRoot "gradle-wrapper.properties"
$ndkPath = Join-Path $AndroidSdk "ndk\$NdkVersion"
$cmakePath = Join-Path $AndroidSdk "cmake\$CmakeVersion"
$officialSdkScript = Join-Path $sourceDir "platforms\android\build_sdk.py"
$officialAarScript = Join-Path $sourceDir "platforms\android\build_java_shared_aar.py"
$downloadDir = Join-Path $OpenCvRoot "downloads"
$sourceArchive = Join-Path $downloadDir "opencv-$openCvVersion.zip"
$sourceArchiveUrl = "https://codeload.github.com/opencv/opencv/zip/refs/tags/$openCvVersion"
$sourceArchiveSha256 = "FA3FAF7581F1FA943C9E670CF57DD6BA1C5B4178F363A188A2C8BFF1EB28B7E4"

# Bootstrap the pinned source when this machine has no previous OpenCV workspace. Downloads are
# promoted only after SHA-256 verification, so an interrupted transfer is never used as source.
if (-not (Test-Path -LiteralPath $sourceDir)) {
    New-Item -ItemType Directory -Force -Path $downloadDir | Out-Null
    $sourceArchiveValid = (Test-Path -LiteralPath $sourceArchive) -and
        ((Get-LightstopFileHash -Algorithm SHA256 -LiteralPath $sourceArchive) -eq $sourceArchiveSha256)
    if (-not $sourceArchiveValid) {
        $partialArchive = "$sourceArchive.partial"
        $partialValid = (Test-Path -LiteralPath $partialArchive) -and
            ((Get-LightstopFileHash -Algorithm SHA256 -LiteralPath $partialArchive) -eq $sourceArchiveSha256)
        if ($partialValid) {
            Move-Item -LiteralPath $partialArchive -Destination $sourceArchive -Force
        }
        else {
            Remove-Item -LiteralPath $partialArchive -Force -ErrorAction SilentlyContinue
            Write-Host "Downloading pinned OpenCV $openCvVersion source"
            $curl = Get-Command -Name "curl.exe" -ErrorAction SilentlyContinue
            if ($curl) {
                & $curl.Source `
                    --fail `
                    --location `
                    --retry 5 `
                    --retry-all-errors `
                    --connect-timeout 30 `
                    --output $partialArchive `
                    $sourceArchiveUrl
                if ($LASTEXITCODE -ne 0) {
                    throw "OpenCV source download failed with curl exit code $LASTEXITCODE"
                }
            }
            else {
                Invoke-WebRequest -Uri $sourceArchiveUrl -OutFile $partialArchive -UseBasicParsing
            }
            $actualSourceHash = Get-LightstopFileHash -Algorithm SHA256 -LiteralPath $partialArchive
            if ($actualSourceHash -ne $sourceArchiveSha256) {
                Remove-Item -LiteralPath $partialArchive -Force -ErrorAction SilentlyContinue
                throw "OpenCV source SHA-256 mismatch: expected $sourceArchiveSha256, got $actualSourceHash"
            }
            Move-Item -LiteralPath $partialArchive -Destination $sourceArchive -Force
        }
    }
    $sourceParent = Split-Path -Parent $sourceDir
    New-Item -ItemType Directory -Force -Path $sourceParent | Out-Null
    Expand-Archive -LiteralPath $sourceArchive -DestinationPath $sourceParent -Force
}

# OpenCV embeds its CMake status report in cv::getBuildInformation(). Without
# sanitizing it, public binaries reveal the local Windows account through SDK,
# NDK, compiler, and Python paths. This deterministic source patch changes only
# that diagnostic string; it does not alter OpenCV algorithms or binary APIs.
$openCvUtilsPath = Join-Path $sourceDir "cmake\OpenCVUtils.cmake"
$buildInfoMarker = "# lightstop: redact the build user's home directory (v2)"
$legacyBuildInfoMarker = "# lightstop: redact the build user's home directory"
$buildInfoNeedle = '  string(REGEX REPLACE "^\n+|\n+$" "" msg "${msg}")'
$legacyBuildInfoSanitizer = @'
  # lightstop: redact the build user's home directory
  if(DEFINED ENV{USERPROFILE})
    file(TO_CMAKE_PATH "$ENV{USERPROFILE}" __lightstop_user_profile)
    string(REPLACE "${__lightstop_user_profile}" "<USERPROFILE>" msg "${msg}")
    string(REPLACE "$ENV{USERPROFILE}" "<USERPROFILE>" msg "${msg}")
  endif()
'@
$buildInfoSanitizer = @'
  # lightstop: redact the build user's home directory (v2)
  if(DEFINED ENV{USERPROFILE})
    file(TO_CMAKE_PATH "$ENV{USERPROFILE}" __lightstop_user_profile)
    string(REPLACE "\\" "\\\\" __lightstop_user_profile_escaped "$ENV{USERPROFILE}")
    string(REPLACE "${__lightstop_user_profile}" "<USERPROFILE>" msg "${msg}")
    string(REPLACE "${__lightstop_user_profile_escaped}" "<USERPROFILE>" msg "${msg}")
    string(REPLACE "$ENV{USERPROFILE}" "<USERPROFILE>" msg "${msg}")
  endif()
'@
if (-not (Test-Path -LiteralPath $openCvUtilsPath)) {
    throw "OpenCV build utility is missing: $openCvUtilsPath"
}
$openCvUtils = [IO.File]::ReadAllText($openCvUtilsPath)
if (-not $openCvUtils.Contains($buildInfoMarker)) {
    if ($openCvUtils.Contains($legacyBuildInfoSanitizer)) {
        $patchedOpenCvUtils = $openCvUtils.Replace(
            $legacyBuildInfoSanitizer,
            $buildInfoSanitizer
        )
    }
    elseif ($openCvUtils.Contains($legacyBuildInfoMarker)) {
        throw "Unknown legacy build-info patch found; audit OpenCVUtils.cmake before rebuilding."
    }
    elseif (-not $openCvUtils.Contains($buildInfoNeedle)) {
        throw "OpenCV build-info patch point changed; audit OpenCVUtils.cmake before upgrading."
    }
    else {
        $patchedOpenCvUtils = $openCvUtils.Replace(
            $buildInfoNeedle,
            "$buildInfoNeedle`r`n$buildInfoSanitizer"
        )
    }
    [IO.File]::WriteAllText(
        $openCvUtilsPath,
        $patchedOpenCvUtils,
        [Text.UTF8Encoding]::new($false)
    )
}

# OpenCV 4.12.0's Android driver unconditionally asks Ninja for opencv_tests on the install ABI,
# even when an ABI config deliberately overrides BUILD_TESTS=OFF. Keep the official default for
# normal builds, but skip that nonexistent target for this runtime-only matrix.
$buildSdkTestMarker = "# lightstop: honor BUILD_TESTS=OFF for the install ABI"
$buildSdkTestPattern = '(?m)^        if do_install:\r?\n            build_targets\.append\("opencv_tests"\)$'
$buildSdkContents = [IO.File]::ReadAllText($officialSdkScript)
if (-not $buildSdkContents.Contains($buildSdkTestMarker)) {
    $buildSdkReplacement = @'
        # lightstop: honor BUILD_TESTS=OFF for the install ABI
        if do_install and abi.cmake_vars.get("BUILD_TESTS", "ON") != "OFF":
            build_targets.append("opencv_tests")
'@
    $patchedBuildSdk = [regex]::Replace(
        $buildSdkContents,
        $buildSdkTestPattern,
        $buildSdkReplacement,
        1
    )
    if ($patchedBuildSdk -eq $buildSdkContents) {
        throw "OpenCV build_sdk.py test-target patch point changed; audit before rebuilding."
    }
    [IO.File]::WriteAllText(
        $officialSdkScript,
        $patchedBuildSdk,
        [Text.UTF8Encoding]::new($false)
    )
}

# The same driver assumes every x86/x86_64 build must contain IPP. Respect an explicit OFF so
# the runtime does not carry a large optional acceleration package that the app never calls.
$buildSdkIppMarker = "# lightstop: honor WITH_IPP=OFF for x86 dependency checks"
$buildSdkIppPattern = '(?m)^        #Check HAVE_IPP x86 / x86_64\r?\n        if abi\.haveIPP\(\):\r?\n'
$buildSdkContents = [IO.File]::ReadAllText($officialSdkScript)
if (-not $buildSdkContents.Contains($buildSdkIppMarker)) {
    $buildSdkIppReplacement = (@'
        #Check HAVE_IPP x86 / x86_64
        # lightstop: honor WITH_IPP=OFF for x86 dependency checks
        if abi.haveIPP() and abi.cmake_vars.get("WITH_IPP", "ON") != "OFF":
'@) + "`r`n"
    $patchedBuildSdk = [regex]::Replace(
        $buildSdkContents,
        $buildSdkIppPattern,
        $buildSdkIppReplacement,
        1
    )
    if ($patchedBuildSdk -eq $buildSdkContents) {
        throw "OpenCV build_sdk.py IPP-check patch point changed; audit before rebuilding."
    }
    [IO.File]::WriteAllText(
        $officialSdkScript,
        $patchedBuildSdk,
        [Text.UTF8Encoding]::new($false)
    )
}

# OpenCV's Android driver also insists that KleidiCV must be enabled for arm64, even when the
# selected ABI configuration explicitly disables it. KleidiCV is an optional acceleration layer;
# a deliberately slim build must skip that dependency assertion while preserving it by default.
$buildSdkKleidiMarker = "# lightstop: honor WITH_KLEIDICV=OFF for armv8 dependency checks"
$buildSdkKleidiPattern = '(?m)^        #Check HAVE_KLEIDICV for armv8\r?\n        if abi\.haveKleidiCV\(\):\r?\n'
$buildSdkContents = [IO.File]::ReadAllText($officialSdkScript)
if (-not $buildSdkContents.Contains($buildSdkKleidiMarker)) {
    $buildSdkKleidiReplacement = (@'
        #Check HAVE_KLEIDICV for armv8
        # lightstop: honor WITH_KLEIDICV=OFF for armv8 dependency checks
        if abi.haveKleidiCV() and abi.cmake_vars.get("WITH_KLEIDICV", "ON") != "OFF":
'@) + "`r`n"
    $patchedBuildSdk = [regex]::Replace(
        $buildSdkContents,
        $buildSdkKleidiPattern,
        $buildSdkKleidiReplacement,
        1
    )
    if ($patchedBuildSdk -eq $buildSdkContents) {
        throw "OpenCV build_sdk.py KleidiCV-check patch point changed; audit before rebuilding."
    }
    [IO.File]::WriteAllText(
        $officialSdkScript,
        $patchedBuildSdk,
        [Text.UTF8Encoding]::new($false)
    )
}
else {
    # Repair the output produced by an earlier revision whose here-string did not preserve the
    # newline before the existing log statement. This branch is idempotent for valid sources.
    $collapsedKleidiLine = 'if abi.haveKleidiCV() and abi.cmake_vars.get("WITH_KLEIDICV", "ON") != "OFF":           log.info'
    if ($buildSdkContents.Contains($collapsedKleidiLine)) {
        $patchedBuildSdk = $buildSdkContents.Replace(
            $collapsedKleidiLine,
            "if abi.haveKleidiCV() and abi.cmake_vars.get(`"WITH_KLEIDICV`", `"ON`") != `"OFF`":`r`n           log.info"
        )
        [IO.File]::WriteAllText(
            $officialSdkScript,
            $patchedBuildSdk,
            [Text.UTF8Encoding]::new($false)
        )
    }
}

$requiredPaths = @(
    $sourceDir,
    $AndroidSdk,
    $ndkPath,
    $cmakePath,
    $PythonExecutable,
    $openCvUtilsPath,
    $configPath,
    $aarWrapperPath,
    $gradleWrapperProperties,
    $officialSdkScript,
    $officialAarScript
)
foreach ($requiredPath in $requiredPaths) {
    if (-not (Test-Path -LiteralPath $requiredPath)) {
        throw "Required path is missing: $requiredPath"
    }
}

New-Item -ItemType Directory -Force -Path $sdkBuildDir, $aarBuildDir, $outputDir | Out-Null

# Keep every dependency downloaded by the OpenCV AAR Gradle build outside the app repo.
$env:GRADLE_USER_HOME = Join-Path $OpenCvRoot "gradle-home"
$existingGradleHome = Join-Path $env:USERPROFILE ".gradle"
$existingDependencyCacheRoot = Join-Path $existingGradleHome "caches"
$existingModulesCache = Join-Path $existingDependencyCacheRoot "modules-2"
if (Test-Path -LiteralPath $existingModulesCache) {
    # Gradle appends "modules-2" itself; the variable must point at its parent.
    $env:GRADLE_RO_DEP_CACHE = $existingDependencyCacheRoot
}

# OpenCV's SDK template requests the much larger Gradle "all" archive. The bin
# distribution contains everything needed for these Android library builds.
$sdkGradleProperties = Join-Path $sourceDir "platforms\android\gradle-wrapper\gradle\wrapper\gradle-wrapper.properties.in"
$aarGradleProperties = Join-Path $sourceDir "platforms\android\aar-template\gradle\wrapper\gradle-wrapper.properties"
Copy-Item -LiteralPath $gradleWrapperProperties -Destination $sdkGradleProperties -Force
Copy-Item -LiteralPath $gradleWrapperProperties -Destination $aarGradleProperties -Force

# Reuse an already validated Gradle 8.13 wrapper distribution when available,
# while keeping the active build cache and every new download under OpenCvRoot.
$existingGradleDistribution = Join-Path $existingGradleHome "wrapper\dists\gradle-8.13-bin"
$buildGradleDists = Join-Path $env:GRADLE_USER_HOME "wrapper\dists"
$buildGradleDistribution = Join-Path $buildGradleDists "gradle-8.13-bin"
if ((Test-Path -LiteralPath $existingGradleDistribution) -and -not (Test-Path -LiteralPath $buildGradleDistribution)) {
    New-Item -ItemType Directory -Force -Path $buildGradleDists | Out-Null
    Copy-Item -LiteralPath $existingGradleDistribution -Destination $buildGradleDists -Recurse
}

$env:ANDROID_SDK = $AndroidSdk
$env:ANDROID_HOME = $AndroidSdk
$env:ANDROID_NDK = $ndkPath
$env:ANDROID_NDK_HOME = $ndkPath

if (-not $SkipSdkBuild) {
    Write-Host "Building OpenCV $openCvVersion modules: $moduleList"
    & $PythonExecutable $officialSdkScript `
        $sdkBuildDir `
        $sourceDir `
        --sdk_path $AndroidSdk `
        --ndk_path $ndkPath `
        --config $configPath `
        --modules_list $moduleList `
        --no_samples_build `
        --no_kotlin `
        --no_ccache `
        --use_android_buildtools
    if ($LASTEXITCODE -ne 0) {
        throw "OpenCV Android SDK build failed with exit code $LASTEXITCODE"
    }
}

$generatedSdk = Join-Path $sdkBuildDir "OpenCV-android-sdk"
if (-not (Test-Path -LiteralPath $generatedSdk)) {
    throw "Generated OpenCV Android SDK is missing: $generatedSdk"
}

if (-not $SkipAarBuild) {
    $aarNdkPath = $ndkPath.Replace("\", "/")
    $aarCmakePath = $cmakePath.Replace("\", "/")
    Push-Location $aarBuildDir
    try {
        & $PythonExecutable $aarWrapperPath `
            $officialAarScript `
            $generatedSdk `
            --android_compile_sdk 34 `
            --android_min_sdk 28 `
            --android_target_sdk 34 `
            --java_version 17 `
            --ndk_location $aarNdkPath `
            --cmake_location $aarCmakePath
        if ($LASTEXITCODE -ne 0) {
            throw "OpenCV AAR build failed with exit code $LASTEXITCODE"
        }
    }
    finally {
        Pop-Location
    }
}

$generatedAar = Join-Path $aarBuildDir "outputs\opencv_java_shared_$openCvVersion.aar"
if (-not (Test-Path -LiteralPath $generatedAar)) {
    throw "Generated OpenCV AAR is missing: $generatedAar"
}

$versionedAar = Join-Path $outputDir "opencv-slim-$openCvVersion-$aarRevision.aar"
Copy-Item -LiteralPath $generatedAar -Destination $versionedAar -Force
$aarHash = Get-LightstopFileHash -Algorithm SHA256 -LiteralPath $versionedAar

if ($InstallIntoProject) {
    $projectLibDir = Join-Path $projectRoot "app\libs"
    $licenseDir = Join-Path $projectRoot "app\src\main\assets\licenses\third-party"
    New-Item -ItemType Directory -Force -Path $projectLibDir | Out-Null
    New-Item -ItemType Directory -Force -Path $licenseDir | Out-Null
    $aarFileName = Split-Path -Leaf $versionedAar
    Copy-Item -LiteralPath $versionedAar -Destination (Join-Path $projectLibDir $aarFileName) -Force
    Copy-Item `
        -LiteralPath (Join-Path $ndkPath "NOTICE.toolchain") `
        -Destination (Join-Path $licenseDir "android-ndk-r27-NOTICE.toolchain.txt") `
        -Force
}

Write-Host "OpenCV AAR: $versionedAar"
Write-Host "SHA-256: $aarHash"
