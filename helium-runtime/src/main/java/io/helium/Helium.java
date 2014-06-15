/*
 * Copyright 2012 The Helium Project
 *
 * The Helium Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.helium;

import io.helium.authorization.Authorizator;
import io.helium.persistence.EventSource;
import io.helium.persistence.Persistor;
import io.helium.server.http.HttpServer;
import io.helium.server.mqtt.MqttServer;
import org.vertx.java.platform.Verticle;

import java.io.File;

/**
 * Main entry point for Helium
 *
 * @author Christoph Grotz
 */
public class Helium extends Verticle {

    @Override
    public void start() {
        try {
            // Workers
            new File("helium/journal").mkdirs();
            container.deployWorkerVerticle(Authorizator.class.getName(), container.config());
            container.deployWorkerVerticle(EventSource.class.getName(), container.config());
            container.deployWorkerVerticle(Persistor.class.getName(), container.config());

            // Channels
            container.deployVerticle(HttpServer.class.getName(), container.config());
            container.deployVerticle(MqttServer.class.getName(), container.config());

            // TODO Administration
            //container.deployVerticle(Administration.class.getName(), container.config());
        } catch (Exception e) {
            container.logger().error("Failed starting Helium", e);
        }
    }
}
