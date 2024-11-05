/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.apache.skywalking.oap.server.library.module;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.ServiceLoader;
import lombok.Getter;

/**
 * The <code>ModuleManager</code> takes charge of all {@link ModuleDefine}s in collector.
 */
public class ModuleManager implements ModuleDefineHolder {
    private boolean isInPrepareStage = true;
    private final Map<String, ModuleDefine> loadedModules = new HashMap<>();
    @Getter
    private final TerminalFriendlyTable bootingParameters;

    public ModuleManager(String description) {
        bootingParameters = new TerminalFriendlyTable(
                String.format("The key booting parameters of %s are listed as following.", description));
    }

    /**
     * Init the given modules
     */
    public void init(ApplicationConfiguration applicationConfiguration)
            throws ModuleNotFoundException, ProviderNotFoundException, ServiceNotProvidedException,
            CycleDependencyException, ModuleConfigException, ModuleStartException {

        String[] moduleNames = applicationConfiguration.moduleList();
        // 加载 ModuleDefine
        ServiceLoader<ModuleDefine> moduleServiceLoader = ServiceLoader.load(ModuleDefine.class);
        // 加载 ModuleProvider：Module 实现
        ServiceLoader<ModuleProvider> moduleProviderLoader = ServiceLoader.load(ModuleProvider.class);

        HashSet<String> moduleSet = new HashSet<>(Arrays.asList(moduleNames));
        for (ModuleDefine module : moduleServiceLoader) {
            if (moduleSet.contains(module.name())) {
                // 配置 ModuleDefine 的实现 ModuleProvider，调用 ModuleProvider 的 prepare 方法
                // 调用 ModuleProvider 时会创建并注册 ModuleDefine 的 Service
                module.prepare(
                        this,
                        applicationConfiguration.getModuleConfiguration(module.name()),
                        moduleProviderLoader,
                        bootingParameters
                );
                loadedModules.put(module.name(), module);
                moduleSet.remove(module.name());
            }
        }
        // Finish prepare stage
        isInPrepareStage = false;

        if (moduleSet.size() > 0) {
            throw new ModuleNotFoundException(moduleSet.toString() + " missing.");
        }

        // 制定启动顺序
        BootstrapFlow bootstrapFlow = new BootstrapFlow(loadedModules);

        // 检查 ModuleDefine 必须依赖的 Service，动态监听配置，启动服务
        bootstrapFlow.start(this);
        bootstrapFlow.notifyAfterCompleted();
    }

    @Override
    public boolean has(String moduleName) {
        return loadedModules.get(moduleName) != null;
    }

    @Override
    public ModuleProviderHolder find(String moduleName) throws ModuleNotFoundRuntimeException {
        assertPreparedStage();
        ModuleDefine module = loadedModules.get(moduleName);
        if (module != null)
            return module;
        throw new ModuleNotFoundRuntimeException(moduleName + " missing.");
    }

    private void assertPreparedStage() {
        if (isInPrepareStage) {
            throw new AssertionError("Still in preparing stage.");
        }
    }
}
