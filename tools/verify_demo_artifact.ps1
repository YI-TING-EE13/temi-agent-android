[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $ApkPath,

    [string] $RepoRoot = (Get-Location).Path,

    [string] $SdkRoot,

    [string] $RequiredAncestor = ''
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$expectedPackage = 'com.robotemi.agent'
$expectedSignerSha256 = '4DA8461B45B02FADCB042F63151FEE05D56EBD5105EB721D7D62E30B88513A7F'

function Stop-Preflight {
    param(
        [Parameter(Mandatory = $true)] [string] $Reason,
        [int] $Code = 1
    )

    Write-Output "ARTIFACT_PREFLIGHT_FAIL reason=$Reason"
    exit $Code
}

function Resolve-AndroidTool {
    param(
        [Parameter(Mandatory = $true)] [string] $ToolName,
        [string] $RequestedSdkRoot
    )

    $sdkRoots = @()
    if ($RequestedSdkRoot) {
        $sdkRoots += $RequestedSdkRoot
    } else {
        foreach ($environmentName in @('ANDROID_SDK_ROOT', 'ANDROID_HOME')) {
            $environmentValue = [Environment]::GetEnvironmentVariable($environmentName)
            if ($environmentValue) {
                $sdkRoots += $environmentValue
            }
        }
    }

    foreach ($root in ($sdkRoots | Select-Object -Unique)) {
        $buildToolsRoot = Join-Path $root 'build-tools'
        if (-not (Test-Path -LiteralPath $buildToolsRoot -PathType Container)) {
            continue
        }

        foreach ($versionDirectory in (Get-ChildItem -LiteralPath $buildToolsRoot -Directory |
                Sort-Object Name -Descending)) {
            foreach ($toolFileName in @($ToolName, "$ToolName.exe", "$ToolName.bat")) {
                $candidate = Join-Path $versionDirectory.FullName $toolFileName
                if (Test-Path -LiteralPath $candidate -PathType Leaf) {
                    return $candidate
                }
            }
        }
    }

    foreach ($commandName in @($ToolName, "$ToolName.exe", "$ToolName.bat")) {
        $command = Get-Command $commandName -ErrorAction SilentlyContinue |
            Select-Object -First 1
        if ($command) {
            return $command.Source
        }
    }

    throw "Android tool unavailable"
}

try {
    if (-not (Test-Path -LiteralPath $RepoRoot -PathType Container)) {
        Stop-Preflight 'REPOSITORY_ROOT_NOT_FOUND' 8
    }
    if (-not (Test-Path -LiteralPath $ApkPath -PathType Leaf)) {
        Stop-Preflight 'APK_NOT_FOUND' 9
    }
    $resolvedRepoRoot = (Resolve-Path -LiteralPath $RepoRoot -ErrorAction Stop).Path
    $resolvedApkPath = (Resolve-Path -LiteralPath $ApkPath -ErrorAction Stop).Path

    $statusOutput = @(& git -c "safe.directory=$resolvedRepoRoot" -C $resolvedRepoRoot status --porcelain --untracked-files=all 2>&1)
    if ($LASTEXITCODE -ne 0) {
        Stop-Preflight 'GIT_STATUS_UNAVAILABLE' 10
    }
    if ($statusOutput.Count -gt 0) {
        Stop-Preflight 'SOURCE_NOT_CLEAN' 11
    }

    $headOutput = @(& git -c "safe.directory=$resolvedRepoRoot" -C $resolvedRepoRoot rev-parse HEAD 2>&1)
    if ($LASTEXITCODE -ne 0 -or $headOutput.Count -eq 0) {
        Stop-Preflight 'SOURCE_HEAD_UNAVAILABLE' 12
    }
    $head = $headOutput[0].ToString().Trim()
    if ($head -notmatch '^[0-9a-fA-F]{40}$') {
        Stop-Preflight 'SOURCE_HEAD_INVALID' 12
    }

    if ($RequiredAncestor) {
        if ($RequiredAncestor -notmatch '^[0-9a-fA-F]{40}$') {
            Stop-Preflight 'REQUIRED_ANCESTOR_INVALID' 13
        }
        & git -c "safe.directory=$resolvedRepoRoot" -C $resolvedRepoRoot merge-base --is-ancestor $RequiredAncestor $head 2>$null
        if ($LASTEXITCODE -ne 0) {
            Stop-Preflight 'SOURCE_NOT_DESCENDANT_OF_REQUIRED_BASELINE' 13
        }
    }

    $buildConfigPath = Join-Path $resolvedRepoRoot 'app\build\generated\source\buildConfig\demo\com\robotemi\agent\BuildConfig.java'
    if (-not (Test-Path -LiteralPath $buildConfigPath -PathType Leaf)) {
        Stop-Preflight 'DEMO_BUILDCONFIG_NOT_FOUND' 14
    }
    $buildConfig = Get-Content -LiteralPath $buildConfigPath -Raw
    if ($buildConfig -notmatch '(?m)MEDIA_V11_ENABLED\s*=\s*true\s*;') {
        Stop-Preflight 'DEMO_MEDIA_V11_NOT_ENABLED' 15
    }
    if ($buildConfig -notmatch '(?m)MEDIA_V11_ATTACH_DEADLINE_MS\s*=\s*10000L\s*;') {
        Stop-Preflight 'DEMO_ATTACH_DEADLINE_INVALID' 16
    }

    $aapt = Resolve-AndroidTool -ToolName 'aapt' -RequestedSdkRoot $SdkRoot
    $apksigner = Resolve-AndroidTool -ToolName 'apksigner' -RequestedSdkRoot $SdkRoot

    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $badgingOutput = @(& $aapt dump badging $resolvedApkPath 2>&1)
        $badgingExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    if ($badgingExitCode -ne 0) {
        Stop-Preflight 'APK_PACKAGE_READ_FAILED' 17
    }
    $badgingText = ($badgingOutput | ForEach-Object { $_.ToString() }) -join "`n"
    $packageMatch = [regex]::Match($badgingText, "package:\s+name='([^']+)'")
    if (-not $packageMatch.Success -or $packageMatch.Groups[1].Value -ne $expectedPackage) {
        Stop-Preflight 'APK_PACKAGE_MISMATCH' 18
    }

    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $signerOutput = @(& $apksigner verify --verbose --print-certs $resolvedApkPath 2>&1)
        $signerExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    if ($signerExitCode -ne 0) {
        Stop-Preflight 'APK_SIGNATURE_READ_FAILED' 19
    }
    $signerText = ($signerOutput | ForEach-Object { $_.ToString() }) -join "`n"
    $certificateMatches = [regex]::Matches(
        $signerText,
        '(?im)certificate\s+SHA-256\s+digest:\s*([0-9a-f:]+)'
    )
    $certificateDigests = @(
        foreach ($certificateMatch in $certificateMatches) {
            $certificateMatch.Groups[1].Value.Replace(':', '').ToUpperInvariant()
        }
    ) | Sort-Object -Unique
    $certificateDigests = @($certificateDigests)
    if ($certificateDigests.Count -ne 1) {
        Stop-Preflight 'SIGNER_COUNT_MISMATCH' 20
    }
    $actualSignerSha256 = $certificateDigests[0]
    if ($actualSignerSha256 -ne $expectedSignerSha256) {
        Stop-Preflight 'SIGNER_MISMATCH' 21
    }

    Write-Output "ARTIFACT_PREFLIGHT_PASS package=$expectedPackage signer_sha256=$actualSignerSha256 source_head=$head media_v11_enabled=true attach_deadline_ms=10000"
    exit 0
} catch {
    Write-Output 'ARTIFACT_PREFLIGHT_FAIL reason=UNEXPECTED_PREFLIGHT_ERROR'
    exit 22
}
