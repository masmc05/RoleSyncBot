package me.masmc05.discordBotRoles;

import com.electronwill.nightconfig.core.conversion.Path;
import lombok.Data;

@Data
public class Config {
    @Path("twitch.streamerAuthToken")
    String streamerAuthToken = "";
    @Path("twitch.moderatorAuthToken")
    String moderatorAuthToken = "";
    @Path("twitch.channel")
    String channel = "";
    @Path("twitch.channelID")
    String id = "";
    @Path("discord.botToken")
    String botToken = "";
    @Path("discord.staffChannel")
    long console;
}
