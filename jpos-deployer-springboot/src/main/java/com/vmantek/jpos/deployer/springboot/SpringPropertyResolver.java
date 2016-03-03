package com.vmantek.jpos.deployer.springboot;

import com.google.common.collect.Multimap;
import com.vmantek.jpos.deployer.spi.PropertyResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.env.PropertySourcesLoader;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SpringPropertyResolver implements PropertyResolver
{
    private static final Logger log = LoggerFactory.getLogger(SpringPropertyResolver.class);

    private static final Pattern pattern = Pattern.compile("applicationConfig: \\[(.*?)\\]");

    private Set<String> trackedSources = new HashSet<>();
    private ConfigurableEnvironment environment;

    private ResourceLoader resourceLoader = new DefaultResourceLoader();
    private PropertySourcesLoader propertySourceLoader = new PropertySourcesLoader();

    public SpringPropertyResolver(ConfigurableEnvironment environment)
    {
        this.environment = environment;
    }

    @Override
    public void initialize() throws IOException
    {
        trackedSources = getTrackedSources();
    }

    @Override
    public String getProperty(String key)
    {
        return environment.getProperty(key);
    }

    @Override
    public boolean isMutable(String key)
    {
        MutablePropertySources sources = environment.getPropertySources();
        for (PropertySource<?> source : sources)
        {
            if (source.containsProperty(key))
            {
                return isMutableSource(source);
            }
        }
        return false;
    }

    public Set<String> scanPropertyChanges(Multimap<String, String> resourceProps)
    {
        Set<String> affectedResources = Collections.EMPTY_SET;
        try
        {
            Map<String, String> oldConfig = getProperties();
            reloadPropertySources();
            Map<String, String> newConfig = getProperties();

            affectedResources = calculateAffectedResources(oldConfig, newConfig, resourceProps);
        }
        catch (Exception e)
        {
            log.error("Error", e);
        }
        return affectedResources;
    }

    public Set<String> getTrackedSources() throws IOException
    {
        Set<String> trackedSources = new HashSet<>();
        MutablePropertySources sources = environment.getPropertySources();
        for (PropertySource<?> source : sources)
        {
            final String trackedSource = getTrackedSource(source);
            if(trackedSource!=null) trackedSources.add(trackedSource);
        }
        return trackedSources;
    }

    private boolean isMutableSource(PropertySource<?> source)
    {
        boolean isFile = false;

        final Matcher matcher = pattern.matcher(source.getName());
        if (matcher.find())
        {
            final String uri = matcher.group(1);
            isFile = uri.startsWith("file:");
        }

        return isFile;
    }

    private String getTrackedSource(PropertySource source) throws IOException
    {
        final Matcher matcher = pattern.matcher(source.getName());
        if (matcher.find())
        {
            final String uri = matcher.group(1);
            if(uri.startsWith("file:"))
            {
                Resource res= resourceLoader.getResource(uri);
                if (res!=null)
                {
                    return res.getFile().getAbsolutePath();
                }
            }
        }
        return null;
    }

    private Map<String, String> getProperties()
    {
        Map<String, String> props = new HashMap<>();
        MutablePropertySources sources = environment.getPropertySources();

        for (PropertySource<?> source : sources)
        {
            if (source instanceof EnumerablePropertySource)
            {
                for (String s : ((EnumerablePropertySource) source).getPropertyNames())
                {
                    if (props.containsKey(s))
                    {
                        continue;
                    }
                    final Object property = source.getProperty(s);
                    if (property != null)
                    {
                        final String v = property.toString();
                        props.put(s, v);
                    }
                }
            }
        }
        return props;
    }

    private synchronized void reloadPropertySources()
    {
        MutablePropertySources sources = environment.getPropertySources();

        for (PropertySource<?> source : sources)
        {
            final String name = source.getName();
            final Matcher matcher = pattern.matcher(name);
            if (matcher.find())
            {
                final String uri = matcher.group(1);
                Resource res=resourceLoader.getResource(uri);
                int cnt=20;
                while(!res.exists())
                {
                    res=resourceLoader.getResource(uri);
                    if(res.exists() || cnt--==0) break;
                    try
                    {
                        Thread.sleep(200);
                    }
                    catch (InterruptedException ignored)
                    {
                    }
                }
                try
                {
                    if (uri.startsWith("file:"))
                    {
                        final PropertySource<?> rload = propertySourceLoader.load(res, name, null);
                        sources.replace(name, rload);
                    }
                }
                catch (IOException e)
                {
                    log.error("Error", e);
                }
            }
        }
    }

}
