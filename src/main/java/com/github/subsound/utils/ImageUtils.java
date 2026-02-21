package com.github.subsound.utils;

import com.github.subsound.utils.ThumbHashUtils.ThumbHash;
import de.androidpit.colorthief.ColorThief;
import de.androidpit.colorthief.MMCQ.CMap;
import de.androidpit.colorthief.MMCQ.VBox;
import org.javagi.base.GErrorException;
import org.gnome.gdk.RGBA;
import org.gnome.gdkpixbuf.InterpType;
import org.gnome.gdkpixbuf.Pixbuf;
import org.gnome.gio.InputStream;
import org.gnome.gio.MemoryInputStream;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class ImageUtils {

    public static Pixbuf readPixbuf(byte[] bytes) {
        try (var stream = MemoryInputStream.fromData(bytes)) {
            return readPixbuf(stream);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

//    public Pixbuf readPixbuf(Path filePath) {
//        UnixInputStream.builder().build();
//        FileInputStream ss = FileInputStream.builder().build();
//        try (var stream = FileInputStream(bytes)) {
//            return readPixbuf(stream);
//        } catch (IOException e) {
//            throw new RuntimeException(e);
//        }
//    }

    // careful: uses a GIO InputStream:
    public static Pixbuf readPixbuf(InputStream stream) {
        // we will buffer pixbuf of maximum this size:
        final int PIXBUF_SIZE = 300;
        try {
            //Pixbuf.fromStreamAtScale(stream, )
            Pixbuf pixbuf = Pixbuf.fromStream(stream, null);
            int width = pixbuf.getWidth();
            int height = pixbuf.getHeight();
            float ratio = (float) width / (float) height;

            int w, h;
            if (ratio > 1.0) {
                w = PIXBUF_SIZE;
                h = (int) ((float) PIXBUF_SIZE / ratio);
            } else {
                w = (int) ((float) PIXBUF_SIZE * ratio);
                h = PIXBUF_SIZE;
            }
            var scaled = pixbuf.scaleSimple(w, h, InterpType.BILINEAR);
            return scaled;
        } catch (GErrorException e) {
            throw new RuntimeException(e);
        }
    }

    public record ColorValue(
            int[] colors,
            RGBA rgba
    ) {
    }

    public static List<ColorValue> getPalette(byte[] jpegBlob) {
        try {
            var img = ImageIO.read(new ByteArrayInputStream(jpegBlob));
            return getPalette(img);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public record ImageResult(
            List<ColorValue> palette,
            ThumbHash thumbHash
    ) {
    }
    public static ImageResult processImage(byte[] jpegBlob) {
        try {
            var img = ImageIO.read(new ByteArrayInputStream(jpegBlob));
            var colorPallette = getPalette(img);
            var thumbHash = ThumbHashUtils.getThumbHash(img, 100);
            return new ImageResult(
                    colorPallette,
                    thumbHash
            );
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // See: https://github.com/SvenWoltmann/color-thief-java/blob/master/src/test/java/de/androidpit/colorthief/test/ColorThiefTest.java
    public static List<ColorValue> getPalette(BufferedImage img) {
        // The dominant color is taken from a 5-map
        CMap result = ColorThief.getColorMap(img, 5);

        //VBox dominantColor = result.vboxes.getFirst();
        //int[] rgbDominant = dominantColor.avg(false);

        // Get the full palette
        //result = ColorThief.getColorMap(img, 10);
        var list = new ArrayList<ColorValue>(result.vboxes.size());
        for (VBox vbox : result.vboxes) {
            int[] rgb = vbox.avg(false);
            // Create color String representations
            //String rgbString = createRGBString(rgb);
            //String rgbHexString = createRGBHexString(rgb);

            var rgba = new RGBA(rgb[0] / 255f, rgb[1] / 255f, rgb[2] / 255f, 1.0f);
            list.add(new ColorValue(rgb, rgba));
        }

        return list;
    }

    /**
     * Creates a string representation of an RGB array.
     *
     * @param rgb the RGB array
     * @return the string representation
     */
    static String createRGBString(int[] rgb) {
        return "rgb(" + rgb[0] + "," + rgb[1] + "," + rgb[2] + ")";
    }

    /**
     * Creates an HTML hex color code for the given RGB array (e.g. <code>#ff0000</code> for red).
     *
     * @param rgb the RGB array
     * @return the HTML hex color code
     */
    static String createRGBHexString(int[] rgb) {
        String rgbHex = Integer.toHexString(rgb[0] << 16 | rgb[1] << 8 | rgb[2]);

        // Left-pad with 0s
        int length = rgbHex.length();
        if (length < 6) {
            rgbHex = "00000".substring(0, 6 - length) + rgbHex;
        }

        return "#" + rgbHex;
    }

    public static byte[] bufferedImageToRgbaBytes(BufferedImage img) {
        int width = img.getWidth();
        int height = img.getHeight();
        int[] pixels = img.getRGB(0, 0, width, height, null, 0, width);
        byte[] rgba = new byte[width * height * 4];

        for (int i = 0; i < pixels.length; i++) {
            int argb = pixels[i];
            rgba[i * 4    ] = (byte) ((argb >> 16) & 0xFF); // R
            rgba[i * 4 + 1] = (byte) ((argb >>  8) & 0xFF); // G
            rgba[i * 4 + 2] = (byte) ( argb        & 0xFF); // B
            rgba[i * 4 + 3] = (byte) ((argb >> 24) & 0xFF); // A
        }

        return rgba;
    }
}
