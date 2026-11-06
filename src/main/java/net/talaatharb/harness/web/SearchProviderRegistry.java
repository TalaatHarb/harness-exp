package net.talaatharb.harness.web;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.google.inject.Inject;

/** All bound {@link SearchProvider}s; the UI lists them and the tools resolve the configured one through here. */
public class SearchProviderRegistry {
    private final List<SearchProvider> providers;

    @Inject
    public SearchProviderRegistry(Set<SearchProvider> providers) {
        this.providers = providers.stream().sorted(Comparator.comparing(SearchProvider::displayName)).toList();
    }

    public List<SearchProvider> all() {
        return providers;
    }

    public Optional<SearchProvider> find(String id) {
        return providers.stream().filter(p -> p.id().equals(id)).findFirst();
    }
}
