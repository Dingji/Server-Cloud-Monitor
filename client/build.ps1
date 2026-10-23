# Build wrapper: pins JAVA_HOME / ANDROID_HOME to this machine's Android Studio JBR + SDK.
# Usage:  .\build.ps1 [gradle args...]      e.g.  .\build.ps1 :composeApp:packageDistributionForCurrentOS
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot

# NOTE: `@() | Where-Object` collapses to a *string* when exactly one item matches,
# so indexing [0] would yield the first CHARACTER ("C"). Always re-wrap with @().
if (-not $env:JAVA_HOME -or -not (Test-Path "$env:JAVA_HOME\bin\java.exe")) {
    $cand = @(
        "$env:LOCALAPPDATA\Programs\Android Studio\jbr",
        "C:\Program Files\Android\Android Studio\jbr",
        "C:\Program Files\Java\jdk-21"
    ) | Where-Object { Test-Path (Join-Path $_ 'bin\java.exe') } | Select-Object -First 1
    if (-not $cand) { throw "No JDK found. Set JAVA_HOME." }
    $env:JAVA_HOME = [string]$cand
}
if (-not $env:ANDROID_HOME) {
    $sdk = @("$env:LOCALAPPDATA\Android\Sdk", "C:\Android\Sdk") | Where-Object { Test-Path $_ } | Select-Object -First 1
    if ($sdk) { $env:ANDROID_HOME = [string]$sdk; $env:ANDROID_SDK_ROOT = [string]$sdk }
}

function Get-JpackageJdk {
    $candidates = @()
    if ($env:DESKTOP_JDK) { $candidates += $env:DESKTOP_JDK }
    $candidates += "C:\Program Files\Java\jdk-21"
    $adoptium = "C:\Program Files\Eclipse Adoptium"
    if (Test-Path $adoptium) {
        $candidates += @(Get-ChildItem -LiteralPath $adoptium -Directory -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName })
    }
    foreach ($p in $candidates) {
        if ($p -and (Test-Path (Join-Path $p 'bin\jpackage.exe'))) { return [string]$p }
    }
    return $null
}

$argStr = ($args -join ' ')
if ($argStr -match 'Distributable|packageMsi|packageReleaseMsi|packageDistribution|checkRuntime|packageReleaseDistribution') {
    $desktopJdk = Get-JpackageJdk
    if ($desktopJdk) {
        $env:DESKTOP_JDK = $desktopJdk
        Write-Host "DESKTOP_JDK  = $desktopJdk" -ForegroundColor DarkGray
    } else {
        Write-Warning "Desktop packaging needs a full JDK with jpackage.exe (e.g. Temurin 21). Set DESKTOP_JDK or install JDK."
    }
}

# Prefer the pinned local Gradle; fall back to the repo wrapper so the build
# still works on machines without .toolchain/ (it is gitignored).
$gradle = Join-Path $root ".toolchain\gradle-9.8.1\bin\gradle.bat"
if (-not (Test-Path $gradle)) {
    $wrapper = Join-Path $root "gradlew.bat"
    if (Test-Path $wrapper) { $gradle = $wrapper }
    else { throw "Gradle not found at $gradle (and no gradlew.bat wrapper)." }
}

Write-Host "JAVA_HOME    = $env:JAVA_HOME" -ForegroundColor DarkGray
Write-Host "ANDROID_HOME = $env:ANDROID_HOME" -ForegroundColor DarkGray

& $gradle -p $root @args
exit $LASTEXITCODE
