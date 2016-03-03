package com.vmantek.jpos.deployer;

import com.google.common.collect.Multimap;
import com.google.common.collect.TreeMultimap;
import com.google.common.io.Resources;
import com.sun.nio.file.SensitivityWatchEventModifier;
import com.vmantek.jpos.deployer.spi.PropertyResolver;
import com.vmantek.jpos.deployer.support.AntPathMatcher;
import com.vmantek.jpos.deployer.support.PropertyModel;
import freemarker.cache.StringTemplateLoader;
import freemarker.ext.beans.BeansWrapper;
import freemarker.template.Configuration;
import freemarker.template.DefaultObjectWrapper;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import org.jpos.q2.install.ModuleUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static java.nio.file.StandardWatchEventKinds.*;

@SuppressWarnings("ResultOfMethodCallIgnored")
public class ResourceDeployer implements Runnable
{
    public static final String RESOURCE_PREFIX = "META-INF/q2-runtime";
    private static final Logger log = LoggerFactory.getLogger(ResourceDeployer.class);
    private static final Pattern pattern1 = Pattern.compile("\\$\\{(.*?)\\}");
    private static final Pattern pattern2 = Pattern.compile("@@(.*?)@@");
    private static final AntPathMatcher antPathMatcher = new AntPathMatcher();
    private static final ClassLoader cl = ResourceDeployer.class.getClassLoader();
    private static ResourceDeployer INSTANCE = null;
    private List<String> filterExclusions = new ArrayList<>();
    private File outputBase;

    private Multimap<String, String> resourceProps = TreeMultimap.create();

    private PropertyResolver propertyResolver;
    private Thread t;
    private boolean running;

    private ResourceDeployer(PropertyResolver propertyResolver, File outputBase)
    {
        this.outputBase = outputBase;
        this.propertyResolver = propertyResolver;
    }

    public static String getResourcePrefix()
    {
        return RESOURCE_PREFIX;
    }

    public static ResourceDeployer newInstance(PropertyResolver propertyResolver, File outputBase) throws IOException
    {
        INSTANCE = new ResourceDeployer(propertyResolver, outputBase);
        return INSTANCE;
    }

    public static ResourceDeployer getInstance()
    {
        return INSTANCE;
    }

    public void setFilterExclusions(Collection<String> exclusions)
    {
        filterExclusions.clear();
        exclusions.forEach(this::addFilterExclusion);
    }

    public void addFilterExclusion(String pattern)
    {
        if (!antPathMatcher.isPattern(pattern))
        {
            throw new IllegalArgumentException("Invalid pattern: " + pattern);
        }
        filterExclusions.add(pattern);
    }

    public void removeFilterExclusion(String pattern)
    {
        filterExclusions.remove(pattern);
    }

    private void init() throws IOException
    {
        propertyResolver.initialize();
    }

    protected void setupDefaultExclusions()
    {
        filterExclusions.clear();
        filterExclusions.add("cfg/*.ks");
        filterExclusions.add("cfg/*.jks");
        filterExclusions.add("**/*.jpg");
        filterExclusions.add("**/*.gif");
        filterExclusions.add("**/*.png");
        filterExclusions.add("**/*.pdf");
    }

    public List<String> getAvailableResources() throws IOException
    {
        return ModuleUtils.getModuleEntries(RESOURCE_PREFIX);
    }

    public void installRuntimeResources() throws IOException
    {
        init();
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

    public void installResource(String resource) throws IOException
    {
        init();
        resourceProps.clear();
        installResource(resource, isResourceFilterable(resource));
    }

    public void installResource(String resource, boolean filtered) throws IOException
    {
        clearResourceKeys(resource);

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

                // We first try with ${prop}
                doc = filterResource(resource, pattern1, doc);
                // Then with @@prop@@
                doc = filterResource(resource, pattern2, doc);

                // Ultimately we do FreeMarker processing
                try
                {
                    doc = filterText(resource, doc);
                }
                catch (TemplateException e)
                {
                    log.error("Could not apply template", e);
                }

                // Write the filtered resource
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

    public void uninstallResource(String resource) throws IOException
    {
        final String filename = resourceToFilename(resource);
        File outputFile = new File(outputBase, filename);
        if (outputFile.exists())
        {
            outputFile.delete();
        }
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
        BeansWrapper bw = new DefaultObjectWrapper();
        PropertyModel mm = new PropertyModel(propertyResolver, bw);
        StringTemplateLoader loader = new StringTemplateLoader();
        loader.putTemplate(resource, doc, System.currentTimeMillis());
        Configuration c = new Configuration(Configuration.VERSION_2_3_23);
        c.setTemplateLoader(loader);
        c.setTagSyntax(Configuration.SQUARE_BRACKET_TAG_SYNTAX);
        Template t = c.getTemplate(resource);

        StringWriter sw = new StringWriter();
        t.process(mm, sw);
        registerResourceKeys(resource, mm.getKeys());
        return sw.toString();
    }

    private String filterResource(String resource, Pattern pattern, String s) throws IOException
    {
        Set<String> keys = new HashSet<>();
        Matcher m = pattern.matcher(s);
        StringBuffer sb = new StringBuffer(s.length() * 2);

        while (m.find())
        {
            String key = m.group(1);
            String val = getConfigProperty(key);
            if (val != null)
            {
                m.appendReplacement(sb, val);
                keys.add(key);
            }
        }
        registerResourceKeys(resource, keys);

        m.appendTail(sb);
        return sb.toString();
    }

    public boolean isMutable(String key)
    {
        return propertyResolver.isMutable(key);
    }

    private void registerResourceKeys(String resource, Set<String> keys)
    {
        TreeSet<String> _keys = keys.stream()
            .filter(this::isMutable)
            .collect(Collectors.toCollection(TreeSet::new));
        resourceProps.putAll(resource, _keys);
    }

    private void clearResourceKeys(String resource)
    {
        resourceProps.removeAll(resource);
    }

    private String getConfigProperty(String key)
    {
        return propertyResolver.getProperty(key);
    }

    public void startConfigMonitoring() throws IOException
    {
        running = true;
        init();
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

    @Override
    public void run()
    {
        while(running)
        {
            WatchService watcher=null;
            try
            {
                watcher = FileSystems.getDefault().newWatchService();
                Set<String> trackedSources = propertyResolver.getTrackedSources();
                List<Path> files = new ArrayList<>();
                Set<Path> dirs = new HashSet<>();
                for (String trackedSource : trackedSources)
                {
                    final File file = new File(trackedSource);
                    if (file.exists())
                    {
                        dirs.add(file.getParentFile().toPath());
                        files.add(file.toPath());
                    }
                }
                for (Path dir : dirs)
                {
                    dir.register(watcher, new WatchEvent.Kind[]{ENTRY_MODIFY, ENTRY_DELETE, ENTRY_CREATE},
                                 SensitivityWatchEventModifier.HIGH);
                }

                while (running)
                {
                    WatchKey key;
                    try
                    {
                        key = watcher.take();
                    }
                    catch (InterruptedException ignored)
                    {
                        break;
                    }

                    for (WatchEvent<?> event : key.pollEvents())
                    {
                        WatchEvent.Kind<?> kind = event.kind();
                        if (kind == OVERFLOW)
                        {
                            continue;
                        }
                        Path dir = (Path)key.watchable();
                        Path filename = ((WatchEvent<Path>) event).context();
                        Path path = dir.resolve(filename);

                        if(files.contains(path))
                        {
                            Set<String> affectedResources = propertyResolver.scanPropertyChanges(resourceProps);
                            for (String resource : affectedResources)
                            {
                                installResource(resource, true);
                            }
                        }
                    }
                    boolean valid = key.reset();
                    if (!valid)
                    {
                        break;
                    }
                }
            }
            catch (Throwable e)
            {
                log.error("Oops", e);
                try
                {
                    Thread.sleep(5000);
                }
                catch (InterruptedException ignored)
                {
                }
            }
            finally
            {
                if(watcher!=null)
                {
                    try
                    {
                        watcher.close();
                    }
                    catch (IOException ignored)
                    {
                    }
                }
            }
        }
    }
}
