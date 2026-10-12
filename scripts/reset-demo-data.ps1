<#
=====================================================================
 reset-demo-data.ps1 —— 演示数据初始化 / 重置脚本（阶段 12）

 设计原则（与任务书一致）：
   1. 默认 **dry-run（只预览，不删除）**；只有显式传入 -Apply 才会真正删除/写入。
   2. 只操作"带演示标记"的数据；运行期残留（随机 UUID）不会被自动删除。
   3. 明确校验连接的是开发演示库；库名含 prod/production 一律拒绝。
   4. 真正删除前先做逻辑备份（mysqldump），可用 -SkipBackup 关闭（不推荐）。
   5. 删除顺序：子表 → 父表，遵守外键。
   6. 初始化完成后执行 06_verify_relations.sql，仅当无 FAIL 才报告成功。

 用法示例：
   # 1) 只预览将要删除/写入的内容（默认）
   pwsh scripts/reset-demo-data.ps1

   # 2) 备份 + 清理演示数据 + 重新灌入演示种子 + 校验
   pwsh scripts/reset-demo-data.ps1 -Apply -WithSeed

   # 3) 只灌种子（不清理），用于首次初始化
   pwsh scripts/reset-demo-data.ps1 -Apply -SeedOnly

 支持通过环境变量提供连接信息：DB_HOST / DB_PORT / DB_NAME / DB_USER / DB_PASSWORD
=====================================================================
#>

[CmdletBinding()]
param(
  [string]$Database = $(if ($env:DB_NAME) { $env:DB_NAME } else { 'mini-omni-merchant' }),
  [string]$MySqlHost = $(if ($env:DB_HOST) { $env:DB_HOST } else { '127.0.0.1' }),
  [int]$Port = $(if ($env:DB_PORT) { [int]$env:DB_PORT } else { 3306 }),
  [string]$User = $(if ($env:DB_USER) { $env:DB_USER } else { 'root' }),
  [string]$Password = $(if ($env:DB_PASSWORD) { $env:DB_PASSWORD } else { '' }),
  [string]$MySqlBin = 'mysql',
  [string]$MySqlDumpBin = 'mysqldump',
  [string]$BackupDir = 'D:\code\java_code\mini-omni-merchant\backup',
  [switch]$Apply,
  [switch]$WithSeed,
  [switch]$SeedOnly,
  [switch]$SkipBackup,
  [switch]$SkipVerify
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$demoSqlDir = Join-Path $repoRoot 'sql\demo'
$seedFiles = @(
  '01_seed_tenant_identity.sql',
  '02_seed_customer_product.sql',
  '03_seed_orders.sql',
  '04_seed_conversations.sql',
  '05_seed_after_sales.sql'
)

function Info($m)   { Write-Host "[INFO ] $m" -ForegroundColor Cyan }
function Warn($m)   { Write-Host "[WARN ] $m" -ForegroundColor Yellow }
function Ok($m)     { Write-Host "[ OK  ] $m" -ForegroundColor Green }
function Fail($m)   { Write-Host "[FAIL ] $m" -ForegroundColor Red }

# ---------------------------------------------------------------------
# 0. 前置检查：命令行工具存在
# ---------------------------------------------------------------------
function Assert-Tool($name) {
  $cmd = Get-Command $name -ErrorAction SilentlyContinue
  if (-not $cmd) { throw "找不到命令 '$name'。请将 MySQL bin 目录加入 PATH，或通过 -MySqlBin / -MySqlDumpBin 指定完整路径。" }
}
Assert-Tool $MySqlBin
if (-not $SkipBackup) { Assert-Tool $MySqlDumpBin }

$pwArg = @()
if ($Password -ne '') { $pwArg = @("-p$Password") }
else { Warn "未提供数据库密码（-Password 或 DB_PASSWORD）。若数据库需要密码，后续连接会失败。" }

function Invoke-MySql([string]$sql, [switch]$Tab) {
  $args = @("-h$MySqlHost", "-P$Port", "-u$User") + $pwArg
  if ($Tab) { $args += @('-B', '-N') }
  $args += @('-e', $sql, $Database)
  return (& $MySqlBin @args 2>&1)
}

function Invoke-MySqlFile([string]$file) {
  $mysqlArgs = @("-h$MySqlHost", "-P$Port", "-u$User") + $pwArg + @($Database)
  Get-Content -Raw -Encoding UTF8 $file | & $MySqlBin @mysqlArgs
  if ($LASTEXITCODE -ne 0) { throw "执行 SQL 文件失败：$file" }
}

# ---------------------------------------------------------------------
# 1. 安全校验：确认是开发演示库
# ---------------------------------------------------------------------
function Assert-DevDatabase {
  if ($Database -match '(?i)prod') {
    throw "拒绝操作：库名 '$Database' 含 'prod'，疑似生产库。本脚本仅供开发演示库使用。"
  }
  $current = (Invoke-MySql 'SELECT DATABASE();' -Tab) -join ''
  if ($current.Trim() -ne $Database) {
    throw "连接后的当前库为 '$current'，与 -Database '$Database' 不一致，已中止。"
  }
  $tenantCheck = (Invoke-MySql "SELECT COUNT(*) FROM tenant WHERE id IN (1001,1002) AND tenant_code LIKE 'OM-%';" -Tab) -join ''
  if ([int]$tenantCheck.Trim() -lt 2) {
    throw "未找到预期的演示租户 1001/1002（tenant_code 形如 OM-%）。请确认这是演示数据库；如为首次初始化，请使用 -Apply -SeedOnly。"
  }
  Ok "已确认开发演示库：$Database（演示租户 1001/1002 存在）"
}

# ---------------------------------------------------------------------
# 2. 演示数据标记与统计（仅统计，不删除）
# ---------------------------------------------------------------------
# 说明：每个标记对应"确定属于本次演示数据集"的 WHERE 条件。
#       运行期随机 UUID 的会话/消息不在此范围，属于"无法安全识别"，不会被删除。
$demoMarks = [ordered]@{
  'chat_message (演示消息)'      = "message_uuid LIKE 'demo-zh-msg-%'"
  'ticket (演示工单)'            = "ticket_no LIKE 'DEMO-ZH-%'"
  'escalation_record (演示升级)' = "ticket_no LIKE 'DEMO-ZH-%'"
  'conversation (演示会话)'      = "conversation_uuid LIKE 'demo-zh-conv-%'"
  'return_request (演示售后)'    = "request_no LIKE 'DEMO-ZH-RR-%'"
  'commerce_action_request'      = "request_no LIKE 'DEMO-ZH-AR-%'"
  'order_info (演示订单)'        = "sync_source='DEMO' AND (external_order_id LIKE 'ord_outdoor_%' OR external_order_id LIKE 'ord_digital_%')"
  'product (演示商品)'           = "external_product_id LIKE 'prod_outdoor_%' OR external_product_id LIKE 'prod_digital_%'"
  'customer (演示客户)'          = "external_customer_id LIKE 'cus_outdoor_%' OR external_customer_id LIKE 'cus_digital_%'"
  'user_tenant_membership (演示)'= "user_id BETWEEN 9001 AND 9007 AND tenant_id IN (1001,1002)"
  'app_user (演示身份)'          = "email LIKE 'demo-%@example.com' AND id BETWEEN 9001 AND 9007"
  'agent_run (演示会话轨迹)'     = "conversation_uuid LIKE 'demo-zh-conv-%'"
  'agent_step (演示会话轨迹)'    = "conversation_uuid LIKE 'demo-zh-conv-%'"
  'agent_conversation_state'     = "conversation_uuid LIKE 'demo-zh-conv-%'"
  'agent_state_transition'       = "conversation_uuid LIKE 'demo-zh-conv-%'"
  'tool_call_log (演示会话)'     = "conversation_uuid LIKE 'demo-zh-conv-%'"
  'agent_idempotency_guard'      = "conversation_uuid LIKE 'demo-zh-conv-%'"
}

function Get-DemoCounts {
  $rows = @()
  foreach ($k in $demoMarks.Keys) {
    # 表名从中文标签中提取（标签首词英文表名）
    $table = ($k -split ' ')[0]
    $where = $demoMarks[$k]
    try {
      $c = (Invoke-MySql "SELECT COUNT(*) FROM $table WHERE $where;" -Tab) -join ''
      $rows += [pscustomobject]@{ Table = $table; Mark = $k; Rows = [int]($c.Trim()) }
    } catch {
      $rows += [pscustomobject]@{ Table = $table; Mark = $k; Rows = 'N/A' }
    }
  }
  return $rows
}

function Show-Undeletable {
  Info '以下为运行期残留（随机 UUID / 运行期 Trace），不在演示标记范围内，脚本不会自动删除：'
  $checks = @(
    @{ n='conversation(随机UUID)'; s="SELECT COUNT(*) FROM conversation WHERE conversation_uuid NOT LIKE 'conv-%' AND conversation_uuid NOT LIKE 'demo-zh-conv-%';" },
    @{ n='chat_message(随机UUID)'; s="SELECT COUNT(*) FROM chat_message WHERE message_uuid NOT LIKE 'msg-%' AND message_uuid NOT LIKE 'demo-zh-msg-%';" },
    @{ n='app_user(测试账号 it-*)'; s="SELECT COUNT(*) FROM app_user WHERE email LIKE 'it-%' OR email LIKE 'widget-it-%';" }
  )
  foreach ($c in $checks) {
    $v = (Invoke-MySql $c.s -Tab) -join ''
    Write-Host ("         - {0}: {1} 条" -f $c.n, $v.Trim())
  }
}

# ---------------------------------------------------------------------
# 3. 删除（仅在 -Apply 时执行；子表 → 父表）
# ---------------------------------------------------------------------
$deleteOrder = @(
  @{ t='chat_message';             w="message_uuid LIKE 'demo-zh-msg-%'" },
  @{ t='agent_step';               w="conversation_uuid LIKE 'demo-zh-conv-%'" },
  @{ t='agent_run';                w="conversation_uuid LIKE 'demo-zh-conv-%'" },
  @{ t='agent_state_transition';   w="conversation_uuid LIKE 'demo-zh-conv-%'" },
  @{ t='agent_conversation_state'; w="conversation_uuid LIKE 'demo-zh-conv-%'" },
  @{ t='tool_call_log';            w="conversation_uuid LIKE 'demo-zh-conv-%'" },
  @{ t='agent_idempotency_guard';  w="conversation_uuid LIKE 'demo-zh-conv-%'" },
  @{ t='ticket';                   w="ticket_no LIKE 'DEMO-ZH-%'" },
  @{ t='escalation_record';        w="ticket_no LIKE 'DEMO-ZH-%'" },
  @{ t='conversation';             w="conversation_uuid LIKE 'demo-zh-conv-%'" },
  @{ t='return_request';           w="request_no LIKE 'DEMO-ZH-RR-%'" },
  @{ t='commerce_action_request';  w="request_no LIKE 'DEMO-ZH-AR-%'" },
  @{ t='order_info';               w="sync_source='DEMO' AND (external_order_id LIKE 'ord_outdoor_%' OR external_order_id LIKE 'ord_digital_%')" },
  @{ t='product';                  w="external_product_id LIKE 'prod_outdoor_%' OR external_product_id LIKE 'prod_digital_%'" },
  @{ t='customer';                 w="external_customer_id LIKE 'cus_outdoor_%' OR external_customer_id LIKE 'cus_digital_%'" },
  @{ t='user_tenant_membership';   w="user_id BETWEEN 9001 AND 9007 AND tenant_id IN (1001,1002)" },
  @{ t='app_user';                 w="email LIKE 'demo-%@example.com' AND id BETWEEN 9001 AND 9007" }
)

function New-Backup {
  if ($SkipBackup) { Warn '已跳过备份（-SkipBackup）'; return $null }
  if (-not (Test-Path $BackupDir)) { New-Item -ItemType Directory -Path $BackupDir | Out-Null }
  $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
  $file = Join-Path $BackupDir "mini-omni-merchant-$stamp.sql"
  Info "备份数据库到：$file"
  $args = @("-h$MySqlHost", "-P$Port", "-u$User") + $pwArg + @('--single-transaction', '--routines', '--databases', $Database)
  & $MySqlDumpBin @args | Out-File -Encoding UTF8 $file
  if ($LASTEXITCODE -ne 0 -or -not (Test-Path $file)) { throw "备份失败：$file" }
  Ok "备份完成：$file"
  return $file
}

function Remove-DemoData {
  Info '开始清理演示数据（仅带 demo 标记的行）…'
  foreach ($d in $deleteOrder) {
    $before = (Invoke-MySql "SELECT COUNT(*) FROM $($d.t) WHERE $($d.w);" -Tab) -join ''
    Invoke-MySql "DELETE FROM $($d.t) WHERE $($d.w);" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "删除失败：$($d.t) WHERE $($d.w)" }
    Ok ("删除 {0}: {1} 行" -f $d.t, $before.Trim())
  }
}

function Seed-DemoData {
  Info '灌入演示种子数据（01-05，幂等 UPSERT）…'
  foreach ($f in $seedFiles) {
    $path = Join-Path $demoSqlDir $f
    if (-not (Test-Path $path)) { throw "缺少种子文件：$path" }
    Info "执行 $f"
    Invoke-MySqlFile $path
    Ok "完成 $f"
  }
}

function Verify-Relations {
  Info '执行关联与隔离校验（06_verify_relations.sql）…'
  $verifyFile = Join-Path $demoSqlDir '06_verify_relations.sql'
  $mysqlArgs = @("-h$MySqlHost", "-P$Port", "-u$User") + $pwArg + @('-t', $Database)
  $out = Get-Content -Raw -Encoding UTF8 $verifyFile | & $MySqlBin @mysqlArgs
  $out | Write-Host
  if ($LASTEXITCODE -ne 0) { throw '校验 SQL 执行失败' }
  if ($out -match '(?m)\|\s*FAIL\s*\|') {
    throw '关联校验存在 FAIL 项，请检查上方输出。'
  }
  Ok '关联校验全部通过（无 FAIL）。'
}

# =====================================================================
# 主流程
# =====================================================================
Write-Host "=============================================================="
Write-Host " OmniMerchant 演示数据初始化 / 重置（阶段 12）"
Write-Host " 目标库: $Database @ $MySqlHost`:$Port   模式: $(if($Apply){'APPLY(执行)'}else{'DRY-RUN(预览)'})"
Write-Host "=============================================================="

Assert-DevDatabase

# 统计预览
$counts = Get-DemoCounts
Write-Host ''
Info '演示数据标记统计（将被 -Apply 清理并重建的范围）：'
$counts | Format-Table -AutoSize | Out-String | Write-Host
Show-Undeletable

if (-not $Apply) {
  Write-Host ''
  Warn '当前为 DRY-RUN：未做任何修改。'
  Warn '确认无误后，使用 -Apply 执行清理与初始化；首次初始化可加 -SeedOnly 只灌种子。'
  exit 0
}

# 执行
if ($SeedOnly) {
  Seed-DemoData
} else {
  New-Backup | Out-Null
  Remove-DemoData
  if ($WithSeed -or $true) { Seed-DemoData }   # 清理后一律重建，保证可重复
}

if (-not $SkipVerify) { Verify-Relations }

Write-Host ''
Ok '演示数据初始化流程执行完毕。'
Write-Host '提示：演示后台身份默认 DISABLED，无法登录。启用方法见 docs/demo-zh-launch.md 第 4 节。' -ForegroundColor DarkGray
