#!/usr/bin/env bash
# Total Eclipse: build the iOS app on this Mac and upload it to TestFlight (about 25 minutes,
# versus 2+ hours on GitHub's hosted runner). Signing uses the Apple account signed in to Xcode
# (team B2Z256T95E, MR3 Management Inc); no keys are stored anywhere.
#
#   scripts/testflight-local.sh            # build and upload
#   scripts/testflight-local.sh --no-upload   # build and export an .ipa to build/testflight/ only
#
# Steps: unsigned Release archive → ad-hoc sign the app with its entitlements (so the shared
# iCloud store survives; Xcode won't ad-hoc sign a target with iCloud itself) → exportArchive with
# automatic (cloud) signing → upload. The version is MARKETING_VERSION without its "-beta…"
# suffix (TestFlight needs numbers only); the build number is the UTC date and time as
# yyDDDHHmm (year, day of year, time), so it always increases, whichever Mac or CI made it.
# Version.xcconfig is put back afterwards.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd -P)"
cd "$root"
upload=true
[[ "${1:-}" == "--no-upload" ]] && upload=false

export JAVA_HOME="${JAVA_HOME:-$(mise where java 2>/dev/null || true)}"
export NUVIO_IOS_DISTRIBUTION=full
out="$root/build/testflight"
archive="$out/TotalEclipse.xcarchive"
mkdir -p "$out"

version_file="iosApp/Configuration/Version.xcconfig"
cp "$version_file" "$out/Version.xcconfig.orig"
restore() { cp "$out/Version.xcconfig.orig" "$root/$version_file"; }
trap restore EXIT

marketing=$(sed -nE 's/^MARKETING_VERSION=([0-9]+(\.[0-9]+){0,2}).*/\1/p' "$version_file")
build=$(date -u +%y%j%H%M)
sed -i '' -E "s/^MARKETING_VERSION=.*/MARKETING_VERSION=${marketing}/; s/^CURRENT_PROJECT_VERSION=.*/CURRENT_PROJECT_VERSION=${build}/" "$version_file"
echo "==> Total Eclipse ${marketing} (${build})"

./scripts/prepare-ios-dependencies.sh

echo "==> Archive (Release, unsigned)"
rm -rf "$archive"
# Memory: Gradle and the Kotlin/Native compiler each ask for 12 GB, and the release link's
# optimizer ran out of heap on a 24 GB Mac. As upstream's release build: compiler in its own
# process, and the three heaviest optimizer passes off. Idle Gradle daemons are stopped first.
./gradlew --stop >/dev/null 2>&1 || true
env \
    "ORG_GRADLE_PROJECT_kotlin.native.disableCompilerDaemon=true" \
    "ORG_GRADLE_PROJECT_nuvio.ios.releaseDisablePhases=DevirtualizationAnalysis,Devirtualization,RemoveRedundantCallsToStaticInitializersPhase" \
xcodebuild archive \
    -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Release \
    -destination 'generic/platform=iOS' -archivePath "$archive" \
    -derivedDataPath build/ios-derived-release \
    CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO CODE_SIGN_IDENTITY= \
    > "$out/archive.log" 2>&1 || { tail -40 "$out/archive.log"; exit 1; }

echo "==> Embed entitlements"
app=$(find "$archive/Products/Applications" -maxdepth 1 -name '*.app' | head -1)
sed 's/$(TeamIdentifierPrefix)/B2Z256T95E./' iosApp/iosApp/iosApp.entitlements > "$out/app.entitlements"
codesign --force --deep --sign - --timestamp=none --entitlements "$out/app.entitlements" "$app"
codesign -d --entitlements :- "$app" 2>/dev/null | grep -q "B2Z256T95E.org.radiorob.hometv" \
    || { echo "The archived app has no iCloud key-value store entitlement" >&2; exit 1; }

destination=upload
$upload || destination=export
cat > "$out/ExportOptions.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>method</key><string>app-store-connect</string>
  <key>destination</key><string>${destination}</string>
  <key>signingStyle</key><string>automatic</string>
  <key>teamID</key><string>B2Z256T95E</string>
  <key>manageAppVersionAndBuildNumber</key><false/>
  <key>uploadSymbols</key><true/>
</dict></plist>
PLIST

echo "==> Export (${destination})"
rm -rf "$out/export"
xcodebuild -exportArchive -archivePath "$archive" -exportOptionsPlist "$out/ExportOptions.plist" \
    -exportPath "$out/export" -allowProvisioningUpdates \
    > "$out/export.log" 2>&1 || { tail -40 "$out/export.log"; exit 1; }

if $upload; then
    echo "==> Uploaded Total Eclipse ${marketing} (${build}). TestFlight shows it after Apple's processing (usually 5–15 minutes)."
else
    echo "==> Exported $(ls "$out/export"/*.ipa)"
fi
