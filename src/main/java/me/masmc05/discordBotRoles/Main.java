package me.masmc05.discordBotRoles;

import com.electronwill.nightconfig.core.conversion.ObjectConverter;
import com.electronwill.nightconfig.core.file.FileConfig;
import com.electronwill.nightconfig.core.file.FileNotFoundAction;
import com.github.philippheuer.credentialmanager.domain.OAuth2Credential;
import com.github.philippheuer.events4j.simple.SimpleEventHandler;
import com.github.philippheuer.events4j.simple.domain.EventSubscriber;
import com.github.twitch4j.chat.TwitchChat;
import com.github.twitch4j.chat.TwitchChatBuilder;
import com.github.twitch4j.chat.events.channel.ChannelMessageEvent;
import com.github.twitch4j.eventsub.domain.RedemptionStatus;
import com.github.twitch4j.helix.TwitchHelixBuilder;
import com.github.twitch4j.helix.domain.CustomRewardRedemption;
import com.github.twitch4j.helix.domain.CustomRewardRedemptionList;
import com.github.twitch4j.pubsub.TwitchPubSub;
import com.github.twitch4j.pubsub.TwitchPubSubBuilder;
import com.github.twitch4j.pubsub.events.RewardRedeemedEvent;
import com.j256.ormlite.dao.Dao;
import com.j256.ormlite.dao.DaoManager;
import com.j256.ormlite.jdbc.JdbcPooledConnectionSource;
import com.j256.ormlite.table.TableUtils;
import lombok.SneakyThrows;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.security.auth.login.LoginException;
import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class Main extends ListenerAdapter {
    private static final Config config;
    private static final Logger logger = LoggerFactory.getLogger(Main.class);
    private static JdbcPooledConnectionSource connectionSource;
    private static JDA bot;
    private static final float perMB = 1024 * 1024;
    private static final Runnable empty = () -> {};
    private static final HashSet<PendingLink> pendingLinks = new HashSet<>();
    private static final Random random = new Random();
    private static final String codeMessage = """
                            Your code is {0}, it will expire in 5 minutes!
                            To link your account you need to type `!verify {0}` in Serg's chat!
                            If your message was deleted, then it means that it was successfully seen and your account is linked
                            If you think that it wasn't, check `/check`
                            """;
    private static final String notFound ="Seems like you didn't link your twitch account";
    private static final String notFoundCode = notFound + '\n' + codeMessage;
    private static final ScheduledThreadPoolExecutor SCHEDULED_THREAD_POOL_EXECUTOR = new ScheduledThreadPoolExecutor(4);
    private static TwitchChat chat;
    private static TwitchPubSub pubSub;

    static {
        boolean write = false;
        File file = new File("config.toml");
        if (!file.exists()) {
            write = true;
            try {
                //noinspection ResultOfMethodCallIgnored
                file.createNewFile();
            } catch (IOException ignored) {}
        }
        var configFile = FileConfig.of(file);
        configFile.load();
        if (write) {
            var finalConfigFile = configFile;
            configFile = new ObjectConverter().toConfig(new Config(),() -> finalConfigFile);
            configFile.save();
        }
        config = new ObjectConverter().toObject(configFile, Config::new);
        configFile.close();
        var chat1 = TwitchChatBuilder.builder()
                .withChatAccount(new OAuth2Credential("twitch", config.moderatorAuthToken))
                .withAutoJoinOwnChannel(false)
                .withScheduledThreadPoolExecutor(SCHEDULED_THREAD_POOL_EXECUTOR);
        var main = new Main();
        SCHEDULED_THREAD_POOL_EXECUTOR.submit(() -> {
            logger.info("Enabling Chat");
            chat = chat1.build();
            chat.joinChannel(config.channel);
            chat.getEventManager().getEventHandler(SimpleEventHandler.class).registerListener(main);
            logger.info("Chat enabled");
        });
        var pubSub1 = TwitchPubSubBuilder.builder().withScheduledThreadPoolExecutor(SCHEDULED_THREAD_POOL_EXECUTOR);
        SCHEDULED_THREAD_POOL_EXECUTOR.submit(() -> {
            logger.info("Enabling PubSub");
            pubSub = pubSub1.build();
            pubSub.listenForChannelPointsRedemptionEvents(new OAuth2Credential("twitch", config.streamerAuthToken), config.id);
            pubSub.getEventManager().getEventHandler(SimpleEventHandler.class).registerListener(main);
            logger.info("PubSub enabled");
        });
    }

    public static void main(String[] args) throws SQLException, LoginException, IOException {
        logger.info("Enabling Discord bot");
        bot = JDABuilder.createDefault(config.botToken)
                .setEventPool(SCHEDULED_THREAD_POOL_EXECUTOR)
                .setRateLimitPool(SCHEDULED_THREAD_POOL_EXECUTOR)
                .setCallbackPool(SCHEDULED_THREAD_POOL_EXECUTOR)
                .setGatewayPool(SCHEDULED_THREAD_POOL_EXECUTOR)
                .enableIntents(GatewayIntent.GUILD_MEMBERS)
                .addEventListeners(new Main())
                .build();
        logger.info("Discord bot enabled");
        var file = new File("users.db");
        if (!file.exists()) //noinspection ResultOfMethodCallIgnored
            file.createNewFile();
        connectionSource = new JdbcPooledConnectionSource("jdbc:sqlite:" + file.getPath());
        TableUtils.createTableIfNotExists(connectionSource, TwitchUser.class);
        bot.setAutoReconnect(true);
        bot.upsertCommand("check","Checks roles now for missed ones").queue();
        bot.upsertCommand("console","Executes a console command").addOption(OptionType.STRING,"command","The command to execute").queue();
        bot.upsertCommand("code","Get a code to link your account").queue();
        SCHEDULED_THREAD_POOL_EXECUTOR.scheduleWithFixedDelay(Main::removeByTime, 10, 1, TimeUnit.MINUTES);

        Runtime.getRuntime().addShutdownHook(new Thread(Main::shutdown));
        new Thread(Main::console).start();
    }

    private static void console() {
        var scanner = new Scanner(System.in);
        while (scanner.hasNext()) {
            var action  = getConsoleAction(scanner.nextLine());
            System.out.println(action.output);
            try {
                action.run();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private static void shutdown() {
        bot.shutdown();
        connectionSource.closeQuietly();
        SCHEDULED_THREAD_POOL_EXECUTOR.shutdown();
        chat.close();
        pubSub.close();
        System.out.println("Closing!");
    }

    @SneakyThrows
    @Override
    public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent event) {
        event.getInteraction().deferReply(true).queue();
        SCHEDULED_THREAD_POOL_EXECUTOR.submit(() -> {
            switch (event.getInteraction().getName()) {
                case "check" -> {
                    TwitchUser user = getUserByDiscord(event.getUser()), user1;
                    if (user == null) {
                        event.getHook().editOriginal(notFound).queue();
                        var found = pendingLinks.stream().filter(pendingLink -> pendingLink.getId() == event.getUser().getIdLong()).findAny();
                        if (found.isPresent()) {
                            pendingLinks.remove(found.get());
                            pendingLinks.add(found.get().toBuilder().time(Instant.now().getEpochSecond()).build());
                            event.getHook().editOriginal(notFoundCode.replaceAll("\\{0}", Short.toString(found.get().getCode()))).queue();
                        }
                        return;
                    }
                    user1 = getActualUser(user.id, user.discordId);
                    if (!user.equals(user1)) {
                        try {
                            Dao<TwitchUser, Long> dao = DaoManager.createDao(connectionSource, TwitchUser.class);
                            dao.update(user1);
                        } catch (SQLException e) {
                            e.printStackTrace();
                        }
                    }
                    event.getHook().editOriginal(String.format("""
                            You redeemed:
                            Mini-fan: %s
                            Fan: %s
                            Big fan: %s
                            Huge fan: %s
                            69420: %s
                            Next to redeem: <@&%s>
                            """, user1.redeemedMini, user1.redeemedNormal, user1.redeemedBig, user1.redeemedHuge, user1.redeemed69K, getRoleIdToRedeem(user1))).queue();
                }
                case "console" -> {
                    if (event.getGuildChannel().getIdLong() == config.console) {
                        var action = getConsoleAction(event.getOptions().get(0).getAsString());
                        event.getHook().editOriginal(action.output).submit().thenRun(action);
                    } else event.getHook().editOriginal("Wrong channel").queue();
                }
                case "code" -> {
                    TwitchUser user = getUserByDiscord(event.getUser());
                    var found = pendingLinks.stream().filter(pendingLink -> pendingLink.getId() == event.getUser().getIdLong()).findAny();
                    if (found.isPresent()) {
                        pendingLinks.remove(found.get());
                        pendingLinks.add(found.get().toBuilder().time(Instant.now().getEpochSecond()).build());
                        event.getHook().editOriginal(codeMessage.replaceAll("\\{0}", Short.toString(found.get().getCode()))).queue();
                        return;
                    }
                    if (user != null) {
                        event.getHook().editOriginal("Sorry, but seems like you already registered").queue();
                        return;
                    }
                    short[] code = new short[1];
                    do {
                        code[0] = (short) random.nextInt(1000, 9999);
                    } while (pendingLinks.stream().anyMatch(pendingLink -> pendingLink.getCode() == code[0]));
                    event.getHook().editOriginal(codeMessage.replaceAll("\\{0}", Short.toString(code[0]))).queue();
                    pendingLinks.add(PendingLink.builder().code(code[0]).id(event.getUser().getIdLong()).time(Instant.now().getEpochSecond()).build());
                }
            }
        });
    }

    private static ConsoleAction getConsoleAction(String asString) {
        var args = asString.split(" ");
        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "stop","close" -> new ConsoleAction(() -> System.exit(0), "Stopping the bot");
            case "hi" -> new ConsoleAction(empty,"hi");
            case "removebycode" -> new ConsoleAction(() -> removeByCode(Short.parseShort(args[1])),"Removed!");
            case "register" -> new ConsoleAction(() -> register(Long.parseLong(args[1]),Long.parseLong(args[2])),"Registered");
            case "memory" -> new ConsoleAction(empty,String.format("""
                        Uses: %.2f MB
                        Free: %.2f MB
                        Total %.0f MB
                        Max: %.0f MB
                        """,(Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / perMB, Runtime.getRuntime().freeMemory() / perMB, Runtime.getRuntime().totalMemory() / perMB, Runtime.getRuntime().maxMemory() / perMB));
            default -> new ConsoleAction(empty, "Command not found");
        };
    }

    private static TwitchUser getUserByDiscord(User user) {
        try (var iterator = DaoManager.createDao(connectionSource,TwitchUser.class).getWrappedIterable()){
            for (var tUser : iterator) {
                if (tUser.discordId == user.getIdLong()) return tUser;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    private static void removeByCode(short code) {
        pendingLinks.removeIf(pendingLink -> pendingLink.getCode() == code);
    }
    private static void removeByTime() {
        pendingLinks.removeIf(pendingLink -> (Instant.now().getEpochSecond() - pendingLink.getTime()) > 300);
    }
    @SneakyThrows
    private static void register(long twitchID, long discordID) {
        var dao = DaoManager.createDao(connectionSource,TwitchUser.class);
        try (var iterator = dao.getWrappedIterable()){
            for (var tUser : iterator) {
                if (tUser.discordId == discordID || tUser.id == twitchID) return;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        TwitchUser user = getActualUser(twitchID, discordID);
        dao.create(user);
    }

    private static TwitchUser getActualUser(long twitchID, long discordID) {
        var user = Objects.requireNonNull(bot.getGuildById(926056343977218118L)).loadMembers().get().stream().filter(member -> member.getIdLong() == discordID).findAny().orElseThrow();
        var roles = user.getRoles().stream().map(Role::getIdLong).toList();
        var twitchUser = new TwitchUser();
        twitchUser.discordId = discordID;
        twitchUser.id = twitchID;
        if (roles.contains(933763015327748126L)) twitchUser.redeemedMini = true;
        if (roles.contains(933763307226144788L)) twitchUser.redeemedNormal = true;
        if (roles.contains(933763539435401216L)) twitchUser.redeemedBig = true;
        if (roles.contains(933763717768818718L)) twitchUser.redeemedHuge = true;
        if (roles.contains(933764050448425021L)) twitchUser.redeemed69K = true;
        var miniRedemption = getRedemptions("9f94505d-940d-452b-b4db-af311d96b1b9",twitchID);
        var normalRedemption = getRedemptions("ebc4ad36-a562-4007-9d44-55b68f21d6cd",twitchID);
        var bigRedemption = getRedemptions("f3526aac-f85a-4ba4-9bb1-38ce6feb05fa",twitchID);
        var hugeRedemption = getRedemptions("a7edfe74-e9f0-4d76-a700-c8b426615824",twitchID);
        var k69Redemption = getRedemptions("54707b69-c685-4ba9-9f26-c99daec34742",twitchID);
        if (miniRedemption.join()) twitchUser.redeemedMini = true;
        if (normalRedemption.join()) twitchUser.redeemedNormal = true;
        if (bigRedemption.join()) twitchUser.redeemedBig = true;
        if (hugeRedemption.join()) twitchUser.redeemedHuge = true;
        if (k69Redemption.join()) twitchUser.redeemed69K = true;
        var toGive = new ArrayList<Role>();
        if (twitchUser.redeemedMini) toGive.add(user.getGuild().getRoleById(933763015327748126L));
        if (twitchUser.redeemedNormal
                && twitchUser.redeemedMini) toGive.add(user.getGuild().getRoleById(933763307226144788L));
        if (twitchUser.redeemedBig
                && twitchUser.redeemedNormal
                && twitchUser.redeemedMini) toGive.add(user.getGuild().getRoleById(933763539435401216L));
        if (twitchUser.redeemedHuge
                && twitchUser.redeemedBig
                && twitchUser.redeemedNormal
                && twitchUser.redeemedMini) toGive.add(user.getGuild().getRoleById(933763717768818718L));
        if (twitchUser.redeemed69K
                && twitchUser.redeemedHuge
                && twitchUser.redeemedBig
                && twitchUser.redeemedNormal
                && twitchUser.redeemedMini) toGive.add(user.getGuild().getRoleById(933764050448425021L));
        try {
            user.getGuild().modifyMemberRoles(user,toGive,Collections.emptyList()).queue();
        } catch (Exception ignored) {}
        return twitchUser;
    }

    private static CompletableFuture<Boolean> getRedemptions(String id, long twitchUser){
        return CompletableFuture.supplyAsync(() -> {
            var mainList = new ArrayList<CustomRewardRedemption>();
            fillRedemptionList(mainList,id, RedemptionStatus.FULFILLED);
            fillRedemptionList(mainList,id, RedemptionStatus.UNFULFILLED);
            fillRedemptionList(mainList,id, RedemptionStatus.CANCELED);
            return mainList.stream()
                    .map(CustomRewardRedemption::getUserId)
                    .map(Long::parseLong)
                    .anyMatch(userId -> userId == twitchUser);
        });
    }
    private static void fillRedemptionList(List<CustomRewardRedemption> mainList, String id, RedemptionStatus status) {
        String cursor = null;
        CustomRewardRedemptionList redemptions;
        do {
            redemptions = getRedemptions(id, cursor, status);
            cursor = redemptions.getPagination().getCursor();
            mainList.addAll(redemptions.getRedemptions());
        } while (!redemptions.getRedemptions().isEmpty());
    }
    private static long getRoleIdToRedeem(TwitchUser user) {
        if (!user.redeemedMini) return 933763015327748126L;
        if (!user.redeemedNormal) return 933763307226144788L;
        if (!user.redeemedBig) return 933763539435401216L;
        if (!user.redeemedHuge) return 933763717768818718L;
        if (!user.redeemed69K) return 933764050448425021L;
        return 926129864766021692L;
    }

    private static CustomRewardRedemptionList getRedemptions(String id, String cursor, RedemptionStatus status){
        return TwitchHelixBuilder.builder().build().getCustomRewardRedemption(
                config.streamerAuthToken,
                config.id,
                id,
                null,
                status,
                null,
                cursor,
                50).execute();
    }

    private record ConsoleAction(Runnable runnable, String output) implements Runnable {
        @Override
        public void run() {
           runnable.run();
        }
    }

    @EventSubscriber
    public void onChat(ChannelMessageEvent event) {
        var message = event.getMessageEvent().getMessage();
        if (message.isEmpty()) return;
        if (!message.get().startsWith("!verify ")) return;
        try {
            int num = Integer.parseInt(message.get().replaceFirst("!verify ",""));
            var pending = pendingLinks.stream().filter(pendingLink -> pendingLink.getCode() == num).findAny();
            if (pending.isEmpty()) return;
            pendingLinks.remove(pending.get());
            event.getTwitchChat().sendMessage(config.channel,"/delete " + event.getMessageEvent().getMessageId().orElse(""),null);
            register(Integer.parseInt(event.getUser().getId()),pending.get().getId());
        } catch (NumberFormatException ignored) {}
    }

    @SneakyThrows
    @EventSubscriber
    public void onRedeem(RewardRedeemedEvent event) {
        switch (event.getRedemption().getReward().getId()) {
            case "9f94505d-940d-452b-b4db-af311d96b1b9","ebc4ad36-a562-4007-9d44-55b68f21d6cd","f3526aac-f85a-4ba4-9bb1-38ce6feb05fa","a7edfe74-e9f0-4d76-a700-c8b426615824","54707b69-c685-4ba9-9f26-c99daec34742" -> {
                Dao<TwitchUser, Long> dao = DaoManager.createDao(connectionSource,TwitchUser.class);
                TwitchUser user = dao.queryForId(Long.parseLong(event.getRedemption().getUser().getId()));
                if (user == null) {
                    String name = event.getRedemption().getUser().getDisplayName();
                    if (name== null || name.isEmpty() || name.trim().isEmpty()) name = "";
                    chat.sendMessage(config.channel,String.format("""
                    Hey, @%s, did you know that you can receive your role right now? Run /code in discord server! (don't forget to select the command from suggestions)
                    """,name));
                    return;
                }
                user = getActualUser(user.id, user.discordId);
                dao.update(user);
            }
        }
    }
}
