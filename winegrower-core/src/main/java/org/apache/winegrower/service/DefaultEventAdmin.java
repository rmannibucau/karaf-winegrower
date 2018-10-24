/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.winegrower.service;

import static java.util.concurrent.TimeUnit.MINUTES;
import static org.osgi.service.event.TopicPermission.SUBSCRIBE;

import java.io.Closeable;
import java.util.Collection;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.osgi.framework.Bundle;
import org.osgi.framework.Filter;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventAdmin;
import org.osgi.service.event.EventHandler;
import org.osgi.service.event.TopicPermission;

// todo: timeout?
public class DefaultEventAdmin implements EventAdmin, Closeable {
    private final Collection<EventHandlerInstance> listeners;
    private final ExecutorService executor;

    public DefaultEventAdmin(final Collection<EventHandlerInstance> listeners,
                             final int poolSize) {
        this.listeners = listeners;

        final AtomicInteger counter = new AtomicInteger(1);
        this.executor = Executors.newFixedThreadPool(poolSize, r -> {
            final Thread t = new Thread(DefaultEventAdmin.class.getName() + "-" + counter.getAndIncrement());
            if (t.isDaemon()) {
                t.setDaemon(false);
            }
            if (t.getPriority() != Thread.NORM_PRIORITY) {
                t.setPriority(Thread.NORM_PRIORITY);
            }
            return t;
        });
    }

    @Override
    public void postEvent(final Event event) {
        executor.execute(() -> sendEvent(event));
    }

    @Override
    public void sendEvent(final Event event) {
        final TopicPermission permission = new TopicPermission(event.getTopic(), SUBSCRIBE);
        listeners.stream()
                 .filter(l -> l.topics == null || l.bundle.hasPermission(permission))
                 .filter(l -> l.matches(event))
                 .forEach(l -> l.handler.handleEvent(event));
    }

    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(1, MINUTES)) {
                executor.shutdownNow();
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static class EventHandlerInstance {
        private final Bundle bundle;
        private final EventHandler handler;
        private final String[] topics;
        private final Filter filter;

        public EventHandlerInstance(final Bundle bundle,
                                    final EventHandler handler,
                                    final String[] topics,
                                    final String eventFilter) {
            this.bundle = bundle;
            this.handler = handler;
            this.topics = topics;
            try {
                this.filter = eventFilter == null ? null : FrameworkUtil.createFilter(eventFilter);
            } catch (final InvalidSyntaxException e) {
                throw new IllegalArgumentException(e);
            }
        }

        public boolean matches(final Event event) {
            return (filter == null || event.matches(filter));
        }

        public EventHandler getHandler() {
            return handler;
        }

        public String[] getTopics() {
            return topics;
        }
    }
}
