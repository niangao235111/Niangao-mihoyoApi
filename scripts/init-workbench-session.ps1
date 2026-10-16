param()
$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
$configPath=Join-Path $projectRoot '.local/workbench-local.yaml'
if(Test-Path -LiteralPath $configPath){
    Write-Host '本地工作台密钥配置已存在，未覆盖。保持该文件稳定，重置密钥会使现有会话无法恢复。'
    return
}
New-Item -ItemType Directory -Force -Path (Join-Path $projectRoot '.local') | Out-Null
$secretBytes=[byte[]]::new(32)
$generator=[Security.Cryptography.RandomNumberGenerator]::Create()
try { $generator.GetBytes($secretBytes) } finally { $generator.Dispose() }
$keyValue=[Convert]::ToBase64String($secretBytes)
$contents="# 本机专用，不提交，不与 Redis 密文存放在同一位置。`nworkbench:`n  session:`n    encryption-key: '$keyValue'`n"
[IO.File]::WriteAllText($configPath,$contents,[Text.UTF8Encoding]::new($false))
[Array]::Clear($secretBytes,0,$secretBytes.Length)
Write-Host '已生成本地工作台 AES-256 密钥配置。密钥未输出；重启后端后生效。'
