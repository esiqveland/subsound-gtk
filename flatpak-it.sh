#!/usr/bin/env bash

set -ex

appstreamcli validate src/main/resources/app/io.github.Subsound.metainfo.xml

flatpak-builder --force-clean --user --install-deps-from=flathub --repo=repo --install builddir io.github.Subsound.yml


