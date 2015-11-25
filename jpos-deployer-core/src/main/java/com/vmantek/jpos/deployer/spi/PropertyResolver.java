package com.vmantek.jpos.deployer.spi;

import com.google.common.collect.MapDifference;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;

import java.io.IOException;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Collectors;

public interface PropertyResolver
{
    void initialize() throws IOException;

    String getProperty(String key);

    boolean isMutable(String key);

    Set<String> scanPropertyChanges(Multimap<String, String> m) throws IOException;

    Set<String> getTrackedSources() throws IOException;

    default Set<String> calculateAffectedResources(Map<String, String> oldConfig,
                                                   Map<String, String> newConfig,
                                                   Multimap<String, String> resourceProps)
    {
        Set<String> affectedResources = new HashSet<>();
        MapDifference<String, String> md = Maps.difference(oldConfig, newConfig);
        if (!md.areEqual())
        {
            Set<Entry<String, Collection<String>>> entries = resourceProps.asMap().entrySet();
            Set<String> differingProperties = md.entriesDiffering().keySet();
            if(differingProperties.size()>0)
            {
                for (String property : differingProperties)
                {
                    affectedResources.addAll(entries
                                                 .stream()
                                                 .filter(entry -> entry.getValue().contains(property))
                                                 .map(Entry::getKey)
                                                 .collect(Collectors.toList()));
                }
            }
        }
        return affectedResources;
    }
}
