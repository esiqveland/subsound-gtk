package io.github.jwharm.javagi.examples.playsound.utils;

import io.github.jwharm.javagi.base.GErrorException;
import io.github.jwharm.javagi.base.Out;
import org.gnome.gdkpixbuf.Pixbuf;
import org.gnome.glib.GError;
import org.junit.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;

import static io.github.jwharm.javagi.examples.playsound.utils.ImageUtils.createRGBString;
import static org.assertj.core.api.Assertions.assertThat;

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

    @Test
    public void testPixbufToBufferedImage() throws GErrorException, IOException {
        var path = Path.of("src/test/resources/fixtures/test2.jpg");
        int size = 300;
        var p = Pixbuf.fromFileAtSize(path.toAbsolutePath().toString(), size, size);
        var scaledOut = new Out<byte[]>();
        var errs = new GError[]{null};
        boolean success = p.saveToBuffer(scaledOut, "jpeg", errs, "", null);
        //boolean success = p.saveToBufferv(scaledOut, "jpeg", null, null);
        if (!success) {
            throw new RuntimeException("halp");
        }

        var tmpPath = Path.of(path.getParent().toAbsolutePath().toString() + File.pathSeparator + path.getFileName() + ".thumb.jpg");
        //Files.write(tmpPath, scaledOut.get());
        var palette = ImageUtils.getPalette(scaledOut.get());
        assertThat(palette).hasSize(5);
    }
}