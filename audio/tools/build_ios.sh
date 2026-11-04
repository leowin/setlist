#!/bin/sh
# Builds the static audio library for iOS devices and the simulator (run on a Mac with Xcode).
# Output: audio/build-ios/device/libsetlistaudio_ios.a (+ libaudiocore.a) and .../simulator/...
set -e
cd "$(dirname "$0")/.."
for variant in device simulator; do
  if [ "$variant" = device ]; then sysroot=iphoneos; else sysroot=iphonesimulator; fi
  cmake -S . -B build-ios/$variant -G Xcode -DCMAKE_SYSTEM_NAME=iOS -DCMAKE_OSX_SYSROOT=$sysroot \
        -DCMAKE_OSX_ARCHITECTURES=arm64 -DCMAKE_OSX_DEPLOYMENT_TARGET=15.0
  cmake --build build-ios/$variant --config Release
  mkdir -p build-ios/$variant/lib
  find build-ios/$variant -name 'lib*.a' -path '*Release*' -exec cp {} build-ios/$variant/lib/ \;
done
echo "iOS audio libraries built in audio/build-ios/{device,simulator}/lib"
