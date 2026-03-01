#!/usr/bin/env bash

set -ex

appstreamcli validate src/main/resources/app/com.github.Subsound.metainfo.xml

flatpak-builder --force-clean --user --install-deps-from=flathub --repo=repo --install builddir com.github.Subsound.yml


