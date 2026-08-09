$cfg=@{}
Get-Content (Join-Path $PSScriptRoot '..\.env.production') | ForEach-Object { if($_ -match '^([^#=]+)=(.*)$'){ $cfg[$matches[1]]=$matches[2].Trim('"') } }
$root=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$env:APP_DATASOURCE_URL='jdbc:postgresql://localhost:5432/codebase_kb_dev'
$env:APP_DATASOURCE_PASSWORD=$cfg.POSTGRES_PASSWORD
$env:APP_INITIAL_ADMIN_USERNAME=$cfg.APP_INITIAL_ADMIN_USERNAME
$env:APP_INITIAL_ADMIN_PASSWORD=$cfg.APP_INITIAL_ADMIN_PASSWORD
$env:APP_LLM_MASTER_KEY=$cfg.APP_LLM_MASTER_KEY
$env:APP_REPOSITORY_ALLOWED_ROOTS=(Resolve-Path (Join-Path $root 'runtime\repositories')).Path
$env:APP_MANAGED_DATA_ROOT=(Resolve-Path (Join-Path $root 'runtime\data')).Path
$env:APP_SESSION_COOKIE_SECURE='false'
Set-Location $root
& mvn.cmd -pl backend spring-boot:run
