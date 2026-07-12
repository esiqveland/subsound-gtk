package org.subsound.persistence.database;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Normalizes text for the local FTS5 search index ({@code search_text} columns).
 *
 * FTS5's unicode61 tokenizer can strip combining diacritics (bård -> bard) but cannot
 * fold standalone letters like ø/æ (no unicode decomposition), and knows nothing about
 * Nordic digraph spellings (bård <-> baard, øystein <-> oeystein). So both the indexed
 * text and the query are folded here, in Java, emitting every plausible ASCII variant
 * per word so that any spelling the user types matches any spelling in the library.
 *
 * CJK text passes through unchanged (Hangul NFD-decomposed, consistently on both the
 * index and query side). Space-separated scripts like Korean match per-word prefixes,
 * but unspaced runs (Chinese/Japanese titles) become a single FTS token, so only
 * title-start prefixes match — a query for a word from the middle of an unspaced title
 * finds nothing. Emitting character bigrams for CJK runs here (plus a migration
 * re-backfilling search_text) is the known fix if that ever becomes a problem.
 * See DatabaseServerServiceTest#testOfflineSearchCjkBehavior.
 */
public final class SearchNormalizer {
    private SearchNormalizer() {
    }

    // Letters that NFD decomposition cannot fold, mapped to their single-letter-ish form.
    private static final Map<Integer, String> SIMPLE_FOLD = Map.of(
            (int) 'ø', "o",
            (int) 'æ', "ae",
            (int) 'œ', "oe",
            (int) 'ð', "d",
            (int) 'þ', "th",
            (int) 'đ', "d",
            (int) 'ł', "l",
            (int) 'ß', "ss"
    );

    // Digraph transliterations (å -> aa, ø -> oe, ü -> ue, ...) as used in ASCII-only spellings.
    private static final Map<Integer, String> DIGRAPH_FOLD = Map.ofEntries(
            Map.entry((int) 'å', "aa"),
            Map.entry((int) 'ø', "oe"),
            Map.entry((int) 'æ', "ae"),
            Map.entry((int) 'ä', "ae"),
            Map.entry((int) 'ö', "oe"),
            Map.entry((int) 'ü', "ue"),
            Map.entry((int) 'œ', "oe"),
            Map.entry((int) 'ð', "d"),
            Map.entry((int) 'þ', "th"),
            Map.entry((int) 'đ', "d"),
            Map.entry((int) 'ł', "l"),
            Map.entry((int) 'ß', "ss")
    );

    private static final List<Map<Integer, String>> FOLDS = List.of(SIMPLE_FOLD, DIGRAPH_FOLD);

    /**
     * Builds the text stored in a {@code search_text} column: every word of every field,
     * expanded to all distinct folded variants, joined by spaces.
     * E.g. {@code normalizeIndexText("Bård", "Øystein")} -> {@code "bard baard oystein oeystein"}.
     */
    public static String normalizeIndexText(String... fields) {
        var out = new ArrayList<String>();
        for (String field : fields) {
            for (String word : splitWords(field)) {
                out.addAll(variants(word));
            }
        }
        return String.join(" ", out);
    }

    /**
     * Builds an FTS5 MATCH expression for a user query: each word becomes a prefix token
     * over all its folded variants, words are ANDed.
     * E.g. {@code "Bård tuf"} -> {@code ("bard"* OR "baard"*) AND ("tuf"*)}.
     * Tokens contain only letters/digits, so the expression is safe from FTS syntax injection.
     */
    public static Optional<String> toFtsQuery(String rawQuery) {
        var groups = new ArrayList<String>();
        for (String word : splitWords(rawQuery)) {
            var terms = variants(word).stream()
                    .map(v -> "\"" + v + "\"*")
                    .toList();
            if (terms.isEmpty()) {
                continue;
            }
            if (terms.size() == 1) {
                groups.add("(" + terms.getFirst() + ")");
            } else {
                groups.add("(" + String.join(" OR ", terms) + ")");
            }
        }
        if (groups.isEmpty()) {
            return Optional.empty();
        }
        // FTS5 only allows implicit AND between plain phrases, not parenthesized groups
        return Optional.of(String.join(" AND ", groups));
    }

    private static List<String> splitWords(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String lower = text.toLowerCase(Locale.ROOT);
        var words = new ArrayList<String>();
        var current = new StringBuilder();
        lower.codePoints().forEach(cp -> {
            if (Character.isLetterOrDigit(cp)) {
                current.appendCodePoint(cp);
            } else if (!current.isEmpty()) {
                words.add(current.toString());
                current.setLength(0);
            }
        });
        if (!current.isEmpty()) {
            words.add(current.toString());
        }
        return words;
    }

    private static Set<String> variants(String word) {
        var out = new LinkedHashSet<String>();
        for (Map<Integer, String> fold : FOLDS) {
            String folded = stripMarks(applyFold(word, fold));
            if (!folded.isEmpty()) {
                out.add(folded);
            }
        }
        return out;
    }

    private static String applyFold(String word, Map<Integer, String> fold) {
        var sb = new StringBuilder(word.length());
        word.codePoints().forEach(cp -> {
            String replacement = fold.get(cp);
            if (replacement != null) {
                sb.append(replacement);
            } else {
                sb.appendCodePoint(cp);
            }
        });
        return sb.toString();
    }

    // NFD-decompose and drop combining marks (é -> e, å -> a, ...). Keeps letters/digits of
    // any script so non-Latin titles stay searchable by their exact spelling.
    private static String stripMarks(String word) {
        String decomposed = Normalizer.normalize(word, Normalizer.Form.NFD);
        var sb = new StringBuilder(decomposed.length());
        decomposed.codePoints().forEach(cp -> {
            if (Character.isLetterOrDigit(cp)) {
                sb.appendCodePoint(cp);
            }
        });
        return sb.toString();
    }
}
