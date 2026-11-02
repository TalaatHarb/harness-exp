package net.talaatharb.harness.llm;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.google.inject.Inject;
import com.google.inject.Singleton;

/** All bound model providers, looked up by the id stored in settings. */
@Singleton
public class ModelProviderRegistry {
    private final Map<String, ModelProvider> providers = new LinkedHashMap<>();

    @Inject
    public ModelProviderRegistry(Set<ModelProvider> providers) {
        providers.forEach(p -> this.providers.put(p.id(), p));
    }

    public Collection<ModelProvider> all() {
        return providers.values();
    }

    public Optional<ModelProvider> find(String id) {
        return Optional.ofNullable(id == null ? null : providers.get(id));
    }

    public ModelProvider get(ModelSettings config) throws ModelProviderException {
        ModelProvider provider = providers.get(config.provider);
        if (provider == null) {
            throw new ModelProviderException("Unknown model provider: " + config.provider);
        }
        return provider;
    }
}
