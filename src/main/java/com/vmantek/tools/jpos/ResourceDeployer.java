package com.vmantek.tools.jpos;

import com.google.common.collect.MapDifference;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.TreeMultimap;
import com.google.common.io.Resources;
import freemarker.cache.StringTemplateLoader;
import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import org.jpos.q2.install.ModuleUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@SuppressWarnings("ResultOfMethodCallIgnored")
public class ResourceDeployer implements ResourceDeployerX, Runnable
{
    private static final Logger log = LoggerFactory.getLogger(ResourceDeployer.class);

    private static final Pattern pattern1 = Pattern.compile("\\$\\{(.*?)\\}", Pattern.MULTILINE);
    private static final Pattern pattern2 = Pattern.compile("@@(.*?)@@", Pattern.MULTILINE);

    private static ResourceDeployer INSTANCE = null;
    private static final AntPathMatcher antPathMatcher = new AntPathMatcher();

    private List<String> configFiles = new ArrayList<>();
    private List<String> filterExclusions = new ArrayList<>();
    private Map<String, Long> configTimestamps = new HashMap<>();

    private File outputBase;

    private Map<String, String> config = new HashMap<>();

    public static final String RESOURCE_PREFIX = "META-INF/q2-runtime";
    private static final String CONFIG_RESOURCE_DEFAULT = "/META-INF/q2-config/resourcedeploy-config.default.yml";
    private static final String CONFIG_RESOURCE = "/META-INF/q2-config/resourcedeploy-config.yml";
    private static final ClassLoader cl = ResourceDeployer.class.getClassLoader();
    private Multimap<String, String> resourceProps = TreeMultimap.create();
    private Thread t;
    private boolean running;

    public static String getResourcePrefix()
    {
        return RESOURCE_PREFIX;
    }

    public static ResourceDeployer newInstance(File outputBase) throws IOException
    {
        INSTANCE = new ResourceDeployer(outputBase);
        return INSTANCE;
    }

    public static ResourceDeployer getInstance()
    {
        return INSTANCE;
    }

    @Override
    public void installRuntimeResources() throws IOException
    {
        readDeployerConfig();
        resourceProps.clear();
        List<String> entries = getAvailableResources();
        final List<String> filtered = entries
            .stream()
            .filter(this::isResourceFilterable)
            .collect(Collectors.toList());

        for (String resource : entries)
        {
            installResource(resource, filtered.contains(resource));
        }
    }

    @Override
    public List<String> getAvailableResources() throws IOException
    {
        return ModuleUtils.getModuleEntries(RESOURCE_PREFIX);
    }

    @Override
    public void installResource(String resource) throws IOException
    {
        readDeployerConfig();
        installResource(resource, isResourceFilterable(resource));
    }

    @Override
    public void uninstallResource(String resource) throws IOException
    {
        final String filename = resourceToFilename(resource);
        File outputFile = new File(outputBase, filename);
        if (outputFile.exists())
        {
            outputFile.delete();
        }
    }

    private void installResource(String resource, boolean filtered) throws IOException
    {
        final URL rez = Resources.getResource(resource);
        final String filename = resourceToFilename(resource);
        File outputFile = new File(outputBase, filename);

        final File dir = outputFile.getParentFile();
        if (!dir.exists())
        {
            dir.mkdirs();
        }

        if (!filtered)
        {
            try (FileOutputStream output = new FileOutputStream(outputFile))
            {
                Resources.copy(rez, output);
            }
        }
        else
        {
            try (final FileWriter w = new FileWriter(outputFile))
            {
                String doc = Resources.toString(rez, Charset.defaultCharset());

                resourceProps.removeAll(resource);
                doc = filterResource(resource, pattern1, doc);
                doc = filterResource(resource, pattern2, doc);
                try
                {
                    doc = filterText(resource, doc);
                }
                catch (TemplateException e)
                {
                    log.error("Could not apply template", e);
                }
                try
                {
                    w.write(doc);
                    w.flush();
                }
                catch (Throwable e)
                {
                    log.error("Could not write file: " + outputFile.getAbsolutePath(), e);
                }
            }
        }
    }

    private ResourceDeployer(File outputBase)
    {
        this.outputBase = outputBase;
    }

    private boolean isResourceFilterable(String resource)
    {
        return !filterExclusions
            .stream()
            .anyMatch(e -> antPathMatcher.match(e, resourceToFilename(resource)));
    }

    private String resourceToFilename(String resource)
    {
        return resource.substring(RESOURCE_PREFIX.length() + 1);
    }

    private String filterText(String resource, String doc) throws IOException, TemplateException
    {
        StringTemplateLoader loader = new StringTemplateLoader();
        loader.putTemplate(resource, doc, System.currentTimeMillis());
        Configuration c = new Configuration(Configuration.VERSION_2_3_23);
        c.setTemplateLoader(loader);
        c.setTagSyntax(Configuration.SQUARE_BRACKET_TAG_SYNTAX);
        Template t = c.getTemplate(resource);

        StringWriter sw = new StringWriter();
        t.process(config, sw);
        return sw.toString();
    }

    private String filterResource(String resource, Pattern pattern, String s) throws IOException
    {
        Matcher m = pattern.matcher(s);
        StringBuffer sb = new StringBuffer(s.length() * 2);

        while (m.find())
        {
            String key = m.group(1);
            String val = getConfigProperty(key);
            if (val != null)
            {
                m.appendReplacement(sb, val);
                resourceProps.put(resource, key);
            }
        }
        m.appendTail(sb);

        return sb.toString();
    }

    private String getConfigProperty(String key)
    {
        return config.get(key);
    }

    private void readDeployerConfig() throws IOException
    {
        Yaml yaml = new Yaml();
        configFiles.clear();

        final InputStream rs = getClass().getResourceAsStream(CONFIG_RESOURCE_DEFAULT);

        Map<String, Object> yml = (Map<String, Object>)
            yaml.load(new InputStreamReader(rs));

        configFiles =
            (List<String>) yml.getOrDefault("config-files",
                                            Collections.EMPTY_LIST);

        final String cf = System.getProperty("CONFIG_FILE");
        if (cf != null)
        {
            configFiles.add(cf);
        }

        Map filter = (Map) yml.get("filter");
        filterExclusions =
            (List<String>) filter.getOrDefault("exclusions",
                                               Collections.EMPTY_LIST);

        config = readConfigFiles();
    }

    private Map<String, String> readConfigFiles() throws IOException
    {
        Map<String, String> c = new HashMap<>();
        for (String s : configFiles)
        {
            File f = new File(s);
            if (f.exists())
            {
                Properties properties = new Properties();
                properties.load(new FileInputStream(f));
                for (String name : properties.stringPropertyNames())
                {
                    c.put(name, properties.getProperty(name));
                }
                configTimestamps.put(s, f.lastModified());
            }
        }
        return c;
    }

    public void startConfigMonitoring()
    {
        running = true;
        t = new Thread(this);
        t.start();
    }

    public void stopConfigMonitoring()
    {
        if (t != null)
        {
            running = false;
            t.interrupt();
            try
            {
                t.join();
            }
            catch (InterruptedException ignored)
            {
            }
        }
    }

    private boolean isConfigFileUpdated(String configFile)
    {
        File f = new File(configFile);
        return f.exists() &&
               f.lastModified() > configTimestamps.getOrDefault(configFile, 0L);
    }

    /**
     * When an object implementing interface <code>Runnable</code> is used
     * to create a thread, starting the thread causes the object's
     * <code>run</code> method to be called in that separately executing
     * thread.
     * <p>
     * The general contract of the method <code>run</code> is that it may
     * take any action whatsoever.
     *
     * @see Thread#run()
     */
    @Override
    public void run()
    {
        while (running)
        {
            try
            {
                if (configFiles.stream().anyMatch(this::isConfigFileUpdated))
                {
                    Map newConfig = readConfigFiles();
                    MapDifference<String, String> md = Maps.difference(config, newConfig);
                    config = newConfig;

                    if (!md.areEqual())
                    {
                        Set<String> affectedResources = new HashSet<>();
                        Set<Map.Entry<String, Collection<String>>> entries = resourceProps.asMap().entrySet();
                        Set<String> differingProperties = md.entriesDiffering().keySet();
                        log.info("Configuration properties have changed: " + differingProperties);
                        for (String property : differingProperties)
                        {
                            affectedResources.addAll(entries
                                                         .stream()
                                                         .filter(entry -> entry.getValue().contains(property))
                                                         .map(Entry::getKey)
                                                         .collect(Collectors.toList()));
                        }
                        if (affectedResources.size() > 0)
                        {
                            for (String resource : affectedResources)
                            {
                                log.info("Properties changed in config affected resource: "
                                         + resourceToFilename(resource) + ", reloading it.");
                                installResource(resource, true);
                            }
                        }
                    }
                }
            }
            catch (Exception e)
            {
                log.error("Error", e);
            }
            try
            {
                Thread.sleep(1000);
            }
            catch (InterruptedException ignored)
            {
            }
        }
    }
}
