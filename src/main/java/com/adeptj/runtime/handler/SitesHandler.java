package com.adeptj.runtime.handler;

import com.adeptj.runtime.common.BundleContextHolder;
import com.adeptj.runtime.templating.BundleClassPathTemplateLocator;
import com.adeptj.runtime.templating.TemplateEngine;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.ResponseCodeHandler;
import io.undertow.util.PathTemplateMatch;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.trimou.Mustache;
import org.trimou.engine.MustacheEngine;

import java.util.Map;

public class SitesHandler implements HttpHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(BundleClassPathTemplateLocator.class);

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {
        LOGGER.info("in SitesHandler!!");
        Map<String, String> parameters = exchange.getAttachment(PathTemplateMatch.ATTACHMENT_KEY).getParameters();
        MustacheEngine mustacheEngine = TemplateEngine.getInstance().getMustacheEngine();
        Mustache mustache = mustacheEngine.getMustache(parameters.get("site") + "/" + parameters.get("*"));
        if (mustache != null) {
            BundleContext bundleContext = BundleContextHolder.getInstance().getBundleContext();
            ServiceReference<?> references = bundleContext
                    .getServiceReference("com.adeptj.modules.commons.crypto.UserService");
            Object service = bundleContext.getService(references);
            exchange.getResponseSender().send(mustache.render(service));
            exchange.endExchange();
            bundleContext.ungetService(references);
            return;
        }
        ResponseCodeHandler.HANDLE_404.handleRequest(exchange);
    }
}
