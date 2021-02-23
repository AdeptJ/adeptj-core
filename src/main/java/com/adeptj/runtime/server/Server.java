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

package com.adeptj.runtime.server;

import ch.qos.logback.classic.ViewStatusMessagesServlet;
import com.adeptj.runtime.common.DefaultExecutorService;
import com.adeptj.runtime.common.Environment;
import com.adeptj.runtime.common.IOUtils;
import com.adeptj.runtime.common.Lifecycle;
import com.adeptj.runtime.common.LogbackManagerHolder;
import com.adeptj.runtime.common.SslContextFactory;
import com.adeptj.runtime.common.Times;
import com.adeptj.runtime.config.Configs;
import com.adeptj.runtime.core.RuntimeInitializer;
import com.adeptj.runtime.exception.RuntimeInitializationException;
import com.adeptj.runtime.handler.HealthCheckHandler;
import com.adeptj.runtime.handler.ServletInitialHandlerWrapper;
import com.adeptj.runtime.handler.SetHeadersHandler;
import com.adeptj.runtime.handler.SitesHandler;
import com.adeptj.runtime.osgi.FrameworkLauncher;
import com.adeptj.runtime.predicate.ContextPathPredicate;
import com.adeptj.runtime.servlet.AdminServlet;
import com.adeptj.runtime.servlet.ErrorServlet;
import com.adeptj.runtime.websocket.ServerLogsWebSocket;
import com.typesafe.config.Config;
import io.undertow.Handlers;
import io.undertow.Undertow;
import io.undertow.Undertow.Builder;
import io.undertow.server.DefaultByteBufferPool;
import io.undertow.server.HttpHandler;
import io.undertow.server.handlers.AllowedMethodsHandler;
import io.undertow.server.handlers.BlockingHandler;
import io.undertow.server.handlers.GracefulShutdownHandler;
import io.undertow.server.handlers.PathHandler;
import io.undertow.server.handlers.PathTemplateHandler;
import io.undertow.server.handlers.PredicateHandler;
import io.undertow.server.handlers.RedirectHandler;
import io.undertow.server.handlers.RequestBufferingHandler;
import io.undertow.server.handlers.RequestLimit;
import io.undertow.server.handlers.RequestLimitingHandler;
import io.undertow.servlet.Servlets;
import io.undertow.servlet.api.CrawlerSessionManagerConfig;
import io.undertow.servlet.api.DeploymentInfo;
import io.undertow.servlet.api.DeploymentManager;
import io.undertow.servlet.api.ErrorPage;
import io.undertow.servlet.api.SecurityConstraint;
import io.undertow.servlet.api.ServletContainerInitializerInfo;
import io.undertow.servlet.api.ServletInfo;
import io.undertow.servlet.api.ServletSessionConfig;
import io.undertow.util.HttpString;
import io.undertow.websockets.jsr.WebSocketDeploymentInfo;
import org.apache.commons.lang3.StringUtils;
import org.h2.mvstore.MVMap;
import org.h2.mvstore.MVStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.bridge.SLF4JBridgeHandler;
import org.xnio.OptionMap;
import org.xnio.Xnio;
import org.xnio.XnioWorker;

import javax.net.ssl.SSLContext;
import javax.servlet.MultipartConfigElement;
import java.io.IOException;
import java.io.InputStream;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.channels.ServerSocketChannel;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.adeptj.runtime.common.Constants.ADMIN_LOGIN_URI;
import static com.adeptj.runtime.common.Constants.BANNER_TXT;
import static com.adeptj.runtime.common.Constants.DEPLOYMENT_NAME;
import static com.adeptj.runtime.common.Constants.DIR_ADEPTJ_RUNTIME;
import static com.adeptj.runtime.common.Constants.DIR_DEPLOYMENT;
import static com.adeptj.runtime.common.Constants.H2_MAP_ADMIN_CREDENTIALS;
import static com.adeptj.runtime.common.Constants.HEADER_SERVER;
import static com.adeptj.runtime.common.Constants.HEADER_X_POWERED_BY;
import static com.adeptj.runtime.common.Constants.KEY_ADMIN_SERVLET_PATH;
import static com.adeptj.runtime.common.Constants.KEY_ALLOWED_METHODS;
import static com.adeptj.runtime.common.Constants.KEY_ERROR_HANDLER_CODES;
import static com.adeptj.runtime.common.Constants.KEY_ERROR_HANDLER_PATH;
import static com.adeptj.runtime.common.Constants.KEY_HEADER_SERVER;
import static com.adeptj.runtime.common.Constants.KEY_HEADER_X_POWERED_BY;
import static com.adeptj.runtime.common.Constants.KEY_HOST;
import static com.adeptj.runtime.common.Constants.KEY_HTTP;
import static com.adeptj.runtime.common.Constants.KEY_LOGBACK_STATUS_SERVLET_PATH;
import static com.adeptj.runtime.common.Constants.KEY_MAX_CONCURRENT_REQUESTS;
import static com.adeptj.runtime.common.Constants.KEY_PORT;
import static com.adeptj.runtime.common.Constants.KEY_REQ_BUFF_MAX_BUFFERS;
import static com.adeptj.runtime.common.Constants.KEY_REQ_LIMIT_QUEUE_SIZE;
import static com.adeptj.runtime.common.Constants.KEY_SYSTEM_CONSOLE_PATH;
import static com.adeptj.runtime.common.Constants.MV_CREDENTIALS_STORE;
import static com.adeptj.runtime.common.Constants.SERVER_CONF_FILE;
import static com.adeptj.runtime.common.Constants.SYS_PROP_SERVER_PORT;
import static com.adeptj.runtime.server.ServerConstants.ADMIN_SERVLET_NAME;
import static com.adeptj.runtime.server.ServerConstants.DEFAULT_WAIT_TIME;
import static com.adeptj.runtime.server.ServerConstants.ERROR_SERVLET_NAME;
import static com.adeptj.runtime.server.ServerConstants.KEY_AUTH_ROLES;
import static com.adeptj.runtime.server.ServerConstants.KEY_CHANGE_SESSION_ID_ON_LOGIN;
import static com.adeptj.runtime.server.ServerConstants.KEY_CONTEXT_PATH;
import static com.adeptj.runtime.server.ServerConstants.KEY_DEFAULT_ENCODING;
import static com.adeptj.runtime.server.ServerConstants.KEY_HEALTH_CHECK_HANDLER_PATH;
import static com.adeptj.runtime.server.ServerConstants.KEY_HTTPS;
import static com.adeptj.runtime.server.ServerConstants.KEY_HTTP_ONLY;
import static com.adeptj.runtime.server.ServerConstants.KEY_IGNORE_FLUSH;
import static com.adeptj.runtime.server.ServerConstants.KEY_INVALIDATE_SESSION_ON_LOGOUT;
import static com.adeptj.runtime.server.ServerConstants.KEY_KEYSTORE;
import static com.adeptj.runtime.server.ServerConstants.KEY_MULTIPART_FILE_LOCATION;
import static com.adeptj.runtime.server.ServerConstants.KEY_MULTIPART_FILE_SIZE_THRESHOLD;
import static com.adeptj.runtime.server.ServerConstants.KEY_MULTIPART_MAX_FILE_SIZE;
import static com.adeptj.runtime.server.ServerConstants.KEY_MULTIPART_MAX_REQUEST_SIZE;
import static com.adeptj.runtime.server.ServerConstants.KEY_PROTECTED_PATHS;
import static com.adeptj.runtime.server.ServerConstants.KEY_PROTECTED_PATHS_SECURED_FOR_METHODS;
import static com.adeptj.runtime.server.ServerConstants.KEY_SESSION_TIMEOUT;
import static com.adeptj.runtime.server.ServerConstants.KEY_USER_CREDENTIAL_MAPPING;
import static com.adeptj.runtime.server.ServerConstants.KEY_USE_CACHED_AUTH_MECHANISM;
import static com.adeptj.runtime.server.ServerConstants.KEY_WORKER_OPTIONS;
import static com.adeptj.runtime.server.ServerConstants.KEY_WORKER_TASK_CORE_THREADS;
import static com.adeptj.runtime.server.ServerConstants.KEY_WORKER_TASK_MAX_THREADS;
import static com.adeptj.runtime.server.ServerConstants.KEY_WS_BUFFER_SIZE;
import static com.adeptj.runtime.server.ServerConstants.KEY_WS_IO_THREADS;
import static com.adeptj.runtime.server.ServerConstants.KEY_WS_TASK_CORE_THREADS;
import static com.adeptj.runtime.server.ServerConstants.KEY_WS_TASK_MAX_THREADS;
import static com.adeptj.runtime.server.ServerConstants.KEY_WS_TCP_NO_DELAY;
import static com.adeptj.runtime.server.ServerConstants.KEY_WS_USE_DIRECT_BUFFER;
import static com.adeptj.runtime.server.ServerConstants.KEY_WS_WEB_SOCKET_OPTIONS;
import static com.adeptj.runtime.server.ServerConstants.LOGBACK_STATUS_SERVLET_NAME;
import static com.adeptj.runtime.server.ServerConstants.PWD_START_INDEX;
import static com.adeptj.runtime.server.ServerConstants.REALM;
import static com.adeptj.runtime.server.ServerConstants.SYS_PROP_CHECK_PORT;
import static com.adeptj.runtime.server.ServerConstants.SYS_PROP_ENABLE_HTTP2;
import static com.adeptj.runtime.server.ServerConstants.SYS_PROP_ENABLE_REQ_BUFF;
import static com.adeptj.runtime.server.ServerConstants.SYS_PROP_MAX_CONCUR_REQ;
import static com.adeptj.runtime.server.ServerConstants.SYS_PROP_REQ_BUFF_MAX_BUFFERS;
import static com.adeptj.runtime.server.ServerConstants.SYS_PROP_REQ_LIMIT_QUEUE_SIZE;
import static com.adeptj.runtime.server.ServerConstants.SYS_PROP_SERVER_HTTPS_PORT;
import static com.adeptj.runtime.server.ServerConstants.SYS_PROP_SESSION_TIMEOUT;
import static com.adeptj.runtime.server.ServerConstants.SYS_PROP_SHUTDOWN_WAIT_TIME;
import static com.adeptj.runtime.server.ServerConstants.SYS_PROP_SYS_TASK_THREAD_MULTIPLIER;
import static com.adeptj.runtime.server.ServerConstants.SYS_PROP_WORKER_TASK_THREAD_MULTIPLIER;
import static com.adeptj.runtime.server.ServerConstants.SYS_TASK_THREAD_MULTIPLIER;
import static com.adeptj.runtime.server.ServerConstants.WORKER_TASK_THREAD_MULTIPLIER;
import static io.undertow.websockets.jsr.WebSocketDeploymentInfo.ATTRIBUTE_NAME;
import static java.nio.file.StandardOpenOption.CREATE;
import static javax.servlet.http.HttpServletRequest.FORM_AUTH;
import static org.apache.commons.lang3.SystemUtils.USER_DIR;
import static org.xnio.Options.TCP_NODELAY;
import static org.xnio.Options.WORKER_IO_THREADS;
import static org.xnio.Options.WORKER_TASK_CORE_THREADS;
import static org.xnio.Options.WORKER_TASK_MAX_THREADS;

/**
 * Provisions the Undertow Web Server, start OSGi framework and much more.
 *
 * @author Rakesh.Kumar, AdeptJ
 */
public final class Server implements Lifecycle {

    private static final Logger LOGGER = LoggerFactory.getLogger(Server.class);

    private Undertow undertow;

    private DeploymentManager deploymentManager;

    private GracefulShutdownHandler rootHandler;

    /**
     * Bootstrap Undertow Server and OSGi Framework.
     */
    @Override
    public void start(Map<String, String> runtimeArgs) {
        LOGGER.debug("AdeptJ Runtime jvm args: {}", runtimeArgs);
        Config undertowConf = Configs.of().undertow();
        Config httpConf = undertowConf.getConfig(KEY_HTTP);
        int httpPort = this.handlePortAvailability(httpConf);
        LOGGER.info("Starting AdeptJ Runtime @port: [{}]", httpPort);
        this.printBanner();
        try {
            this.deploymentManager = Servlets.defaultContainer().addDeployment(this.deploymentInfo(undertowConf));
            this.deploymentManager.deploy();
            HttpHandler httpContinueReadHandler = this.deploymentManager.start();
            this.rootHandler = this.createHandlerChain(httpContinueReadHandler, undertowConf);
            Builder undertowBuilder = Undertow.builder();
            this.setWorkerOptions(undertowBuilder, undertowConf);
            new SocketOptions().setOptions(undertowBuilder, undertowConf);
            new ServerOptions().setOptions(undertowBuilder, undertowConf);
            this.undertow = this.addHttpsListener(undertowBuilder, undertowConf)
                    .addHttpListener(httpPort, httpConf.getString(KEY_HOST))
                    .setHandler(this.rootHandler)
                    .build();
            this.undertow.start();
            this.populateCredentialsStore(undertowConf);
        } catch (Exception ex) { // NOSONAR
            throw new RuntimeInitializationException(ex);
        }
        this.createServerConfFile();
    }

    /**
     * Does graceful server shutdown, this first cleans up the deployment and then stops Undertow server.
     */
    @Override
    public void stop() {
        long startTime = System.nanoTime();
        LOGGER.info("Stopping AdeptJ Runtime!!");
        try {
            this.gracefulShutdown();
            // Calls stop on LifecycleObjects - io.undertow.servlet.core.Lifecycle
            this.deploymentManager.stop();
            // Calls contextDestroyed on all registered ServletContextListener and performs other cleanup tasks.
            this.deploymentManager.undeploy();
            this.undertow.stop();
            DefaultExecutorService.getInstance().shutdown();
            LOGGER.info("AdeptJ Runtime stopped in [{}] ms!!", Times.elapsedMillis(startTime));
        } catch (Throwable ex) { // NOSONAR
            LOGGER.error("Exception while stopping AdeptJ Runtime!!", ex);
        } finally {
            SLF4JBridgeHandler.uninstall();
            // Let the Logback cleans up it's state.
            LogbackManagerHolder.getInstance().getLogbackManager().stopLogback();
        }
    }

    private void populateCredentialsStore(Config undertowConf) {
        try (MVStore store = MVStore.open(MV_CREDENTIALS_STORE)) {
            MVMap<String, String> credentials = store.openMap(H2_MAP_ADMIN_CREDENTIALS);
            // put the default password only when it is not set from web console.
            undertowConf.getObject(KEY_USER_CREDENTIAL_MAPPING)
                    .entrySet()
                    .stream()
                    .filter(entry -> StringUtils.isEmpty(credentials.get(entry.getKey())))
                    .forEach(entry -> credentials.put(entry.getKey(), ((String) entry.getValue().unwrapped())
                            .substring(PWD_START_INDEX)));
        }
    }

    private void gracefulShutdown() {
        try {
            this.rootHandler.shutdown();
            if (this.rootHandler.awaitShutdown(Long.getLong(SYS_PROP_SHUTDOWN_WAIT_TIME, DEFAULT_WAIT_TIME))) {
                LOGGER.debug("Completed remaining requests successfully!!");
            }
        } catch (InterruptedException ie) {
            LOGGER.error("Error while waiting for pending request(s) to complete!!", ie);
            // SONAR - "InterruptedException" should not be ignored
            // Can't really rethrow it as we are yet to stop the server and anyway it's a shutdown hook
            // and JVM itself will be shutting down shortly.
            Thread.currentThread().interrupt();
        }
    }

    private void printBanner() {
        try (InputStream stream = this.getClass().getResourceAsStream(BANNER_TXT)) {
            LOGGER.info(IOUtils.toString(stream)); // NOSONAR
        } catch (IOException ex) {
            // Just log it, its not critical.
            LOGGER.error("Exception while printing server banner!!", ex);
        }
    }

    private void createServerConfFile() {
        if (!Environment.isServerConfFileExists()) {
            try (InputStream stream = this.getClass().getResourceAsStream("/" + SERVER_CONF_FILE)) {
                Files.write(Paths.get(USER_DIR, DIR_ADEPTJ_RUNTIME, DIR_DEPLOYMENT, SERVER_CONF_FILE),
                        IOUtils.toBytes(stream), CREATE);
            } catch (IOException ex) {
                LOGGER.error("Exception while creating server conf file!!", ex);
            }
        }
    }

    private void setWorkerOptions(Builder builder, Config undertowConf) {
        if (Environment.isProd()) {
            long startTime = System.nanoTime();
            // Note : For a 16 core system, number of worker task core and max threads will be.
            // 1. core task thread: 128 (16[cores] * 8)
            // 2. max task thread: 128 * 2 = 256
            // Default settings would have set the following.
            // 1. core task thread: 128 (16[cores] * 8)
            // 2. max task thread: 128 (Same as core task thread)
            Config config = undertowConf.getConfig(KEY_WORKER_OPTIONS);
            // defaults to 64
            int cfgCoreTaskThreads = config.getInt(KEY_WORKER_TASK_CORE_THREADS);
            LOGGER.info("Configured worker task core threads: [{}]", cfgCoreTaskThreads);
            int availableProcessors = Runtime.getRuntime().availableProcessors();
            LOGGER.info("No. of CPU available: [{}]", availableProcessors);
            int calcCoreTaskThreads = availableProcessors *
                    Integer.getInteger(SYS_PROP_WORKER_TASK_THREAD_MULTIPLIER, WORKER_TASK_THREAD_MULTIPLIER);
            LOGGER.info("Calculated worker task core threads: [{}]", calcCoreTaskThreads);
            // defaults to double of [worker-task-core-threads] i.e 128
            int cfgMaxTaskThreads = config.getInt(KEY_WORKER_TASK_MAX_THREADS);
            LOGGER.info("Configured worker task max threads: [{}]", cfgMaxTaskThreads);
            int calcMaxTaskThreads = calcCoreTaskThreads *
                    Integer.getInteger(SYS_PROP_SYS_TASK_THREAD_MULTIPLIER, SYS_TASK_THREAD_MULTIPLIER);
            LOGGER.info("Calculated worker task max threads: [{}]", calcMaxTaskThreads);
            WorkerOptions options = new WorkerOptions();
            options.setOptions(builder, undertowConf);
            options.overrideOption(builder, WORKER_TASK_CORE_THREADS, Math.max(cfgCoreTaskThreads, calcCoreTaskThreads))
                    .overrideOption(builder, WORKER_TASK_MAX_THREADS, Math.max(cfgMaxTaskThreads, calcMaxTaskThreads));
            LOGGER.info("Undertow WorkerOptions configured in [{}] ms!!", Times.elapsedMillis(startTime));
        }
    }

    private Builder addHttpsListener(Builder builder, Config undertowConf) throws GeneralSecurityException {
        if (Boolean.getBoolean(SYS_PROP_ENABLE_HTTP2)) {
            Config httpsConf = undertowConf.getConfig(KEY_HTTPS);
            int httpsPort = Integer.getInteger(SYS_PROP_SERVER_HTTPS_PORT, httpsConf.getInt(KEY_PORT));
            if (!Environment.useProvidedKeyStore()) {
                System.setProperty("adeptj.rt.keyStore", httpsConf.getString(KEY_KEYSTORE));
                System.setProperty("adeptj.rt.keyStorePassword", httpsConf.getString("keyStorePwd"));
                System.setProperty("adeptj.rt.keyPassword", httpsConf.getString("keyPwd"));
                LOGGER.info("HTTP2 enabled @ port: [{}] using bundled KeyStore.", httpsPort);
            }
            SSLContext sslContext = SslContextFactory.newSslContext(httpsConf.getString("tlsVersion"));
            builder.addHttpsListener(httpsPort, httpsConf.getString(KEY_HOST), sslContext);
        }
        return builder;
    }

    private int handlePortAvailability(Config httpConf) {
        Integer port = Integer.getInteger(SYS_PROP_SERVER_PORT);
        if (port == null) {
            LOGGER.info("No port specified via system property: [{}], using default port: [{}]", SYS_PROP_SERVER_PORT,
                    httpConf.getInt(KEY_PORT));
            port = httpConf.getInt(KEY_PORT);
        }
        // Note: Shall we do it ourselves or let server do it later? Problem may arise in OSGi Framework provisioning
        // as it is being started already and another server start(from same location) will again start new OSGi
        // Framework which may interfere with already started OSGi Framework as the bundle deployment, heap dump,
        // OSGi configurations directory is common, this is unknown at this moment but just to be on safer side doing this.
        if (Boolean.getBoolean(SYS_PROP_CHECK_PORT) && !isPortAvailable(port)) {
            LOGGER.error("Port: [{}] already used, shutting down JVM!!", port);
            SLF4JBridgeHandler.uninstall();
            // Let the LOGBACK cleans up it's state.
            LogbackManagerHolder.getInstance().getLogbackManager().stopLogback();
            System.exit(-1); // NOSONAR
        }
        return port;
    }

    private boolean isPortAvailable(int port) {
        boolean portAvailable = false;
        ServerSocket socket = null;
        try (ServerSocketChannel socketChannel = ServerSocketChannel.open()) {
            socket = socketChannel.socket();
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(port));
            portAvailable = true;
        } catch (BindException ex) {
            LOGGER.error("BindException while acquiring port: [{}], cause:", port, ex);
        } catch (IOException ex) {
            LOGGER.error("IOException while acquiring port: [{}], cause:", port, ex);
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException ex) {
                    LOGGER.error("IOException while closing socket!!", ex);
                }
            }
        }
        return portAvailable;
    }

    /**
     * Chaining of Undertow {@link HttpHandler} instances as follows.
     * <p>
     * 1. GracefulShutdownHandler
     * 2. RequestLimitingHandler
     * 3. AllowedMethodsHandler
     * 4. PathHandler which resolves to either PredicateHandler or HealthCheckHandler
     * 5. RequestBufferingHandler if request buffering is enabled, wrapped in SetHeadersHandler
     * 5. And Finally HttpContinueReadHandler
     *
     * @param httpContinueReadHandler the {@link io.undertow.server.handlers.HttpContinueReadHandler}
     * @param cfg                     the undertow server config object.
     * @return GracefulShutdownHandler as the root handler
     */
    private GracefulShutdownHandler createHandlerChain(HttpHandler httpContinueReadHandler, Config cfg) {
        Map<HttpString, String> headers = new HashMap<>();
        headers.put(HttpString.tryFromString(HEADER_SERVER), cfg.getString(KEY_HEADER_SERVER));
        if (Environment.isDev()) {
            headers.put(HttpString.tryFromString(HEADER_X_POWERED_BY), cfg.getString(KEY_HEADER_X_POWERED_BY));
        }
        RedirectHandler contextHandler = Handlers.redirect(cfg.getString(KEY_SYSTEM_CONSOLE_PATH));
        HttpHandler headersHandler = Boolean.getBoolean(SYS_PROP_ENABLE_REQ_BUFF) ?
                new SetHeadersHandler(new RequestBufferingHandler(httpContinueReadHandler,
                        Integer.getInteger(SYS_PROP_REQ_BUFF_MAX_BUFFERS, cfg.getInt(KEY_REQ_BUFF_MAX_BUFFERS))), headers) :
                new SetHeadersHandler(httpContinueReadHandler, headers);
        ContextPathPredicate contextPathPredicate = new ContextPathPredicate(cfg.getString(KEY_CONTEXT_PATH));
        PredicateHandler predicateHandler = Handlers.predicate(contextPathPredicate, contextHandler, headersHandler);
        PathTemplateHandler templateHandler = new PathTemplateHandler(predicateHandler).add("/sites/{site}/*",
                new BlockingHandler(new SitesHandler()));
        PathHandler pathHandler = Handlers.path(templateHandler)
                .addPrefixPath(cfg.getString(KEY_HEALTH_CHECK_HANDLER_PATH), new HealthCheckHandler());
        AllowedMethodsHandler allowedMethodsHandler = new AllowedMethodsHandler(pathHandler, this.allowedMethods(cfg));
        int maxConcurrentRequests = Integer.getInteger(SYS_PROP_MAX_CONCUR_REQ, cfg.getInt(KEY_MAX_CONCURRENT_REQUESTS));
        int queueSize = Integer.getInteger(SYS_PROP_REQ_LIMIT_QUEUE_SIZE, cfg.getInt(KEY_REQ_LIMIT_QUEUE_SIZE));
        RequestLimit limit = new RequestLimit(maxConcurrentRequests, queueSize);
        RequestLimitingHandler requestLimitingHandler = Handlers.requestLimitingHandler(limit, allowedMethodsHandler);
        return Handlers.gracefulShutdown(requestLimitingHandler);
    }

    private Set<HttpString> allowedMethods(Config cfg) {
        return cfg.getStringList(KEY_ALLOWED_METHODS)
                .stream()
                .map(HttpString::tryFromString)
                .collect(Collectors.toSet());
    }

    private List<ErrorPage> errorPages(Config undertowConf) {
        return undertowConf.getIntList(KEY_ERROR_HANDLER_CODES)
                .stream()
                .map(code -> Servlets.errorPage(undertowConf.getString(KEY_ERROR_HANDLER_PATH), code))
                .collect(Collectors.toList());
    }

    private ServletContainerInitializerInfo sciInfo() {
        // Since the execution order of StartupAware instances matter that's why a LinkedHashSet.
        // FrameworkLauncher must always be executed first.
        Set<Class<?>> handlesTypes = new LinkedHashSet<>();
        handlesTypes.add(FrameworkLauncher.class);
        handlesTypes.add(DefaultStartupAware.class);
        return new ServletContainerInitializerInfo(RuntimeInitializer.class, handlesTypes);
    }

    private SecurityConstraint securityConstraint(Config cfg) {
        return Servlets.securityConstraint()
                .addRolesAllowed(cfg.getStringList(KEY_AUTH_ROLES))
                .addWebResourceCollection(Servlets.webResourceCollection()
                        .addUrlPatterns(cfg.getStringList(KEY_PROTECTED_PATHS))
                        .addHttpMethods(cfg.getStringList(KEY_PROTECTED_PATHS_SECURED_FOR_METHODS)));
    }

    private List<ServletInfo> servlets(Config undertowConf) {
        List<ServletInfo> servlets = new ArrayList<>();
        servlets.add(Servlets
                .servlet(ERROR_SERVLET_NAME, ErrorServlet.class)
                .addMapping(undertowConf.getString(KEY_ERROR_HANDLER_PATH))
                .setAsyncSupported(true));
        servlets.add(Servlets
                .servlet(ADMIN_SERVLET_NAME, AdminServlet.class)
                .addMapping(undertowConf.getString(KEY_ADMIN_SERVLET_PATH))
                .setAsyncSupported(true));
        servlets.add(Servlets.servlet(LOGBACK_STATUS_SERVLET_NAME, ViewStatusMessagesServlet.class)
                .addMapping(undertowConf.getString(KEY_LOGBACK_STATUS_SERVLET_PATH))
                .setAsyncSupported(true));
        return servlets;
    }

    private MultipartConfigElement defaultMultipartConfig(Config cfg) {
        return Servlets.multipartConfig(cfg.getString(KEY_MULTIPART_FILE_LOCATION),
                cfg.getLong(KEY_MULTIPART_MAX_FILE_SIZE),
                cfg.getLong(KEY_MULTIPART_MAX_REQUEST_SIZE),
                cfg.getInt(KEY_MULTIPART_FILE_SIZE_THRESHOLD));
    }

    private WebSocketDeploymentInfo webSocketDeploymentInfo(Config cfg) {
        Config wsOptions = cfg.getConfig(KEY_WS_WEB_SOCKET_OPTIONS);
        return new WebSocketDeploymentInfo().setDispatchToWorkerThread(true)
                .setWorker(this.webSocketWorker(wsOptions))
                .setBuffers(new DefaultByteBufferPool(wsOptions.getBoolean(KEY_WS_USE_DIRECT_BUFFER),
                        wsOptions.getInt(KEY_WS_BUFFER_SIZE)))
                .addEndpoint(ServerLogsWebSocket.class);
    }

    private XnioWorker webSocketWorker(Config wsOptions) {
        XnioWorker worker = null;
        try {
            worker = Xnio.getInstance().createWorker(OptionMap.builder()
                    .set(WORKER_IO_THREADS, wsOptions.getInt(KEY_WS_IO_THREADS))
                    .set(WORKER_TASK_CORE_THREADS, wsOptions.getInt(KEY_WS_TASK_CORE_THREADS))
                    .set(WORKER_TASK_MAX_THREADS, wsOptions.getInt(KEY_WS_TASK_MAX_THREADS))
                    .set(TCP_NODELAY, wsOptions.getBoolean(KEY_WS_TCP_NO_DELAY))
                    .getMap());
        } catch (IOException ex) {
            LOGGER.error("Can't create XnioWorker!!", ex);
        }
        return worker;
    }

    private int sessionTimeout(Config cfg) {
        return Integer.getInteger(SYS_PROP_SESSION_TIMEOUT, cfg.getInt(KEY_SESSION_TIMEOUT));
    }

    private ServletSessionConfig sessionConfig(Config cfg) {
        return new ServletSessionConfig().setHttpOnly(cfg.getBoolean(KEY_HTTP_ONLY));
    }

    private DeploymentInfo deploymentInfo(Config cfg) {
        return Servlets.deployment()
                .setDeploymentName(DEPLOYMENT_NAME)
                .setContextPath(cfg.getString(KEY_CONTEXT_PATH))
                .setClassLoader(this.getClass().getClassLoader())
                .addServletContainerInitializer(this.sciInfo())
                .setIgnoreFlush(cfg.getBoolean(KEY_IGNORE_FLUSH))
                .setDefaultEncoding(cfg.getString(KEY_DEFAULT_ENCODING))
                .setDefaultSessionTimeout(this.sessionTimeout(cfg))
                .setChangeSessionIdOnLogin(cfg.getBoolean(KEY_CHANGE_SESSION_ID_ON_LOGIN))
                .setInvalidateSessionOnLogout(cfg.getBoolean(KEY_INVALIDATE_SESSION_ON_LOGOUT))
                .setIdentityManager(new SimpleIdentityManager(cfg))
                .setUseCachedAuthenticationMechanism(cfg.getBoolean(KEY_USE_CACHED_AUTH_MECHANISM))
                .setLoginConfig(Servlets.loginConfig(FORM_AUTH, REALM, ADMIN_LOGIN_URI, ADMIN_LOGIN_URI))
                .addSecurityConstraint(this.securityConstraint(cfg))
                .addServlets(this.servlets(cfg))
                .addErrorPages(this.errorPages(cfg))
                .setDefaultMultipartConfig(this.defaultMultipartConfig(cfg))
                .addInitialHandlerChainWrapper(new ServletInitialHandlerWrapper())
                .addServletContextAttribute(ATTRIBUTE_NAME, this.webSocketDeploymentInfo(cfg))
                .setServletSessionConfig(this.sessionConfig(cfg))
                .setCrawlerSessionManagerConfig(new CrawlerSessionManagerConfig());
    }
}