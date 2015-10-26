# Why a resource deployer?

Q2 has a nice and simple deployer. You write an xml file in the 'deploy'
directory and a new service is deployed. You remove that file and the 
service gets undeployed. This is excellent, but presents a few problems 
in terms of configurability. 
I may have settings that would only apply to a certain 
environment (hostnames, ports for endpoints - or we only connect
two different load balanced hosts in production!) or perhaps in development
we might want to turn on the Debug transaction participant, but not in test 
or production.

The traditional approach many developers follow is to have a set of property
files that during 'build' replace placeholders in the deployable resources.
Can you guess what problems are associated with this approach? Yes, you need
to have a separate build for each environment (dev, test and prod).
Want to change a configuration parameter? Create a whole bunch of builds to see
if it works as expected in different environments. 
This approach while extremely simple, is not DevOps
friendly, nor follows best practices in separation of code and configuration.

A while back I wrote the "Freemarker Decorator" in attempt to solve many of these
issues. You have a configuration file, and every time a file gets deployed,
it gets "decorated" using the contents of the configuration file as properties,
therefore the configuration becomes a deployment concern and not a developer 
concern.

While I am very satisfied with this approach, I still thought that these deployables 
were part of the code and therefore should live as 'resources' within the app. This 
allows us to expose just the bare minimum to the outside - a single configuration file.
Everything else lives inside the application.

# How do you use it?

It's as simple as creating a new main class that starts Q2 with a code block similar to: 

```java
    File tmpDir = Files.createTempDirectory("jpos-Q2").toAbsolutePath().toFile();
    final ResourceDeployer deployer = ResourceDeployer.newInstance(tmpDir);
    deployer.installRuntimeResources();
    deployer.startConfigMonitoring();
    new Q2(new File(tmpDir, "deploy").getAbsolutePath()).start();
```
  
At runtime, anything stored as a resource under META-INF/q2-runtime/ will get copied 
to jPOS's deploy directory's parent. In the above example, we create a temporary directory
 and install the resources there, and Q2 is started with a custom deploy directory within
 that temporary directory.

## Configuration file

The deployer will read a configuration file at startup which by default points to 'cfg/config.properties'
relative to the working directory. This configuration is just a list of properties specific to your 
application.

You can set the CONFIG_FILE system property 
to override the location of the config file within the filesystem.
 
## Filtering

You can use placeholders within your resources in the form of @@myProperty@@ or ${myProperty} 
to replace them at runtime with the contents of your properties.

The following paths are excluded by default from filtering:

- cfg/*.jks
- cfg/*.ks
- **/*.jpg
- **/*.gif
- **/*.png
- **/*.pdf

## Automatic reloading of configuration file

If you invoke "startConfigMonitoring()", then we start tracking modifications 
to the config file. If any property is modified, any previously deployed resource
that depended on that property would be redeployed.

## Notes

I wrote this for me. You might not needs this. I'm just letting it out there in case someone else does...

