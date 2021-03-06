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

package io.helium.event.changelog;

import io.helium.common.Path;
import org.vertx.java.core.json.JsonArray;

public class ChangeLog extends JsonArray{

    public ChangeLog(JsonArray body) {
        super(body.toArray());
    }

    public void addChildAddedLogEntry(String name, Path path, Path parent, Object value, long numChildren) {
        add(new ChildAdded(name, path, parent, value, numChildren));
    }

    public void addChildChangedLogEntry(String name, Path path, Path parent, Object value, long numChildren) {
        if (name != null) {
            add(new ChildChanged(name, path, parent, value, numChildren));
        }
    }

    public void addValueChangedLogEntry(String name, Path path, Path parent, Object value) {
        if(value != null)
            add(new ValueChanged(name, path, parent, value));
    }

    public void addChildDeletedLogEntry(Path path, String name, Object value) {
        add(new ChildDeleted(path, name, value));
    }

    public static ChangeLog of(JsonArray body) {
        return new ChangeLog(body);
    }
}
