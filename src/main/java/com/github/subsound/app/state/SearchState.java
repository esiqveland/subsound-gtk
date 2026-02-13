package com.github.subsound.app.state;

import com.github.subsound.app.state.SearchResultStore.SearchStatus;

public record SearchState(
        SearchStatus status
) {}
