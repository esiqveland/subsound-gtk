package io.github.jwharm.javagi.examples.playsound.utils;

import org.junit.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;

import static io.github.jwharm.javagi.examples.playsound.utils.ImageUtils.createRGBString;

public class ImageUtilsTest {

    @Test
    public void testDominantColors() throws IOException {

        {
            byte[] content = Files.readAllBytes(Path.of("src/test/resources/fixtures/test1.jpg"));
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(content));
            var palette = ImageUtils.getPalette(img);
            var css = palette.stream().map(p -> createRGBString(p.colors())).collect(Collectors.joining(";\n"));
            System.out.println(css);
        }

        {
            byte[] content = Files.readAllBytes(Path.of("src/test/resources/fixtures/test2.jpg"));
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(content));
            var palette = ImageUtils.getPalette(img);
            var css = palette.stream().map(p -> createRGBString(p.colors())).collect(Collectors.joining(";\n"));
            System.out.println(css);
        }

    }
}