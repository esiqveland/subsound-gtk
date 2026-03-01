package org.subsound.app.state;

import org.subsound.app.state.SearchResultStore.SearchStatus;

public record SearchState(
        SearchStatus status
) {}
