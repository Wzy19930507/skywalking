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

package org.apache.skywalking.oap.server.starter;

import lombok.extern.slf4j.Slf4j;
import org.apache.skywalking.oap.server.core.CoreModule;
import org.apache.skywalking.oap.server.core.RunningMode;
import org.apache.skywalking.oap.server.core.status.ServerStatusService;
import org.apache.skywalking.oap.server.core.version.Version;
import org.apache.skywalking.oap.server.library.module.ApplicationConfiguration;
import org.apache.skywalking.oap.server.library.module.ModuleManager;
import org.apache.skywalking.oap.server.library.module.TerminalFriendlyTable;
import org.apache.skywalking.oap.server.starter.config.ApplicationConfigLoader;

import static org.apache.skywalking.oap.server.library.module.TerminalFriendlyTable.Row;

/**
 * Starter core. Load the core configuration file, and initialize the startup sequence through {@link ModuleManager}.
 */
@Slf4j
public class OAPServerBootstrap {
    public static void start() {
        // 模块管理器，管理所有模块的定义
        ModuleManager manager = new ModuleManager("Apache SkyWalking OAP");
        // 拼接成对的参数为固定的格式
        final TerminalFriendlyTable bootingParameters = manager.getBootingParameters();

        // 初始化模式，noInit Init
        String mode = System.getProperty("mode");
        RunningMode.setMode(mode);

        // 初始化配置加载器（加载 application.yml，路径固定）
        ApplicationConfigLoader configLoader = new ApplicationConfigLoader(bootingParameters);

        bootingParameters.addRow(new Row("Running Mode", mode));
        bootingParameters.addRow(new Row("Version", Version.CURRENT.toString()));

        try {
            // 加载 application.yml，环境变量优先 ，并 log.info 引导参数
            ApplicationConfiguration applicationConfiguration = configLoader.load();
            // 加载 module 以及 provider，执行 module 的 prepare 和 start 方法
            // prepare 时，会为 module 配置 provider，并调用 provider 的 prepare 方法，prepare 时会注册 service
            // start 时，检查 ModuleDefine 依赖的 Service 是否注册，动态监听配置，启动服务
            manager.init(applicationConfiguration);

            // 将启动时间设置到自监控中
            manager.find(CoreModule.NAME)
                    .provider()
                    .getService(ServerStatusService.class)
                    .bootedNow(configLoader.getResolvedConfigurations(), System.currentTimeMillis());

            if (RunningMode.isInitMode()) {
                log.info("OAP starts up in init mode successfully, exit now...");
                System.exit(0);
            }
        } catch (Throwable t) {
            log.error(t.getMessage(), t);
            System.exit(1);
        } finally {
            // 输出引导配置
            log.info(bootingParameters.toString());
        }
    }
}
