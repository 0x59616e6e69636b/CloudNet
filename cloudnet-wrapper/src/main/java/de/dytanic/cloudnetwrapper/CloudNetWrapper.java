/*
 * Copyright (c) Tarek Hosni El Alaoui 2017
 */

package de.dytanic.cloudnetwrapper;

import de.dytanic.cloudnet.command.CommandManager;
import de.dytanic.cloudnet.lib.ConnectableAddress;
import de.dytanic.cloudnet.lib.NetworkUtils;
import de.dytanic.cloudnet.lib.network.NetDispatcher;
import de.dytanic.cloudnet.lib.network.NetworkConnection;
import de.dytanic.cloudnet.lib.network.auth.Auth;
import de.dytanic.cloudnet.lib.network.protocol.packet.PacketRC;
import de.dytanic.cloudnet.lib.server.ProxyGroup;
import de.dytanic.cloudnet.lib.server.ServerGroup;
import de.dytanic.cloudnet.lib.interfaces.Executeable;
import de.dytanic.cloudnet.lib.user.SimpledUser;
import de.dytanic.cloudnet.lib.utility.threading.Scheduler;
import de.dytanic.cloudnet.logging.CloudLogger;
import de.dytanic.cloudnet.logging.handler.ICloudLoggerHandler;
import de.dytanic.cloudnet.web.client.WebClient;
import de.dytanic.cloudnetwrapper.command.*;
import de.dytanic.cloudnetwrapper.handlers.CPUUsageHandler;
import de.dytanic.cloudnetwrapper.handlers.IWrapperHandler;
import de.dytanic.cloudnetwrapper.handlers.StopTimeHandler;
import de.dytanic.cloudnetwrapper.network.packet.in.*;
import de.dytanic.cloudnetwrapper.network.packet.out.PacketOutSetReadyWrapper;
import de.dytanic.cloudnetwrapper.network.packet.out.PacketOutUpdateCPUUsage;
import de.dytanic.cloudnetwrapper.network.packet.out.PacketOutUpdateWrapperInfo;
import de.dytanic.cloudnetwrapper.network.packet.out.PacketOutWrapperScreen;
import de.dytanic.cloudnetwrapper.screen.ScreenProvider;
import de.dytanic.cloudnetwrapper.server.BungeeCord;
import de.dytanic.cloudnetwrapper.server.CloudGameServer;
import de.dytanic.cloudnetwrapper.server.GameServer;
import de.dytanic.cloudnetwrapper.server.process.ServerProcessQueue;
import de.dytanic.cloudnetwrapper.setup.SetupSpigotVersion;
import de.dytanic.cloudnetwrapper.util.FileCopy;
import de.dytanic.cloudnetwrapper.util.ShutdownOnCentral;
import joptsimple.OptionSet;
import lombok.Getter;
import lombok.Setter;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class CloudNetWrapper implements Executeable, Runnable, ShutdownOnCentral {

    public static volatile boolean RUNNING = false;

    @Getter
    private static CloudNetWrapper instance;

    @Getter
    private final NetworkConnection networkConnection;
    @Getter
    private final CloudLogger cloudNetLogging;
    @Getter
    private final CloudNetWrapperConfig wrapperConfig;
    @Getter
    private final Scheduler scheduler = new Scheduler(40);
    @Getter
    private final ScreenProvider screenProvider = new ScreenProvider();
    @Getter
    private final CommandManager commandManager = new CommandManager();
    @Getter
    private final WebClient webClient = new WebClient();
    @Getter
    private Auth auth;
    @Getter
    private OptionSet optionSet;
    @Setter
    @Getter
    private ServerProcessQueue serverProcessQueue;
    @Setter
    @Getter
    private SimpledUser simpledUser;

    //Sytem meta
    @Setter
    @Getter
    private int maxMemory;

    @Getter
    private Collection<Double> cpuUsageEntries = new ConcurrentLinkedQueue<>();

    @Getter
    private final java.util.Map<String, GameServer> servers = new ConcurrentHashMap<>();
    @Getter
    private final java.util.Map<String, BungeeCord> proxys = new ConcurrentHashMap<>();
    @Getter
    private final java.util.Map<String, CloudGameServer> cloudservers = new ConcurrentHashMap<>();

    @Getter
    private final java.util.Map<String, ServerGroup> serverGroups = new ConcurrentHashMap<>();
    @Getter
    private final java.util.Map<String, ProxyGroup> proxyGroups = new ConcurrentHashMap<>();

    @Getter
    private boolean canDeployed = false;

    public CloudNetWrapper(OptionSet optionSet, CloudNetWrapperConfig cloudNetWrapperConfig, CloudLogger cloudNetLogging) throws Exception
    {
        if (instance == null)
        {
            instance = this;
    }

        this.wrapperConfig = cloudNetWrapperConfig;
        this.cloudNetLogging = cloudNetLogging;
        this.networkConnection = new NetworkConnection(new ConnectableAddress(
            cloudNetWrapperConfig.getCloudnetHost(), cloudNetWrapperConfig.getCloudnetPort()), new Runnable() {
        @Override
        public void run()
        {
            try
            {
                onShutdownCentral();
            } catch (Exception e)
            {
                e.printStackTrace();
            }
        }
    });

    String key = NetworkUtils.readWrapperKey();

        if (key == null)
    {
        System.out.println("Please copy the WRAPPER_KEY.cnd for the auth!");
        System.out.println("The Wrapper stop in 5 seconds");
        NetworkUtils.sleepUninterruptedly(5000);
        System.exit(0);
        return;
    }

        this.auth = new Auth(key, cloudNetWrapperConfig.getWrapperId());
        this.serverProcessQueue = new ServerProcessQueue(cloudNetWrapperConfig.getProcessQueueSize());
        this.maxMemory = cloudNetWrapperConfig.getMaxMemory();
        this.optionSet = optionSet;
}

    @Override
    public boolean bootstrap() throws Exception
    {
        if (!optionSet.has("disallow_bukkit_download") && !Files.exists(Paths.get("local/spigot.jar")))
        {
            new SetupSpigotVersion().run(cloudNetLogging.getReader());
        }

        Thread thread = new Thread(scheduler);
        thread.setDaemon(true);
        thread.start();

        commandManager.registerCommand(new CommandHelp()).registerCommand(new CommandClear()).registerCommand(new CommandClearCache()).registerCommand(new CommandStop()).registerCommand(new CommandReload());

        networkConnection.getPacketManager().registerHandler(PacketRC.CN_CORE, PacketInWrapperInfo.class);
        networkConnection.getPacketManager().registerHandler(PacketRC.CN_CORE + 1, PacketInStartProxy.class);
        networkConnection.getPacketManager().registerHandler(PacketRC.CN_CORE + 2, PacketInStopProxy.class);
        networkConnection.getPacketManager().registerHandler(PacketRC.CN_CORE + 3, PacketInStartServer.class);
        networkConnection.getPacketManager().registerHandler(PacketRC.CN_CORE + 4, PacketInStopServer.class);
        networkConnection.getPacketManager().registerHandler(PacketRC.CN_CORE + 5, PacketInCreateTemplate.class);
        networkConnection.getPacketManager().registerHandler(PacketRC.CN_CORE + 6, PacketInScreen.class);
        networkConnection.getPacketManager().registerHandler(PacketRC.CN_CORE + 7, PacketInExecuteServerCommand.class);
        networkConnection.getPacketManager().registerHandler(PacketRC.CN_CORE + 8, PacketInInstallUpdate.class);
        networkConnection.getPacketManager().registerHandler(PacketRC.CN_CORE + 9, PacketInExecuteCommand.class);
        networkConnection.getPacketManager().registerHandler(PacketRC.CN_CORE + 10, PacketInCopyServer.class);
        networkConnection.getPacketManager().registerHandler(PacketRC.CN_CORE + 11, PacketInOnlineServer.class);
        networkConnection.getPacketManager().registerHandler(PacketRC.CN_CORE + 12, PacketInUpdateWrapperProperties.class);
        networkConnection.getPacketManager().registerHandler(PacketRC.CN_CORE + 13, PacketInStartCloudServer.class);

        networkConnection.getPacketManager().registerHandler(PacketRC.TEST + 1, PacketInTestResult.class);

        System.out.println("trying to connect " + networkConnection.getConnectableAddress().getHostName() + ":" + networkConnection.getConnectableAddress().getPort());
        while (networkConnection.getConnectionTrys() < 5 && networkConnection.getChannel() == null)
        {
            networkConnection.tryConnect(optionSet.has("ssl"), new NetDispatcher(networkConnection, false), auth);
            if (networkConnection.getChannel() != null)
            {
                networkConnection.sendPacketSynchronized(new PacketOutUpdateWrapperInfo());
                break;
            }
            Thread.sleep(2000);
            if (networkConnection.getConnectionTrys() == 5)
            {
                System.exit(0);
            }
        }

        if (!Files.exists(Paths.get("local/server-icon.png")))
            FileCopy.insertData("files/server-icon.png", "local/server-icon.png");

        System.out.println("starting process queue... with " + wrapperConfig.getProcessQueueSize() + " server server");
        scheduler.runTaskRepeatSync(serverProcessQueue, 0, 40);

        //Server Handlers
        {
            networkConnection.sendPacket(new PacketOutSetReadyWrapper(true));
            IWrapperHandler iWrapperHandler = new StopTimeHandler();
            IWrapperHandler iWrapperHandler1 = new CPUUsageHandler();
            scheduler.runTaskRepeatSync(iWrapperHandler.toExecutor(), 0, iWrapperHandler.getTicks());
            scheduler.runTaskRepeatSync(iWrapperHandler1.toExecutor(), 0, iWrapperHandler1.getTicks());

            scheduler.runTaskRepeatSync(new Runnable() {
                @Override
                public void run()
                {
                    networkConnection.sendPacket(new PacketOutUpdateCPUUsage(getCpuUsage()));
                }
            }, 0, 200);
        }

        cloudNetLogging.getHandler().add(new ICloudLoggerHandler() {
            @Override
            public void handleConsole(String input)
            {
                if (networkConnection.isConnected())
                    networkConnection.sendPacket(new PacketOutWrapperScreen(input));
            }
        });

        if (!optionSet.has("disable-autoupdate"))
        {
            scheduler.runTaskAsync(new Runnable() {
                @Override
                public void run()
                {
                    checkForUpdates();
                }
            });
        }

        canDeployed = true;
        RUNNING = true;
        Runtime.getRuntime().addShutdownHook(new Thread(this));

        return true;
    }

    public int getUsedMemory()
    {
        int m = 0;
        for (GameServer gameServer : servers.values())
        {
            m = m + gameServer.getServerProcess().getMeta().getMemory();
        }

        for (BungeeCord bungeeCord : proxys.values())
        {
            m = m + bungeeCord.getProxyProcessMeta().getMemory();
        }

        return m;
    }

    public void checkForUpdates()
    {
        if (!wrapperConfig.isAutoUpdate()) return;

        String version = webClient.getNewstVersion();
        if (version != null)
        {
            if (!version.equals(CloudNetWrapper.class.getPackage().getImplementationVersion()))
            {
                System.out.println("prepare update...");
                if (System.getProperty("os.name").toLowerCase().contains("windows"))
                {
                    try
                    {
                        System.out.println("Downloading update...");
                        URLConnection urlConnection = new URL(WebClient.DEFAULT_URL + "cloud/wrapper").openConnection();
                        urlConnection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/537.11 (KHTML, like Gecko) Chrome/23.0.1271.95 Safari/537.11");
                        urlConnection.connect();
                        Files.copy(urlConnection.getInputStream(), Paths.get("CloudNet-Wrapper-Update#" + version + "-" + NetworkUtils.RANDOM.nextLong() + ".jar"));
                        shutdown();
                    } catch (Exception ex)
                    {
                    }
                } else
                {
                    try
                    {
                        System.out.println("Downloading update...");
                        URLConnection urlConnection = new URL(WebClient.DEFAULT_URL + "cloud/wrapper").openConnection();
                        urlConnection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/537.11 (KHTML, like Gecko) Chrome/23.0.1271.95 Safari/537.11");
                        urlConnection.connect();
                        new File("CloudNet-Wrapper.jar").delete();
                        Files.copy(urlConnection.getInputStream(), Paths.get(CloudNetWrapper.class.getProtectionDomain().getCodeSource().getLocation().toURI().getPath()));
                        shutdown();
                    } catch (Exception ex)
                    {
                    }
                }
            } else
            {
                System.out.println("No updates found!");
            }
        } else
        {
            System.out.println("Failed to check updates");
        }
    }

    @Override
    public boolean shutdown()
    {
        if (!RUNNING) return false;
        System.out.println("Wrapper shutdown...");

        if(scheduler != null)
            scheduler.cancelAllTasks();
        if(serverProcessQueue != null)
            serverProcessQueue.setRunning(false);

        for (GameServer gameServer : servers.values())
        {
            gameServer.shutdown();
        }

        for (BungeeCord gameServer : proxys.values())
        {
            gameServer.shutdown();
        }

        for (CloudGameServer gameServer : cloudservers.values())
        {
            gameServer.shutdown();
        }

        this.cloudNetLogging.shutdownAll();
        if(networkConnection.getChannel() != null)
            networkConnection.tryDisconnect();
        try
        {
            FileUtils.deleteDirectory(new File("temp"));
        } catch (IOException e)
        {
        }

        System.out.println("\n    _  _     _______   _                       _          \n" +
                "  _| || |_  |__   __| | |                     | |         \n" +
                " |_  __  _|    | |    | |__     __ _   _ __   | | __  ___ \n" +
                "  _| || |_     | |    | '_ \\   / _` | | '_ \\  | |/ / / __|\n" +
                " |_  __  _|    | |    | | | | | (_| | | | | | |   <  \\__ \\\n" +
                "   |_||_|      |_|    |_| |_|  \\__,_| |_| |_| |_|\\_\\ |___/\n" +
                "                                                          \n" +
                "                                                          ");
        RUNNING = false;
        if (x_bnosxo)
            System.exit(0);
        return true;
    }

    private boolean x_bnosxo = true;

    @Override
    public void run()
    {
        x_bnosxo = false;
        shutdown();
    }

    @Override
    public void onShutdownCentral() throws Exception
    {
        canDeployed = false;
        if(serverProcessQueue != null)
        {
            serverProcessQueue.getStartups().clear();
            serverProcessQueue.getProxys().clear();
            serverProcessQueue.getServers().clear();
            serverProcessQueue.setRunning(false);
        }

        for (GameServer gameServer : servers.values())
        {
            gameServer.shutdown();
        }

        for (BungeeCord gameServer : proxys.values())
        {
            gameServer.shutdown();
        }

        proxyGroups.clear();
        serverGroups.clear();

        System.out.println("Wrapper try to connect to the CloudNet-Core");

        try {
            FileUtils.deleteDirectory(new File("temp"));
        }catch (Exception ex) {

        }

        new File("temp").mkdir();

        while (networkConnection.getChannel() == null)
        {
            networkConnection.tryConnect(optionSet.has("ssl"), new NetDispatcher(networkConnection, false), auth);
            if (networkConnection.getChannel() != null)
            {
                networkConnection.sendPacketSynchronized(new PacketOutUpdateWrapperInfo());
                break;
            }
            Thread.sleep(2000);
        }

        canDeployed = true;
        if(serverProcessQueue != null)
            serverProcessQueue.setRunning(true);

    }

    public double getCpuUsage() {

        double cpu = 0;

        for(double entry : cpuUsageEntries)
        {
            cpu = cpu + entry;
        }

        return cpu / cpuUsageEntries.size();
    }

}