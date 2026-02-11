package com.github.subsound.persistence;

@FunctionalInterface
public interface SongCacheChecker {
    boolean isCached(SongCache.SongCacheQuery query);
}
