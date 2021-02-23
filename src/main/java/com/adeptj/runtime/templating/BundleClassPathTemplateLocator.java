package com.adeptj.runtime.templating;

import org.apache.commons.lang3.StringUtils;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleEvent;
import org.osgi.util.tracker.BundleTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.trimou.engine.locator.TemplateLocator;
import org.trimou.exception.MustacheException;
import org.trimou.exception.MustacheProblem;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static org.osgi.framework.Bundle.ACTIVE;

public class BundleClassPathTemplateLocator extends BundleTracker<String> implements TemplateLocator {

    private static final Logger LOGGER = LoggerFactory.getLogger(BundleClassPathTemplateLocator.class);

    private final ConcurrentMap<String, Bundle> bundleBySite;

    public BundleClassPathTemplateLocator(BundleContext context) {
        super(context, ACTIVE, null);
        this.bundleBySite = new ConcurrentHashMap<>();
    }

    @Override
    public Reader locate(String templateId) {
        // path pattern /sites/spica/home.html
        String site = StringUtils.substringBefore(templateId, "/");
        Bundle bundle = this.bundleBySite.get(site);
        if (bundle == null) {
            return null;
        }
        String templateLocation = bundle.getHeaders().get("Template-Location");
        URL resource = bundle.getEntry(templateLocation + StringUtils.substringAfter(templateId, site));
        if (resource == null) {
            return null;
        }
        try {
            return new InputStreamReader(resource.openStream(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new MustacheException(MustacheProblem.TEMPLATE_LOADING_ERROR, e);
        }
    }

    @Override
    public String addingBundle(Bundle bundle, BundleEvent event) {
        String templateLocation = bundle.getHeaders().get("Template-Location");
        String siteName = bundle.getHeaders().get("Site-Name");
        if (StringUtils.isNoneEmpty(templateLocation, siteName)) {
            LOGGER.info("templateLocation: {}", templateLocation);
            LOGGER.info("siteName: {}", siteName);
            this.bundleBySite.put(siteName, bundle);
            return siteName;
        }
        return null;
    }

    @Override
    public void removedBundle(Bundle bundle, BundleEvent event, String siteName) {
        this.bundleBySite.remove(siteName);
    }
}
