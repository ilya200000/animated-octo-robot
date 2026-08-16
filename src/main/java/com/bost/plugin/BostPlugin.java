package com.bost.plugin;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class BostPlugin extends JavaPlugin implements TabCompleter {

    private static final Pattern NAME_PATTERN =
            Pattern.compile("^[a-zA-Z0-9_]{3,16}$");

    private static final long MAX_TIME_DIFF = 5000;

    private static final String DEFAULT_SECRET_KEY =
            "BustDefaultSecret_ChangeMe_2026";

    // =========================================================================
    // СЕРВЕРЫ СЕТИ
    // =========================================================================
    // Все ключи специально в LOWERCASE.
    // Это важно, потому что /bust join переводит аргумент в lowercase.
    // =========================================================================

    private static final Map<String, InetSocketAddress> HARDCODED_SERVERS = Map.of(
            "lobby", new InetSocketAddress("localhost", 15545),
            "smp", new InetSocketAddress("localhost", 15544),
            "grief", new InetSocketAddress("localhost", 25567),
            "minigames", new InetSocketAddress("localhost", 25568),
            "creative", new InetSocketAddress("localhost", 25569)
    );

    // =========================================================================
    // ПОРТЫ СИНХРОНИЗАЦИИ ЭКОНОМИКИ
    // =========================================================================

    private static final Map<String, Integer> HARDCODED_SYNC_PORTS = Map.of(
            "lobby", 16544,
            "smp", 16545,
            "grief", 16546,
            "minigames", 16547,
            "creative", 16548
    );

    // =========================================================================
    // ИМЯ ЭТОГО СЕРВЕРА
    //
    // Для сервера SMP:
    // "smp"
    //
    // Для Lobby:
    // "lobby"
    //
    // Для Grief:
    // "grief"
    //
    // Для Minigames:
    // "minigames"
    //
    // Для Creative:
    // "creative"
    // =========================================================================

    private static final String CURRENT_SERVER_NAME = "smp";

    // =========================================================================
    // ЭКОНОМИКА
    // =========================================================================

    private final ConcurrentHashMap<String, Integer> balanceCache =
            new ConcurrentHashMap<>();

    private final ReentrantLock globalLock = new ReentrantLock();

    private final ThreadPoolExecutor executor =
            new ThreadPoolExecutor(
                    2,
                    4,
                    30L,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(50),
                    r -> {
                        Thread thread = new Thread(r);
                        thread.setName("Bust-Async-Worker");
                        return thread;
                    },
                    new ThreadPoolExecutor.CallerRunsPolicy()
            );

    private String secretKey;

    private File ecoFile;

    private ServerSocket socketServer;

    private volatile boolean listening = true;

    // =========================================================================
    // ENABLE
    // =========================================================================

    @Override
    public void onEnable() {

        secretKey = DEFAULT_SECRET_KEY;

        if (!getDataFolder().exists()) {
            if (!getDataFolder().mkdirs()) {
                getLogger().warning("Не удалось создать папку плагина!");
            }
        }

        ecoFile = new File(getDataFolder(), "economy.txt");

        loadBalancesIntoMemory();

        if (getCommand("bust") != null) {
            getCommand("bust").setTabCompleter(this);
        } else {
            getLogger().warning("Команда /bust не найдена в plugin.yml!");
        }

        startSyncServer();

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {

            new BostPlaceholderExpansion(this).register();

            getLogger().info(
                    "PlaceholderAPI expansion registered successfully!"
            );
        }

        getLogger().info(
                "BUST SECURE ECONOMY INITIALIZED. Server ID: ["
                        + CURRENT_SERVER_NAME
                        + "]"
        );
    }

    // =========================================================================
    // DISABLE
    // =========================================================================

    @Override
    public void onDisable() {

        listening = false;

        executor.shutdownNow();

        try {

            if (socketServer != null && !socketServer.isClosed()) {
                socketServer.close();
            }

        } catch (IOException e) {

            getLogger().warning(
                    "Failed to close sync server socket: "
                            + e.getMessage()
            );
        }

        saveBalancesToDisk();
    }

    // =========================================================================
    // ЗАГРУЗКА БАЛАНСОВ
    // =========================================================================

    private void loadBalancesIntoMemory() {

        if (!ecoFile.exists()) {
            return;
        }

        globalLock.lock();

        try (
                BufferedReader reader =
                        new BufferedReader(
                                new FileReader(
                                        ecoFile,
                                        StandardCharsets.UTF_8
                                )
                        )
        ) {

            String line;

            while ((line = reader.readLine()) != null) {

                String[] parts = line.split(":", 2);

                if (parts.length != 2) {
                    continue;
                }

                try {

                    String playerName = parts[0];
                    int balance = Integer.parseInt(parts[1]);

                    if (NAME_PATTERN.matcher(playerName).matches()) {
                        balanceCache.put(playerName, Math.max(0, balance));
                    }

                } catch (NumberFormatException ignored) {
                }
            }

        } catch (IOException e) {

            getLogger().warning(
                    "Failed to load economy: "
                            + e.getMessage()
            );

        } finally {

            globalLock.unlock();
        }
    }

    // =========================================================================
    // СОХРАНЕНИЕ БАЛАНСОВ
    // =========================================================================

    private void saveBalancesToDisk() {

        globalLock.lock();

        try {

            File tempFile =
                    new File(
                            getDataFolder(),
                            "economy.tmp"
                    );

            try (
                    PrintWriter writer =
                            new PrintWriter(
                                    new FileWriter(
                                            tempFile,
                                            StandardCharsets.UTF_8
                                    )
                            )
            ) {

                for (
                        Map.Entry<String, Integer> entry
                        : balanceCache.entrySet()
                ) {

                    writer.println(
                            entry.getKey()
                                    + ":"
                                    + entry.getValue()
                    );
                }
            }

            try {

                Files.move(
                        tempFile.toPath(),
                        ecoFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE
                );

            } catch (AtomicMoveNotSupportedException e) {

                Files.move(
                        tempFile.toPath(),
                        ecoFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING
                );
            }

        } catch (IOException e) {

            getLogger().warning(
                    "Failed to save economy to disk: "
                            + e.getMessage()
            );

        } finally {

            globalLock.unlock();
        }
    }

    // =========================================================================
    // СЕРВЕР СИНХРОНИЗАЦИИ
    // =========================================================================

    private void startSyncServer() {

        String currentServer =
                CURRENT_SERVER_NAME.toLowerCase(Locale.ROOT);

        Integer mySyncPort =
                HARDCODED_SYNC_PORTS.get(currentServer);

        if (mySyncPort == null) {

            getLogger().severe(
                    "CRITICAL: Sync port for server '"
                            + CURRENT_SERVER_NAME
                            + "' is not defined!"
            );

            return;
        }

        Thread syncThread = new Thread(() -> {

            try {

                socketServer = new ServerSocket(mySyncPort);

                getLogger().info(
                        "Secure sync server started successfully on port "
                                + mySyncPort
                );

                while (listening) {

                    try (
                            Socket clientSocket =
                                    socketServer.accept()
                    ) {

                        InetAddress clientAddr =
                                clientSocket.getInetAddress();

                        // Разрешаем синхронизацию только с localhost
                        if (!clientAddr.isLoopbackAddress()) {
                            continue;
                        }

                        clientSocket.setSoTimeout(3000);

                        BufferedReader reader =
                                new BufferedReader(
                                        new InputStreamReader(
                                                clientSocket.getInputStream(),
                                                StandardCharsets.UTF_8
                                        )
                                );

                        String message =
                                reader.readLine();

                        if (message == null || message.isEmpty()) {
                            continue;
                        }

                        message = message.trim();

                        String[] parts =
                                message.split(":", 5);

                        if (parts.length < 5) {
                            continue;
                        }

                        String receivedSign =
                                parts[0];

                        String dataToSign =
                                parts[1]
                                        + ":"
                                        + parts[2]
                                        + ":"
                                        + parts[3]
                                        + ":"
                                        + parts[4];

                        // Проверяем подпись
                        if (!verifySignature(
                                dataToSign,
                                receivedSign
                        )) {
                            getLogger().warning(
                                    "Rejected packet: invalid signature."
                            );
                            continue;
                        }

                        // Проверяем timestamp
                        long packetTime;

                        try {

                            packetTime =
                                    Long.parseLong(parts[4]);

                        } catch (NumberFormatException e) {

                            continue;
                        }

                        if (
                                Math.abs(
                                        System.currentTimeMillis()
                                                - packetTime
                                ) > MAX_TIME_DIFF
                        ) {
                            continue;
                        }

                        // Обрабатываем экономику
                        if (parts[1].equals("ECO")) {

                            String playerName =
                                    parts[2];

                            if (
                                    !NAME_PATTERN.matcher(
                                            playerName
                                    ).matches()
                            ) {
                                continue;
                            }

                            int remoteBalance;

                            try {

                                remoteBalance =
                                        Integer.parseInt(parts[3]);

                            } catch (NumberFormatException e) {

                                continue;
                            }

                            remoteBalance =
                                    Math.max(0, remoteBalance);

                            globalLock.lock();

                            try {

                                balanceCache.put(
                                        playerName,
                                        remoteBalance
                                );

                                saveBalancesToDisk();

                            } finally {

                                globalLock.unlock();
                            }
                        }

                    } catch (SocketTimeoutException ignored) {

                    } catch (IOException e) {

                        if (listening) {

                            getLogger().warning(
                                    "Error handling sync client: "
                                            + e.getMessage()
                            );
                        }
                    }
                }

            } catch (IOException e) {

                if (listening) {

                    getLogger().warning(
                            "Could not start sync server on port "
                                    + mySyncPort
                                    + ": "
                                    + e.getMessage()
                    );
                }
            }

        }, "Bust-Sync-Server-Thread");

        syncThread.setDaemon(true);
        syncThread.start();
    }

    // =========================================================================
    // ОТПРАВКА БАЛАНСА НА ДРУГИЕ СЕРВЕРЫ
    // =========================================================================

    private void sendBalanceToOtherServers(
            String playerName,
            int balance
    ) {

        long timestamp =
                System.currentTimeMillis();

        String dataToSign =
                "ECO:"
                        + playerName
                        + ":"
                        + balance
                        + ":"
                        + timestamp;

        String signature =
                signData(dataToSign);

        if (signature.isEmpty()) {
            return;
        }

        String payload =
                signature
                        + ":"
                        + dataToSign;

        String currentServer =
                CURRENT_SERVER_NAME.toLowerCase(Locale.ROOT);

        for (
                Map.Entry<String, Integer> entry
                : HARDCODED_SYNC_PORTS.entrySet()
        ) {

            String targetServer =
                    entry.getKey();

            int port =
                    entry.getValue();

            if (
                    targetServer.equalsIgnoreCase(
                            currentServer
                    )
            ) {
                continue;
            }

            executor.submit(() -> {

                try (
                        Socket socket = new Socket()
                ) {

                    socket.connect(
                            new InetSocketAddress(
                                    "127.0.0.1",
                                    port
                            ),
                            2000
                    );

                    socket.setSoTimeout(2000);

                    try (
                            PrintWriter writer =
                                    new PrintWriter(
                                            new OutputStreamWriter(
                                                    socket.getOutputStream(),
                                                    StandardCharsets.UTF_8
                                            ),
                                            true
                                    )
                    ) {

                        writer.println(payload);
                    }

                } catch (IOException ignored) {
                }
            });
        }
    }

    // =========================================================================
    // HMAC
    // =========================================================================

    private String signData(String data) {

        try {

            Mac sha256HMAC =
                    Mac.getInstance("HmacSHA256");

            SecretKeySpec secretKeySpec =
                    new SecretKeySpec(
                            secretKey.getBytes(
                                    StandardCharsets.UTF_8
                            ),
                            "HmacSHA256"
                    );

            sha256HMAC.init(secretKeySpec);

            byte[] hash =
                    sha256HMAC.doFinal(
                            data.getBytes(
                                    StandardCharsets.UTF_8
                            )
                    );

            StringBuilder hexString =
                    new StringBuilder();

            for (byte b : hash) {

                String hex =
                        Integer.toHexString(
                                0xff & b
                        );

                if (hex.length() == 1) {
                    hexString.append('0');
                }

                hexString.append(hex);
            }

            return hexString.toString();

        } catch (Exception e) {

            getLogger().warning(
                    "Failed to sign data: "
                            + e.getMessage()
            );

            return "";
        }
    }

    // =========================================================================
    // ПРОВЕРКА HMAC
    // =========================================================================

    private boolean verifySignature(
            String data,
            String signature
    ) {

        String expected =
                signData(data);

        if (expected.isEmpty()) {
            return false;
        }

        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8)
        );
    }

    // =========================================================================
    // BALANCE
    // =========================================================================

    public int getBalance(String playerName) {

        globalLock.lock();

        try {

            return balanceCache.getOrDefault(
                    playerName,
                    0
            );

        } finally {

            globalLock.unlock();
        }
    }

    // =========================================================================
    // SET BALANCE
    // =========================================================================

    public void setBalance(
            String playerName,
            int amount
    ) {

        int finalAmount =
                Math.max(0, amount);

        globalLock.lock();

        try {

            balanceCache.put(
                    playerName,
                    finalAmount
            );

            saveBalancesToDisk();

        } finally {

            globalLock.unlock();
        }

        sendBalanceToOtherServers(
                playerName,
                finalAmount
        );
    }

    // =========================================================================
    // ADD BALANCE
    // =========================================================================

    public void addBalance(
            String playerName,
            int amount
    ) {

        if (amount <= 0) {
            return;
        }

        globalLock.lock();

        try {

            int current =
                    balanceCache.getOrDefault(
                            playerName,
                            0
                    );

            int newBalance =
                    current + amount;

            balanceCache.put(
                    playerName,
                    newBalance
            );

            saveBalancesToDisk();

            sendBalanceToOtherServers(
                    playerName,
                    newBalance
            );

        } finally {

            globalLock.unlock();
        }
    }

    // =========================================================================
    // REMOVE BALANCE
    // =========================================================================

    public boolean removeBalance(
            String playerName,
            int amount
    ) {

        if (amount <= 0) {
            return false;
        }

        globalLock.lock();

        try {

            int current =
                    balanceCache.getOrDefault(
                            playerName,
                            0
                    );

            if (current < amount) {
                return false;
            }

            int newBalance =
                    current - amount;

            balanceCache.put(
                    playerName,
                    newBalance
            );

            saveBalancesToDisk();

            sendBalanceToOtherServers(
                    playerName,
                    newBalance
            );

            return true;

        } finally {

            globalLock.unlock();
        }
    }

    // =========================================================================
    // COMMAND
    // =========================================================================

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            String[] args
    ) {

        if (
                !command.getName()
                        .equalsIgnoreCase("bust")
        ) {
            return false;
        }

        // /bust
        // /bust balance
        if (
                args.length == 0
                        || args[0].equalsIgnoreCase("balance")
        ) {

            if (!(sender instanceof Player)) {

                sender.sendMessage(
                        "§cЭту команду могут использовать только игроки!"
                );

                return true;
            }

            Player player =
                    (Player) sender;

            int balance =
                    getBalance(
                            player.getName()
                    );

            player.sendMessage(
                    "§aВаш баланс: §e"
                            + balance
                            + " монет"
            );

            return true;
        }

        // =========================================================================
        // /bust join <server>
        // =========================================================================

        if (
                args[0].equalsIgnoreCase("join")
                        && args.length >= 2
        ) {

            if (!(sender instanceof Player)) {

                sender.sendMessage(
                        "§cТолько игроки могут перенаправляться между серверами!"
                );

                return true;
            }

            Player player =
                    (Player) sender;

            String target =
                    args[1].toLowerCase(
                            Locale.ROOT
                    );

            InetSocketAddress address =
                    HARDCODED_SERVERS.get(target);

            if (address == null) {

                player.sendMessage(
                        "§cСервер §e"
                                + args[1]
                                + " §cне найден!"
                );

                return true;
            }

            String currentServer =
                    CURRENT_SERVER_NAME.toLowerCase(
                            Locale.ROOT
                    );

            if (target.equals(currentServer)) {

                player.sendMessage(
                        "§eВы уже на сервере §b"
                                + target
                                + "§e!"
                );

                return true;
            }

            player.sendMessage(
                    "§bПеренаправляю на §e"
                            + target
                            + "§b..."
            );

            try {

                player.transfer(
                        address.getHostString(),
                        address.getPort()
                );

            } catch (Exception e) {

                player.sendMessage(
                        "§cНе удалось перенаправить на сервер!"
                );

                getLogger().warning(
                        "Transfer error for "
                                + player.getName()
                                + " -> "
                                + target
                                + ": "
                                + e.getMessage()
                );
            }

            return true;
        }

        // =========================================================================
        // /bust take <player> <amount>
        // =========================================================================

        if (
                args[0].equalsIgnoreCase("take")
                        && args.length >= 3
        ) {

            if (
                    !sender.hasPermission(
                            "bust.admin"
                    )
            ) {

                sender.sendMessage(
                        "§cУ вас нет прав!"
                );

                return true;
            }

            String targetName =
                    args[1];

            if (
                    !NAME_PATTERN.matcher(
                            targetName
                    ).matches()
            ) {

                sender.sendMessage(
                        "§cНеверное имя игрока!"
                );

                return true;
            }

            int amount;

            try {

                amount =
                        Integer.parseInt(
                                args[2]
                        );

            } catch (NumberFormatException e) {

                sender.sendMessage(
                        "§cНеверная сумма!"
                );

                return true;
            }

            if (amount <= 0) {

                sender.sendMessage(
                        "§cСумма должна быть больше нуля!"
                );

                return true;
            }

            boolean success =
                    removeBalance(
                            targetName,
                            amount
                    );

            if (!success) {

                sender.sendMessage(
                        "§cУ игрока недостаточно средств!"
                );

                return true;
            }

            int newBal =
                    getBalance(
                            targetName
                    );

            sender.sendMessage(
                    "§aВы забрали "
                            + amount
                            + " у игрока §e"
                            + targetName
                            + "§a. Остаток: "
                            + newBal
            );

            return true;
        }

        sender.sendMessage(
                "§cИспользование: /bust [balance|join|take] ..."
        );

        return true;
    }

    // =========================================================================
    // TAB COMPLETE
    // =========================================================================

    @Override
    public List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String alias,
            String[] args
    ) {

        if (
                !command.getName()
                        .equalsIgnoreCase("bust")
        ) {
            return Collections.emptyList();
        }

        if (args.length == 1) {

            String input =
                    args[0].toLowerCase(
                            Locale.ROOT
                    );

            return Arrays.asList(
                            "balance",
                            "join",
                            "take"
                    )
                    .stream()
                    .filter(
                            s -> s.startsWith(input)
                    )
                    .collect(
                            Collectors.toList()
                    );
        }

        if (args.length == 2) {

            if (
                    args[0].equalsIgnoreCase(
                            "join"
                    )
            ) {

                String input =
                        args[1].toLowerCase(
                                Locale.ROOT
                        );

                return HARDCODED_SERVERS.keySet()
                        .stream()
                        .filter(
                                s -> s.startsWith(input)
                        )
                        .sorted()
                        .collect(
                                Collectors.toList()
                        );
            }
        }

        return Collections.emptyList();
    }

    // =========================================================================
    // PLACEHOLDER API
    // =========================================================================

    public static class BostPlaceholderExpansion
            extends PlaceholderExpansion {

        private final BostPlugin plugin;

        public BostPlaceholderExpansion(
                BostPlugin plugin
        ) {

            this.plugin = plugin;
        }

        @Override
        public @NotNull String getIdentifier() {
            return "bust";
        }

        @Override
        public @NotNull String getAuthor() {
            return "Bust";
        }

        @Override
        public @NotNull String getVersion() {
            return "1.8";
        }

        @Override
        public boolean persist() {
            return true;
        }

        @Override
        public String onPlaceholderRequest(
                Player player,
                @NotNull String params
        ) {

            if (player == null) {
                return "";
            }

            if (
                    params.equalsIgnoreCase(
                            "balance"
                    )
            ) {

                return String.valueOf(
                        plugin.getBalance(
                                player.getName()
                        )
                );
            }

            return null;
        }
    }
}
