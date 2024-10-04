#!/usr/bin/env bash

set -ex

flatpak-builder --force-clean --user --install-deps-from=flathub --repo=repo --install builddir com.github.Subsound.yml


