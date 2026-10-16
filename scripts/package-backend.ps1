param(
    [Parameter(Mandatory=$true)][string]$OutputDirectory,
    [switch]$SkipBuild
)
$ErrorActionPreference='Stop'
$projectRoot=[IO.Path]::GetFullPath((Split-Path $PSScriptRoot -Parent))
$outputRoot=[IO.Path]::GetFullPath($OutputDirectory)
if($outputRoot -eq $projectRoot -or $outputRoot.StartsWith($projectRoot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){
    throw 'Output must be outside the source project.'
}
New-Item -ItemType Directory -Force -Path $outputRoot | Out-Null
Push-Location $projectRoot
try {
    if(-not $SkipBuild){
        & mvn -DskipTests package
        if($LASTEXITCODE -ne 0){throw 'Backend build failed'}
    }
    $jar=Get-ChildItem -LiteralPath 'target' -Filter '*.jar' | Select-Object -First 1
    if(-not $jar){throw 'Build the backend JAR first.'}
    $tag=Get-Date -Format 'yyyyMMdd-HHmmss-fff'
    $stage=Join-Path $outputRoot ('backend-stage-'+$tag)
    foreach($dir in @($stage,(Join-Path $stage 'config'),(Join-Path $stage 'bin'),(Join-Path $stage 'docs'))){New-Item -ItemType Directory -Path $dir | Out-Null}
    Copy-Item -LiteralPath $jar.FullName -Destination (Join-Path $stage 'app.jar')
    Copy-Item -LiteralPath 'deploy/application-production.example.yaml','deploy/backend.env.example' -Destination (Join-Path $stage 'config')
    Copy-Item -LiteralPath 'deploy/start.sh' -Destination (Join-Path $stage 'bin/start.sh')
    Copy-Item -LiteralPath 'deploy/niangao-mihoyo.service' -Destination $stage
    Copy-Item -LiteralPath 'db','licenses','requests' -Destination $stage -Recurse
    Copy-Item -Path 'docs/*.md' -Destination (Join-Path $stage 'docs')
    Get-ChildItem -LiteralPath $stage -Recurse -File | Where-Object {$_.Extension -in '.sh','.service'} | ForEach-Object {
        [IO.File]::WriteAllText($_.FullName,[IO.File]::ReadAllText($_.FullName).Replace("`r`n","`n"),[Text.UTF8Encoding]::new($false))
    }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive=Join-Path $outputRoot ('Niangao-backend-'+$tag+'.zip')
    [IO.Compression.ZipFile]::CreateFromDirectory($stage,$archive,[IO.Compression.CompressionLevel]::Optimal,$false)
    ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLower()+'  '+[IO.Path]::GetFileName($archive)) | Set-Content -LiteralPath ($archive+'.sha256') -Encoding ascii
    $resolvedStage=[IO.Path]::GetFullPath($stage)
    if($resolvedStage.StartsWith($outputRoot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -and (Split-Path $resolvedStage -Leaf) -eq ('backend-stage-'+$tag)){
        Remove-Item -LiteralPath $resolvedStage -Recurse -Force
    } else {throw 'Unexpected staging directory; cleanup stopped'}
    Get-Item -LiteralPath $archive | Select-Object FullName,@{n='MiB';e={[math]::Round($_.Length/1MB,2)}}
} finally {Pop-Location}
