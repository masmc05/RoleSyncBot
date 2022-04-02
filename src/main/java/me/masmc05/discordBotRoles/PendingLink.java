package me.masmc05.discordBotRoles;

import lombok.Builder;
import lombok.Value;

@Builder(toBuilder = true)
@Value
public class PendingLink {
    long id, time;
    short code;
}
