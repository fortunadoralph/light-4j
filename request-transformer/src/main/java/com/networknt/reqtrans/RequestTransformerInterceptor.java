package com.networknt.reqtrans;

import com.networknt.handler.BuffersUtils;
import com.networknt.handler.MiddlewareHandler;
import com.networknt.handler.RequestInterceptor;
import com.networknt.httpstring.AttachmentConstants;
import com.networknt.rule.RuleConstants;
import com.networknt.rule.RuleEngine;
import com.networknt.rule.RuleLoaderStartupHook;
import com.networknt.utility.Constants;
import com.networknt.utility.ModuleRegistry;
import io.undertow.Handlers;
import io.undertow.connector.PooledByteBuffer;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.protocol.http.HttpContinue;
import io.undertow.util.Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xnio.Buffers;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Transforms the request body of an active request being processed.
 * This is executed by RequestInterceptorExecutionHandler.
 *
 * @author Kalev Gonvick
 *
 */
public class RequestTransformerInterceptor implements RequestInterceptor {
    static final Logger logger = LoggerFactory.getLogger(RequestTransformerInterceptor.class);
    static final String REQUEST_TRANSFORM = "request-transform";

    private RequestTransformerConfig config;
    private volatile HttpHandler next;
    private RuleEngine engine;

    public RequestTransformerInterceptor() {
        if(logger.isInfoEnabled()) logger.info("RequestTransformerHandler is loaded");
        config = RequestTransformerConfig.load();
    }

    @Override
    public HttpHandler getNext() {
        return next;
    }

    @Override
    public MiddlewareHandler setNext(HttpHandler next) {
        Handlers.handlerNotNull(next);
        this.next = next;
        return this;
    }

    @Override
    public boolean isEnabled() {
        return config.isEnabled();
    }

    @Override
    public void register() {
        ModuleRegistry.registerModule(RequestTransformerInterceptor.class.getName(), config.getMappedConfig(), null);
    }

    @Override
    public void reload() {
        config.reload();
    }

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {
        logger.info("RequestTransformerHandler.handleRequest is called.");
        if(engine == null) {
            engine = new RuleEngine(RuleLoaderStartupHook.rules, null);
        }
        String method = exchange.getRequestMethod().toString();
        if (!HttpContinue.requiresContinueResponse(exchange.getRequestHeaders())) {

            Map<String, Object> auditInfo = exchange.getAttachment(AttachmentConstants.AUDIT_INFO);
            // need to get the rule/rules to execute from the RuleLoaderStartupHook. First, get the endpoint.
            String endpoint = null;
            if(auditInfo != null) {
                endpoint = (String) auditInfo.get("endpoint");
            } else {
                endpoint = exchange.getRequestPath() + "@" + method.toString().toLowerCase();
            }
            // checked the RuleLoaderStartupHook to ensure it is loaded. If not, return an error to the caller.
            if(RuleLoaderStartupHook.endpointRules == null) {
                logger.error("RuleLoaderStartupHook endpointRules is null");
            }
            // get the rules (maybe multiple) based on the endpoint.
            Map<String, List> endpointRules = (Map<String, List>)RuleLoaderStartupHook.endpointRules.get(endpoint);
            if(endpointRules != null) {
                List<Map<String, Object>> requestTransformRules = endpointRules.get(REQUEST_TRANSFORM);
                if(requestTransformRules != null) {
                    boolean finalResult = true;
                    // call the rule engine to transform the request metadata or body. The input contains all the request elements
                    Map<String, Object> objMap = new HashMap<>();
                    objMap.put("auditInfo", auditInfo);
                    objMap.put("requestHeaders", exchange.getRequestHeaders());
                    objMap.put("responseHeaders", exchange.getRequestHeaders());
                    objMap.put("queryParameters", exchange.getQueryParameters());
                    objMap.put("pathParameters", exchange.getPathParameters());
                    objMap.put("method", method);
                    objMap.put("requestURL", exchange.getRequestURL());
                    objMap.put("requestURI", exchange.getRequestURI());
                    objMap.put("requestPath", exchange.getRequestPath());
                    if ((method.equalsIgnoreCase("post") || method.equalsIgnoreCase("put") || method.equalsIgnoreCase("patch")) && !exchange.isRequestComplete()) {
                        // This object contains the reference to the request data buffer. Any modification done to this will be reflected in the request.
                        PooledByteBuffer[] requestData = this.getBuffer(exchange);
                        String s = BuffersUtils.toString(requestData, StandardCharsets.UTF_8);
                        // Transform the request body with the rule engine.
                        if(logger.isDebugEnabled()) logger.debug("original request body = " + s);
                        objMap.put("requestBody", s);
                    }
                    Map<String, Object> result = null;
                    String ruleId = null;
                    // iterate the rules and execute them in sequence. Break only if one rule is successful.
                    for(Map<String, Object> ruleMap: requestTransformRules) {
                        ruleId = (String)ruleMap.get(Constants.RULE_ID);
                        result = engine.executeRule(ruleId, objMap);
                        boolean res = (Boolean)result.get(RuleConstants.RESULT);
                        if(!res) {
                            finalResult = false;
                            break;
                        }
                    }
                    if(finalResult) {
                        for(Map.Entry<String, Object> entry: result.entrySet()) {
                            if(logger.isTraceEnabled()) logger.trace("key = " + entry.getKey() + " value = " + entry.getValue());
                            // you can only update the response headers and response body in the transformation.
                            switch(entry.getKey()) {
                                case "requestPath":
                                    String requestPath = (String)result.get("requestPath");
                                    exchange.setRequestPath(requestPath);
                                    if(logger.isTraceEnabled()) logger.trace("requestPath is changed to " + requestPath);
                                    break;
                                case "requestURI":
                                    String requestURI = (String)result.get("requestURI");
                                    exchange.setRequestURI(requestURI);
                                    break;
                                case "requestBody":
                                    String s = (String)result.get("requestBody");
                                    ByteBuffer overwriteData = ByteBuffer.wrap(s.getBytes());
                                    PooledByteBuffer[] requestData = this.getBuffer(exchange);
                                    // Do the overwrite operation by copying our overwriteData to the source buffer pool.
                                    int pidx = 0;
                                    while (overwriteData.hasRemaining() && pidx < requestData.length) {
                                        ByteBuffer _dest;
                                        if (requestData[pidx] == null) {
                                            requestData[pidx] = exchange.getConnection().getByteBufferPool().allocate();
                                            _dest = requestData[pidx].getBuffer();
                                        } else {
                                            _dest = requestData[pidx].getBuffer();
                                            _dest.clear();
                                        }
                                        Buffers.copy(_dest, overwriteData);
                                        _dest.flip();
                                        pidx++;
                                    }
                                    while (pidx < requestData.length) {
                                        requestData[pidx] = null;
                                        pidx++;
                                    }

                                    // We need to update the content length.
                                    long length = 0;
                                    for (PooledByteBuffer dest : requestData) {
                                        if (dest != null) {
                                            length += dest.getBuffer().limit();
                                        }
                                    }
                                    exchange.getRequestHeaders().put(Headers.CONTENT_LENGTH, length);
                                    break;
                            }
                        }
                    }
                }
            }
        }
    }

    @Override
    public boolean isRequiredContent() {
        return config.isRequiredContent();
    }

    public PooledByteBuffer[] getBuffer(HttpServerExchange exchange) {
        PooledByteBuffer[] buffer = exchange.getAttachment(AttachmentConstants.BUFFERED_REQUEST_DATA_KEY);
        if (buffer == null) {
            throw new IllegalStateException("Request content is not available in exchange attachment as there is no interceptors.");
        }
        return buffer;
    }
}