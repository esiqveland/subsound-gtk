#!/usr/bin/env bash

set -ex

rm -rf generated
mkdir -p generated

magick convert icon-512.png -strip -resize 512x512 generated/icon-512.png
magick convert icon-512.png -strip -resize 256x256 generated/icon-256.png
magick convert icon-512.png -strip -resize 128x128 generated/icon-128.png
magick convert icon-512.png -strip -resize 64x64   generated/icon-64.png
magick convert icon-512.png -strip -resize 48x48   generated/icon-48.png
magick convert icon-512.png -strip -resize 32x32   generated/icon-32.png
magick convert icon-512.png -strip -resize 24x24   generated/icon-24.png
magick convert icon-512.png -strip -resize 16x16   generated/icon-16.png
