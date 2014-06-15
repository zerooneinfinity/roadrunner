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

package io.helium.persistence;

import io.helium.common.Path;
import io.helium.persistence.actions.Push;
import io.helium.persistence.actions.Remove;
import io.helium.persistence.actions.Set;
import io.helium.persistence.actions.Update;
import io.helium.persistence.mapdb.MapDbBackedNode;
import io.helium.persistence.mapdb.MapDbPersistence;
import org.vertx.java.core.Handler;
import org.vertx.java.core.eventbus.Message;
import org.vertx.java.core.json.JsonObject;
import org.vertx.java.platform.Verticle;

import java.util.UUID;

public class Persistor extends Verticle {

    public static final String PUSH = "io.helium.persistor.push";
    public static final String SET = "io.helium.persistor.set";
    public static final String DELETE = "io.helium.persistor.remove";
    public static final String UPDATE = "io.helium.persistor.update";
    public static final String GET = "io.helium.persistor.get";

    private Push push;
    private Set set;
    private Remove remove;
    private Update update;

    private MapDbPersistence persistence;

    public void start() {
        persistence = new MapDbPersistence(vertx);
        push = new Push(persistence);
        update = new Update(persistence);
        set = new Set(persistence);
        remove = new Remove(persistence);
        initDefaults();

        vertx.eventBus().registerHandler(PUSH, push::handle);
        vertx.eventBus().registerHandler(DELETE, remove::handle);
        vertx.eventBus().registerHandler(SET, set::handle);
        vertx.eventBus().registerHandler(UPDATE, update::handle);

        vertx.eventBus().registerHandler(GET, new Handler<Message<JsonObject>>() {
            @Override
            public void handle(Message<JsonObject> event) {
                if (MapDbBackedNode.exists(Path.of(event.body().getString("path")))) {
                    event.reply(MapDbBackedNode.of(Path.of(event.body().getString("path"))).toJsonObject());
                } else {
                    Object value = persistence.get(Path.of(event.body().getString("path")));
                    if (value instanceof MapDbBackedNode) {
                        event.reply(((MapDbBackedNode) value).toJsonObject());
                    } else {
                        event.reply(value);
                    }
                }
            }
        });
    }

    private void initDefaults() {

        if (!persistence.exists(Path.of("/users"))) {
            String uuid = UUID.randomUUID().toString();
            MapDbBackedNode user = MapDbBackedNode.of(Path.of("/users").append(uuid.replaceAll("-", "")));
            user.put("username", "admin").put("password", "admin").put("isAdmin", true);
        }

        if (!persistence.exists(Path.of("/rules"))) {
            MapDbBackedNode rules = MapDbBackedNode.of(Path.of("/rules"));
            rules.put(".write", "function(auth, path, data, root){\n" +
                    "   return auth.isAdmin;\n" +
                    "}\n");
            rules.put(".read", true);
            rules.getNode("rules").put(".write", "function(auth, path, data, root){\n" +
                    "  return auth.isAdmin;\n" +
                    "}\n")
                    .put(".read", "function(auth, path, data, root){\n" +
                            "  return auth.isAdmin;\n" +
                            "}\n");
            rules.getNode("users").put(".write", "function(auth, path, data, root){\n" +
                    "  return auth.isAdmin;\n" +
                    "}\n")
                    .put(".read", "function(auth, path, data, root){\n" +
                            "  return auth.isAdmin;\n" +
                            "}\n");
        }
    }

    public static JsonObject get(Path path) {
        return new JsonObject().putString("path", path.toString());
    }
}
