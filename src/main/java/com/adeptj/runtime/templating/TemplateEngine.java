/*
###############################################################################
#                                                                             # 
#    Copyright 2016, AdeptJ (http://www.adeptj.com)                           #
#                                                                             #
#    Licensed under the Apache License, Version 2.0 (the "License");          #
#    you may not use this file except in compliance with the License.         #
#    You may obtain a copy of the License at                                  #
#                                                                             #
#        http://www.apache.org/licenses/LICENSE-2.0                           #
#                                                                             #
#    Unless required by applicable law or agreed to in writing, software      #
#    distributed under the License is distributed on an "AS IS" BASIS,        #
#    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. #
#    See the License for the specific language governing permissions and      #
#    limitations under the License.                                           #
#                                                                             #
###############################################################################
*/

package com.adeptj.runtime.templating;

import com.adeptj.runtime.common.Times;
import com.adeptj.runtime.config.Configs;
import com.typesafe.config.Config;
import org.slf4j.LoggerFactory;
import org.trimou.Mustache;
import org.trimou.engine.MustacheEngine;
import org.trimou.engine.MustacheEngineBuilder;
import org.trimou.engine.locator.ClassPathTemplateLocator;
import org.trimou.handlebars.i18n.ResourceBundleHelper;

import static com.adeptj.runtime.common.Constants.CONTENT_TYPE_HTML_UTF8;
import static com.adeptj.runtime.common.Constants.UTF8;
import static org.trimou.engine.config.EngineConfigurationKey.DEFAULT_FILE_ENCODING;
import static org.trimou.engine.config.EngineConfigurationKey.END_DELIMITER;
import static org.trimou.engine.config.EngineConfigurationKey.START_DELIMITER;
import static org.trimou.engine.config.EngineConfigurationKey.TEMPLATE_CACHE_ENABLED;
import static org.trimou.engine.config.EngineConfigurationKey.TEMPLATE_CACHE_EXPIRATION_TIMEOUT;
import static org.trimou.handlebars.i18n.ResourceBundleHelper.Format.MESSAGE;

/**
 * Trimou based TemplateEngine for rendering the HTML templates.
 *
 * @author Rakesh.Kumar, AdeptJ.
 */
public enum TemplateEngine {

    INSTANCE;

    private static final String TEMPLATE_ENGINE_INIT_MSG = "TemplateEngine initialized in [{}] ms!!";

    private static final String RB_HELPER_NAME = "msg";

    private static final String KEY_RB_BASE_NAME = "resourceBundleBasename";

    private static final String KEY_START_DELIMITER = "startDelimiter";

    private static final String KEY_END_DELIMITER = "endDelimiter";

    private static final String KEY_CACHE_ENABLED = "cacheEnabled";

    private static final String KEY_CACHE_EXPIRATION = "cacheExpiration";

    private static final String KEY_TEMPLATE_LOCATOR_PRIORITY = "templateLocatorPriority";

    private static final String KEY_PREFIX = "prefix";

    private static final String KEY_SUFFIX = "suffix";

    private final MustacheEngine mustacheEngine;

    TemplateEngine() {
        long startTime = System.nanoTime();
        Config config = Configs.of().trimou();
        this.mustacheEngine = MustacheEngineBuilder.newBuilder()
                .registerHelper(RB_HELPER_NAME, new ResourceBundleHelper(config.getString(KEY_RB_BASE_NAME), MESSAGE))
                .addTemplateLocator(ClassPathTemplateLocator.builder()
                        .setPriority(config.getInt(KEY_TEMPLATE_LOCATOR_PRIORITY))
                        .setRootPath(config.getString(KEY_PREFIX))
                        .setSuffix(config.getString(KEY_SUFFIX))
                        .setScanClasspath(false)
                        .build())
                .addTemplateLocator(BundleClassPathTemplateLocatorHolder.getInstance().getTemplateLocator())
                .setProperty(START_DELIMITER, config.getString(KEY_START_DELIMITER))
                .setProperty(END_DELIMITER, config.getString(KEY_END_DELIMITER))
                .setProperty(DEFAULT_FILE_ENCODING, UTF8)
                .setProperty(TEMPLATE_CACHE_ENABLED, config.getBoolean(KEY_CACHE_ENABLED))
                .setProperty(TEMPLATE_CACHE_EXPIRATION_TIMEOUT, config.getInt(KEY_CACHE_EXPIRATION))
                .build();
        LoggerFactory.getLogger(this.getClass()).info(TEMPLATE_ENGINE_INIT_MSG, Times.elapsedMillis(startTime));
    }

    /**
     * Renders the template contained by the {@link TemplateEngineContext#getTemplate()}
     *
     * @param context the TemplateEngine context
     * @throws TemplateProcessingException if there was some issue while rendering the template.
     */
    public void render(TemplateEngineContext context) {
        try {
            // Making sure the Content-Type will always be text/html;charset=UTF-8.
            context.getResponse().setContentType(CONTENT_TYPE_HTML_UTF8);
            Mustache mustache = this.mustacheEngine.getMustache(context.getTemplate());
            mustache.render(context.getResponse().getWriter(), context.getTemplateData());
        } catch (Exception ex) { // NOSONAR
            throw new TemplateProcessingException(ex);
        }
    }

    public MustacheEngine getMustacheEngine() {
        return this.mustacheEngine;
    }

    public static TemplateEngine getInstance() {
        return INSTANCE;
    }
}
