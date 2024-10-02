package com.github.subsound.utils.javahttp;

import org.junit.Test;

import static com.github.subsound.utils.javahttp.TextUtils.parseLink;
import static org.assertj.core.api.Assertions.assertThat;

public class TextUtilsTest {

    @Test
    public void testBiography() {
        assertThat(parseLink("")).isNotNull();
        String sample = "backing vocals <a target='_blank' href=\"https://www.last.fm/music/The+Test+Band\" rel=\"nofollow\">Read more on Last.fm</a>";
        assertThat(parseLink(sample)).satisfies(bio -> {
            assertThat(bio.original()).isEqualTo(sample);
            assertThat(bio.cleaned()).isEqualTo("backing vocals");
            assertThat(bio.link()).isEqualTo("<a  href=\\\"https://www.last.fm/music/The+Test+Band\\\" >Read more on Last.fm</a>");
        });
    }

}