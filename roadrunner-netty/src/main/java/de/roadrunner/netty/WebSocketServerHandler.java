/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package de.roadrunner.netty;

import static io.netty.handler.codec.http.HttpHeaders.isKeepAlive;
import static io.netty.handler.codec.http.HttpHeaders.setContentLength;
import static io.netty.handler.codec.http.HttpHeaders.Names.CONTENT_TYPE;
import static io.netty.handler.codec.http.HttpHeaders.Names.HOST;
import static io.netty.handler.codec.http.HttpMethod.GET;
import static io.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshakerFactory;
import io.netty.util.CharsetUtil;

import java.io.IOException;
import java.net.URL;
import java.util.Set;

import org.json.JSONException;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Sets;
import com.google.common.io.Resources;

import de.skiptag.roadrunner.Roadrunner;
import de.skiptag.roadrunner.disruptor.event.RoadrunnerEvent;
import de.skiptag.roadrunner.disruptor.event.RoadrunnerEventType;
import de.skiptag.roadrunner.messaging.RoadrunnerSender;
import de.skiptag.roadrunner.persistence.Path;

/**
 * Handles handshakes and messages
 */
public class WebSocketServerHandler extends SimpleChannelInboundHandler<Object>
	implements RoadrunnerSender {
    private static final org.slf4j.Logger logger = LoggerFactory.getLogger(WebSocketServerHandler.class.getName());

    private static final String WEBSOCKET_PATH = "/";

    private WebSocketServerHandshaker handshaker;

    private Set<Channel> channels = Sets.newHashSet();

    private Roadrunner roadrunner;

    public WebSocketServerHandler(Roadrunner roadrunner) {
	this.roadrunner = roadrunner;
	roadrunner.addSender(this);
    }

    @Override
    public void messageReceived(ChannelHandlerContext ctx, Object msg)
	    throws Exception {
	if (msg instanceof FullHttpRequest) {
	    handleHttpRequest(ctx, (FullHttpRequest) msg);
	} else if (msg instanceof WebSocketFrame) {
	    handleWebSocketFrame(ctx, (WebSocketFrame) msg);
	}
    }

    private void handleHttpRequest(ChannelHandlerContext ctx,
	    FullHttpRequest req) throws Exception {
	roadrunner.setBasePath(getWebSocketLocation(req));
	// Handle a bad request.
	if (!req.getDecoderResult().isSuccess()) {
	    sendHttpResponse(ctx, req, new DefaultFullHttpResponse(HTTP_1_1,
		    BAD_REQUEST));
	    return;
	}
	if ("/favicon.ico".equals(req.getUri())) {
	    FullHttpResponse res = new DefaultFullHttpResponse(HTTP_1_1,
		    HttpResponseStatus.NOT_FOUND);
	    sendHttpResponse(ctx, req, res);
	    return;
	}

	if (req.getMethod() == GET && "/roadrunner.js".equals(req.getUri())) {
	    FullHttpResponse res = new DefaultFullHttpResponse(HTTP_1_1, OK);
	    sendFile(res, "roadrunner.js");
	    sendHttpResponse(ctx, req, res);
	    return;
	}

	if (!req.headers().contains("Upgrade")) {
	    FullHttpResponse res = new DefaultFullHttpResponse(HTTP_1_1, OK);
	    handleRestCall(ctx, req, res);
	    sendHttpResponse(ctx, req, res);
	    return;
	}

	// Handshake
	WebSocketServerHandshakerFactory wsFactory = new WebSocketServerHandshakerFactory(
		getWebSocketLocation(req), null, false);
	handshaker = wsFactory.newHandshaker(req);
	if (handshaker == null) {
	    WebSocketServerHandshakerFactory.sendUnsupportedWebSocketVersionResponse(ctx.channel());
	} else {
	    handshaker.handshake(ctx.channel(), req);
	}
	channels.add(ctx.channel());
    }

    private void handleRestCall(ChannelHandlerContext ctx, FullHttpRequest req,
	    FullHttpResponse res) throws JSONException {

	String basePath = getHttpSocketLocation(req);
	Path nodePath = new Path(
		RoadrunnerEvent.extractPath(basePath, req.getUri()));
	if (req.getMethod() == GET) {
	    res.content().writeBytes(roadrunner.getPersistence()
		    .get(nodePath)
		    .toString()
		    .getBytes());
	} else if (req.getMethod() == HttpMethod.POST
		|| req.getMethod() == HttpMethod.PUT) {
	    String msg = new String(req.content().array());
	    roadrunner.handleEvent(RoadrunnerEventType.SET, req.getUri(), msg);
	} else if (req.getMethod() == HttpMethod.DELETE) {
	    roadrunner.handleEvent(RoadrunnerEventType.SET, req.getUri(), null);
	}
	res.headers().set(CONTENT_TYPE, "application/json; charset=UTF-8");
	setContentLength(res, res.content().readableBytes());
    }

    private void sendFile(FullHttpResponse res, String fileName)
	    throws IOException {
	URL resource = Thread.currentThread()
		.getContextClassLoader()
		.getResource(fileName);
	res.content().writeBytes(Resources.toByteArray(resource));
	res.headers()
		.set(CONTENT_TYPE, "application/javascript; charset=UTF-8");
	setContentLength(res, res.content().readableBytes());
    }

    private void handleWebSocketFrame(ChannelHandlerContext ctx,
	    WebSocketFrame frame) {

	// Check for closing frame
	if (frame instanceof CloseWebSocketFrame) {
	    handshaker.close(ctx.channel(), (CloseWebSocketFrame) frame.retain());
	    return;
	}
	if (frame instanceof PingWebSocketFrame) {
	    ctx.channel()
		    .write(new PongWebSocketFrame(frame.content().retain()));
	    return;
	}
	if (!(frame instanceof TextWebSocketFrame)) {
	    throw new UnsupportedOperationException(
		    String.format("%s frame types not supported", frame.getClass()
			    .getName()));
	}

	// Send the uppercase string back.
	String msg = ((TextWebSocketFrame) frame).text();
	roadrunner.handle(msg);

    }

    private static void sendHttpResponse(ChannelHandlerContext ctx,
	    FullHttpRequest req, FullHttpResponse res) {
	// Generate an error page if response getStatus code is not OK (200).
	if (res.getStatus().code() != 200) {
	    res.content().writeBytes(Unpooled.copiedBuffer(res.getStatus()
		    .toString(), CharsetUtil.UTF_8));
	    setContentLength(res, res.content().readableBytes());
	}

	// Send the response and close the connection if necessary.
	ChannelFuture f = ctx.channel().write(res);
	if (!isKeepAlive(req) || res.getStatus().code() != 200) {
	    f.addListener(ChannelFutureListener.CLOSE);
	}
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause)
	    throws Exception {
	cause.printStackTrace();
	ctx.close();
    }

    private static String getWebSocketLocation(FullHttpRequest req) {
	return "ws://" + req.headers().get(HOST) + WEBSOCKET_PATH;
    }

    private static String getHttpSocketLocation(FullHttpRequest req) {

	return "http://" + req.headers().get(HOST) + WEBSOCKET_PATH;
    }

    public void send(String msg) {
	for (Channel channel : channels) {
	    channel.write(new TextWebSocketFrame(msg));
	}
    }
}