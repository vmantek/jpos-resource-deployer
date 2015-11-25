package com.vmantek.jpos.deployer.simple;

import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import com.vmantek.jpos.deployer.spi.PropertyResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.*;

public class SimplePropertyResolver implements PropertyResolver
{
    private static final Logger log = LoggerFactory.getLogger(SimplePropertyResolver.class);
    protected File outputBase;
    private Map<String, String> config = new HashMap<>();
    private List<String> configFiles = new LinkedList<>();

    public SimplePropertyResolver(File outputBase)
    {
        this.outputBase = outputBase;
        setupDefaults();
    }

    protected void setupDefaults()
    {
        configFiles.add("cfg/config.properties");
    }

    protected String getBuiltin(String key)
    {
        if (key.startsWith("env:") && key.trim().length() > 4)
        {
            return System.getProperty(key.substring(4));
        }
        if (key.equals("basedir"))
        {
            return outputBase.getAbsolutePath();
        }
        return null;
    }

    public void addConfigFile(String fname)
    {
        configFiles.add(fname);
    }

    public void removeConfigFile(String fname)
    {
        configFiles.remove(fname);
    }

    @Override
    public void initialize() throws IOException
    {
        config = readConfigFiles();
    }

    public String getProperty(String key)
    {
        String v = getBuiltin(key);
        if (v != null)
        {
            return v;
        }
        return config.get(key);
    }

    public boolean isMutable(String key)
    {
        return getBuiltin(key) == null;
    }

    @Override
    public Set<String> getTrackedSources() throws IOException
    {
        Set<String> set=new HashSet<>();
        set.addAll(configFiles);
        return set;
    }

    @Override
    public Set<String> scanPropertyChanges(Multimap<String, String> resourceProps) throws IOException
    {
        Set<String> affectedResources=Collections.EMPTY_SET;
        Map<String,String> newConfig = readConfigFiles();
        affectedResources=calculateAffectedResources(config, newConfig, resourceProps);
        config = newConfig;
        return affectedResources;
    }

    private Map<String, String> readConfigFiles() throws IOException
    {
        Map<String, String> c = new HashMap<>();
        for (String s : Lists.reverse(configFiles))
        {
            File f = new File(s);
            if (f.exists())
            {
                Properties properties = new Properties();
                properties.load(new FileInputStream(f));
                //noinspection Convert2streamapi
                for (String name : properties.stringPropertyNames())
                {
                    if(!c.containsKey(name))
                        c.put(name, properties.getProperty(name));
                }
            }
        }
        return c;
    }
}
