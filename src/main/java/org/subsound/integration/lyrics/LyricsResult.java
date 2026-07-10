package org.subsound.integration.lyrics;

import org.subsound.integration.lyrics.LrclibClient.LyricLine;

import java.util.List;

/**
 * Lyrics for a song: either time-synced lines (LRC) or plain unsynced text lines.
 */
public sealed interface LyricsResult {
    record SyncedLyrics(List<LyricLine> lines) implements LyricsResult {}

    record PlainLyrics(List<String> lines) implements LyricsResult {}
}
