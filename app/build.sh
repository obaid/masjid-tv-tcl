#!/bin/zsh
# Builds and signs Home.apk (no Gradle): aapt2 -> javac -> d8 -> zipalign -> apksigner.
set -e
cd "$(dirname "$0")"
[ -f assets/juz_amma.json ] || { echo "Run tools/fetch_data.py first (downloads the Quran text, hadith, schedule and adhan audio)."; exit 1; }
[ -f ~/.android/debug.keystore ] || keytool -genkeypair -keystore ~/.android/debug.keystore -storepass android \
    -alias androiddebugkey -keypass android -keyalg RSA -validity 10000 -dname "CN=Android Debug" >/dev/null
BT=~/Library/Android/sdk/build-tools/36.0.0
AJ=~/Library/Android/sdk/platforms/android-36/android.jar
rm -rf build && mkdir -p build/classes build/dex
$BT/aapt2 compile --dir res -o build/res.zip
$BT/aapt2 link -I $AJ --manifest AndroidManifest.xml -A assets -o build/unsigned.apk build/res.zip
javac -nowarn --release 8 -classpath $AJ -d build/classes src/com/home/launcher/*.java
$BT/d8 --lib $AJ --min-api 26 --output build/dex $(find build/classes -name '*.class')
(cd build/dex && zip -q ../unsigned.apk classes.dex)
$BT/zipalign -f 4 build/unsigned.apk build/aligned.apk
$BT/apksigner sign --ks ~/.android/debug.keystore --ks-pass pass:android --out Home.apk build/aligned.apk
echo "built $(pwd)/Home.apk"
