package cn.warriorView.Configuration.File;

import cn.warriorView.Configuration.Form.Comments;
import cn.warriorView.Configuration.Form.ConfigurationFile;
import cn.warriorView.Configuration.Form.ConfigurationPart;

public class Config extends ConfigurationFile {

    @Comments("版本号, 请勿修改")
    public static int version = 2;

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
            public String textFormat = "<red>-%.2f";

            @Comments("文字替换功能")
            public String replacement = "";

            @Comments("生成文字的大小, 可使用单个和多个值或0.9-1.1这种格式, 随机取值")
            public String scale = "0.9, 1.0, 1.1";

            @Comments("文字阴影")
            public boolean shadow = true;

            @Comments("文字不透明度, 0-100, 100为不透明")
            public double opacity = 80.0;

            @Comments("视距")
            public double viewRange = 1.0;

            @Comments("可见玩家的范围, 设置为0时只有玩家本人可见")
            public int viewMarge = 16;

            @Comments("使用ARGB, 0时为透明")
            public int backgroundColor = 0;

            @Comments("可否穿过方块看见")
            public boolean seeThrough = true;

            @Comments("是否只有玩家相关的才会显示")
            public boolean onlyPlayer = true;

            @Comments({"数字显示的位置, 可使用: eye, foot.","下方在特殊配置文件中, 如果涉及到生物之间的攻击, 还可使用damage代表精确攻击的位置"})
            public String position = "eye";

            @Comments("默认动画类型, 需要你去animation.yml文件中进行配置")
            public String animation = "1";

            @Comments("相对向上偏移")
            public double offsetUp = 0;

            @Comments("相对向玩家偏移, 如果没有玩家的时候只会向移动方向偏移")
            public double offsetApproach = 0;
        }

        @Comments("特殊配置文件路径, 在下方文件中添加了造成伤害的原因才能正常使用")
        public String apply = "views/damage_cause.yml";

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
            public String textFormat = "<green>+%.1f";

            @Comments("文字替换功能, 需要你去replacement.yml中进行配置")
            public String replacement = "";

            @Comments("生成文字的大小, 可使用单个和多个值或0.9-1.1这种格式, 随机取值")
            public String scale = "1.0";

            @Comments("文字阴影")
            public boolean shadow = true;

            @Comments("文字不透明度, 0-100, 100为不透明")
            public double opacity = 80.0;

            @Comments("视距")
            public double viewRange = 1.0;

            @Comments("可见玩家的范围, 设置为0时只有玩家本人可见")
            public int viewMarge = 12;

            @Comments("使用ARGB, 0时为透明")
            public int backgroundColor = 0;

            @Comments("可否穿过方块看见")
            public boolean seeThrough = true;

            @Comments("是否只有玩家相关的才会显示")
            public boolean onlyPlayer = false;

            @Comments("数字显示的位置, 可使用: eye, foot")
            public String position = "foot";

            @Comments("默认动画类型, 需要你去animation.yml文件中进行配置")
            public String animation = "2";

            @Comments("相对向上偏移")
            public double offsetUp = 0;

            @Comments("相对向玩家偏移, 如果没有玩家的时候只会向移动方向偏移")
            public double offsetApproach = 0;
        }

        @Comments("特殊配置文件路径, 在下方文件中添加了治疗的原因才能正常使用")
        public String apply = "views/regain_reason.yml";

    }

    @Comments("动画配置文件路径")
    public static String animation = "animation.yml";

    @Comments("字符替换配置文件路径")
    public static String replacement = "replacement.yml";

}
