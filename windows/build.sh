#!/usr/bin/env bash
# Builds the Windows kit zip with Mono's mcs against the real .NET Framework 4.8 reference
# assemblies (Mono's own corlib has .NET Core overloads such as String.Split(char, options) that
# do not exist on Windows' .NET 4.x, so compiling against it produces an exe that crashes there).
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
rm -rf "$OUT" && mkdir -p "$OUT"
COMMON=(-langversion:5 -noconfig -nostdlib "-lib:$R" -r:mscorlib.dll -r:System.dll -r:System.Core.dll)
mcs "${COMMON[@]}" -r:System.ServiceProcess.dll -r:System.Security.dll -r:System.Configuration.Install.dll -r:System.Net.Http.dll \
    -out:"$OUT/FocusGuardDns.exe" kit/FocusGuardDns.cs
mcs "${COMMON[@]}" -target:winexe -r:System.Drawing.dll -r:System.Windows.Forms.dll \
    -out:"$OUT/blocked.exe" kit/FocusGuardBlocked.cs
cp kit/lists.txt kit/setup.ps1 kit/setup.cmd kit/remove.ps1 kit/remove.cmd kit/release.ps1 kit/README.md "$OUT/"
(cd "$OUT" && mono ./FocusGuardDns.exe selftest ./lists.txt)
# .NET 4.x on Windows has no String.Split(char, StringSplitOptions); make sure none slipped in.
if monodis "$OUT/FocusGuardDns.exe" | grep -q 'string::Split(char,'; then echo "BAD Split overload"; exit 1; fi
(cd "$(dirname "$OUT")" && rm -f FocusGuard-Windows.zip && zip -qr FocusGuard-Windows.zip FocusGuard-Windows)
echo "built $(dirname "$OUT")/FocusGuard-Windows.zip"
