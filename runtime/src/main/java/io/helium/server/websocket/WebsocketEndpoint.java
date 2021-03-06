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

package io.helium.server.websocket;

import com.google.common.base.Strings;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import io.helium.authorization.Authorizator;
import io.helium.authorization.Operation;
import io.helium.common.EndpointConstants;
import io.helium.common.ExceptionWrapper;
import io.helium.common.PasswordHelper;
import io.helium.common.Path;
import io.helium.event.HeliumEvent;
import io.helium.event.HeliumEventType;
import io.helium.event.builder.HeliumEventBuilder;
import io.helium.event.changelog.*;
import io.helium.persistence.Persistence;
import io.helium.persistence.actions.Get;
import io.helium.persistence.mapdb.MapDbService;
import io.helium.persistence.mapdb.Node;
import io.helium.persistence.mapdb.PersistenceExecutor;
import io.helium.persistence.queries.QueryEvaluator;
import io.helium.server.websocket.rpc.Rpc;
import org.vertx.java.core.Handler;
import org.vertx.java.core.Vertx;
import org.vertx.java.core.buffer.Buffer;
import org.vertx.java.core.eventbus.Message;
import org.vertx.java.core.http.ServerWebSocket;
import org.vertx.java.core.json.JsonArray;
import org.vertx.java.core.json.JsonObject;
import org.vertx.java.platform.Container;

import java.util.List;
import java.util.Map.Entry;
import java.util.Optional;

public class WebsocketEndpoint {
    private final Vertx vertx;
    private Multimap<String, String> attached_listeners = HashMultimap.create();
    private String basePath;
    private Optional<JsonObject> auth = Optional.empty();
    private QueryEvaluator queryEvaluator;
    private ServerWebSocket socket;
    private List<HeliumEvent> disconnectEvents = Lists.newArrayList();

    private Rpc rpc;
    private final Container container;

    public WebsocketEndpoint(String basePath,
                             ServerWebSocket socket,
                             Vertx vertx,
                             Container container) {
        this.socket = socket;
        this.container = container;
        this.vertx = vertx;
        this.basePath = basePath;
        this.queryEvaluator = new QueryEvaluator();

        this.rpc = new Rpc(container);
        this.rpc.register(this);

        this.socket.dataHandler(event -> rpc.handle(event.toString(), WebsocketEndpoint.this));

        Handler<Message<JsonArray>> distributeChangeLogHandler = message -> distributeChangeLog(ChangeLog.of(message.body()));
        vertx.eventBus().registerHandler(EndpointConstants.DISTRIBUTE_CHANGE_LOG, distributeChangeLogHandler);

        Handler<Message<JsonObject>> distributeEventHandler = message -> distributeEvent(new Path(HeliumEvent.extractPath(message.body().getString("path"))), message.body().getObject("payload"));
        vertx.eventBus().registerHandler(EndpointConstants.DISTRIBUTE_EVENT, distributeEventHandler);

        socket.closeHandler(event -> {
            vertx.eventBus().unregisterHandler(EndpointConstants.DISTRIBUTE_CHANGE_LOG, distributeChangeLogHandler);
            vertx.eventBus().unregisterHandler(EndpointConstants.DISTRIBUTE_EVENT, distributeEventHandler);
            executeDisconnectEvents();

        });
    }


    @Rpc.Method
    public void attachListener(@Rpc.Param("path") String path,
                               @Rpc.Param("event_type") String eventType) {
        container.logger().trace("attachListener");
        addListener(new Path(HeliumEvent.extractPath(path)), eventType);
        if ("child_added".equals(eventType)) {
            ChangeLog log = ChangeLog.of(new JsonArray());
            syncPath(Path.of(HeliumEvent.extractPath(path)));
            distributeChangeLog(log);
        } else if ("value".equals(eventType)) {
            ChangeLog log = ChangeLog.of(new JsonArray());
            syncPropertyValue(new Path(HeliumEvent.extractPath(path)));
            distributeChangeLog(log);
        }
    }

    @Rpc.Method
    public void detachListener(@Rpc.Param("path") String path,
                               @Rpc.Param("event_type") String eventType) {
        container.logger().trace("detachListener");
        removeListener(new Path(HeliumEvent.extractPath(path)), eventType);
    }

    @Rpc.Method
    public void attachQuery(@Rpc.Param("path") String path, @Rpc.Param("query") String query) {
        container.logger().trace("attachQuery");
        addQuery(new Path(HeliumEvent.extractPath(path)), query);
        syncPathWithQuery(new Path(HeliumEvent.extractPath(path)), this,
                new QueryEvaluator(), query);
    }

    @Rpc.Method
    public void detachQuery(@Rpc.Param("path") String path, @Rpc.Param("query") String query) {
        container.logger().trace("detachQuery");
        deleteQuery(new Path(HeliumEvent.extractPath(path)), query);
    }

    @Rpc.Method
    public void event(@Rpc.Param("path") String path, @Rpc.Param("data") JsonObject data) {
        container.logger().trace("event");
        vertx.eventBus().send(EndpointConstants.DISTRIBUTE_EVENT, new JsonObject().putString("path", path).putObject("payload", data));
    }

    @Rpc.Method
    public void push(@Rpc.Param("path") String path, @Rpc.Param("name") String name,
                     @Rpc.Param("data") JsonObject data) {
        container.logger().trace("push");
        HeliumEvent event = new HeliumEvent(HeliumEventType.PUSH, path + "/" + name, data);
        if (auth.isPresent())
            event.setAuth(auth.get());

        Authorizator.get().check(Operation.WRITE, auth, Path.of(path), data, securityCheck -> {
            if (securityCheck) {
                vertx.eventBus().send(Persistence.PUSH, event, (Message<JsonArray> changeLogMsg) -> {
                    if (changeLogMsg.body().size() > 0) {
                        vertx.eventBus().publish(EndpointConstants.DISTRIBUTE_CHANGE_LOG, changeLogMsg.body());
                        vertx.eventBus().send(PersistenceExecutor.PERSIST_CHANGE_LOG, changeLogMsg.body());
                    }
                });
                container.logger().trace("authorized: " + event);
            } else {
                container.logger().warn("not authorized: " + event);
            }
        });
    }

    @Rpc.Method
    public void set(@Rpc.Param("path") String path, @Rpc.Param("data") Object data) {
        long start = System.currentTimeMillis();
        container.logger().trace("set");
        HeliumEvent event = new HeliumEvent(HeliumEventType.SET, path, data);
        if (auth.isPresent())
            event.setAuth(auth.get());

        Authorizator.get().check(Operation.WRITE, auth, Path.of(path), data, securityCheckResult -> {
            container.logger().info("Security Check took: "+(System.currentTimeMillis()-start)+"ms");
            if (securityCheckResult) {
                vertx.eventBus().send(Persistence.SET, event, ExceptionWrapper.wrap((Message<JsonArray> changeLogMsg) -> {
                    container.logger().info("Calculating of Changelog took: " + (System.currentTimeMillis() - start) + "ms");
                    JsonArray changeLog = changeLogMsg.body();
                    if (changeLog.size() > 0) {
                        vertx.eventBus().send(EndpointConstants.DISTRIBUTE_CHANGE_LOG, changeLogMsg.body());
                        vertx.eventBus().publish(PersistenceExecutor.PERSIST_CHANGE_LOG, changeLogMsg.body());
                    }
                    changeLogMsg.reply();
                }));
                container.logger().trace("authorized: " + event);
            } else {
                container.logger().warn("not authorized: " + event);
            }
        });
    }

    @Rpc.Method
    public void update(@Rpc.Param("path") String path, @Rpc.Param("data") JsonObject data) {
        container.logger().trace("update");
        HeliumEvent event = new HeliumEvent(HeliumEventType.UPDATE, path, data);
        if (auth.isPresent())
            event.setAuth(auth.get());

        Authorizator.get().check(Operation.WRITE, auth, Path.of(path), null, securityCheck -> {
            if (securityCheck) {
                vertx.eventBus().send(Persistence.UPDATE, event, (Message<JsonArray> changeLogMsg) -> {
                    if (changeLogMsg.body().size() > 0) {
                        vertx.eventBus().publish(EndpointConstants.DISTRIBUTE_CHANGE_LOG, changeLogMsg.body());
                        vertx.eventBus().publish(PersistenceExecutor.PERSIST_CHANGE_LOG, changeLogMsg.body());
                    }
                });
                container.logger().trace("authorized: " + event);
            } else {
                container.logger().warn("not authorized: " + event);
            }
        });
    }

    @Rpc.Method
    public void pushOnDisconnect(@Rpc.Param("path") String path, @Rpc.Param("name") String name,
                                 @Rpc.Param("payload") JsonObject payload) {
        container.logger().trace("pushOnDisconnect");
        HeliumEvent event = new HeliumEvent(HeliumEventType.PUSH, path + "/" + name,
                payload);
        if (auth.isPresent())
            event.setAuth(auth.get());
        this.disconnectEvents.add(event);
    }

    @Rpc.Method
    public void setOnDisconnect(@Rpc.Param("path") String path, @Rpc.Param("data") JsonObject data) {
        container.logger().trace("setOnDisconnect");
        HeliumEvent event = new HeliumEvent(HeliumEventType.SET, path, data);
        if (auth.isPresent())
            event.setAuth(auth.get());
        this.disconnectEvents.add(event);
    }

    @Rpc.Method
    public void updateOnDisconnect(@Rpc.Param("path") String path, @Rpc.Param("data") JsonObject data) {
        container.logger().trace("updateOnDisconnect");
        HeliumEvent event = new HeliumEvent(HeliumEventType.UPDATE, path, data);
        if (auth.isPresent())
            event.setAuth(auth.get());
        this.disconnectEvents.add(event);
    }

    @Rpc.Method
    public void deleteOnDisconnect(@Rpc.Param("path") String path) {
        container.logger().trace("deleteOnDisconnect");
        HeliumEvent event = HeliumEventBuilder.delete(Path.of(path)).build();
        if (auth.isPresent())
            event.setAuth(auth.get());
        this.disconnectEvents.add(event);
    }

    @Rpc.Method
    public void authenticate(@Rpc.Param("username") String username, @Rpc.Param("password") String password) {
        container.logger().trace("authenticate");
        extractAuthentication(username, password, event -> {
            auth = event;
            for (String path : attached_listeners.keys()) {
                for (String eventType : attached_listeners.get(path))
                    if ("child_added".equals(eventType)) {
                        ChangeLog log = ChangeLog.of(new JsonArray());
                        syncPath(Path.of(HeliumEvent.extractPath(path)));
                        distributeChangeLog(log);
                    } else if ("value".equals(eventType)) {
                        ChangeLog log = ChangeLog.of(new JsonArray());
                        syncPropertyValue(new Path(HeliumEvent.extractPath(path)));
                        distributeChangeLog(log);
                    }
            }
        });
    }

    private void extractAuthentication(String username, String password, Handler<Optional<JsonObject>> handler) {
        vertx.eventBus().send(Persistence.GET, Get.request(Path.of("/users")), (Message<JsonObject> event) -> {
            JsonObject users = event.body();
            if (users != null) {
                for (String key : users.getFieldNames()) {
                    Object value = users.getObject(key);
                    if (value != null) {
                        JsonObject node = (JsonObject) value;
                        if (node.containsField("username") && node.containsField("password")) {
                            String localUsername = node.getString("username");
                            String localPassword = node.getString("password");
                            if (username.equals(localUsername) && PasswordHelper.get().comparePassword(localPassword, password)) {
                                handler.handle(Optional.of(node));
                                return;
                            }
                        }
                    }
                }
            }
            handler.handle(Optional.empty());
        });
    }

    public void handle(String msg) {
        rpc.handle(msg, this);
    }

    public void distributeChangeLog(ChangeLog changeLog) {
        long startTime = System.currentTimeMillis();

        changeLog.forEach(obj -> {
            JsonObject logE = (JsonObject) obj;

            if (logE.getString("type").equals(ChildAdded.class.getSimpleName())) {
                ChildAdded logEvent = ChildAdded.of(logE);
                processQuery(logEvent);
                if (hasListener(logEvent.path(), EndpointConstants.CHILD_ADDED)) {
                    fireChildAdded(logEvent.name(), logEvent.path(), logEvent.parent(),
                            logEvent.value(), logEvent.hasChildren(), logEvent.numChildren()
                    );
                }
            }
            if (logE.getString("type").equals(ChildChanged.class.getSimpleName())) {
                ChildChanged logEvent = ChildChanged.of(logE);
                processQuery(logEvent);
                if (hasListener(logEvent.path(), EndpointConstants.CHILD_CHANGED)) {
                    fireChildChanged(logEvent.name(), logEvent.path(), logEvent.parent(),
                            logEvent.value(), logEvent.hasChildren(), logEvent.numChildren()
                    );
                }
            }
            if (logE.getString("type").equals(ValueChanged.class.getSimpleName())) {
                ValueChanged logEvent = ValueChanged.of(logE);
                processQuery(logEvent);
                if (hasListener(logEvent.path(), EndpointConstants.VALUE)) {
                    fireValue(logEvent.name(), logEvent.path(), logEvent.parent(),
                            logEvent.value());
                }
            }
            if (logE.getString("type").equals(ChildDeleted.class.getSimpleName())) {
                ChildDeleted logEvent = ChildDeleted.of(logE);
                processQuery(logEvent);
                if (hasListener(logEvent.path(), EndpointConstants.CHILD_DELETED)) {
                    fireChildDeleted(logEvent.path(), logEvent.name(), logEvent.value());
                }
            }
        });
        container.logger().trace("distribute " + (System.currentTimeMillis() - startTime) + "ms");
    }

    private void processQuery(ChangeLogEvent event) {
        Path nodePath = event.path();
        if (MapDbService.get().exists(nodePath)) {
            nodePath = nodePath.parent();
        }

        if (hasQuery(nodePath.parent())) {
            for (Entry<String, String> queryEntry : queryEvaluator.getQueries()) {
                if (event.value() != null) {
                    JsonObject value = MapDbService.get().of(nodePath).toJsonObject();
                    boolean matches = queryEvaluator.evaluateQueryOnValue(value, queryEntry.getValue());
                    boolean containsNode = queryEvaluator.queryContainsNode(new Path(queryEntry.getKey()),
                            queryEntry.getValue(), nodePath);

                    if (matches) {
                        if (!containsNode) {
                            fireQueryChildAdded(nodePath, value);
                            queryEvaluator.addNodeToQuery(nodePath.parent(), queryEntry.getValue(), nodePath);
                        } else {
                            fireQueryChildChanged(nodePath, value);
                        }
                    } else if (containsNode) {
                        fireQueryChildDeleted(nodePath, value);
                        queryEvaluator.deleteNodeFromQuery(nodePath.parent(), queryEntry.getValue(),
                                nodePath);
                    }
                } else {
                    fireQueryChildDeleted(nodePath, null);
                    queryEvaluator.deleteNodeFromQuery(nodePath.parent(), queryEntry.getValue(), nodePath);
                }
            }
        }
    }

    public void fireQueryChildAdded(Path path, Object value) {
        Authorizator.get().check(Operation.READ, auth, path, value, securityCheck -> {
            if (securityCheck) {
                Authorizator.get().filter(auth, path, value, event -> {
                    JsonObject broadcast = new JsonObject();
                    broadcast.putValue(HeliumEvent.TYPE, EndpointConstants.QUERY_CHILD_ADDED);
                    broadcast.putValue("name", path.lastElement());
                    broadcast.putValue(HeliumEvent.PATH, createPath(path.parent()));
                    broadcast.putValue("parent", createPath(path.parent().parent()));
                    broadcast.putValue(HeliumEvent.PAYLOAD, event);
                    broadcast.putValue("hasChildren", Node.hasChildren(value));
                    broadcast.putValue("numChildren", Node.childCount(value));
                    sendViaWebSocket(broadcast);
                });
            }
        });
    }

    private void sendViaWebSocket(JsonObject broadcast) {
        try {
            socket.writeTextFrame(broadcast.toString());
        } catch (IllegalStateException e) {
            // Websocket was probably closed
            socket.close();
        }
    }

    public void fireChildChanged(String name, Path path, Path parent, Object node,
                                 boolean hasChildren, long numChildren) {
        Authorizator.get().check(Operation.READ, auth, path, node, securityCheck -> {
            if (securityCheck) {
                Authorizator.get().filter(auth, path, node, event -> {
                    JsonObject broadcast = new JsonObject();
                    broadcast.putValue(HeliumEvent.TYPE, EndpointConstants.CHILD_CHANGED);
                    broadcast.putValue("name", name);
                    broadcast.putValue(HeliumEvent.PATH, createPath(path));
                    broadcast.putValue("parent", createPath(parent));
                    broadcast.putValue(HeliumEvent.PAYLOAD, event);
                    broadcast.putValue("hasChildren", hasChildren);
                    broadcast.putValue("numChildren", numChildren);
                    sendViaWebSocket(broadcast);
                });
            }
        });
    }

    public void fireChildDeleted(Path path, String name, Object payload) {
        Authorizator.get().check(Operation.READ, auth, path, payload, securityCheck -> {
            if (securityCheck) {
                Authorizator.get().filter(auth, path, payload, event -> {
                    JsonObject broadcast = new JsonObject();
                    broadcast.putValue(HeliumEvent.TYPE, EndpointConstants.CHILD_DELETED);
                    broadcast.putValue(HeliumEvent.NAME, name);
                    broadcast.putValue(HeliumEvent.PATH, createPath(path));
                    broadcast.putValue(HeliumEvent.PAYLOAD, event);
                    sendViaWebSocket(broadcast);
                });
            }
        });
    }

    public void fireValue(String name, Path path, Path parent, Object value) {
        Authorizator.get().check(Operation.READ, auth, path, value, securityCheck -> {
            if (securityCheck) {
                 Authorizator.get().filter(auth, path, value, event -> {
                     JsonObject broadcast = new JsonObject();
                     broadcast.putValue(HeliumEvent.TYPE, EndpointConstants.VALUE);
                     broadcast.putValue("name", name);
                     broadcast.putValue(HeliumEvent.PATH, createPath(path));
                     broadcast.putValue("parent", createPath(parent));
                     broadcast.putValue(HeliumEvent.PAYLOAD, event);
                     sendViaWebSocket(broadcast);
                 });
            }
        });
    }

    public void fireChildAdded(String name, Path path, Path parent, Object value, boolean hasChildren, long numChildren) {

        Authorizator.get().check(Operation.READ, auth, path, value, securityCheck -> {
            if (securityCheck) {
                Authorizator.get().filter(auth, path, value, event -> {
                    JsonObject broadcast = new JsonObject();
                    broadcast.putValue(HeliumEvent.TYPE, EndpointConstants.CHILD_ADDED);
                    broadcast.putValue("name", name);
                    broadcast.putValue(HeliumEvent.PATH, createPath(path));
                    broadcast.putValue("parent", createPath(parent));
                    if(event instanceof  Node) {
                        broadcast.putValue(HeliumEvent.PAYLOAD, ((Node)event).toJsonObject());
                    }
                    else {
                        broadcast.putValue(HeliumEvent.PAYLOAD, event);
                    }
                    broadcast.putValue("hasChildren", hasChildren);
                    broadcast.putValue("numChildren", numChildren);
                    sendViaWebSocket(broadcast);
                });
            }
        });
    }

    public void fireQueryChildChanged(Path path, Object value) {
        Authorizator.get().check(Operation.READ, auth, path, value, securityCheck -> {
            if (securityCheck) {
                Authorizator.get().filter(auth, path, value, event -> {
                    JsonObject broadcast = new JsonObject();
                    broadcast.putValue(HeliumEvent.TYPE, EndpointConstants.QUERY_CHILD_CHANGED);
                    broadcast.putValue("name", path.lastElement());
                    broadcast.putValue(HeliumEvent.PATH, createPath(path.parent()));
                    broadcast.putValue("parent", createPath(path.parent().parent()));
                    broadcast.putValue(HeliumEvent.PAYLOAD, event);
                    broadcast.putValue("hasChildren", Node.hasChildren(value));
                    broadcast.putValue("numChildren", Node.childCount(value));
                    sendViaWebSocket(broadcast);
                });
            }
        });
    }

    public void fireQueryChildDeleted(Path path, Object payload) {
        Authorizator.get().check(Operation.READ, auth, path, payload, securityCheck -> {
            if (securityCheck) {
                Authorizator.get().filter(auth, path, payload, event -> {
                    JsonObject broadcast = new JsonObject();
                    broadcast.putValue(HeliumEvent.TYPE, EndpointConstants.QUERY_CHILD_DELETED);
                    broadcast.putValue(HeliumEvent.NAME, path.lastElement());
                    broadcast.putValue(HeliumEvent.PATH, createPath(path.parent()));
                    broadcast.putValue(HeliumEvent.PAYLOAD, event);
                    sendViaWebSocket(broadcast);
                });
            }
        });
    }

    public void distributeEvent(Path path, JsonObject payload) {
        if (hasListener(path, "event")) {
            Authorizator.get().check(Operation.READ, auth, path, payload, securityCheck -> {
                if (securityCheck) {
                    JsonObject broadcast = new JsonObject();
                    broadcast.putValue(HeliumEvent.TYPE, "event");

                    broadcast.putValue(HeliumEvent.PATH, createPath(path));
                    broadcast.putValue(HeliumEvent.PAYLOAD, payload);
                    container.logger().trace("Distributing Message (basePath: '" + basePath + "',path: '" + path + "') : "
                            + broadcast.toString());
                    sendViaWebSocket(broadcast);
                }
            });
        }
    }

    private String createPath(String path) {
        if (basePath.endsWith("/") && path.startsWith("/")) {
            return basePath + path.substring(1);
        } else {
            return basePath + path;
        }
    }

    private String createPath(Path path) {
        return createPath(path.toString());
    }

    public void addListener(Path path, String type) {
        attached_listeners.put(path.toString(), type);
    }

    public void removeListener(Path path, String type) {
        attached_listeners.remove(path, type);
    }

    private boolean hasListener(Path path, String type) {
        if (path.isEmtpy()) {
            return attached_listeners.containsKey("/") && attached_listeners.get("/").contains(type);
        } else {
            return attached_listeners.containsKey(path.toString())
                    && attached_listeners.get(path.toString()).contains(type);
        }
    }

    public void addQuery(Path path, String query) {
        queryEvaluator.addQuery(path, query);
    }

    public void deleteQuery(Path path, String query) {
        queryEvaluator.deleteQuery(path, query);
    }

    public boolean hasQuery(Path path) {
        return queryEvaluator.hasQuery(path);
    }

    public void executeDisconnectEvents() {
        for (HeliumEvent event : disconnectEvents) {
            Authorizator.get().check(Operation.WRITE, auth, Path.of(event.getPath()), event.getPayload(), (Boolean event1) -> {
                if (event1) {
                    vertx.eventBus().send(event.getType().eventBus, event);
                    container.logger().trace("authorized: " + event);
                } else {
                    container.logger().warn("not authorized: " + event);
                }
            });
        }
    }

    public void send(String msg) {
        socket.write(new Buffer(msg));
    }

    public void syncPath(Path path) {
        Node node;
        if (MapDbService.get().exists(path)) {
            node = MapDbService.get().of(path);
        } else {
            node = MapDbService.get().of(path.parent());
        }

        node.keys().stream().filter(childNodeKey -> !Strings.isNullOrEmpty(childNodeKey)).forEach(childNodeKey -> {
            Object object = node.get(childNodeKey);
            boolean hasChildren = (object instanceof Node) && ((Node) object).hasChildren();
            int numChildren = (object instanceof Node) ? ((Node) object).length() : 0;
            if (object != null) {
                fireChildAdded(childNodeKey, path, path.parent(), object, hasChildren,
                        numChildren);
            }
        });
    }


    public void syncPathWithQuery(Path path, WebsocketEndpoint handler,
                                  QueryEvaluator queryEvaluator, String query) {
        Node node;
        if (MapDbService.get().exists(path)) {
            node = MapDbService.get().of(path);
        } else {
            node = MapDbService.get().of(path.parent());
        }

        for (String childNodeKey : node.keys()) {
            Object object = node.get(childNodeKey);
            if (queryEvaluator.evaluateQueryOnValue(object, query)) {
                if (object != null) {
                    handler.fireQueryChildAdded(path, object);
                }
            }
        }
    }

    public void syncPropertyValue(Path path) {
        Node node = MapDbService.get().of(path.parent());
        String childNodeKey = path.lastElement();
        if (node.has(path.lastElement())) {
            Object object = node.get(path.lastElement());
            fireValue(childNodeKey, path, path.parent(), object
            );
        } else {
            fireValue(childNodeKey, path, path.parent(), "");
        }
    }

}


