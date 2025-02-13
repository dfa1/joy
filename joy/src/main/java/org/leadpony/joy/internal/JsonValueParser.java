/*
 * Copyright 2019 the Joy Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.leadpony.joy.internal;

import java.math.BigDecimal;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import javax.json.JsonArray;
import javax.json.JsonNumber;
import javax.json.JsonObject;
import javax.json.JsonString;
import javax.json.JsonValue;
import javax.json.JsonValue.ValueType;
import javax.json.stream.JsonLocation;

/**
 * A JSON parser type which parses in-memory JSON structures.
 *
 * @author leadpony
 */
class JsonValueParser extends AbstractJsonParser {

    private static final Scope GLOBAL_SCOPE = new GlobalScope();
    private Scope scope;

    private static final Event[] VALUE_EVENTS;

    static {
        VALUE_EVENTS = new Event[ValueType.values().length];
        VALUE_EVENTS[ValueType.ARRAY.ordinal()] = Event.START_ARRAY;
        VALUE_EVENTS[ValueType.OBJECT.ordinal()] = Event.START_OBJECT;
        VALUE_EVENTS[ValueType.STRING.ordinal()] = Event.VALUE_STRING;
        VALUE_EVENTS[ValueType.NUMBER.ordinal()] = Event.VALUE_NUMBER;
        VALUE_EVENTS[ValueType.TRUE.ordinal()] = Event.VALUE_TRUE;
        VALUE_EVENTS[ValueType.FALSE.ordinal()] = Event.VALUE_FALSE;
        VALUE_EVENTS[ValueType.NULL.ordinal()] = Event.VALUE_NULL;
    }

    JsonValueParser(JsonArray value) {
        this.scope = new ArrayScope(value);
    }

    JsonValueParser(JsonObject value) {
        this.scope = new ObjectScope(value);
    }

    @Override
    public boolean hasNext() {
        return scope != GLOBAL_SCOPE;
    }

    @Override
    public Event next() {
        Event event = scope.getEvent(this);
        setCurrentEvent(event);
        return event;
    }

    @Override
    public String getString() {
        Event event = getCurrentEvent();
        if (event == null) {
            throw newIllegalStateException("getString");
        }
        switch (event) {
        case KEY_NAME:
            return scope.getKey();
        case VALUE_STRING:
            JsonString string = (JsonString) scope.getValue();
            return string.getString();
        case VALUE_NUMBER:
            return scope.getValue().toString();
        default:
            throw newIllegalStateException("getString");
        }
    }

    @Override
    public boolean isIntegralNumber() {
        if (getCurrentEvent() != Event.VALUE_NUMBER) {
            throw newIllegalStateException("isIntegralNumber");
        }
        JsonNumber number = (JsonNumber) scope.getValue();
        return number.isIntegral();
    }

    @Override
    public int getInt() {
        if (getCurrentEvent() != Event.VALUE_NUMBER) {
            throw newIllegalStateException("getInt");
        }
        JsonNumber number = (JsonNumber) scope.getValue();
        return number.intValue();
    }

    @Override
    public long getLong() {
        if (getCurrentEvent() != Event.VALUE_NUMBER) {
            throw newIllegalStateException("getLong");
        }
        JsonNumber number = (JsonNumber) scope.getValue();
        return number.longValue();
    }

    @Override
    public BigDecimal getBigDecimal() {
        if (getCurrentEvent() != Event.VALUE_NUMBER) {
            throw newIllegalStateException("getBigDecimal");
        }
        JsonNumber number = (JsonNumber) scope.getValue();
        return number.bigDecimalValue();
    }

    @Override
    public JsonLocation getLocation() {
        return JsonLocationImpl.UNKNOWN;
    }

    @Override
    public JsonObject getObject() {
        if (getCurrentEvent() != Event.START_OBJECT) {
            throw newIllegalStateException("getObject");
        }
        final JsonObject object = (JsonObject) scope.getValue();
        skipObject();
        return object;
    }

    @Override
    public JsonValue getValue() {
        Event event = getCurrentEvent();
        if (event == null) {
            throw newIllegalStateException("getValue");
        }
        JsonValue value;
        switch (event) {
        case START_ARRAY:
            value = scope.getValue();
            skipArray();
            break;
        case START_OBJECT:
            value = scope.getValue();
            skipObject();
            break;
        case END_ARRAY:
        case END_OBJECT:
            throw newIllegalStateException("getValue");
        default:
            value = scope.getValue();
            break;
        }
        return value;
    }

    @Override
    public JsonArray getArray() {
        if (getCurrentEvent() != Event.START_ARRAY) {
            throw newIllegalStateException("getArray");
        }
        final JsonArray array = (JsonArray) scope.getValue();
        skipArray();
        return array;
    }

    /* As a AbstractJsonParser */

    @Override
    boolean isInCollection() {
        return getCurrentEvent() != null;
    }

    @Override
    boolean isInArray() {
        Event event = getCurrentEvent();
        if (event == Event.START_ARRAY || event == Event.END_ARRAY) {
            return true;
        }
        for (Scope scope = this.scope; scope != GLOBAL_SCOPE; scope = scope.getOuterScope()) {
            if (scope instanceof ArrayScope) {
                return true;
            }
        }
        return false;
    }

    @Override
    boolean isInObject() {
        Event event = getCurrentEvent();
        if (event == Event.START_OBJECT || event == Event.END_OBJECT) {
            return true;
        }
        for (Scope scope = this.scope; scope != GLOBAL_SCOPE; scope = scope.getOuterScope()) {
            if (scope instanceof ObjectScope) {
                return true;
            }
        }
        return false;
    }

    final void setScope(Scope scope) {
        this.scope = scope;
    }

    static Event getEventStarting(JsonValue value) {
        return VALUE_EVENTS[value.getValueType().ordinal()];
    }

    /**
     * A scope in a JSON document.
     *
     * @author leadpony
     */
    interface Scope {

        Event getEvent(JsonValueParser parser);

        default String getKey() {
            throw new UnsupportedOperationException();
        }

        JsonValue getValue();

        default Scope getOuterScope() {
            return this;
        }
    }

    /**
     * A global scope of the JSON document.
     *
     * @author leadpony
     */
    static class GlobalScope implements Scope {

        @Override
        public Event getEvent(JsonValueParser parser) {
            throw new NoSuchElementException(Message.PARSER_NO_EVENTS.toString());
        }

        @Override
        public JsonValue getValue() {
            throw new IllegalStateException();
        }
    }

    /**
     * A scope of a JSON structure.
     *
     * @author leadpony
     */
    abstract static class CollectionScope implements Scope {

        private final Scope outerScope;

        CollectionScope(Scope outerScope) {
            this.outerScope = outerScope;
        }

        @Override
        public final Scope getOuterScope() {
            return outerScope;
        }
    }

    /**
     * A scope of a JSON array.
     *
     * @author leadpony
     */
    static class ArrayScope extends CollectionScope {

        private final List<JsonValue> items;
        private final int length;
        private int index;
        private ArrayState state;
        private JsonValue currentValue;

        ArrayScope(JsonArray array) {
            super(GLOBAL_SCOPE);
            this.items = array;
            this.length = array.size();
            this.state = ArrayState.START;
            this.currentValue = array;
        }

        ArrayScope(JsonArray array, Scope outerScope) {
            super(outerScope);
            this.items = array;
            this.length = array.size();
            this.state = ArrayState.ITEM;
            this.currentValue = array;
        }

        @Override
        public Event getEvent(JsonValueParser parser) {
            return state.process(parser, this);
        }

        @Override
        public JsonValue getValue() {
            return currentValue;
        }

        final boolean hasNext() {
            return index < length;
        }

        final JsonValue getNext() {
            return items.get(index++);
        }

        final void setState(ArrayState state) {
            this.state = state;
        }

        final void setValue(JsonValue value) {
            this.currentValue = value;
        }

        /**
         * A state in a JSON array.
         *
         * @author leadpony
         */
        enum ArrayState {
            START() {
                @Override
                public Event process(JsonValueParser parser, ArrayScope scope) {
                    scope.setState(ITEM);
                    return Event.START_ARRAY;
                }
            },

            ITEM() {
                @Override
                public Event process(JsonValueParser parser, ArrayScope scope) {
                    Event event;
                    if (scope.hasNext()) {
                        JsonValue value = scope.getNext();
                        event = getEventStarting(value);
                        switch (event) {
                        case START_ARRAY:
                            parser.setScope(new ArrayScope((JsonArray) value, scope));
                            break;
                        case START_OBJECT:
                            parser.setScope(new ObjectScope((JsonObject) value, scope));
                            break;
                        default:
                            break;
                        }
                        scope.setValue(value);
                    } else {
                        event = Event.END_ARRAY;
                        scope.setValue(null);
                        parser.setScope(scope.getOuterScope());
                    }
                    return event;
                }
            };

            abstract Event process(JsonValueParser parser, ArrayScope scope);
        }
    }

    /**
     * A scope of a JSON object.
     *
     * @author leadpony
     */
    static class ObjectScope extends CollectionScope {

        private final Iterator<Map.Entry<String, JsonValue>> iterator;
        private ObjectState state;
        private String keyName;
        private JsonValue currentValue;

        ObjectScope(JsonObject object) {
            super(GLOBAL_SCOPE);
            this.iterator = object.entrySet().iterator();
            this.state = ObjectState.START;
            this.currentValue = object;
        }

        ObjectScope(JsonObject object, Scope outerScope) {
            super(outerScope);
            this.iterator = object.entrySet().iterator();
            this.state = ObjectState.KEY;
            this.currentValue = object;
        }

        @Override
        public Event getEvent(JsonValueParser parser) {
            return state.process(parser, this);
        }

        @Override
        public final String getKey() {
            return keyName;
        }

        @Override
        public final JsonValue getValue() {
            return currentValue;
        }

        final boolean fetchProperty() {
            if (iterator.hasNext()) {
                Map.Entry<String, JsonValue> entry = iterator.next();
                this.keyName = entry.getKey();
                this.currentValue = entry.getValue();
                return true;
            } else {
                this.keyName = null;
                this.currentValue = null;
                return false;
            }
        }

        final void setState(ObjectState state) {
            this.state = state;
        }

        /**
         * A state in a JSON object.
         *
         * @author leadpony
         */
        enum ObjectState {
            START() {
                @Override
                public Event process(JsonValueParser parser, ObjectScope scope) {
                    scope.setState(KEY);
                    return Event.START_OBJECT;
                }
            },

            KEY() {
                @Override
                public Event process(JsonValueParser parser, ObjectScope scope) {
                    if (scope.fetchProperty()) {
                        scope.setState(VALUE);
                        return Event.KEY_NAME;
                    } else {
                        parser.setScope(scope.getOuterScope());
                        return Event.END_OBJECT;
                    }
                }
            },

            VALUE() {
                @Override
                public Event process(JsonValueParser parser, ObjectScope scope) {
                    JsonValue value = scope.getValue();
                    Event event = getEventStarting(value);
                    switch (event) {
                    case START_ARRAY:
                        parser.setScope(new ArrayScope((JsonArray) value, scope));
                        break;
                    case START_OBJECT:
                        parser.setScope(new ObjectScope((JsonObject) value, scope));
                        break;
                    default:
                        break;
                    }
                    scope.setState(KEY);
                    return event;
                }
            };

            abstract Event process(JsonValueParser parser, ObjectScope scope);
        }
    }
}
