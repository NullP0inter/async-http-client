/*
 * Copyright (c) 2014 AsyncHttpClient Project. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at
 *     http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package com.ning.http.client.providers.netty.pool;

import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.group.ChannelGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.providers.netty.Channels;
import com.ning.http.client.providers.netty.CleanupChannelGroup;
import com.ning.http.client.providers.netty.NettyAsyncHttpProvider;
import com.ning.http.client.providers.netty.NettyResponseFuture;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

public class ChannelManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(ChannelManager.class);

    private final ChannelPool channelPool;
    private final boolean maxTotalConnectionsEnabled;
    private final Semaphore freeChannels;
    private final ChannelGroup openChannels;
    private final int maxConnectionsPerHost;
    private final boolean maxConnectionsPerHostEnabled;
    private final ConcurrentHashMap<String, Semaphore> freeChannelsPerHost;
    private final ConcurrentHashMap<Integer, String> channelId2KeyPool;

    public ChannelManager(AsyncHttpClientConfig config, ChannelPool channelPool) {
        this.channelPool = channelPool;

        maxTotalConnectionsEnabled = config.getMaxTotalConnections() > 0;
        
        if (maxTotalConnectionsEnabled) {
            openChannels = new CleanupChannelGroup("asyncHttpClient") {
                @Override
                public boolean remove(Object o) {
                    boolean removed = super.remove(o);
                    if (removed) {
                        freeChannels.release();
                        if (maxConnectionsPerHostEnabled) {
                            String poolKey = channelId2KeyPool.remove(Channel.class.cast(o).getId());
                            if (poolKey != null) {
                                Semaphore freeChannelsForHost = freeChannelsPerHost.get(poolKey);
                                if (freeChannelsForHost != null)
                                    freeChannelsForHost.release();
                            }
                        }
                    }
                    return removed;
                }
            };
            freeChannels = new Semaphore(config.getMaxTotalConnections());
        } else {
            openChannels = new CleanupChannelGroup("asyncHttpClient");
            freeChannels = null;
        }

        maxConnectionsPerHost = config.getMaxConnectionPerHost();
        maxConnectionsPerHostEnabled = config.getMaxConnectionPerHost() > 0;
        
        if (maxConnectionsPerHostEnabled) {
            freeChannelsPerHost = new ConcurrentHashMap<String, Semaphore>();
            channelId2KeyPool = new ConcurrentHashMap<Integer, String>();
        } else {
            freeChannelsPerHost = null;
            channelId2KeyPool = null;
        }
    }

    public final void tryToOfferChannelToPool(ChannelHandlerContext ctx, boolean keepAlive, String poolKey) {
        Channel channel = ctx.getChannel();
        if (keepAlive && channel.isReadable()) {
            LOGGER.debug("Adding key: {} for channel {}", poolKey, channel);
            channelPool.offer(channel, poolKey);
            if (maxConnectionsPerHostEnabled)
                channelId2KeyPool.putIfAbsent(channel.getId(), poolKey);
            Channels.setDiscard(ctx);
        } else {
            // not offered
            closeChannel(ctx);
        }
    }

    public Channel poll(String uri) {
        return channelPool.poll(uri);
    }

    public boolean removeAll(Channel connection) {
        return channelPool.removeAll(connection);
    }

    private boolean tryAcquireGlobal() {
        return !maxTotalConnectionsEnabled || freeChannels.tryAcquire();
    }

    private Semaphore getFreeConnectionsForHost(String poolKey) {
        Semaphore freeConnections = freeChannelsPerHost.get(poolKey);
        if (freeConnections == null) {
            // lazy create the semaphore
            Semaphore newFreeConnections = new Semaphore(maxConnectionsPerHost);
            freeConnections = freeChannelsPerHost.putIfAbsent(poolKey, newFreeConnections);
            if (freeConnections == null)
                freeConnections = newFreeConnections;
        }
        return freeConnections;
    }
    
    private boolean tryAcquirePerHost(String poolKey) {
        return !maxConnectionsPerHostEnabled || getFreeConnectionsForHost(poolKey).tryAcquire();
    }
    
    public boolean preemptChannel(String poolKey) {
        return channelPool.isOpen() && tryAcquireGlobal() && tryAcquirePerHost(poolKey);
    }

    public void destroy() {
        channelPool.destroy();
        openChannels.close();
        
        for (Channel channel : openChannels) {
            ChannelHandlerContext ctx = channel.getPipeline().getContext(NettyAsyncHttpProvider.class);
            Object attachment = Channels.getAttachment(ctx);
            if (attachment instanceof NettyResponseFuture<?>) {
                NettyResponseFuture<?> future = (NettyResponseFuture<?>) attachment;
                future.cancelTimeouts();
            }
        }
    }

    public void closeChannel(final ChannelHandlerContext ctx) {
        removeAll(ctx.getChannel());
        Channels.setDiscard(ctx);

        Channel channel = ctx.getChannel();

        // The channel may have already been removed if a timeout occurred, and this method may be called just after.
        if (channel != null) {
            LOGGER.debug("Closing Channel {} ", channel);
            try {
                channel.close();
            } catch (Throwable t) {
                LOGGER.debug("Error closing a connection", t);
            }
            openChannels.remove(channel);
        }
    }

    public void abortChannelPreemption(String poolKey) {
        if (maxTotalConnectionsEnabled)
            freeChannels.release();
        if (maxConnectionsPerHostEnabled)
            getFreeConnectionsForHost(poolKey).release();
    }

    public void registerOpenChannel(Channel channel) {
        openChannels.add(channel);
    }
}
