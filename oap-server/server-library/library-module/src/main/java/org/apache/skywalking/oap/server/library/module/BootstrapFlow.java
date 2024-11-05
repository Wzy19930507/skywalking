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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.apache.skywalking.oap.server.library.util.CollectionUtils;

@Slf4j
class BootstrapFlow {
    private Map<String, ModuleDefine> loadedModules;
    private List<ModuleProvider> startupSequence;

    BootstrapFlow(Map<String, ModuleDefine> loadedModules) throws CycleDependencyException, ModuleNotFoundException {
        this.loadedModules = loadedModules;
        startupSequence = new ArrayList<>();

        // 制定启动顺序
        makeSequence();
    }

    @SuppressWarnings("unchecked")
    void start(
            ModuleManager moduleManager) throws ModuleNotFoundException, ServiceNotProvidedException, ModuleStartException {
        for (ModuleProvider provider : startupSequence) {
            log.info("start the provider {} in {} module.", provider.name(), provider.getModuleName());
            // 检查 ModuleDefine 必须依赖的 Service
            provider.requiredCheck(provider.getModule().services());

            // 动态监听配置；配置服务；启动服务
            provider.start();
        }
    }

    void notifyAfterCompleted() throws ServiceNotProvidedException, ModuleStartException {
        for (ModuleProvider provider : startupSequence) {
            provider.notifyAfterCompleted();
        }
    }

    private void makeSequence() throws CycleDependencyException, ModuleNotFoundException {
        List<ModuleProvider> allProviders = new ArrayList<>();
        for (final ModuleDefine module : loadedModules.values()) {
            // 从 ModuleProvider 中获取所依赖的 ModuleDefine，判断 requiredModule 是否存在
            String[] requiredModules = module.provider().requiredModules();
            if (requiredModules != null) {
                for (String requiredModule : requiredModules) {
                    if (!loadedModules.containsKey(requiredModule)) {
                        throw new ModuleNotFoundException(
                                requiredModule + " module is required by "
                                        + module.provider().getModuleName() + "."
                                        + module.provider().name() + ", but not found.");
                    }
                }
            }

            allProviders.add(module.provider());
        }

        // 获取必须模块提前加载
        do {
            int numOfToBeSequenced = allProviders.size();
            for (int i = 0; i < allProviders.size(); i++) {
                ModuleProvider provider = allProviders.get(i);
                String[] requiredModules = provider.requiredModules();
                if (CollectionUtils.isNotEmpty(requiredModules)) {
                    boolean isAllRequiredModuleStarted = true;
                    for (String module : requiredModules) {
                        // find module in all ready existed startupSequence
                        boolean exist = false;
                        for (ModuleProvider moduleProvider : startupSequence) {
                            if (moduleProvider.getModuleName().equals(module)) {
                                exist = true;
                                break;
                            }
                        }
                        if (!exist) {
                            isAllRequiredModuleStarted = false;
                            break;
                        }
                    }

                    if (isAllRequiredModuleStarted) {
                        startupSequence.add(provider);
                        allProviders.remove(i);
                        i--;
                    }
                } else {
                    startupSequence.add(provider);
                    allProviders.remove(i);
                    i--;
                }
            }

            if (numOfToBeSequenced == allProviders.size()) {
                StringBuilder unSequencedProviders = new StringBuilder();
                allProviders.forEach(provider -> unSequencedProviders.append(provider.getModuleName())
                        .append("[provider=")
                        .append(provider.getClass().getName())
                        .append("]\n"));
                throw new CycleDependencyException(
                        "Exist cycle module dependencies in \n" + unSequencedProviders.substring(0, unSequencedProviders
                                .length() - 1));
            }
        }
        while (allProviders.size() != 0);
    }
}
