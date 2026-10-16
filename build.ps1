param(
    [string]$JdkHome = "$env:USERPROFILE/.jdks/jbr-21.0.11",
    [string]$Maven = 'C:/Program Files/JetBrains/IntelliJ IDEA 2026.2.3/plugins/maven-plugin/lib/maven3/bin/mvn.cmd',
    [string]$Repository = (Join-Path (Split-Path $PSScriptRoot -Parent) 'plugin-modernization/build/m2'),
    [string]$NukkitJar = (Join-Path (Split-Path $PSScriptRoot -Parent) 'Nukkit/target/nukkit-1.0-SNAPSHOT.jar'),
    [switch]$Offline
)
$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
$previousJava = $env:JAVA_HOME
try {
    if (!(Test-Path (Join-Path $JdkHome 'bin/java.exe'))) { throw "JDK missing: $JdkHome" }
    if (!(Test-Path $Maven)) { throw "Maven missing: $Maven" }
    $core = $NukkitJar
    if (!(Test-Path $core)) { throw "Nukkit jar missing, build it first: $core" }
    $env:JAVA_HOME = $JdkHome
    $common = @('-B', '-ntp', "-Dmaven.repo.local=$Repository")
    if ($Offline) { $common += '-o' }
    # The same core jar as the other plugins use
    & $Maven @common 'org.apache.maven.plugins:maven-install-plugin:3.1.4:install-file' "-Dfile=$core" '-DgroupId=cn.nukkit' '-DartifactId=Nukkit' '-Dversion=1.0-SNAPSHOT' '-Dpackaging=jar' '-DgeneratePom=true'
    if ($LASTEXITCODE) { throw 'Nukkit dependency installation failed.' }
    & $Maven @common clean install
    if ($LASTEXITCODE) { throw 'Build failed. See Maven errors above.' }
    New-Item -ItemType Directory -Force output | Out-Null
    Copy-Item -LiteralPath 'target/tokaDiscordLink-0.1.5.jar' -Destination 'output/tokaDiscordLink-0.1.5.jar' -Force
    # A config.yml that can be copied to plugins/tokaDiscordLink/ as it is
    Copy-Item -LiteralPath 'src/main/resources/config.yml' -Destination 'output/config.yml' -Force
    Get-FileHash 'output/tokaDiscordLink-0.1.5.jar'
} finally {
    $env:JAVA_HOME = $previousJava
    Pop-Location
}

