[CmdletBinding()]
param(
    [string]$OpenCvRoot = "",
    [string]$AndroidSdk = "",
    [string]$NdkVersion = "25.1.8937393",
    [string]$CmakeVersion = "3.22.1",
    [string]$PythonExecutable = "python",
    [switch]$SkipSdkBuild,
    [switch]$SkipAarBuild,
    [switch]$InstallIntoProject
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$openCvVersion = "4.12.0"
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

# Reuse an already validated Gradle 8.7 wrapper distribution when available,
# while keeping the active build cache and every new download under OpenCvRoot.
$existingGradleDistribution = Join-Path $existingGradleHome "wrapper\dists\gradle-8.7-bin"
$buildGradleDists = Join-Path $env:GRADLE_USER_HOME "wrapper\dists"
$buildGradleDistribution = Join-Path $buildGradleDists "gradle-8.7-bin"
if ((Test-Path -LiteralPath $existingGradleDistribution) -and -not (Test-Path -LiteralPath $buildGradleDistribution)) {
    New-Item -ItemType Directory -Force -Path $buildGradleDists | Out-Null
    Copy-Item -LiteralPath $existingGradleDistribution -Destination $buildGradleDists -Recurse
}

$env:ANDROID_SDK = $AndroidSdk
$env:ANDROID_HOME = $AndroidSdk
$env:ANDROID_NDK = $ndkPath
$env:ANDROID_NDK_HOME = $ndkPath

# Seed OpenCV's verified download cache. These hashes are the values requested
# by OpenCV 4.12.0 in CMakeDownloadLog.txt.
$downloadSeeds = @(
    @{
        Source = Join-Path $downloadDir "oneTBB-v2022.1.0.tar.gz"
        Destination = Join-Path $sourceDir ".cache\tbb\cce28e6cb1ceae14a93848990c98cb6b-v2022.1.0.tar.gz"
        Md5 = "CCE28E6CB1CEAE14A93848990C98CB6B"
    },
    @{
        Source = Join-Path $downloadDir "kleidicv-0.5.0.tar.gz"
        Destination = Join-Path $sourceDir ".cache\kleidicv\ba5648f8df678548f337d19d8ac607d6-kleidicv-0.5.0.tar.gz"
        Md5 = "BA5648F8DF678548F337D19D8AC607D6"
    },
    @{
        Source = Join-Path $downloadDir "ade-v0.1.2e.zip"
        Destination = Join-Path $sourceDir ".cache\ade\962ce79e0b95591f226431f7b5f152cd-v0.1.2e.zip"
        Md5 = "962CE79E0B95591F226431F7B5F152CD"
    }
)
foreach ($seed in $downloadSeeds) {
    if (-not (Test-Path -LiteralPath $seed.Source)) {
        throw "Required verified dependency archive is missing: $($seed.Source)"
    }
    $actualMd5 = (Get-FileHash -Algorithm MD5 -LiteralPath $seed.Source).Hash
    if ($actualMd5 -ne $seed.Md5) {
        throw "MD5 mismatch for $($seed.Source): expected $($seed.Md5), got $actualMd5"
    }
    $cacheDir = Split-Path -Parent $seed.Destination
    New-Item -ItemType Directory -Force -Path $cacheDir | Out-Null
    Copy-Item -LiteralPath $seed.Source -Destination $seed.Destination -Force
}

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

$versionedAar = Join-Path $outputDir "opencv-slim-$openCvVersion-r1.aar"
Copy-Item -LiteralPath $generatedAar -Destination $versionedAar -Force
$aarHash = Get-FileHash -Algorithm SHA256 -LiteralPath $versionedAar

if ($InstallIntoProject) {
    $projectLibDir = Join-Path $projectRoot "app\libs"
    New-Item -ItemType Directory -Force -Path $projectLibDir | Out-Null
    $aarFileName = Split-Path -Leaf $versionedAar
    Copy-Item -LiteralPath $versionedAar -Destination (Join-Path $projectLibDir $aarFileName) -Force
}

Write-Host "OpenCV AAR: $versionedAar"
Write-Host "SHA-256: $($aarHash.Hash)"
