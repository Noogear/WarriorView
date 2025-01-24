package cn.warriorView.Util;

import cn.warriorView.Configuration.Language;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;

public class MsgUtil {

    public static void send(CommandSender sender, String text) {
        sender.sendMessage(MiniMessage.miniMessage().deserialize(Language.prefix + text));
    }

    public static void send(CommandSender sender, String text, Object... args) {
        sender.sendMessage(MiniMessage.miniMessage().deserialize(String.format(Language.prefix + text, args)));
    }
}
