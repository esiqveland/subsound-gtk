package io.github.jwharm.javagi.examples.playsound.ui.components;

import java.util.Arrays;
import java.util.stream.Stream;

public enum Classes {
    card("card"),
    boxedList("boxed-list"),
    richlist("rich-list"),
    title1("title1"),
    title2("title2"),
    title3("title3"),
    labelDim("dim-label"),
    labelNumeric("numeric"), // display label with numbers as monospace-ish
    ;

    private final String className;
    Classes(String className) {
        this.className = className;
    }

    public String className() {
        return className;
    }

    public String[] add(Classes ...cs) {
        return toClassnames(this, cs);
    }

    public static String[] toClassnames(Classes ...classes) {
        return Arrays.stream(classes).map(Classes::className).toArray(String[]::new);
    }
    public static String[] toClassnames(Classes clazz) {
        return new String[]{clazz.className()};
    }
    public static String[] toClassnames(Classes clazz, Classes ...classes) {
        return Stream.concat(Stream.ofNullable(clazz), Arrays.stream(classes))
                .map(Classes::className)
                .toArray(String[]::new);
    }
}
