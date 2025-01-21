package cn.warriorView.Configuration;

import cn.warriorView.Util.ConfigFile.Comments;
import cn.warriorView.Util.ConfigFile.ConfigurationFile;
import cn.warriorView.Util.ConfigFile.ConfigurationPart;

public class Config extends ConfigurationFile {

    @Comments("版本号")
    public static int version = 0;

    @Comments("总开关")
    public static boolean enabled = true;

    @Comments("造成伤害时显示的伤害数字")
    public static DamageEntity damageEntity = new DamageEntity();

    public static class DamageEntity extends ConfigurationPart {

        @Comments("功能开关")
        public boolean enabled = true;

        @Comments("默认配置")
        public static Defaults defaults = new Defaults();
        public static class Defaults extends ConfigurationPart {

            @Comments("显示的文本, 只能使用minimessage颜色格式")
            public String textFormat = "<red>-%.2f</red>";

            @Comments("文字替换功能")
            public String replacement = "";

            @Comments("生成文字的大小, 可使用单个和多个值或0.9-1.1这种格式, 随机取值")
            public String scale = "0.9, 1.0, 1.1";

            @Comments("文字阴影")
            public boolean shadow = true;

            @Comments("视距")
            public float viewRange = 1.0f;

            @Comments("可见玩家的范围, 设置为0时只有玩家本人可见")
            public byte viewMarge = 16;

            @Comments("使用ARGB")
            public int backgroundColor = -1000000;

            @Comments("可否穿过方块看见")
            public boolean seeThrough = true;

            @Comments("是否只有玩家造成的才会显示")
            public boolean onlyPlayer = true;

            @Comments("位置, 可使用: eye, foot. 如果涉及到生物之间的攻击, 还可使用damage代表精确攻击的位置")
            public String position = "eye";

            @Comments("动画类型")
            public String animation = "1";

            @Comments("移动次数")
            public byte moveCount = 8;

            @Comments("移动间隔, 单位ticks")
            public long delay = 2;

        }

        @Comments("其他配置文件")
        public String apply = "damage_cause.yml";

    }

    @Comments("回复血量时显示的治疗数字")
    public static RegainHealth regainHealth = new RegainHealth();

    public static class RegainHealth extends ConfigurationPart {

        @Comments("功能开关")
        public boolean enabled = true;

        @Comments("默认配置")
        public static Defaults defaults = new Defaults();
        public static class Defaults extends ConfigurationPart {

            @Comments("显示的文本, 只能使用minimessage颜色格式")
            public String textFormat = "<red>-%.2f</red>";

            @Comments("文字替换功能")
            public String replacement = "";

            @Comments("生成文字的大小, 可使用单个和多个值或0.9-1.1这种格式, 随机取值")
            public String scale = "0.9, 1.0, 1.1";

            @Comments("文字阴影")
            public boolean shadow = true;

            @Comments("视距")
            public float viewRange = 1.0f;

            @Comments("可见玩家的范围, 设置为0时只有玩家本人可见")
            public byte viewMarge = 16;

            @Comments("使用ARGB")
            public int backgroundColor = -1000000;

            @Comments("可否穿过方块看见")
            public boolean seeThrough = true;

            @Comments("是否只有玩家造成的才会显示")
            public boolean onlyPlayer = true;

            @Comments("位置, 可使用: eye, foot. 如果涉及到生物之间的攻击, 还可使用damage代表精确攻击的位置")
            public String position = "eye";

            @Comments("动画类型")
            public String animation = "1";

            @Comments("移动次数")
            public byte moveCount = 8;

            @Comments("移动间隔, 单位ticks")
            public long delay = 2;

        }

        @Comments("其他配置文件")
        public String apply = "regain_reason.yml";

    }

    @Comments("动画配置文件")
    public static String animation = "animation.yml";

    @Comments("字符替换配置文件")
    public static String replacement = "replacement.yml";

}
