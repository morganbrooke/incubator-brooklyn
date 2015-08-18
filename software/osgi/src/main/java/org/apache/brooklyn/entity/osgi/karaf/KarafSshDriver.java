/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.brooklyn.entity.osgi.karaf;

import static java.lang.String.format;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import brooklyn.entity.java.JavaSoftwareProcessSshDriver;

import org.apache.brooklyn.entity.core.Entities;
import org.apache.brooklyn.location.basic.SshMachineLocation;
import org.apache.brooklyn.util.collections.MutableList;
import org.apache.brooklyn.util.collections.MutableMap;
import org.apache.brooklyn.util.net.Networking;
import org.apache.brooklyn.util.os.Os;
import org.apache.brooklyn.util.ssh.BashCommands;

import com.google.common.collect.ImmutableList;

public class KarafSshDriver extends JavaSoftwareProcessSshDriver implements KarafDriver {

    // TODO getJmxJavaSystemProperties(), don't set via JAVA_OPTS; set ourselves manually
    // (karaf reads from props files)
    // but do set "java.rmi.server.hostname"

    public KarafSshDriver(KarafContainerImpl entity, SshMachineLocation machine) {
        super(entity, machine);
    }

    @Override
    public KarafContainerImpl getEntity() {
        return (KarafContainerImpl) super.getEntity();
    }

    @Override
    protected String getLogFileLocation() {
        return Os.mergePaths(getRunDir(), "data", "karaf.out");
    }

    @Override
    public void preInstall() {
        resolver = Entities.newDownloader(this);
        setExpandedInstallDir(Os.mergePaths(getInstallDir(), resolver.getUnpackedDirectoryName(format("apache-karaf-%s", getVersion()))));
    }

    @Override
    public void install() {
        List<String> urls = resolver.getTargets();
        String saveAs = resolver.getFilename();

        List<String> commands = ImmutableList.<String>builder()
                .addAll(BashCommands.commandsToDownloadUrlsAs(urls, saveAs))
                .add(BashCommands.INSTALL_TAR)
                .add("tar xzfv " + saveAs)
                .build();

        newScript(INSTALLING)
                .body.append(commands)
                .execute();
    }

    @Override
    public void customize() {
        Map<String, Object> ports = new HashMap<String, Object>();
        ports.put("jmxPort", getJmxPort());
        ports.put("rmiRegistryPort", getRmiRegistryPort());
        Networking.checkPortsValid(ports);

        newScript(CUSTOMIZING)
                .body.append(
                        format("cd %s", getRunDir()),
                        format("cp -R %s/{bin,etc,lib,system,deploy} . || exit $!", getExpandedInstallDir()),
                        format("sed -i.bk 's/rmiRegistryPort = 1099/rmiRegistryPort = %s/g' etc/org.apache.karaf.management.cfg", getRmiRegistryPort()),
                        format("sed -i.bk 's/rmiServerPort = 44444/rmiServerPort = %s/g' etc/org.apache.karaf.management.cfg", getJmxPort())
                    )
                .execute();
    }

    @Override
    public void launch() {
        newScript(MutableMap.of(USE_PID_FILE, true), LAUNCHING)
                .body.append("nohup ./bin/start")
                .execute();
    }

    @Override
    public boolean isRunning() {
        // TODO Can we use the pidFile, auto-generated by launch?

        Integer pid = entity.getAttribute(KarafContainer.KARAF_PID);
        // This method is called on startup, before JMX is initialised, so pid won't always be available.
        if (pid != null) {
            return newScript(CHECK_RUNNING)
                    .body.append(format("ps aux | grep 'karaf' | grep %s > /dev/null", pid))
                    .execute() == 0;
        } else {
            // Simple method isn't available, use pid in instance.properties.
            return newScript(CHECK_RUNNING)
                    .body.append(
                            format("cd %s/instances/",getRunDir()),
                            "[ $(uname) = \"Darwin\" ] && pid=$(sed -n -e 's/.*pid=\\([0-9]*\\)$/\\1/p' instance.properties) || pid=$(sed -r -n -e 's/.*pid=([0-9]*)$/\\1/p' instance.properties)",
                            "ps aux | grep 'karaf' | grep $(echo ${pid:-X}) > /dev/null"
                        )
                    .execute() == 0;
        }
    }

    @Override
    public void stop() {
        newScript(STOPPING)
                .environmentVariablesReset()
                .body.append(format("%s/bin/stop",getRunDir()))
                .execute();
    }

    @Override
    public void kill() {
        stop(); // TODO no pid file to easily do `kill -9`
    }

    @Override
    protected List<String> getCustomJavaConfigOptions() {
        return MutableList.<String>builder()
                .addAll(super.getCustomJavaConfigOptions())
                .add("-Xms200m")
                .add("-Xmx800m")
                .add("-XX:MaxPermSize=400m")
                .build();
    }

}
