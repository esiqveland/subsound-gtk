package io.github.jwharm.javagi.examples.playsound.utils;

import de.androidpit.colorthief.ColorThief;
import de.androidpit.colorthief.MMCQ.CMap;
import de.androidpit.colorthief.MMCQ.VBox;
import org.gnome.gdk.RGBA;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

public class ImageUtils {

    record ColorValue(
            int[] colors,
            RGBA rgba
    ) {}
    // See: https://github.com/SvenWoltmann/color-thief-java/blob/master/src/test/java/de/androidpit/colorthief/test/ColorThiefTest.java
    public static List<ColorValue> getPalette(BufferedImage img) {
        // The dominant color is taken from a 5-map
        CMap result = ColorThief.getColorMap(img, 5);
        VBox dominantColor = result.vboxes.getFirst();
        int[] rgbDominant = dominantColor.avg(false);

        // Get the full palette
        //result = ColorThief.getColorMap(img, 10);
        var list = new ArrayList<ColorValue>(result.vboxes.size());
        for (VBox vbox : result.vboxes) {
            int[] rgb = vbox.avg(false);
            // Create color String representations
            String rgbString = createRGBString(rgb);
            String rgbHexString = createRGBHexString(rgb);

            var rgba = new RGBA(rgb[0] / 255f, rgb[1] / 255f, rgb[2] / 255f, 1.0f);
            list.add(new ColorValue(rgb, rgba));
        }

        return list;
    }

    /**
     * Creates a string representation of an RGB array.
     * @param rgb the RGB array
     * @return the string representation
     */
    static String createRGBString(int[] rgb) {
        return "rgb(" + rgb[0] + "," + rgb[1] + "," + rgb[2] + ")";
    }

    /**
     * Creates an HTML hex color code for the given RGB array (e.g. <code>#ff0000</code> for red).
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

}
