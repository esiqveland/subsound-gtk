#!/usr/bin/env bash

set -ex

appstreamcli validate src/main/resources/app/org.subsound.Subsound.metainfo.xml

flatpak-builder --force-clean --user --install-deps-from=flathub --repo=repo --install builddir org.subsound.Subsound.yml


