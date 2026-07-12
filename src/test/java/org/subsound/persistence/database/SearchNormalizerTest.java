package org.subsound.persistence.database;

import org.assertj.core.api.Assertions;
import org.junit.Test;

import java.text.Normalizer;
import java.util.Optional;

public class SearchNormalizerTest {

    @Test
    public void testNorwegianVariantExpansion() {
        Assertions.assertThat(SearchNormalizer.normalizeIndexText("Bård")).isEqualTo("bard baard");
        Assertions.assertThat(SearchNormalizer.normalizeIndexText("Øystein")).isEqualTo("oystein oeystein");
        Assertions.assertThat(SearchNormalizer.normalizeIndexText("Kjærlighet")).isEqualTo("kjaerlighet");
    }

    @Test
    public void testPlainAsciiSingleVariant() {
        Assertions.assertThat(SearchNormalizer.normalizeIndexText("Hello World")).isEqualTo("hello world");
        Assertions.assertThat(SearchNormalizer.normalizeIndexText("baard")).isEqualTo("baard");
    }

    @Test
    public void testCombiningDiacriticsAreStripped() {
        Assertions.assertThat(SearchNormalizer.normalizeIndexText("Beyoncé")).isEqualTo("beyonce");
        Assertions.assertThat(SearchNormalizer.normalizeIndexText("Über")).isEqualTo("uber ueber");
    }

    @Test
    public void testMultipleFieldsAndPunctuation() {
        Assertions.assertThat(SearchNormalizer.normalizeIndexText("AC/DC", "Back in Black"))
                .isEqualTo("ac dc back in black");
        Assertions.assertThat(SearchNormalizer.normalizeIndexText(null, "", "Åge"))
                .isEqualTo("age aage");
    }

    @Test
    public void testToFtsQuery() {
        Assertions.assertThat(SearchNormalizer.toFtsQuery("baard"))
                .isEqualTo(Optional.of("(\"baard\"*)"));
        Assertions.assertThat(SearchNormalizer.toFtsQuery("Bård tuf"))
                .isEqualTo(Optional.of("(\"bard\"* OR \"baard\"*) AND (\"tuf\"*)"));
    }

    @Test
    public void testToFtsQueryBlankAndSymbolOnly() {
        Assertions.assertThat(SearchNormalizer.toFtsQuery("")).isEmpty();
        Assertions.assertThat(SearchNormalizer.toFtsQuery("   ")).isEmpty();
        Assertions.assertThat(SearchNormalizer.toFtsQuery("*\"()-")).isEmpty();
    }

    @Test
    public void testCjkPassthrough() {
        // Han text is kept as-is; Hangul is NFD-decomposed to jamo — what matters is that
        // the index and query sides normalize identically, so they always agree.
        Assertions.assertThat(SearchNormalizer.normalizeIndexText("月亮代表我的心"))
                .isEqualTo("月亮代表我的心");
        String decomposedIU = Normalizer.normalize("아이유", Normalizer.Form.NFD);
        Assertions.assertThat(SearchNormalizer.normalizeIndexText("아이유"))
                .isEqualTo(decomposedIU);
        Assertions.assertThat(SearchNormalizer.toFtsQuery("아이유"))
                .isEqualTo(Optional.of("(\"" + decomposedIU + "\"*)"));
        Assertions.assertThat(SearchNormalizer.toFtsQuery("月亮"))
                .isEqualTo(Optional.of("(\"月亮\"*)"));
    }

    @Test
    public void testToFtsQueryNeutralizesFtsSyntax() {
        // Quotes, parens and operators are word separators; nothing from the input can
        // escape the quoted prefix tokens.
        Assertions.assertThat(SearchNormalizer.toFtsQuery("a\" OR \"b"))
                .isEqualTo(Optional.of("(\"a\"*) AND (\"or\"*) AND (\"b\"*)"));
        Assertions.assertThat(SearchNormalizer.toFtsQuery("NEAR(x, y)"))
                .isEqualTo(Optional.of("(\"near\"*) AND (\"x\"*) AND (\"y\"*)"));
    }
}
