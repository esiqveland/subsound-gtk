#!/usr/bin/env bash

set -ex

rm -rf generated
mkdir -p generated

for size in 512 256 128 64 48 32 24 16; do
    rsvg-convert -w $size -h $size icon.svg -o generated/icon-${size}.png
done
