package com.intellectualsites.web.core;

import com.intellectualsites.web.config.ConfigVariableProvider;
import com.intellectualsites.web.config.ConfigurationFile;
import com.intellectualsites.web.config.YamlConfiguration;
import com.intellectualsites.web.events.Event;
import com.intellectualsites.web.events.EventManager;
import com.intellectualsites.web.events.defaultEvents.ShutdownEvent;
import com.intellectualsites.web.events.defaultEvents.StartupEvent;
import com.intellectualsites.web.object.*;
import com.intellectualsites.web.object.syntax.*;
import com.intellectualsites.web.util.*;
import com.intellectualsites.web.views.*;
import org.apache.commons.io.output.TeeOutputStream;
import ro.fortsoft.pf4j.DefaultPluginManager;
import ro.fortsoft.pf4j.PluginManager;
import sun.misc.Signal;
import sun.misc.SignalHandler;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;

/**
 * The core server
 * <p>
 * TODO: Make an interface implementation
 *
 * @author Citymonstret
 */
public class Server {

    /**
     * The logging prefix
     */
    public static final String PREFIX = "Web";
    private static Server instance;
    private final int port;
    /**
     * Is the server stopping?
     */
    public boolean stopping;
    /**
     * The Crush syntax particles
     */
    public Set<Syntax> syntaxes;
    /**
     * The folder from which everything is based
     */
    public File coreFolder;
    protected ViewManager viewManager;
    protected Collection<ProviderFactory> providers;
    private boolean started, standalone;
    private ServerSocket socket;
    private SessionManager sessionManager;
    private String hostName;
    private boolean ipv4;
    private boolean mysqlEnabled;
    private String mysqlHost;
    private Integer mysqlPort;
    private String mysqlUser;
    private String mysqlPass;
    private String mysqlDB;
    private int bufferIn, bufferOut;
    private ConfigurationFile configViews;
    private Map<String, Class<? extends View>> viewBindings;
    private EventCaller eventCaller;
    private PluginManager pluginManager;
    private MySQLConnManager mysqlConnManager;

    {
        viewBindings = new HashMap<>();
        providers = new ArrayList<>();
    }

    /**
     * Constructor
     *
     * @param standalone Should the server run async?
     */
    protected Server(boolean standalone) {
        instance = this;

        this.standalone = standalone;
        addViewbinding("html", HTMLView.class);
        addViewbinding("css", CSSView.class);
        addViewbinding("javascript", JSView.class);
        addViewbinding("less", LessView.class);
        addViewbinding("img", ImgView.class);
        addViewbinding("download", DownloadView.class);
        addViewbinding("redirect", RedirectView.class);

        this.coreFolder = new File("./");
        {
            Signal.handle(new Signal("INT"), new SignalHandler() {
                @Override
                public void handle(Signal signal) {
                    if (signal.toString().equals("SIGINT")) {
                        stop();
                    }
                }
            });

            InputThread thread = new InputThread(this);
            thread.start();
        }

        File logFolder = new File(coreFolder, "log");
        if (!logFolder.exists()) {
            if (!logFolder.mkdirs()) {
                log("Couldn't create the log folder");
            }
        }
        try {
            FileUtils.addToZip(new File(logFolder, "old.zip"), logFolder.listFiles(new FilenameFilter() {
                @Override
                public boolean accept(File dir, String name) {
                    return name.endsWith(".txt");
                }
            }), true);
            FileOutputStream fos = new FileOutputStream(new File(logFolder, TimeUtil.getTimeStamp(TimeUtil.LogFileFormat) + ".txt"));
            TeeOutputStream out = new TeeOutputStream(System.out, fos);
            PrintStream ps = new PrintStream(out);
            System.setOut(ps);
        } catch (final Exception e) {
            e.printStackTrace();
        }

        // The configuration files that handles
        // the server settings, quite clear.
        ConfigurationFile configServer;
        try {
            configServer = new YamlConfiguration("server", new File(new File(coreFolder, "config"), "server.yml"));
            configServer.loadFile();
            configServer.setIfNotExists("port", 80);
            configServer.setIfNotExists("hostname", "localhost");
            configServer.setIfNotExists("buffer.in", 1024 * 1024); // 16 mb
            configServer.setIfNotExists("buffer.out", 1024 * 1024);
            configServer.setIfNotExists("ipv4", false);
            configServer.setIfNotExists("mysql-enabled", false);
            configServer.setIfNotExists("mysql-host", "localhost");
            configServer.setIfNotExists("mysql-port", "3306");
            configServer.setIfNotExists("mysql-db", "database");
            configServer.setIfNotExists("mysql-user", "changeme");
            configServer.setIfNotExists("mysql-pass", "password");
            configServer.saveFile();
        } catch (final Exception e) {
            throw new RuntimeException("Couldn't load in the config file...", e);
        }

        this.port = configServer.get("port");
        this.hostName = configServer.get("hostname");
        this.bufferIn = configServer.get("buffer.in");
        this.bufferOut = configServer.get("buffer.out");
        this.ipv4 = configServer.get("ipv4");
        this.mysqlEnabled = configServer.get("mysql-enabled");
        this.mysqlHost = configServer.get("mysql-host");
        this.mysqlPort = configServer.get("mysql-port");
        this.mysqlDB = configServer.get("mysql-db");
        this.mysqlUser = configServer.get("mysql-user");
        this.mysqlPass = configServer.get("mysql-pass");

        this.started = false;
        this.stopping = false;

        this.viewManager = new ViewManager();
        this.sessionManager = new SessionManager(this);

        this.mysqlConnManager = new MySQLConnManager(mysqlHost, mysqlPort, mysqlDB, mysqlUser, mysqlPass);

        try {
            configViews = new YamlConfiguration("views", new File(new File(coreFolder, "config"), "views.yml"));
            configViews.loadFile();
            // These are the default views
            Map<String, Object> views = new HashMap<>();
            // HTML View
            Map<String, Object> view = new HashMap<>();
            view.put("filter", "(\\/)([A-Za-z0-9]*)(.html)?");
            view.put("type", "html");
            views.put("html", view);
            // CSS View
            view = new HashMap<>();
            view.put("filter", "(\\/style\\/)([A-Za-z0-9]*)(.css)?");
            view.put("type", "css");
            views.put("css", view);
            configViews.setIfNotExists("views", views);
            configViews.saveFile();
        } catch (final Exception e) {
            throw new RuntimeException("Couldn't load in views");
        }

        // Setup the provider factories
        this.providers.add(this.sessionManager);
        this.providers.add(new ServerProvider());
        this.providers.add(ConfigVariableProvider.getInstance());
        this.providers.add(new PostProviderFactory());
        this.providers.add(new MetaProvider());

        // Setup the crush syntaxes
        this.syntaxes = new LinkedHashSet<>();
        syntaxes.add(new Include());
        syntaxes.add(new Comment());
        syntaxes.add(new MetaBlock());
        syntaxes.add(new IfStatement());
        syntaxes.add(new ForEachBlock());
        syntaxes.add(new Variable());
    }

    /**
     * Get THE instance of the server
     *
     * @return this, literally... this!
     */
    public static Server getInstance() {
        return instance;
    }

    /**
     * Add a view binding to the engine
     *
     * @param key Binding Key
     * @param c   The View Class
     * @see #validateViews()
     */
    public void addViewbinding(final String key, final Class<? extends View> c) {
        viewBindings.put(key, c);
    }

    private void validateViews() {
        List<String> toRemove = new ArrayList<>();
        for (Map.Entry<String, Class<? extends View>> e : viewBindings.entrySet()) {
            Class<? extends View> vc = e.getValue();
            try {
                vc.getDeclaredConstructor(String.class, Map.class);
            } catch (final Exception ex) {
                log("Invalid view '%s' - Constructor has to be #(String.class, Map.class)", e.getKey());
                toRemove.add(e.getKey());
            }
        }
        for (String s : toRemove) {
            viewBindings.remove(s);
        }
    }

    protected void handleEvent(final Event event) {
        if (standalone) {
            // Use the internal event handler only
            // when running as a standalone app
            EventManager.getInstance().handle(event);
        } else {
            if (eventCaller != null) {
                eventCaller.callEvent(event);
            } else {
                // Oi... This ain't supposed to happen!
                log("STANDALONE = TRUE; but there is no alternate event caller set");
            }
        }
    }

    /**
     * Set the engine event caller
     *
     * @param caller New Event Caller
     */
    public void setEventCaller(final EventCaller caller) {
        this.eventCaller = caller;
    }

    /**
     * Add a provider factory to the core provider
     *
     * @param factory Factory to add
     */
    public void addProviderFactory(final ProviderFactory factory) {
        this.providers.add(factory);
    }

    /**
     * Load the plugins
     */
    private void loadPlugins() {
        File file = new File(coreFolder, "plugins");
        if (!file.exists()) {
            if (!file.mkdirs()) {
                log("Couldn't create %s - No plugins were loaded.", file);
                return;
            }
        }
        // The plugin manager is quite awesome... #proud
        // Anyhow, we should actually make it so it doesn't
        // automatically use the DefaultPluginManager
        pluginManager = new DefaultPluginManager(file);
        pluginManager.loadPlugins();
        pluginManager.loadPlugins();
        pluginManager.startPlugins();
    }

    /**
     * Start the web server
     *
     * @throws RuntimeException If anything goes wrong
     */
    @SuppressWarnings("ALL")
    public void start() throws RuntimeException {
        if (this.started) {
            throw new RuntimeException("Cannot start the server when it's already started...");
        }
        // Standalone = The server isn't intergrated
        // Thus we have to handle our own events
        if (standalone) {
            loadPlugins();
            EventManager.getInstance().bake();
        }
        handleEvent(new StartupEvent(this));
        // Start the MySQL connection manager.
        if (mysqlEnabled)
            mysqlConnManager.init();
        // Let's make sure the views are valid
        // (Has the right constructor)
        validateViews();
        log("Loading views...");
        Map<String, Map<String, Object>> views = configViews.get("views");
        for (final Map.Entry<String, Map<String, Object>> entry : views.entrySet()) {
            Map<String, Object> view = entry.getValue();
            String type = "html", filter = view.get("filter").toString();
            if (view.containsKey("type")) {
                type = view.get("type").toString();
            }
            Map<String, Object> options;
            if (view.containsKey("options")) {
                options = (HashMap<String, Object>) view.get("options");
            } else {
                options = new HashMap<>();
            }

            if (viewBindings.containsKey(type.toLowerCase())) {
                // Exists :d
                Class<? extends View> vc = viewBindings.get(type.toLowerCase());
                try {
                    View vv = vc.getDeclaredConstructor(String.class, Map.class).newInstance(filter, options);
                    this.viewManager.add(vv);
                } catch (final Exception e) {
                    e.printStackTrace();
                }
            }
        }
        // This dumps useless information into
        // this class :_:
        viewManager.dump(this);
        // IPv4 enabled - Should actually do something?
        if (this.ipv4) {
            log("ipv4 is enabled - Using IPv4 stack");
            System.setProperty("java.net.preferIPv4Stack", "true");
        }
        // This is where we start the web server
        this.started = true;
        log("Starting the web server on port %s", this.port);
        try {
            socket = new ServerSocket(this.port);
            log("Server started");
        } catch (final Exception e) {
            throw new RuntimeException("Couldn't start the server...", e);
        }
        // Some basic information
        log("Accepting connections on 'http://%s/'", hostName);
        log("Output buffer size: %skb | Input buffer size: %skb", bufferOut / 1024, bufferIn / 1024);
        // The main loop
        // "for (;;) {}" is faster than "while(true) {}"
        for (; ; ) {
            // This really isn't the proper way to do it :_:
            if (this.stopping) {
                // Stop the server gracefully :D
                log("Shutting down...");
                break;
            }
            try {
                tick();
            } catch (final Exception e) {
                log("Error in server ticking...");
                e.printStackTrace();
            }
        }
    }

    /**
     * Accept the socket and the information async
     *
     * @param remote Socket to send and read from
     */
    private void runAsync(final Socket remote) {
        new Thread() {
            @Override
            public void run() {
                // TODO Make configurable
                // Might get overly spammy
                log("Connection Accepted! Sending data...");
                StringBuilder rRaw = new StringBuilder();
                BufferedOutputStream out;
                BufferedReader input;
                Request r;
                try {
                    // Let's read from the socket :D
                    input = new BufferedReader(new InputStreamReader(remote.getInputStream()), bufferIn);
                    // And... write!
                    out = new BufferedOutputStream(remote.getOutputStream(), bufferOut);
                    String str;
                    while ((str = input.readLine()) != null && !str.equals("")) {
                        rRaw.append(str).append("|");
                    }
                    r = new Request(rRaw.toString(), remote);
                    // This is horrible, and it has to be redone!
                    // TODO: Remake!
                    if (r.getQuery().getMethod() == Method.POST) {
                        StringBuilder pR = new StringBuilder();
                        int cl = Integer.parseInt(r.getHeader("Content-Length").substring(1));
                        int c;
                        for (int i = 0; i < cl; i++) {
                            c = input.read();
                            pR.append((char) c);
                        }
                        r.setPostRequest(new PostRequest(pR.toString()));
                    }
                } catch (final Exception e) {
                    e.printStackTrace();
                    return;
                }
                // TODO: Make configurable
                log(r.buildLog());
                // Get the view
                View view = viewManager.match(r);
                // And generate the response
                Response response = view.generate(r);
                // Response headers
                response.getHeader().apply(out);
                Session session = sessionManager.getSession(r, out);
                if (session != null) {
                    // TODO: Session stuff
                    session.set("existing", "true");
                }
                byte[] bytes;
                if (response.isText()) {
                    // Let's make a copy of the content before
                    // getting the bytes
                    String content = response.getContent();
                    // Provider factories are fun, and so is the
                    // global map. But we also need the view
                    // specific ones!
                    Map<String, ProviderFactory> factories = new HashMap<>();
                    for (final ProviderFactory factory : providers) {
                        factories.put(factory.providerName().toLowerCase(), factory);
                    }
                    // Now make use of the view specific ProviderFactory
                    ProviderFactory z = view.getFactory(r);
                    if (z != null) {
                        factories.put(z.providerName().toLowerCase(), z);
                    }
                    // This is how the crush engine works.
                    // Quite simple, yet powerful!
                    for (Syntax syntax : syntaxes) {
                        if (syntax.matches(content)) {
                            content = syntax.handle(content, r, factories);
                        }
                    }
                    // Now, finally, let's get the bytes.
                    bytes = content.getBytes();
                } else {
                    bytes = response.getBytes();
                }
                try {
                    out.write(bytes);
                    out.flush();
                } catch (final Exception e) {
                    e.printStackTrace();
                }

                try {
                    input.close();
                } catch (final Exception e) {
                    e.printStackTrace();
                }
            }
        }.start();
    }

    /**
     * The core tick method
     * <p>
     * (runs the async socket accept)
     *
     * @see #runAsync(Socket)
     */
    protected void tick() {
        try {
            runAsync(socket.accept());
        } catch (final Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Log a message
     *
     * @param message String message to log
     * @param args    Arguments to be sent (replaces %s with arg#toString)
     */
    public void log(String message, final Object... args) {
        for (final Object a : args) {
            message = message.replaceFirst("%s", a.toString());
        }
        System.out.printf("[%s][%s] %s\n", PREFIX, TimeUtil.getTimeStamp(), message);
    }

    /**
     * Stop the web server
     */
    public synchronized void stop() {
        log("Shutting down!");
        EventManager.getInstance().handle(new ShutdownEvent(this));
        pluginManager.stopPlugins();
        System.exit(0);
    }
}
