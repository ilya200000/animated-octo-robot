package com.bost.plugin;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.stream.Collectors;

public class BostPlugin extends JavaPlugin implements TabCompleter {

    // Секретный токен для межсерверной защиты (должен быть одинаковым на всех серверах сети!)
    private static final String SECRET_TOKEN = "BostSecretKey_2026_SecureSync";

    private Map<String, InetSocketAddress> servers = new HashMap<>();
    private Map<String, Integer> syncPorts = new HashMap<>();
    private File ecoFile;
    private ServerSocket socketServer;
    private boolean listening = true;

    @Override
    public void onEnable() {
        servers.put("survival", new InetSocketAddress("127.0.0.1", 15544));
        servers.put("rpg", new InetSocketAddress("127.0.0.1", 15545));

        syncPorts.put("survival", 16544);
        syncPorts.put("rpg", 16545);

        if (!getDataFolder().exists()) getDataFolder().mkdir();
        ecoFile = new File(getDataFolder(), "economy.txt");

        getCommand("bust").setTabCompleter(this);

        startSyncServer();

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new BostPlaceholderExpansion(this).register();
            getLogger().info("PlaceholderAPI expansion registered successfully!");
        }

        getLogger().info("BOST SECURE ECONOMY & TRANSFER GATEWAY INITIALIZED");
    }

    @Override
    public void onDisable() {
        listening = false;
        try {
            if (socketServer != null) socketServer.close();
        } catch (IOException ignored) {}
    }

    private void startSyncServer() {
        int myPort = (getServer().getPort() == 15545) ? 16545 : 16544;

        new Thread(() -> {
            try {
                socketServer = new ServerSocket(myPort);
                while (listening) {
                    Socket clientSocket = socketServer.accept();
                    BufferedReader reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
                    String message = reader.readLine();
                    clientSocket.close();

                    if (message != null) {
                        // Формат: TOKEN:ECO:Игрок:Баланс
                        String[] parts = message.split(":", 4);
                        if (parts.length >= 4 && parts[0].equals(SECRET_TOKEN)) {
                            if (parts[1].equals("ECO")) {
                                setBalanceInternal(parts[2], Integer.parseInt(parts[3]));
                            }
                        }
                    }
                }
            } catch (IOException ignored) {}
        }).start();
    }

    private void sendBalanceToOtherServers(String playerName, int balance) {
        for (String serverKey : servers.keySet()) {
            Integer port = syncPorts.get(serverKey);
            if (port == null) continue;

            new Thread(() -> {
                try (Socket socket = new Socket("127.0.0.1", port);
                     PrintWriter writer = new PrintWriter(socket.getOutputStream(), true)) {
                    // Отправляем пакет вместе с токеном
                    writer.println(SECRET_TOKEN + ":ECO:" + playerName + ":" + balance);
                } catch (IOException ignored) {}
            }).start();
        }
    }

    public int getBalance(String playerName) {
        if (!ecoFile.exists()) return 0;
        try (BufferedReader reader = new BufferedReader(new FileReader(ecoFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(":");
                if (parts.length == 2 && parts[0].equalsIgnoreCase(playerName)) {
                    return Integer.parseInt(parts[1]);
                }
            }
        } catch (IOException | NumberFormatException ignored) {}
        return 0;
    }

    private synchronized void setBalanceInternal(String playerName, int amount) {
        Map<String, Integer> balances = new HashMap<>();
        if (ecoFile.exists()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(ecoFile))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.split(":", 2);
                    if (parts.length == 2) {
                        balances.put(parts[0], Integer.parseInt(parts[1]));
                    }
                }
            } catch (IOException | NumberFormatException ignored) {}
        }

        balances.put(playerName, amount);

        try (PrintWriter writer = new PrintWriter(new FileWriter(ecoFile, false))) {
            for (Map.Entry<String, Integer> entry : balances.entrySet()) {
                writer.println(entry.getKey() + ":" + entry.getValue());
            }
        } catch (IOException ignored) {}
    }

    public void setBalance(String playerName, int amount) {
        int finalAmount = Math.max(0, amount);
        setBalanceInternal(playerName, finalAmount);
        sendBalanceToOtherServers(playerName, finalAmount);
    }

    public void addBalance(String playerName, int amount) {
        if (amount <= 0) return;
        int newBalance = getBalance(playerName) + amount;
        setBalance(playerName, newBalance);
    }

    public boolean removeBalance(String playerName, int amount) {
        if (amount <= 0) return false;
        int current = getBalance(playerName);
        if (current < amount) return false;
        int newBalance = current - amount;
        setBalance(playerName, newBalance);
        return true;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) return true;
        Player player = (Player) sender;

        if (command.getName().equalsIgnoreCase("bust")) {
            if (args.length == 0 || args[0].equalsIgnoreCase("balance")) {
                int balance = getBalance(player.getName());
                player.sendMessage("§aВаш баланс: §e" + balance + " монет");
                return true;
            }

            if (args.length >= 2) {
                if (args[0].equalsIgnoreCase("join")) {
                    String target = args[1].toLowerCase();
                    if (servers.containsKey(target)) {
                        player.sendMessage("§bПеренаправляю на " + target + "...");
                        InetSocketAddress address = servers.get(target);
                        player.transfer(address.getHostString(), address.getPort());
                    } else {
                        player.sendMessage("§cСервер не найден!");
                    }
                    return true;
                }

                if (args[0].equalsIgnoreCase("pay") && args.length >= 3) {
                    String targetServer = args[1].toLowerCase();
                    int amount;
                    try {
                        amount = Integer.parseInt(args[2]);
                    } catch (Exception e) {
                        player.sendMessage("§cНеверная сумма!");
                        return true;
                    }

                    if (amount <= 0) {
                        player.sendMessage("§cСумма должна быть больше нуля!");
                        return true;
                    }

                    if (!servers.containsKey(targetServer)) {
                        player.sendMessage("§cТакого сервера не существует!");
                        return true;
                    }

                    if (!removeBalance(player.getName(), amount)) {
                        player.sendMessage("§cУ вас недостаточно средств! Баланс: " + getBalance(player.getName()));
                        return true;
                    }

                    player.sendMessage("§aВы успешно перевели " + amount + " на сервер §e" + targetServer + "§a!");
                    return true;
                }

                if (args[0].equalsIgnoreCase("give") && args.length >= 3) {
                    if (!player.hasPermission("bost.admin")) {
                        player.sendMessage("§cУ вас нет прав!");
                        return true;
                    }
                    String targetName = args[1];
                    int amount;
                    try {
                        amount = Integer.parseInt(args[2]);
                    } catch (Exception e) {
                        player.sendMessage("§cНеверная сумма!");
                        return true;
                    }

                    addBalance(targetName, amount);
                    player.sendMessage("§aВы выдали " + amount + " монет игроку §e" + targetName + "§a!");
                    return true;
                }

                if (args[0].equalsIgnoreCase("take") && args.length >= 3) {
                    if (!player.hasPermission("bost.admin")) {
                        player.sendMessage("§cУ вас нет прав!");
                        return true;
                    }
                    String targetName = args[1];
                    int amount;
                    try {
                        amount = Integer.parseInt(args[2]);
                    } catch (Exception e) {
                        player.sendMessage("§cНеверная сумма!");
                        return true;
                    }

                    int current = getBalance(targetName);
                    int newBal = Math.max(0, current - amount);
                    setBalance(targetName, newBal);
                    player.sendMessage("§aВы забрали " + amount + " у игрока §e" + targetName + "§a. Остаток: " + newBal);
                    return true;
                }
            }

            player.sendMessage("§cИспользование: /bust [balance|join|pay|give|take] ...");
            return true;
        }

        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("bust")) {
            if (args.length == 1) {
                return Arrays.asList("balance", "join", "pay", "give", "take");
            }
            if (args.length == 2) {
                if (args[0].equalsIgnoreCase("join") || args[0].equalsIgnoreCase("pay")) {
                    return servers.keySet().stream()
                            .filter(s -> s.startsWith(args[1].toLowerCase()))
                            .collect(Collectors.toList());
                }
            }
        }
        return Collections.emptyList();
    }

    public static class BostPlaceholderExpansion extends PlaceholderExpansion {
        private final BostPlugin plugin;

        public BostPlaceholderExpansion(BostPlugin plugin) {
            this.plugin = plugin;
        }

        @Override
        public @NotNull String getIdentifier() {
            return "bost";
        }

        @Override
        public @NotNull String getAuthor() {
            return "Bust";
        }

        @Override
        public @NotNull String getVersion() {
            return "1.0";
        }

        @Override
        public boolean persist() {
            return true;
        }

        @Override
        public String onPlaceholderRequest(Player player, @NotNull String params) {
            if (player == null) return "";
            if (params.equalsIgnoreCase("balance")) {
                return String.valueOf(plugin.getBalance(player.getName()));
            }
            return null;
        }
    }
}
