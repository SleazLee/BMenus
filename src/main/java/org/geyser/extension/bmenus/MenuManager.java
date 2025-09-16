package org.geyser.extension.bmenus;

import org.geysermc.cumulus.form.CustomForm;
import org.geysermc.cumulus.form.SimpleForm;
import org.geysermc.geyser.api.GeyserApi;
import org.geysermc.geyser.api.connection.GeyserConnection;
import org.geysermc.geyser.api.extension.Extension;
import org.geysermc.geyser.api.network.RemoteServer;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handles loading menus from the configuration file and displaying them to players.
 */
public class MenuManager {

    private final Extension extension;
    private final Map<String, Menu> menus = new HashMap<>();
    private final Map<UUID, LinkedHashMap<String, Integer>> usage = new HashMap<>();
    private final Map<UUID, Map<String, Long>> usageTimes = new HashMap<>();
    private final Path usagePath;
    private List<String> defaultCommands = new ArrayList<>();

    private enum QueryState {
        UNKNOWN,
        ENABLED,
        UNAVAILABLE,
        DISABLED
    }

    private final Object playerListLock = new Object();
    private volatile List<String> playerListCache = Collections.emptyList();
    private volatile long playerListCacheTime = 0L;
    private long playerCacheDurationMillis = TimeUnit.SECONDS.toMillis(3);
    private QueryState queryState = QueryState.UNKNOWN;
    private boolean queryExplicitlyDisabled = false;
    private int queryPortOverride = -1;
    private int queryTimeoutMillis = 1500;
    private long queryRetryDelayMillis = TimeUnit.SECONDS.toMillis(30);
    private long nextQueryAttemptMillis = 0L;
    private boolean queryFailureLogged = false;

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> saveTask;
    private long saveIntervalSeconds = 300;
    private int maxCommands = 50;
    private long usageExpiryMillis = TimeUnit.DAYS.toMillis(7);

    public MenuManager(Extension extension) {
        this.extension = extension;
        this.usagePath = extension.dataFolder().resolve("usage.yml");
    }

    /**
     * Loads the menus.yml file from the extension data folder.
     */
    public void loadConfig() {
        Path configPath = extension.dataFolder().resolve("menus.yml");
        if (Files.notExists(configPath)) {
            saveDefault(configPath);
        }

        try (Reader reader = Files.newBufferedReader(configPath)) {
            Yaml yaml = new Yaml();
            Map<String, Object> root = yaml.load(reader);
            if (root == null) {
                return;
            }
            Map<String, Object> menusMap = (Map<String, Object>) root.get("menus");
            for (Map.Entry<String, Object> entry : menusMap.entrySet()) {
                menus.put(entry.getKey(), Menu.fromMap((Map<String, Object>) entry.getValue()));
            }

            Menu mainMenu = menus.get("main");
            if (mainMenu != null) {
                for (int i = 0; i < mainMenu.buttons.size(); i++) {
                    MenuButton button = mainMenu.buttons.get(i);
                    if ("common".equalsIgnoreCase(button.menu)) {
                        if (i != 0) {
                            mainMenu.buttons.remove(i);
                            mainMenu.buttons.add(0, button);
                        }
                        break;
                    }
                }

            }

            Map<String, Object> defaults = (Map<String, Object>) root.get("defaults");
            if (defaults != null) {
                List<String> common = (List<String>) defaults.get("common");
                if (common != null) {
                    defaultCommands = new ArrayList<>(common);
                }
            }

            Map<String, Object> usageCfg = (Map<String, Object>) root.get("usage");
            if (usageCfg != null) {
                Number flush = (Number) usageCfg.get("flush-interval-seconds");
                if (flush != null) {
                    saveIntervalSeconds = flush.longValue();
                }
                Number max = (Number) usageCfg.get("max-commands");
                if (max != null) {
                    maxCommands = max.intValue();
                }
                Number expiry = (Number) usageCfg.get("expiry-seconds");
                if (expiry != null) {
                    usageExpiryMillis = expiry.longValue() * 1000L;
                }
            }

            Map<String, Object> playersCfg = (Map<String, Object>) root.get("players");
            configurePlayerSources(playersCfg);
        } catch (IOException e) {
            extension.logger().error("Unable to load menus.yml", e);
        }

        loadUsage();
        startSaver();
    }

    private void saveDefault(Path path) {
        try {
            Files.createDirectories(path.getParent());
            try (InputStream in = extension.getClass().getClassLoader().getResourceAsStream("menus.yml")) {
                if (in != null) {
                    Files.copy(in, path);
                }
            }
        } catch (IOException e) {
            extension.logger().error("Unable to save default menus.yml", e);
        }
    }

    private void configurePlayerSources(Map<String, Object> config) {
        playerListCache = Collections.emptyList();
        playerListCacheTime = 0L;
        nextQueryAttemptMillis = 0L;
        queryFailureLogged = false;

        playerCacheDurationMillis = TimeUnit.SECONDS.toMillis(3);
        queryTimeoutMillis = 1500;
        queryRetryDelayMillis = TimeUnit.SECONDS.toMillis(30);
        queryPortOverride = -1;
        queryExplicitlyDisabled = false;
        queryState = QueryState.UNKNOWN;

        if (config == null) {
            return;
        }

        Object cacheObj = config.get("cache-seconds");
        if (cacheObj instanceof Number number) {
            long seconds = number.longValue();
            if (seconds < 0) {
                seconds = 0;
            }
            playerCacheDurationMillis = TimeUnit.SECONDS.toMillis(seconds);
        }

        Object queryObj = config.get("query");
        if (queryObj instanceof Map<?, ?> rawMap) {
            Map<?, ?> queryMap = rawMap;

            Object enabledObj = queryMap.get("enabled");
            if (enabledObj instanceof Boolean bool) {
                queryExplicitlyDisabled = !bool;
                queryState = bool ? QueryState.UNKNOWN : QueryState.DISABLED;
            }

            Object portObj = queryMap.get("port");
            if (portObj instanceof Number number) {
                int port = number.intValue();
                queryPortOverride = port > 0 ? port : -1;
            }

            Object timeoutObj = queryMap.get("timeout-ms");
            if (timeoutObj instanceof Number number) {
                int timeout = number.intValue();
                if (timeout > 0) {
                    queryTimeoutMillis = timeout;
                }
            }

            Object retryObj = queryMap.get("retry-seconds");
            if (retryObj instanceof Number number) {
                long seconds = number.longValue();
                if (seconds < 0) {
                    seconds = 0;
                }
                queryRetryDelayMillis = TimeUnit.SECONDS.toMillis(seconds);
            }
        }
    }


    /**
     * Opens a menu with the given id for the player.
     */
    public void openMenu(GeyserConnection connection, String id) {
        Menu menu = menus.get(id);
        if (menu == null) {
            extension.logger().warning("Menu " + id + " not found");
            return;
        }
        if ("common".equalsIgnoreCase(id)) {
            openCommon(connection, menu);
            return;
        }
        if ("simple".equalsIgnoreCase(menu.type)) {
            openSimple(connection, menu);
        } else if ("custom".equalsIgnoreCase(menu.type)) {
            openCustom(connection, menu);
        }
    }

    private void openSimple(GeyserConnection connection, Menu menu) {
        SimpleForm.Builder builder = SimpleForm.builder()
                .title(menu.title);
        if (menu.content != null) {
            builder.content(menu.content);
        }
        for (MenuButton button : menu.buttons) {
            builder.button(button.text);
        }
        builder.validResultHandler((form, response) -> {
            int index = response.clickedButtonId();
            if (index >= 0 && index < menu.buttons.size()) {
                handleButton(connection, menu.buttons.get(index));
            }
        });
        connection.sendForm(builder.build());
    }

    private void openCommon(GeyserConnection connection, Menu menu) {
        LinkedHashMap<String, Integer> map = usage.computeIfAbsent(connection.playerUuid(), uuid -> {
            LinkedHashMap<String, Integer> defaults = new LinkedHashMap<>();
            for (String def : defaultCommands) {
                defaults.put(def, 0);
            }
            return defaults;
        });
        Map<String, Long> times = usageTimes.computeIfAbsent(connection.playerUuid(), uuid -> {
            Map<String, Long> defaults = new HashMap<>();
            for (String def : defaultCommands) {
                defaults.put(def, 0L);
            }
            return defaults;
        });

        cleanupUsage(connection.playerUuid(), map, times);

        List<Map.Entry<String, Integer>> entries = new ArrayList<>(map.entrySet());
        entries.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));

        SimpleForm.Builder builder = SimpleForm.builder().title(menu.title);
        List<String> commands = new ArrayList<>();
        int limit = Math.min(10, entries.size());
        for (int i = 0; i < limit; i++) {
            String cmd = entries.get(i).getKey();
            builder.button(toLabel(cmd));
            commands.add(cmd);
        }

        builder.validResultHandler((form, response) -> {
            int index = response.clickedButtonId();
            if (index >= 0 && index < commands.size()) {
                runCommandTemplate(connection, toLabel(commands.get(index)), commands.get(index));
            }
        });

        connection.sendForm(builder.build());
    }

    private void handleButton(GeyserConnection connection, MenuButton button) {
        if (button.menu != null) {
            openMenu(connection, button.menu);
        } else if (button.command != null) {
            runCommandTemplate(connection, button.text, button.command);
        }
    }

    private void openCustom(GeyserConnection connection, Menu menu) {
        if (menu.command != null) {
            runCommandTemplate(connection, menu.title, menu.command);
        }
    }

    private void runCommandTemplate(GeyserConnection connection, String title, String command) {
        CommandTemplate template = CommandTemplate.parse(command, extension);
        if (template.arguments.isEmpty()) {
            recordCommandUsage(connection, template.raw);
            execute(connection, template.raw);
        } else {
            openCommandForm(connection, title, template);
        }
    }

    private void openCommandForm(GeyserConnection connection, String title, CommandTemplate template) {
        CustomForm.Builder builder = CustomForm.builder().title(title);
        List<List<String>> optionLists = new ArrayList<>();

        for (Argument arg : template.arguments) {
            switch (arg.type) {
                case INPUT -> {
                    builder.input(arg.label);
                    optionLists.add(null);
                }
                case DROPDOWN -> {
                    builder.dropdown(arg.label, arg.options);
                    optionLists.add(arg.options);
                }
                case PLAYER_LIST -> {
                    List<String> names = getOnlinePlayerNames();
                    builder.dropdown(arg.label, names);
                    optionLists.add(names);
                }
                case TOGGLE -> {
                    builder.toggle(arg.label, false);
                    optionLists.add(null);
                }
                case SLIDER -> {
                    builder.slider(arg.label, arg.min, arg.max, arg.step, arg.min);
                    optionLists.add(null);
                }
                case STEP_SLIDER -> {
                    builder.stepSlider(arg.label, arg.options);
                    optionLists.add(arg.options);
                }
            }
        }

        builder.validResultHandler((form, response) -> {
            List<String> values = new ArrayList<>();
            int index = 0;
            for (Argument arg : template.arguments) {
                switch (arg.type) {
                    case INPUT -> values.add(response.asInput(index));
                    case DROPDOWN, PLAYER_LIST -> {
                        List<String> opts = optionLists.get(index);
                        values.add(opts.get(response.asDropdown(index)));
                    }
                    case TOGGLE -> values.add(Boolean.toString(response.asToggle(index)));
                    case SLIDER -> values.add(Integer.toString((int) response.asSlider(index)));
                    case STEP_SLIDER -> {
                        List<String> opts = optionLists.get(index);
                        values.add(opts.get(response.asStepSlider(index)));
                    }
                }
                index++;
            }
            String cmd = template.build(values);
            recordCommandUsage(connection, cmd);
            execute(connection, cmd);
        });

        connection.sendForm(builder.build());
    }

    private List<String> getOnlinePlayerNames() {
        long now = System.currentTimeMillis();
        if (now - playerListCacheTime < playerCacheDurationMillis) {
            return new ArrayList<>(playerListCache);
        }

        synchronized (playerListLock) {
            now = System.currentTimeMillis();
            if (now - playerListCacheTime >= playerCacheDurationMillis) {
                List<String> refreshed = refreshPlayerNames();
                playerListCache = refreshed;
                playerListCacheTime = now;
            }
            return new ArrayList<>(playerListCache);
        }
    }

    private List<String> refreshPlayerNames() {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (GeyserConnection online : extension.geyserApi().onlineConnections()) {
            names.add(online.name());
        }

        if (shouldAttemptQuery()) {
            try {
                List<String> javaPlayers = queryRemotePlayerNames();
                names.addAll(javaPlayers);
                queryState = QueryState.ENABLED;
                queryFailureLogged = false;
                nextQueryAttemptMillis = 0L;
            } catch (IOException e) {
                if (!queryFailureLogged) {
                    extension.logger().warning("Unable to query remote server for player list: " + e.getMessage());
                    queryFailureLogged = true;
                } else if (extension.logger().isDebug()) {
                    extension.logger().debug("Unable to query remote server for player list: " + e.getMessage());
                }
                queryState = QueryState.UNAVAILABLE;
                nextQueryAttemptMillis = System.currentTimeMillis() + queryRetryDelayMillis;
            }
        }

        List<String> result = new ArrayList<>(names);
        result.sort(String.CASE_INSENSITIVE_ORDER);
        return result;
    }

    private boolean shouldAttemptQuery() {
        if (queryState == QueryState.DISABLED || queryExplicitlyDisabled) {
            return false;
        }
        if (queryState == QueryState.UNAVAILABLE) {
            long now = System.currentTimeMillis();
            if (now < nextQueryAttemptMillis) {
                return false;
            }
            queryState = QueryState.UNKNOWN;
        }
        return true;
    }

    private List<String> queryRemotePlayerNames() throws IOException {
        RemoteServer remote = extension.geyserApi().defaultRemoteServer();
        if (remote == null) {
            return Collections.emptyList();
        }

        String host = remote.address();
        int port = queryPortOverride > 0 ? queryPortOverride : remote.port();
        InetAddress address = InetAddress.getByName(host);

        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(queryTimeoutMillis);

            int sessionId = ThreadLocalRandom.current().nextInt();
            byte[] handshake = createHandshake(sessionId);
            socket.send(new DatagramPacket(handshake, handshake.length, address, port));

            DatagramPacket response = new DatagramPacket(new byte[128], 128);
            socket.receive(response);

            ByteBuffer handshakeBuffer = ByteBuffer.wrap(response.getData(), 0, response.getLength());
            if (!handshakeBuffer.hasRemaining() || handshakeBuffer.get() != (byte) 0x09) {
                throw new IOException("Invalid handshake response");
            }
            if (handshakeBuffer.remaining() < 4) {
                throw new IOException("Incomplete handshake response");
            }
            int responseSessionId = handshakeBuffer.getInt();
            if (responseSessionId != sessionId) {
                throw new IOException("Session ID mismatch");
            }
            String challenge = readNullTerminatedString(handshakeBuffer);
            int challengeToken;
            try {
                challengeToken = Integer.parseInt(challenge.trim());
            } catch (NumberFormatException e) {
                throw new IOException("Invalid challenge token: " + challenge, e);
            }

            byte[] statRequest = createStatRequest(sessionId, challengeToken);
            socket.send(new DatagramPacket(statRequest, statRequest.length, address, port));

            DatagramPacket statPacket = new DatagramPacket(new byte[4096], 4096);
            socket.receive(statPacket);
            byte[] statData = Arrays.copyOf(statPacket.getData(), statPacket.getLength());
            return extractPlayersFromStat(statData);
        }
    }

    private byte[] createHandshake(int sessionId) {
        ByteBuffer buffer = ByteBuffer.allocate(7);
        buffer.put((byte) 0xFE);
        buffer.put((byte) 0xFD);
        buffer.put((byte) 0x09);
        buffer.putInt(sessionId);
        return buffer.array();
    }

    private byte[] createStatRequest(int sessionId, int challengeToken) {
        ByteBuffer buffer = ByteBuffer.allocate(15);
        buffer.put((byte) 0xFE);
        buffer.put((byte) 0xFD);
        buffer.put((byte) 0x00);
        buffer.putInt(sessionId);
        buffer.putInt(challengeToken);
        buffer.putInt(0);
        return buffer.array();
    }

    private String readNullTerminatedString(ByteBuffer buffer) {
        StringBuilder builder = new StringBuilder();
        while (buffer.hasRemaining()) {
            byte value = buffer.get();
            if (value == 0) {
                break;
            }
            builder.append((char) (value & 0xFF));
        }
        return builder.toString();
    }

    private List<String> extractPlayersFromStat(byte[] data) {
        if (data.length <= 5) {
            return Collections.emptyList();
        }

        int offset = 5;
        List<String> segments = new ArrayList<>();
        int start = offset;
        for (int i = offset; i < data.length; i++) {
            if (data[i] == 0) {
                segments.add(new String(data, start, i - start, StandardCharsets.UTF_8));
                start = i + 1;
            }
        }
        if (start < data.length) {
            segments.add(new String(data, start, data.length - start, StandardCharsets.UTF_8));
        }

        int playerIndex = -1;
        for (int i = 0; i < segments.size(); i++) {
            String value = sanitizeSegment(segments.get(i));
            if ("player_".equals(value)) {
                playerIndex = i;
                break;
            }
        }

        if (playerIndex == -1) {
            return Collections.emptyList();
        }

        int index = playerIndex + 1;
        while (index < segments.size() && sanitizeSegment(segments.get(index)).isEmpty()) {
            index++;
        }

        List<String> players = new ArrayList<>();
        for (; index < segments.size(); index++) {
            String player = sanitizeSegment(segments.get(index));
            if (player.isEmpty()) {
                break;
            }
            players.add(player);
        }
        return players;
    }

    private String sanitizeSegment(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        int start = 0;
        while (start < value.length() && (value.charAt(start) == 0 || value.charAt(start) == 1)) {
            start++;
        }
        if (start >= value.length()) {
            return "";
        }
        return value.substring(start);
    }

    private void execute(GeyserConnection connection, String command) {
        if (command.startsWith("/")) {
            command = command.substring(1);
        }
        connection.sendCommand(command);
    }

    private void recordCommandUsage(GeyserConnection connection, String command) {
        LinkedHashMap<String, Integer> map = usage.computeIfAbsent(connection.playerUuid(), uuid -> {
            LinkedHashMap<String, Integer> defaults = new LinkedHashMap<>();
            for (String def : defaultCommands) {
                defaults.put(def, 0);
            }
            return defaults;
        });
        Map<String, Long> times = usageTimes.computeIfAbsent(connection.playerUuid(), uuid -> {
            Map<String, Long> defaults = new HashMap<>();
            for (String def : defaultCommands) {
                defaults.put(def, 0L);
            }
            return defaults;
        });
        int previous = map.getOrDefault(command, 0);
        map.merge(command, 1, Integer::sum);
        times.put(command, System.currentTimeMillis());

        if (!defaultCommands.contains(command) && previous == 0) {
            for (String def : defaultCommands) {
                Integer count = map.get(def);
                if (count != null && count == 0) {
                    map.remove(def);
                    times.remove(def);
                    break;
                }
            }
        }
        cleanupUsage(connection.playerUuid(), map, times);
    }

    private String toLabel(String command) {
        return command.replaceAll("\\s*\\{[^}]+}\\s*", " ").trim();
    }

    private void loadUsage() {
        usage.clear();
        usageTimes.clear();
        if (Files.notExists(usagePath)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(usagePath)) {
            Yaml yaml = new Yaml();
            Map<String, Object> root = yaml.load(reader);
            if (root == null) {
                return;
            }
            for (Map.Entry<String, Object> entry : root.entrySet()) {
                UUID uuid = UUID.fromString(entry.getKey());
                Map<String, Object> cmds = (Map<String, Object>) entry.getValue();
                LinkedHashMap<String, Integer> map = new LinkedHashMap<>();
                Map<String, Long> times = new HashMap<>();
                for (Map.Entry<String, Object> cmd : cmds.entrySet()) {
                    Object val = cmd.getValue();
                    if (val instanceof Map<?, ?> data) {
                        Number count = (Number) data.get("count");
                        Number last = (Number) data.get("last");
                        map.put(cmd.getKey(), count == null ? 0 : count.intValue());
                        times.put(cmd.getKey(), last == null ? 0L : last.longValue());
                    } else if (val instanceof Number num) { // legacy format
                        map.put(cmd.getKey(), num.intValue());
                        times.put(cmd.getKey(), 0L);
                    }
                }
                usage.put(uuid, map);
                usageTimes.put(uuid, times);
            }
        } catch (IOException e) {
            extension.logger().error("Unable to load usage data", e);
        }
    }

    private void saveUsage() {
        try {
            Files.createDirectories(usagePath.getParent());
            Map<String, Object> root = new LinkedHashMap<>();
            for (Map.Entry<UUID, LinkedHashMap<String, Integer>> entry : usage.entrySet()) {
                Map<String, Object> cmds = new LinkedHashMap<>();
                Map<String, Long> times = usageTimes.getOrDefault(entry.getKey(), Collections.emptyMap());
                for (Map.Entry<String, Integer> cmd : entry.getValue().entrySet()) {
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("count", cmd.getValue());
                    Long last = times.get(cmd.getKey());
                    if (last != null) {
                        data.put("last", last);
                    }
                    cmds.put(cmd.getKey(), data);
                }
                root.put(entry.getKey().toString(), cmds);
            }
            Yaml yaml = new Yaml();
            try (Writer writer = Files.newBufferedWriter(usagePath)) {
                yaml.dump(root, writer);
            }
        } catch (IOException e) {
            extension.logger().error("Unable to save usage data", e);
        }
    }

    private void startSaver() {
        if (saveTask != null) {
            saveTask.cancel(false);
        }
        saveTask = executor.scheduleAtFixedRate(this::saveUsage, saveIntervalSeconds, saveIntervalSeconds, TimeUnit.SECONDS);
    }

      private void cleanupUsage(UUID uuid, Map<String, Integer> counts, Map<String, Long> times) {
          // uuid currently unused but kept for potential future per-player operations
          long now = System.currentTimeMillis();
          Iterator<Map.Entry<String, Long>> iter = times.entrySet().iterator();
          while (iter.hasNext()) {
              Map.Entry<String, Long> e = iter.next();
              String cmd = e.getKey();
              if (defaultCommands.contains(cmd) && counts.getOrDefault(cmd, 0) == 0) {
                  continue;
              }
              if (now - e.getValue() > usageExpiryMillis) {
                  counts.remove(cmd);
                  iter.remove();
              }
          }
          if (counts.size() > maxCommands) {
              List<Map.Entry<String, Integer>> entries = new ArrayList<>(counts.entrySet());
              entries.sort(Map.Entry.comparingByValue());
              int toRemove = counts.size() - maxCommands;
              for (int i = 0; i < toRemove; i++) {
                  String cmd = entries.get(i).getKey();
                  counts.remove(cmd);
                  times.remove(cmd);
              }
          }
      }

    void shutdown() {
        saveUsage();
        executor.shutdown();
    }

    private static class Menu {
        String type;
        String title;
        String content;
        String command;
        List<MenuButton> buttons;

        static Menu fromMap(Map<String, Object> map) {
            Menu menu = new Menu();
            menu.type = (String) map.get("type");
            menu.title = (String) map.get("title");
            menu.content = (String) map.get("content");
            menu.command = (String) map.get("command");
            menu.buttons = new ArrayList<>();
            List<Map<String, Object>> buttons = (List<Map<String, Object>>) map.get("buttons");
            if (buttons != null) {
                for (Map<String, Object> btn : buttons) {
                    MenuButton b = new MenuButton();
                    b.text = (String) btn.get("text");
                    b.menu = (String) btn.get("menu");
                    b.command = (String) btn.get("command");
                    menu.buttons.add(b);
                }
            }
            return menu;
        }
    }

    private static class MenuButton {
        String text;
        String menu;
        String command;
    }

    private static class CommandTemplate {
        private static final Pattern ARG_PATTERN = Pattern.compile("\\{[^}]+}");

        final String raw;
        final List<Argument> arguments;

        private CommandTemplate(String raw, List<Argument> arguments) {
            this.raw = raw;
            this.arguments = arguments;
        }

        static CommandTemplate parse(String input, Extension extension) {
            Matcher matcher = ARG_PATTERN.matcher(input);
            List<Argument> args = new ArrayList<>();
            while (matcher.find()) {
                String inside = matcher.group();
                inside = inside.substring(1, inside.length() - 1);
                args.add(Argument.parse(inside, extension));
            }
            return new CommandTemplate(input, args);
        }

        String build(List<String> values) {
            String result = raw;
            for (String value : values) {
                result = result.replaceFirst("\\{[^}]+}", value);
            }
            return result;
        }
    }

    private enum ArgType {
        INPUT,
        DROPDOWN,
        PLAYER_LIST,
        TOGGLE,
        SLIDER,
        STEP_SLIDER
    }

    private static class Argument {
        String label;
        ArgType type;
        List<String> options = Collections.emptyList();
        int min;
        int max;
        int step = 1;

        static Argument parse(String content, Extension extension) {
            List<String> parts = split(content);
            Argument arg = new Argument();
            arg.label = strip(parts.get(0));
            String typeStr = parts.size() > 1 ? parts.get(1).trim().toUpperCase() : "INPUT";
            try {
                arg.type = ArgType.valueOf(typeStr);
            } catch (IllegalArgumentException e) {
                if (extension != null) {
                    extension.logger().warning("Unknown argument type: " + typeStr + ", defaulting to INPUT");
                }
                arg.type = ArgType.INPUT;
            }
            switch (arg.type) {
                case DROPDOWN, STEP_SLIDER -> {
                    if (parts.size() > 2) {
                        String list = strip(join(parts, 2));
                        arg.options = Arrays.stream(list.split("\\s*,\\s*")).toList();
                    }
                }
                case SLIDER -> {
                    if (parts.size() > 2) {
                        String[] nums = join(parts, 2).split("\\s*,\\s*");
                        if (nums.length > 0) arg.min = Integer.parseInt(nums[0].trim());
                        if (nums.length > 1) arg.max = Integer.parseInt(nums[1].trim());
                        if (nums.length > 2) arg.step = Integer.parseInt(nums[2].trim());
                    }
                }
                default -> {}
            }
            return arg;
        }

        private static List<String> split(String content) {
            List<String> parts = new ArrayList<>();
            StringBuilder current = new StringBuilder();
            boolean inQuotes = false;
            for (char c : content.toCharArray()) {
                if (c == '"') {
                    inQuotes = !inQuotes;
                } else if (c == ',' && !inQuotes) {
                    parts.add(current.toString().trim());
                    current.setLength(0);
                    continue;
                }
                current.append(c);
            }
            parts.add(current.toString().trim());
            return parts;
        }

        private static String join(List<String> parts, int start) {
            StringBuilder sb = new StringBuilder();
            for (int i = start; i < parts.size(); i++) {
                if (i > start) sb.append(',');
                sb.append(parts.get(i));
            }
            return sb.toString();
        }

        private static String strip(String s) {
            s = s.trim();
            if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) {
                s = s.substring(1, s.length() - 1);
            }
            return s;
        }
    }
}
