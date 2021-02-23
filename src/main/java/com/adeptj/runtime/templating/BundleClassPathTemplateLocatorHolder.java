package com.adeptj.runtime.templating;

public enum BundleClassPathTemplateLocatorHolder {

    INSTANCE;

    private BundleClassPathTemplateLocator templateLocator;

    public BundleClassPathTemplateLocator getTemplateLocator() {
        return templateLocator;
    }

    public void setTemplateLocator(BundleClassPathTemplateLocator templateLocator) {
        this.templateLocator = templateLocator;
    }

    public static BundleClassPathTemplateLocatorHolder getInstance() {
        return INSTANCE;
    }
}
