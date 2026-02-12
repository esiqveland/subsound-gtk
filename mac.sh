#!/bin/bash

set -eou pipefail

links=$(pkg-config --cflags gstreamer-1.0 gtk4)

# brew install gtk4 libadwaita adwaita-icon-theme font-adwaita

#    jvmArgs += "-XstartOnFirstThread"
#    jvmArgs += "--enable-native-access=ALL-UNNAMED"

./gradlew build -x test

java \
  -XstartOnFirstThread \
  --enable-native-access=ALL-UNNAMED \
  -Djava.library.path=/usr/lib64:/lib64:/lib:/usr/lib:/lib/x86_64-linux-gnu:/opt/homebrew/lib:/opt/homebrew/lib/gstreamer-1.0 \
  -jar build/libs/*final.jar


