package com.vmantek.tools.jpos;

import java.io.IOException;
import java.util.List;

public interface ResourceDeployerX
{
    List<String> getAvailableResources() throws IOException;

    void installRuntimeResources() throws IOException;

    void installResource(String resource) throws IOException;

    void uninstallResource(String resource) throws IOException;

    void startConfigMonitoring();

    void stopConfigMonitoring();
}
