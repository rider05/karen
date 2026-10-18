<#
  karen-tunnel.ps1 - expose the phone's Karen server at http://127.0.0.1:<Port> on this PC.

  Why this exists:
    Browsers only expose WebGPU (needed for "Join as GPU worker") on *secure
    contexts*. http://192.168.x.x is NOT a secure context, so navigator.gpu is
    undefined there. http://127.0.0.1 IS trusted. This script forwards a local
    port to the phone, so the dashboard page is served from 127.0.0.1 while the
    actual TCP connection still lands on the phone.

  Usage:
    powershell -ExecutionPolicy Bypass -File tools\karen-tunnel.ps1 -Phone 192.168.1.7
    then open  http://127.0.0.1:11434  on this PC.

  Notes:
    - Keep this window open while using the dashboard / worker mode.
    - The phone must be reachable (same Wi-Fi; Karen -> Settings -> Local Server
      shows the LAN address and the token).
    - Alternative without the script: chrome://flags/#unsafely-treat-insecure-origin-as-secure
#>
param(
  [Parameter(Mandatory = $true)][string]$Phone,
  [int]$Port = 11434,
  [int]$Listen = 11434
)

$ErrorActionPreference = 'Continue'
$listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $Listen)
try { $listener.Start() } catch {
  Write-Host "Could not listen on 127.0.0.1:$Listen - $($_.Exception.Message)"
  Write-Host "If another Karen tunnel is already running, keep that one (or close it and retry)."
  exit 1
}
Write-Host ("Karen tunnel: http://127.0.0.1:{0}  ->  {1}:{2}   (Ctrl+C to stop)" -f $Listen, $Phone, $Port)

# One shared list of open connections; each direction pumps asynchronously so
# several requests (page assets, SSE chat stream, worker poll) can be in flight
# at once from the single-threaded PowerShell host.
$active = [System.Collections.Generic.List[object]]::new()
try {
  while ($true) {
    while ($listener.Pending()) {
      $client = $listener.AcceptTcpClient()
      $remote = [System.Net.Sockets.TcpClient]::new()
      try {
        $remote.Connect($Phone, $Port)
        $cs = $client.GetStream()
        $rs = $remote.GetStream()
        $t1 = $cs.CopyToAsync($rs)
        $t2 = $rs.CopyToAsync($cs)
        $active.Add([pscustomobject]@{ C = $client; R = $remote; T = @($t1, $t2) })
      } catch {
        Write-Warning ("phone unreachable at {0}:{1} - {2}" -f $Phone, $Port, $_.Exception.Message)
        try { $client.Close() } catch { }
        try { $remote.Close() } catch { }
      }
    }
    for ($i = $active.Count - 1; $i -ge 0; $i--) {
      $conn = $active[$i]
      if ($conn.T[0].IsCompleted -or $conn.T[1].IsCompleted) {
        try { $conn.C.Close() } catch { }
        try { $conn.R.Close() } catch { }
        $active.RemoveAt($i)
      }
    }
    Start-Sleep -Milliseconds 15
  }
} finally {
  $listener.Stop()
  foreach ($conn in $active) {
    try { $conn.C.Close() } catch { }
    try { $conn.R.Close() } catch { }
  }
}
