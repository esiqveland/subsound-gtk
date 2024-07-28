package io.github.jwharm.javagi.examples.playsound.persistence;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static io.github.jwharm.javagi.examples.playsound.persistence.ThumbnailCache.convertWebpToJpeg;

public class ThumbnailCacheTest {

    @Test
    public void testWebpConversion() throws IOException {
        byte[] webpBytes = Files.readAllBytes(Path.of("src/test/resources/fixtures/2.webp"));
        var out = convertWebpToJpeg(webpBytes);
    }

}