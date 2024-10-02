package com.github.subsound.persistence;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.github.subsound.persistence.ThumbnailCache.convertWebpToJpeg;

public class ThumbnailCacheTest {

    @Test
    public void testWebpConversion() throws IOException {
        byte[] webpBytes = Files.readAllBytes(Path.of("src/test/resources/fixtures/2.webp"));
        var out = convertWebpToJpeg(webpBytes);
    }

}