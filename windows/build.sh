#!/usr/bin/env bash
# Builds the Windows kit zip. The zip ships source only (setup.ps1 compiles it on the target with
# the .NET Framework's csc.exe, so no unsigned exe is downloaded); this script compiles the same
# sources with Mono's mcs against the real .NET Framework 4.8 reference assemblies to catch API
# mismatches (Mono's own corlib has .NET Core overloads such as String.Split(char, options) that do
# not exist on Windows' .NET 4.x) and runs the self-test.
set -euo pipefail
cd "$(dirname "$0")"
REF="${REF:-$HOME/.cache/netfx48ref}"
if [ ! -d "$REF/build/.NETFramework/v4.8" ]; then
  mkdir -p "$REF" && cd "$REF"
  curl -sL -o ref.zip https://www.nuget.org/api/v2/package/Microsoft.NETFramework.ReferenceAssemblies.net48/1.0.3
  unzip -oq ref.zip && cd - >/dev/null
fi
R="$REF/build/.NETFramework/v4.8"
OUT="${OUT:-/tmp/kitbuild/FocusGuard-Windows}"
BIN="${BIN:-/tmp/kitbuild/bin}"
rm -rf "$OUT" "$BIN" && mkdir -p "$OUT" "$BIN"
# same references as setup.ps1 passes to csc.exe
COMMON=(-langversion:5 -noconfig -nostdlib "-lib:$R" -r:mscorlib.dll -r:System.dll -r:System.Core.dll)
mcs "${COMMON[@]}" -r:System.ServiceProcess.dll -r:System.Security.dll \
    -out:"$BIN/FocusGuardDns.exe" kit/FocusGuardDns.cs
mcs "${COMMON[@]}" -target:winexe -r:System.Drawing.dll -r:System.Windows.Forms.dll \
    -out:"$BIN/blocked.exe" kit/FocusGuardBlocked.cs
mono "$BIN/FocusGuardDns.exe" selftest "$PWD/kit/lists.txt" "$PWD/kit/allowlist.txt"
# .NET 4.x on Windows has no String.Split(char, StringSplitOptions); make sure none slipped in.
if monodis "$BIN/FocusGuardDns.exe" | grep -q 'string::Split(char,'; then echo "BAD Split overload"; exit 1; fi
cp kit/FocusGuardDns.cs kit/FocusGuardBlocked.cs kit/lists.txt kit/allowlist.txt kit/allow.ps1 kit/allow.cmd kit/block.ps1 kit/block.cmd kit/fg.ps1 kit/fg.cmd kit/setup.ps1 kit/setup.cmd kit/remove.ps1 kit/remove.cmd kit/release.ps1 kit/extend.ps1 kit/extend.cmd kit/README.md "$OUT/"
(cd "$(dirname "$OUT")" && rm -f FocusGuard-Windows.zip && zip -qr FocusGuard-Windows.zip FocusGuard-Windows)
echo "built $(dirname "$OUT")/FocusGuard-Windows.zip"
