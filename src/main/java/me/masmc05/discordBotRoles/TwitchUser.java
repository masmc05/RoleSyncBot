package me.masmc05.discordBotRoles;

import com.j256.ormlite.field.DatabaseField;
import com.j256.ormlite.table.DatabaseTable;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@EqualsAndHashCode
@ToString
@DatabaseTable(tableName = "twitch_users")
public class TwitchUser {
    @DatabaseField(id = true, canBeNull = false, unique = true)
    public long id;
    @DatabaseField( canBeNull = false, unique = true)
    public long discordId;
    @DatabaseField
    public boolean redeemedMini = false;
    @DatabaseField
    public boolean redeemedNormal = false;
    @DatabaseField
    public boolean redeemedBig = false;
    @DatabaseField
    public boolean redeemedHuge = false;
    @DatabaseField
    public boolean redeemed69K = false;
}
